package app.palimpsest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * Minimal Camera2 still capture into memory: back camera, preview on a TextureView, one JPEG
 * per shot from an ImageReader. Nothing is written to a file or the gallery.
 */
class InMemoryCamera(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensorOrientation = 90

    /** Degrees to rotate the JPEG clockwise for an upright photo (phone held in portrait). */
    val rotation get() = sensorOrientation

    private fun backCamera(): String? = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: manager.cameraIdList.firstOrNull()

    /**
     * Starts the preview. [onReady] gets the preview's width/height ratio for a phone held
     * upright, so the view can be sized without stretching.
     */
    @SuppressLint("MissingPermission") // the caller asks for CAMERA first
    fun open(view: TextureView, onReady: (Float) -> Unit, onError: () -> Unit) {
        val id = backCamera() ?: return onError()
        val chars = manager.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return onError()
        // Largest JPEG up to ~12 MP; ImagePrep scales it down to 2048 px anyway.
        val jpeg = map.getOutputSizes(ImageFormat.JPEG).filter { it.width * it.height <= 12_500_000 }
            .maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
        val aspect = jpeg.width.toFloat() / jpeg.height
        // Preview: same shape as the photo, as large as 1080p allows.
        val preview = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 && it.height <= 1080 }
            .sortedWith(compareBy<Size>({ kotlin.math.abs(it.width.toFloat() / it.height - aspect) > 0.02f }, { -it.width * it.height }))
            .firstOrNull() ?: Size(1280, 960)
        val sideways = sensorOrientation % 180 != 0
        onReady(if (sideways) preview.height.toFloat() / preview.width else preview.width.toFloat() / preview.height)
        reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)

        fun start(texture: SurfaceTexture) {
            texture.setDefaultBufferSize(preview.width, preview.height)
            val previewSurface = Surface(texture)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(
                        listOf(previewSurface, reader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(previewSurface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }
                                runCatching { s.setRepeatingRequest(request.build(), null, handler) }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) = onError()
                        },
                        handler,
                    )
                }

                override fun onDisconnected(camera: CameraDevice) = camera.close()
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    onError()
                }
            }, handler)
        }

        val texture = view.surfaceTexture
        if (texture != null) {
            start(texture)
        } else {
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = start(t)
                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(t: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
            }
        }
    }

    /** One still; [onJpeg] runs on the camera thread with the encoded bytes. */
    fun capture(onJpeg: (ByteArray) -> Unit, onError: () -> Unit) {
        val camera = device ?: return onError()
        val s = session ?: return onError()
        val r = reader ?: return onError()
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()
            onJpeg(bytes)
        }, handler)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(r.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.JPEG_QUALITY, 92.toByte())
        }
        runCatching { s.capture(request.build(), null, handler) }.onFailure { onError() }
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        thread.quitSafely()
    }
}
