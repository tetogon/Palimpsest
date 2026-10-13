package app.palimpsest.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.palimpsest.ImagePrep
import app.palimpsest.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Grab { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, RIGHT, TOP, BOTTOM }

/**
 * Crop and rotate a photo before it is stored. Works on the decoded picture in memory;
 * [onDone] gets the (possibly turned) picture and the crop as fractions of it.
 */
@Composable
fun CropDialog(bitmap: Bitmap, onDone: (Bitmap, RectF) -> Unit, onCancel: () -> Unit) {
    var image by remember { mutableStateOf(bitmap) }
    // Crop in fractions of the picture: 0..1 on both axes.
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val handle = with(density) { 28.dp.toPx() }
    val minSize = 0.08f

    fun turn(quarters: Int) {
        if (busy) return
        busy = true
        scope.launch {
            image = withContext(Dispatchers.Default) { ImagePrep.turned(image, quarters) }
            crop = Rect(0f, 0f, 1f, 1f)
            busy = false
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.cancel), tint = Color.White) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = min(boxW / image.width, boxH / image.height)
                val shown = Size(image.width * scale, image.height * scale)
                val origin = Offset((boxW - shown.width) / 2, (boxH - shown.height) / 2)
                fun toScreen(r: Rect) = Rect(
                    origin.x + r.left * shown.width, origin.y + r.top * shown.height,
                    origin.x + r.right * shown.width, origin.y + r.bottom * shown.height,
                )
                var grab by remember { mutableStateOf(Grab.NONE) }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(image, boxW, boxH) {
                        detectDragGestures(
                            onDragStart = { p ->
                                val s = toScreen(crop)
                                fun near(a: Offset) = abs(p.x - a.x) < handle && abs(p.y - a.y) < handle
                                grab = when {
                                    near(s.topLeft) -> Grab.TOP_LEFT
                                    near(s.topRight) -> Grab.TOP_RIGHT
                                    near(s.bottomLeft) -> Grab.BOTTOM_LEFT
                                    near(s.bottomRight) -> Grab.BOTTOM_RIGHT
                                    abs(p.x - s.left) < handle && p.y in s.top..s.bottom -> Grab.LEFT
                                    abs(p.x - s.right) < handle && p.y in s.top..s.bottom -> Grab.RIGHT
                                    abs(p.y - s.top) < handle && p.x in s.left..s.right -> Grab.TOP
                                    abs(p.y - s.bottom) < handle && p.x in s.left..s.right -> Grab.BOTTOM
                                    s.contains(p) -> Grab.MOVE
                                    else -> Grab.NONE
                                }
                            },
                            onDragEnd = { grab = Grab.NONE },
                            onDragCancel = { grab = Grab.NONE },
                            onDrag = { change, d ->
                                change.consume()
                                val dx = d.x / shown.width
                                val dy = d.y / shown.height
                                val c = crop
                                crop = when (grab) {
                                    Grab.MOVE -> {
                                        val mx = dx.coerceIn(-c.left, 1f - c.right)
                                        val my = dy.coerceIn(-c.top, 1f - c.bottom)
                                        c.translate(mx, my)
                                    }
                                    Grab.NONE -> c
                                    else -> {
                                        var (l, t, r, b) = listOf(c.left, c.top, c.right, c.bottom)
                                        if (grab in setOf(Grab.TOP_LEFT, Grab.BOTTOM_LEFT, Grab.LEFT)) l = (l + dx).coerceIn(0f, r - minSize)
                                        if (grab in setOf(Grab.TOP_RIGHT, Grab.BOTTOM_RIGHT, Grab.RIGHT)) r = (r + dx).coerceIn(l + minSize, 1f)
                                        if (grab in setOf(Grab.TOP_LEFT, Grab.TOP_RIGHT, Grab.TOP)) t = (t + dy).coerceIn(0f, b - minSize)
                                        if (grab in setOf(Grab.BOTTOM_LEFT, Grab.BOTTOM_RIGHT, Grab.BOTTOM)) b = (b + dy).coerceIn(t + minSize, 1f)
                                        Rect(l, t, r, b)
                                    }
                                }
                            },
                        )
                    },
                ) {
                    drawImage(
                        image.asImageBitmap(),
                        dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                        dstSize = IntSize(shown.width.roundToInt(), shown.height.roundToInt()),
                    )
                    val s = toScreen(crop)
                    val shade = Color.Black.copy(alpha = 0.6f)
                    val all = Rect(origin, shown)
                    drawRect(shade, all.topLeft, Size(all.width, s.top - all.top))
                    drawRect(shade, Offset(all.left, s.bottom), Size(all.width, all.bottom - s.bottom))
                    drawRect(shade, Offset(all.left, s.top), Size(s.left - all.left, s.height))
                    drawRect(shade, Offset(s.right, s.top), Size(all.right - s.right, s.height))
                    val thin = with(density) { 1.dp.toPx() }
                    for (i in 1..2) {
                        val x = s.left + s.width * i / 3
                        val y = s.top + s.height * i / 3
                        drawLine(Color.White.copy(alpha = 0.5f), Offset(x, s.top), Offset(x, s.bottom), thin)
                        drawLine(Color.White.copy(alpha = 0.5f), Offset(s.left, y), Offset(s.right, y), thin)
                    }
                    drawRect(Color.White, s.topLeft, s.size, style = Stroke(with(density) { 2.dp.toPx() }))
                    val arm = with(density) { 18.dp.toPx() }
                    val thick = with(density) { 4.dp.toPx() }
                    for ((corner, sx, sy) in listOf(
                        Triple(s.topLeft, 1f, 1f), Triple(s.topRight, -1f, 1f),
                        Triple(s.bottomLeft, 1f, -1f), Triple(s.bottomRight, -1f, -1f),
                    )) {
                        drawLine(Color.White, corner, corner + Offset(arm * sx, 0f), thick)
                        drawLine(Color.White, corner, corner + Offset(0f, arm * sy), thick)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    IconButton(onClick = { turn(-1) }) { Icon(RotateLeftIcon, stringResource(R.string.rotate_left), tint = Color.White) }
                    IconButton(onClick = { turn(1) }) { Icon(RotateRightIcon, stringResource(R.string.rotate_right), tint = Color.White) }
                    IconButton(onClick = { crop = Rect(0f, 0f, 1f, 1f) }) { Icon(Icons.Filled.Refresh, stringResource(R.string.crop_reset), tint = Color.White) }
                }
                Box {
                    Button(onClick = { onDone(image, RectF(crop.left, crop.top, crop.right, crop.bottom)) }, enabled = !busy) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}
