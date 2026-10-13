package app.palimpsest.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import app.palimpsest.InMemoryCamera
import app.palimpsest.R
import app.palimpsest.VoiceRecorder
import app.palimpsest.ffi.AttachmentInfo
import kotlinx.coroutines.delay

val AttachmentInfo.isImage get() = mime.startsWith("image/")
val AttachmentInfo.isAudio get() = mime.startsWith("audio/")

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Thumbnail loaded from the core on demand (and cached by the view model). */
@Composable
fun Thumbnail(id: String, load: suspend (String) -> Bitmap?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, id) { value = load(id) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(ImageIcon, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * Photos and voice notes not placed in the text. Tap opens or plays; long press lifts an item,
 * to put it elsewhere in the strip or (a photo, while editing) into the text. A photo dragged
 * out of the text can be dropped back here. In edit mode a × removes an item.
 */
@Composable
fun AttachmentStrip(
    attachments: List<AttachmentInfo>,
    playingId: String?,
    loadThumb: suspend (String) -> Bitmap?,
    editable: Boolean,
    drag: PhotoDrag,
    onOpenImage: (AttachmentInfo) -> Unit,
    onToggleVoice: (AttachmentInfo) -> Unit,
    onRemove: (AttachmentInfo) -> Unit,
) {
    val fromText = drag.item?.docKey != null
    if (attachments.isEmpty() && !fromText) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    SideEffect {
        drag.stripOrder = attachments.map { it.attachmentId }
        drag.slop = with(density) { 24.dp.toPx() }
    }
    DisposableEffect(Unit) { onDispose { drag.strip = null } }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { drag.strip = it.boundsInRoot() }
            .horizontalScroll(rememberScrollState(), enabled = drag.item == null)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (attachments.isEmpty()) {
            Box(
                Modifier.height(96.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.strip_drop_here), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (a in attachments) {
            key(a.attachmentId) {
                DisposableEffect(a.attachmentId) { onDispose { drag.stripItems.remove(a.attachmentId) } }
                val coords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                val lifted = drag.item?.let { it.id == a.attachmentId && it.docKey == null } == true
                Box(
                    Modifier
                        .onGloballyPositioned {
                            coords[0] = it
                            drag.stripItems[a.attachmentId] = it.boundsInRoot()
                        }
                        .graphicsLayer { alpha = if (lifted) 0.3f else 1f }
                        .pointerInput(a.attachmentId) {
                            detectTapGestures(onTap = { if (a.isImage) onOpenImage(a) else onToggleVoice(a) })
                        }
                        .dragHandle(drag, coords, DragItem(a.attachmentId, null, a.isImage)) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        },
                ) {
                    AttachmentTile(a, playingId == a.attachmentId, loadThumb)
                    if (editable && !lifted) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).clickable { onRemove(a) },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, stringResource(R.string.delete), Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One strip item, drawn only: taps and drags are handled by the strip. */
@Composable
private fun AttachmentTile(a: AttachmentInfo, playing: Boolean, loadThumb: suspend (String) -> Bitmap?) {
    val shape = RoundedCornerShape(14.dp)
    if (a.isImage) {
        Thumbnail(a.attachmentId, loadThumb, Modifier.size(96.dp).clip(shape))
    } else {
        Surface(
            shape = shape,
            color = if (playing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.height(96.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (playing) PauseIcon else Icons.Filled.PlayArrow, stringResource(R.string.play), Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Icon(MicIcon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDuration(a.durationMs), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** A photo placed in the text (read mode); tap opens it full screen. */
@Composable
fun EmbeddedPhoto(a: AttachmentInfo, load: suspend (String) -> Bitmap?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, a.attachmentId) { value = load(a.attachmentId) }
    val ratio = if (a.width > 0 && a.height > 0) a.width.toFloat() / a.height else 4f / 3f
    Box(
        modifier.aspectRatio(ratio.coerceIn(0.5f, 3f)).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onOpen),
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
@Composable
fun RemoveAttachmentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.attachment_remove_confirm)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Full-screen photo with pinch-zoom and pan. Decoded in memory, never written to a file. */
@Composable
fun ImageViewer(id: String, load: suspend (String) -> Bitmap?, onClose: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, id) { value = load(id) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val b = bitmap
            if (b == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    b.asImageBitmap(), null,
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    contentScale = ContentScale.Fit,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
            }
        }
    }
}

/**
 * In-app camera: Camera2 captures straight into memory (no file, nothing in the gallery).
 * [onCaptured] gets the JPEG bytes and the rotation to apply.
 */
@Composable
fun CameraCapture(onCaptured: (ByteArray, Int) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val camera = remember { InMemoryCamera(context) }
    var busy by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(3f / 4f) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { camera.close() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                factory = { ctx ->
                    TextureView(ctx).also { view ->
                        camera.open(
                            view,
                            onReady = { a -> view.post { aspect = a } },
                            onError = { view.post { failed = true } },
                        )
                    }
                },
            )
            if (failed) Text(stringResource(R.string.camera_failed), color = Color.White)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
            }
            Box(
                Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 32.dp).size(76.dp)
                    .border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape)
                    .background(if (busy) Color.Gray else Color.White)
                    .combinedClickableShutter(enabled = !busy && !failed) {
                        busy = true
                        val main = android.os.Handler(android.os.Looper.getMainLooper())
                        camera.capture(
                            onJpeg = { bytes -> main.post { onCaptured(bytes, camera.rotation) } },
                            onError = { main.post { busy = false } },
                        )
                    },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableShutter(enabled: Boolean, onClick: () -> Unit) =
    combinedClickable(enabled = enabled, onClick = onClick)

/** Records while shown; Done hands the AAC bytes over, Cancel throws them away. */
@Composable
fun VoiceRecordDialog(onDone: (ByteArray, Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        runCatching { recorder.start() }.onFailure { failed = true }
        onDispose { recorder.cancel() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = recorder.elapsedMs
            delay(200)
        }
    }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(MicIcon, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(if (failed) R.string.voice_failed else R.string.voice_recording)) },
        text = {
            Text(
                formatDuration(elapsed) + " / " + formatDuration(VoiceRecorder.MAX_DURATION_MS.toLong()),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val result = recorder.stop()
                if (result != null) onDone(result.audio, result.durationMs) else onDismiss()
            }, enabled = !failed) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
