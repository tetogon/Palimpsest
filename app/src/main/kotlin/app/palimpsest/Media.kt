package app.palimpsest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Everything here stays in memory: no temporary files, so no plaintext ever reaches the
 * disk outside the encrypted container.
 */
object ImagePrep {
    const val MAX_SIDE = 2048
    const val THUMB_SIDE = 360
    private const val MAX_INPUT = 40 * 1024 * 1024

    class Prepared(val jpeg: ByteArray, val thumbnail: ByteArray, val width: Int, val height: Int)

    /**
     * Decodes, applies the EXIF orientation (plus [extraRotation] from the camera), scales
     * down and re-encodes as JPEG. Re-encoding drops every EXIF tag, including GPS position.
     */
    fun prepare(source: ByteArray, extraRotation: Int = 0): Prepared = finish(load(source, extraRotation), null)

    /** Decoded, turned upright and scaled down: what the crop screen shows. */
    fun load(source: ByteArray, extraRotation: Int = 0): Bitmap {
        val rotation = (exifRotation(source) + extraRotation) % 360
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("cannot decode image")
        return fit(rotate(decoded, rotation), MAX_SIDE)
    }

    /** A copy turned by [quarterTurns] × 90° clockwise; [b] is left alone (it may be on screen). */
    fun turned(b: Bitmap, quarterTurns: Int): Bitmap {
        val degrees = ((quarterTurns % 4) + 4) % 4 * 90
        if (degrees == 0) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }

    /**
     * Crops [b] to [crop] (fractions of its size; null = all of it) and encodes it.
     * Re-encoding drops every EXIF tag, including GPS position.
     */
    fun finish(b: Bitmap, crop: RectF?): Prepared {
        val out = if (crop == null) b else {
            val x = (crop.left * b.width).roundToInt().coerceIn(0, b.width - 1)
            val y = (crop.top * b.height).roundToInt().coerceIn(0, b.height - 1)
            val w = (crop.width() * b.width).roundToInt().coerceIn(1, b.width - x)
            val h = (crop.height() * b.height).roundToInt().coerceIn(1, b.height - y)
            if (x == 0 && y == 0 && w == b.width && h == b.height) b else Bitmap.createBitmap(b, x, y, w, h)
        }
        val thumbBitmap = fit(out, THUMB_SIDE)
        val prepared = Prepared(encode(out, 85), encode(thumbBitmap, 75), out.width, out.height)
        if (thumbBitmap !== out) thumbBitmap.recycle()
        if (out !== b) out.recycle()
        return prepared
    }

    fun read(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open image" }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_INPUT) { "image too large" }
            }
            return out.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** Decodes at no more than about [maxSide] on the long side (power-of-two subsampling). */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun exifRotation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (r !== b) b.recycle()
        return r
    }

    private fun fit(b: Bitmap, side: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= side) return b
        val scale = side.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun encode(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}

/**
 * Voice notes: MediaRecorder writes AAC-ADTS (a streamable format) into a pipe, and a reader
 * thread collects the bytes in memory.
 */
class VoiceRecorder(private val context: Context) {
    class Result(val audio: ByteArray, val durationMs: Long)

    private var recorder: MediaRecorder? = null
    private var reader: Thread? = null
    private var readEnd: ParcelFileDescriptor? = null
    private val buffer = ByteArrayOutputStream()
    private var startedAt = 0L

    val elapsedMs get() = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    fun start() {
        check(recorder == null)
        val (read, write) = ParcelFileDescriptor.createPipe()
        readEnd = read
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(64_000)
        r.setMaxDuration(MAX_DURATION_MS)
        r.setOutputFile(write.fileDescriptor)
        r.prepare()
        write.close() // the recorder holds its own duplicate
        reader = thread(name = "voice-reader") {
            ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = runCatching { input.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    synchronized(buffer) { buffer.write(buf, 0, n) }
                }
            }
        }
        r.start()
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Null if nothing usable was recorded. */
    fun stop(): Result? {
        val r = recorder ?: return null
        val duration = elapsedMs
        recorder = null
        runCatching { r.stop() }
        r.release()
        reader?.join(2_000)
        reader = null
        runCatching { readEnd?.close() }
        readEnd = null
        val bytes = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
        return if (bytes.size < 1_000 || duration < 500) null else Result(bytes, duration)
    }

    fun cancel() {
        stop()
    }

    companion object {
        const val MAX_DURATION_MS = 10 * 60 * 1000
    }
}

/** Plays audio straight from a byte array. One at a time. */
class VoicePlayer {
    private var player: MediaPlayer? = null
    var playingId: String? = null
        private set

    fun play(id: String, audio: ByteArray, onDone: () -> Unit) {
        stop()
        val p = MediaPlayer()
        p.setDataSource(BytesSource(audio))
        p.setOnCompletionListener {
            stop()
            onDone()
        }
        p.prepare()
        p.start()
        player = p
        playingId = id
    }

    val positionMs get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingId = null
    }

    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize() = data.size.toLong()
        override fun close() = Unit
    }
}
