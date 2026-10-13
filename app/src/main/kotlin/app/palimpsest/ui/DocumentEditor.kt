package app.palimpsest.ui

import android.graphics.Bitmap
import android.widget.EditText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.palimpsest.ffi.AttachmentInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/** A photo (or voice note) being dragged: from the strip ([docKey] null) or from the text. */
data class DragItem(val id: String, val docKey: Long?, val image: Boolean)

sealed interface DropTarget {
    /** Into the strip, at [index] among the other items; [x] is where the marker goes. */
    data class Strip(val index: Int, val x: Float, val top: Float, val bottom: Float) : DropTarget

    /** Into text block [key] at [offset] (a line start or its end); [y] is where the marker goes. */
    data class Text(val key: Long, val offset: Int, val y: Float) : DropTarget
}

/**
 * Drag and drop of attachments between the strip and the text. Every place that can take a
 * drop reports its bounds in root coordinates; the pointer is in root coordinates too, so
 * scrolling under a held finger keeps the target right.
 */
@Stable
class PhotoDrag {
    var item by mutableStateOf<DragItem?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var target by mutableStateOf<DropTarget?>(null)
        private set

    /** Text takes drops only while editing. */
    var allowText = false
    var viewport = Rect.Zero
    var onDrop: (DragItem, DropTarget) -> Unit = { _, _ -> }

    internal var strip: Rect? = null
    internal var stripOrder: List<String> = emptyList()
    internal val stripItems = HashMap<String, Rect>()
    internal var doc: List<DocBlock> = emptyList()
    internal val blocks = HashMap<Long, Rect>()
    internal val views = HashMap<Long, EditText>()
    internal var slop = 0f

    fun start(item: DragItem, at: Offset) {
        this.item = item
        move(at)
    }

    fun move(at: Offset) {
        pointer = at
        target = compute(at)
    }

    /** After the content scrolled under a finger that did not move. */
    fun refresh() {
        if (item != null) target = compute(pointer)
    }

    fun end() {
        val i = item
        val t = target
        cancel()
        if (i != null && t != null) onDrop(i, t)
    }

    fun cancel() {
        item = null
        target = null
    }

    private fun compute(p: Offset): DropTarget? {
        val dragged = item ?: return null
        strip?.let { s ->
            if (p.y >= s.top - slop && p.y <= s.bottom + slop) return stripTarget(dragged, s, p)
        }
        if (!allowText || !dragged.image) return null
        var best: DocBlock? = null
        var bestRect = Rect.Zero
        var bestDistance = Float.MAX_VALUE
        for (b in doc) {
            val r = blocks[b.key] ?: continue
            val d = if (p.y in r.top..r.bottom) 0f else minOf(abs(p.y - r.top), abs(p.y - r.bottom))
            if (d < bestDistance) {
                bestDistance = d
                best = b
                bestRect = r
            }
        }
        return when (val b = best) {
            is DocText -> views[b.key]?.let { textTarget(b.key, it, bestRect, p.y) } ?: DropTarget.Text(b.key, 0, bestRect.top)
            is DocPhoto -> {
                val i = doc.indexOf(b)
                if (p.y < bestRect.center.y) {
                    val prev = doc[i - 1] as DocText
                    DropTarget.Text(prev.key, prev.text.length, bestRect.top)
                } else {
                    DropTarget.Text(doc[i + 1].key, 0, bestRect.bottom)
                }
            }
            null -> null
        }
    }

    private fun stripTarget(dragged: DragItem, s: Rect, p: Offset): DropTarget.Strip {
        val others = stripOrder.filter { it != dragged.id }
        for ((i, id) in others.withIndex()) {
            val r = stripItems[id] ?: continue
            if (p.x < r.center.x) return DropTarget.Strip(i, r.left - 4f, s.top, s.bottom)
        }
        val last = others.lastOrNull()?.let { stripItems[it] }
        return DropTarget.Strip(others.size, (last?.right ?: s.left) + 4f, s.top, s.bottom)
    }

    /** The paragraph boundary of [view] nearest to [y]. */
    private fun textTarget(key: Long, view: EditText, r: Rect, y: Float): DropTarget.Text {
        val layout = view.layout ?: return DropTarget.Text(key, 0, r.top)
        val text = view.text
        val local = y - r.top
        var best = 0
        var bestY = 0f
        var bestDistance = Float.MAX_VALUE
        fun consider(offset: Int, ly: Float) {
            val d = abs(ly - local)
            if (d < bestDistance) {
                bestDistance = d
                best = offset
                bestY = ly
            }
        }
        for (offset in 0..text.length) {
            if (offset == 0 || text[offset - 1] == '\n') consider(offset, layout.getLineTop(layout.getLineForOffset(offset)).toFloat())
        }
        if (text.isNotEmpty() && text.last() != '\n') consider(text.length, layout.getLineBottom(layout.lineCount - 1).toFloat())
        return DropTarget.Text(key, best, r.top + bestY)
    }
}

/** The lifted item under the finger and the marker where it would land. */
@Composable
fun DragOverlay(drag: PhotoDrag, loadThumb: suspend (String) -> Bitmap?) {
    val item = drag.item ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val ghost = with(density) { 84.dp.toPx() }
    val line = with(density) { 3.dp.toPx() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        when (val t = drag.target) {
            is DropTarget.Text -> {
                val inset = with(density) { 20.dp.toPx() }
                val width = with(density) { (drag.viewport.width - 2 * inset).coerceAtLeast(0f).toDp() }
                Box(
                    Modifier
                        .offset { IntOffset((drag.viewport.left + inset - origin.x).roundToInt(), (t.y - line / 2 - origin.y).roundToInt()) }
                        .size(width, 3.dp)
                        .background(accent, RoundedCornerShape(2.dp)),
                )
            }
            is DropTarget.Strip -> {
                val height = with(density) { (t.bottom - t.top).toDp() }
                Box(
                    Modifier
                        .offset { IntOffset((t.x - line / 2 - origin.x).roundToInt(), (t.top - origin.y).roundToInt()) }
                        .size(3.dp, height)
                        .background(accent, RoundedCornerShape(2.dp)),
                )
            }
            null -> Unit
        }
        val shape = RoundedCornerShape(14.dp)
        val ghostModifier = Modifier
            .offset { IntOffset((drag.pointer.x - ghost / 2 - origin.x).roundToInt(), (drag.pointer.y - ghost / 2 - origin.y).roundToInt()) }
            .size(84.dp)
            .graphicsLayer {
                alpha = 0.9f
                shadowElevation = 18f
                this.shape = shape
                clip = true
                scaleX = 1.05f
                scaleY = 1.05f
            }
        if (item.image) {
            Thumbnail(item.id, loadThumb, ghostModifier)
        } else {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = ghostModifier) {
                Box(contentAlignment = Alignment.Center) { Icon(MicIcon, null) }
            }
        }
    }
}

/**
 * A photo that lifts on long press. Tap goes to [onTap]. [docKey] says where it sits: in the
 * text, or (null) in the strip.
 */
@Composable
fun DraggablePhoto(
    id: String,
    info: AttachmentInfo?,
    docKey: Long?,
    selected: Boolean,
    drag: PhotoDrag,
    load: suspend (String) -> Bitmap?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(null, id) { value = load(id) }
    val tap by rememberUpdatedState(onTap)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(14.dp)
    val ratio = info?.let { if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else null } ?: (4f / 3f)
    val lifted = drag.item?.let { it.id == id && it.docKey == docKey } == true
    Box(
        modifier
            .aspectRatio(ratio.coerceIn(0.5f, 3f))
            .onGloballyPositioned { coords[0] = it }
            .graphicsLayer { alpha = if (lifted) 0.3f else 1f }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .pointerInput(id, docKey) { detectTapGestures(onTap = { tap() }) }
            .dragHandle(drag, coords, DragItem(id, docKey, image = true)) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(ImageIcon, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Long press lifts [item]; moving drags it; release drops it. */
fun Modifier.dragHandle(drag: PhotoDrag, coords: Array<LayoutCoordinates?>, item: DragItem, onLift: () -> Unit) =
    pointerInput(item) {
        detectDragGesturesAfterLongPress(
            onDragStart = { at ->
                coords[0]?.let {
                    onLift()
                    drag.start(item, it.localToRoot(at))
                }
            },
            onDrag = { change, _ ->
                change.consume()
                coords[0]?.let { drag.move(it.localToRoot(change.position)) }
            },
            onDragEnd = { drag.end() },
            onDragCancel = { drag.cancel() },
        )
    }

/**
 * The entry body while editing: text fields with photos between them. A photo that is small
 * or medium and on the left or right has a text field of its own beside it.
 */
@Composable
fun DocumentEditor(
    doc: List<DocBlock>,
    attachments: List<AttachmentInfo>,
    hint: String,
    sideHint: String,
    hideMarkers: Boolean,
    selected: Long?,
    drag: PhotoDrag,
    focus: FocusRequest?,
    controller: (Long) -> MdController,
    loadImage: suspend (String) -> Bitmap?,
    onText: (Long, String) -> Unit,
    onSide: (Long, String) -> Unit,
    onFocused: (MdController) -> Unit,
    onSelect: (Long?) -> Unit,
    onFocusHandled: () -> Unit,
    find: List<String> = emptyList(),
    onDoubleBracket: (MdController) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        doc.forEachIndexed { i, b ->
            key(b.key) {
                DisposableEffect(b.key) { onDispose { drag.blocks.remove(b.key) } }
                when (b) {
                    is DocText -> {
                        val c = controller(b.key)
                        val previous = doc.getOrNull(i - 1)?.key
                        MarkdownEditField(
                            b.text, { onText(b.key, it) },
                            if (i == 0) hint else "",
                            c, hideMarkers,
                            Modifier.fillMaxWidth().onGloballyPositioned { drag.blocks[b.key] = it.boundsInRoot() },
                            onFocused = { onFocused(c) },
                            // Backspace at the start of the text after a photo selects the photo.
                            onBackspaceAtStart = previous?.let { { onSelect(it) } },
                            focusAt = focus?.takeIf { it.key == b.key }?.offset,
                            onFocusHandled = onFocusHandled,
                            onView = { v -> if (v != null) drag.views[b.key] = v else drag.views.remove(b.key) },
                            find = find,
                            onDoubleBracket = { onDoubleBracket(c) },
                        )
                    }
                    is DocPhoto -> {
                        val info = attachments.firstOrNull { it.attachmentId == b.id }
                        val photo = @Composable { m: Modifier ->
                            DraggablePhoto(b.id, info, b.key, selected == b.key, drag, loadImage, onTap = {
                                onSelect(if (selected == b.key) null else b.key)
                            }, modifier = m)
                        }
                        PhotoRow(b, Modifier.onGloballyPositioned { drag.blocks[b.key] = it.boundsInRoot() }, photo) { m ->
                            val c = controller(-b.key)
                            MarkdownEditField(
                                b.side, { onSide(b.key, it) }, sideHint, c, hideMarkers, m,
                                textSizeSp = 17f,
                                onFocused = { onFocused(c) },
                                onBackspaceAtStart = { onSelect(b.key) },
                                focusAt = focus?.takeIf { it.key == -b.key }?.offset,
                                onFocusHandled = onFocusHandled,
                                find = find,
                                onDoubleBracket = { onDoubleBracket(c) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The entry body in read mode, laid out exactly as in the editor. */
@Composable
fun DocumentView(
    doc: List<DocBlock>,
    attachments: List<AttachmentInfo>,
    loadImage: suspend (String) -> Bitmap?,
    onOpen: (String) -> Unit,
    onToggleTask: (key: Long, line: Int) -> Unit,
    onLink: (String) -> Unit,
    find: List<String> = emptyList(),
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in doc) {
            key(b.key) {
                when (b) {
                    is DocText -> if (b.text.isNotBlank()) MarkdownText(b.text, { onToggleTask(b.key, it) }, onLink, find = find)
                    is DocPhoto -> {
                        val info = attachments.firstOrNull { it.attachmentId == b.id && it.isImage }
                        if (info != null) {
                            PhotoRow(b, Modifier, { m -> EmbeddedPhoto(info, loadImage, onOpen = { onOpen(b.id) }, modifier = m) }) { m ->
                                MarkdownText(b.side, { onToggleTask(-b.key, it) }, onLink, m, find = find)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A photo on its own line (left, centre or right), or beside its [side] text. */
@Composable
private fun PhotoRow(
    p: DocPhoto,
    modifier: Modifier,
    photo: @Composable (Modifier) -> Unit,
    side: @Composable (Modifier) -> Unit,
) {
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (p.wraps) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (p.align == MdAlign.START) photo(Modifier.fillMaxWidth(p.size.fraction))
                side(Modifier.weight(1f))
                if (p.align == MdAlign.END) photo(Modifier.fillMaxWidth(p.size.fraction))
            }
        } else {
            val align = when (p.align) {
                MdAlign.START -> Alignment.TopStart
                MdAlign.CENTER -> Alignment.TopCenter
                MdAlign.END -> Alignment.TopEnd
            }
            photo(Modifier.fillMaxWidth(p.size.fraction).align(align))
        }
    }
}
