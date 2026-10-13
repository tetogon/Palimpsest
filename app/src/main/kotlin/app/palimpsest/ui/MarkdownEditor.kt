package app.palimpsest.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.palimpsest.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Our spans carry this marker so re-highlighting removes only them. */
private interface MdMark
private class MdStyle(style: Int) : StyleSpan(style), MdMark
private class MdRelSize(size: Float) : RelativeSizeSpan(size), MdMark
private class MdColor(color: Int) : ForegroundColorSpan(color), MdMark
private class MdBack(color: Int) : BackgroundColorSpan(color), MdMark
private class MdStrike : StrikethroughSpan(), MdMark
private class MdUnderline : UnderlineSpan(), MdMark
private class MdMono : TypefaceSpan("monospace"), MdMark
private class MdQuote(color: Int) : QuoteSpan(color), MdMark
private class MdAlignSpan(align: Layout.Alignment) : AlignmentSpan.Standard(align), MdMark

/** A Markdown marker taking no room: `**`, `# `, `:-: `… on lines away from the cursor. */
private class MdHidden : ReplacementSpan(), MdMark {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return 0
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
}

/** A marker drawn as a symbol: `-` as •, `- [ ]` as ☐. */
private class MdGlyph(private val glyph: String, private val color: Int) : ReplacementSpan(), MdMark {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return paint.measureText(glyph).roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val old = paint.color
        paint.color = color
        canvas.drawText(glyph, x, y.toFloat(), paint)
        paint.color = old
    }
}

private class HighlightColors(var marker: Int = 0, var accent: Int = 0, var muted: Int = 0, var code: Int = 0, var dim: Int = 0)

/** Live Markdown styling inside the EditText. The text itself is never changed. */
private object Highlighter {
    private const val MAX_LENGTH = 60_000

    /** [activeStart]..[activeEnd]: the lines holding the selection, -1 when unfocused. */
    fun apply(e: Editable, c: HighlightColors, hideMarkers: Boolean, activeStart: Int, activeEnd: Int, find: List<String> = emptyList()) {
        e.getSpans(0, e.length, MdMark::class.java).forEach(e::removeSpan)
        if (e.length > MAX_LENGTH) return
        fun active(s: Int, t: Int) = activeStart >= 0 && s <= activeEnd && t >= activeStart
        val text = e.toString()
        var lineStart = 0
        for (line in text.split('\n')) {
            val end = lineStart + line.length
            styleLine(e, line, lineStart, c, hide = hideMarkers && !active(lineStart, end))
            lineStart = end + 1
        }
        for (m in Md.INLINE.findAll(text)) {
            val g = m.groups
            val r = m.range
            val hide = hideMarkers && !active(r.first, r.last + 1)
            when {
                g[1] != null -> {
                    span(e, MdMono(), r.first, r.last + 1)
                    span(e, MdBack(c.code), r.first, r.last + 1)
                    if (hide) {
                        span(e, MdHidden(), r.first, r.first + 1)
                        span(e, MdHidden(), r.last, r.last + 1)
                    }
                }
                g[2] != null || g[3] != null -> inner(e, r, 2, c, hide) { MdStyle(Typeface.BOLD) }
                g[4] != null -> inner(e, r, 2, c, hide) { MdStrike() }
                g[5] != null || g[6] != null -> inner(e, r, 1, c, hide) { MdStyle(Typeface.ITALIC) }
                g[7] != null -> {
                    val textEnd = g[7]!!.range.last + 1
                    span(e, MdColor(c.accent), r.first, r.last + 1)
                    if (hide) {
                        span(e, MdUnderline(), r.first + 1, textEnd)
                        span(e, MdHidden(), r.first, r.first + 1)
                        span(e, MdHidden(), textEnd, r.last + 1)
                    }
                }
                g[9] != null -> span(e, MdColor(c.accent), r.first, r.last + 1)
            }
        }
        // Search: everything but the matches fades. Added last, so it wins over other colours.
        if (find.isNotEmpty()) {
            var at = 0
            for (r in Md.findRanges(text, find)) {
                span(e, MdColor(c.dim), at, r.first)
                at = r.last + 1
            }
            span(e, MdColor(c.dim), at, text.length)
        }
    }

    private fun styleLine(e: Editable, line: String, at: Int, c: HighlightColors, hide: Boolean) {
        val end = at + line.length
        var body = line
        var bodyAt = at
        val alignMatch = Md.ALIGN.find(line)
        if (alignMatch != null) {
            val prefix = alignMatch.range.last + 1
            span(e, if (hide) MdHidden() else MdColor(c.marker), at, at + prefix)
            val align = if (Md.alignOf(alignMatch.groupValues[1]) == MdAlign.CENTER) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_OPPOSITE
            span(e, MdAlignSpan(align), at, end)
            body = line.substring(prefix)
            bodyAt = at + prefix
        }
        Md.HEADING.matchEntire(body)?.let { m ->
            val level = m.groupValues[1].length
            span(e, MdRelSize(floatArrayOf(1.45f, 1.3f, 1.15f)[level - 1]), bodyAt, end)
            span(e, MdStyle(Typeface.BOLD), bodyAt, end)
            val markerEnd = bodyAt + (m.groups[2]?.range?.first ?: level)
            if (hide) span(e, MdHidden(), bodyAt, markerEnd) else span(e, MdColor(c.marker), bodyAt, bodyAt + level)
            return
        }
        // Aligned lines are plain paragraphs (see Md.parse).
        if (alignMatch != null) return
        Md.TASK.matchEntire(line)?.let { m ->
            val indent = m.groupValues[1].length
            val markerEnd = (at + indent + line.trimStart().indexOf(']') + 1).coerceAtMost(end)
            val checked = m.groupValues[2] != " "
            if (hide) span(e, MdGlyph(if (checked) "☑" else "☐", c.accent), at + indent, markerEnd)
            else span(e, MdColor(c.accent), at, markerEnd)
            if (checked) span(e, MdColor(c.muted), markerEnd, end)
            return
        }
        Md.BULLET.matchEntire(line)?.let { m ->
            val indent = m.groupValues[1].length
            if (hide) span(e, MdGlyph("•", c.accent), at + indent, at + indent + 1)
            else span(e, MdColor(c.accent), at, at + indent + 1)
            return
        }
        Md.NUMBERED.matchEntire(line)?.let { m ->
            val markerEnd = at + m.groupValues[1].length + m.groupValues[2].length + 1
            span(e, MdColor(c.accent), at, markerEnd)
            return
        }
        Md.QUOTE.matchEntire(line)?.let { m ->
            span(e, MdColor(c.muted), at, end)
            span(e, MdStyle(Typeface.ITALIC), at, end)
            if (hide) {
                span(e, MdHidden(), at, end - m.groupValues[1].length)
                span(e, MdQuote(c.accent), at, end)
            }
        }
    }

    /** Style the inside of a delimited token; dim its delimiters, or hide them. */
    private fun inner(e: Editable, r: IntRange, delimiter: Int, c: HighlightColors, hide: Boolean, style: () -> Any) {
        val start = r.first
        val end = r.last + 1
        span(e, style(), start + delimiter, end - delimiter)
        span(e, if (hide) MdHidden() else MdColor(c.marker), start, start + delimiter)
        span(e, if (hide) MdHidden() else MdColor(c.marker), end - delimiter, end)
    }

    private fun span(e: Editable, what: Any, start: Int, end: Int) {
        if (start < end) e.setSpan(what, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/**
 * Toolbar commands and undo/redo for one [MarkdownEditField]. Edits go straight into the
 * EditText's Editable, so the cursor and the keyboard stay where they are.
 */
class MdController {
    internal var view: EditText? = null

    private data class Snapshot(val text: String, val start: Int, val end: Int)

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private var lastChange = 0L
    private var restoring = false

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Called when a new undo step is recorded, so the editor can order steps across fields. */
    var onSnapshot: (() -> Unit)? = null

    var alignAtCursor by mutableStateOf(MdAlign.START)
        private set

    internal fun selectionChanged() {
        val v = view ?: return
        val (ls, le) = lineBounds(v.text, v.selectionStart)
        alignAtCursor = Md.alignOf(Md.ALIGN.find(v.text.substring(ls, le))?.groupValues?.get(1))
    }

    /** Typing bursts become one undo step: a snapshot is kept when a pause precedes a change. */
    internal fun beforeChange(force: Boolean = false) {
        val v = view ?: return
        if (restoring) return
        val now = SystemClock.uptimeMillis()
        if (force || undo.isEmpty() || now - lastChange > 900) {
            undo.addLast(Snapshot(v.text.toString(), v.selectionStart, v.selectionEnd))
            if (undo.size > 200) undo.removeFirst()
            redo.clear()
            onSnapshot?.invoke()
        }
        lastChange = now
        sync()
    }

    /** False if there was nothing to undo here (or the field is gone). */
    fun undo() = step(undo, redo)

    fun redo() = step(redo, undo)

    /** Text set from outside (the blocks changed): not an undo step of this field. */
    internal fun <T> quietly(block: () -> T): T {
        val was = restoring
        restoring = true
        try {
            return block()
        } finally {
            restoring = was
        }
    }

    private fun step(from: ArrayDeque<Snapshot>, to: ArrayDeque<Snapshot>): Boolean {
        val v = view ?: return false
        val target = from.removeLastOrNull() ?: return false
        to.addLast(Snapshot(v.text.toString(), v.selectionStart, v.selectionEnd))
        restoring = true
        v.setText(target.text)
        v.setSelection(target.start.coerceIn(0, v.length()), target.end.coerceIn(0, v.length()))
        restoring = false
        lastChange = 0
        sync()
        return true
    }

    private fun sync() {
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
    }

    private inline fun edit(block: (Editable, Int, Int) -> Unit) {
        val v = view ?: return
        beforeChange(force = true)
        val s = minOf(v.selectionStart, v.selectionEnd).coerceAtLeast(0)
        val e = maxOf(v.selectionStart, v.selectionEnd).coerceAtLeast(0)
        block(v.text, s, e)
        v.requestFocus()
    }

    /** **bold**, *italic*, ~~strike~~, `code`: wrap the selection or open an empty pair. */
    fun wrap(mark: String) = edit { text, s, e ->
        if (s != e) {
            text.insert(e, mark)
            text.insert(s, mark)
            view?.setSelection(s + mark.length, e + mark.length)
        } else {
            text.insert(s, mark + mark)
            view?.setSelection(s + mark.length)
        }
    }

    fun link() = edit { text, s, e ->
        if (s != e) {
            text.insert(e, "](https://)")
            text.insert(s, "[")
            view?.setSelection(e + 3, e + 11) // "[sel](" shifted by one: selects "https://" to type over
        } else {
            text.insert(s, "[](https://)")
            view?.setSelection(s + 1)
        }
    }

    /**
     * A link to another entry, `[title](note:<id>)`, at the cursor. With [afterBrackets] the
     * `[[` just typed is replaced.
     */
    fun noteLink(title: String, id: String, afterBrackets: Boolean) = edit { text, s, e ->
        val start = if (afterBrackets && s >= 2 && text.substring(s - 2, s) == "[[") s - 2 else s
        val label = title.replace(Regex("""[\[\]\n]"""), " ").trim().ifEmpty { "…" }
        val link = "[$label](note:$id)"
        text.replace(start, e, link)
        view?.setSelection((start + link.length).coerceAtMost(text.length))
    }

    /** Cycles the current line through #, ##, ### and plain text, keeping its alignment. */
    fun heading() = edit { text, s, _ ->
        val (ls, le) = lineBounds(text, s)
        val line = text.substring(ls, le)
        val prefix = Md.ALIGN.find(line)?.value.orEmpty()
        val rest = line.substring(prefix.length)
        val level = Md.HEADING.matchEntire(rest)?.groupValues?.get(1)?.length ?: 0
        val content = rest.replaceFirst(Regex("""^#{1,3}\s+"""), "")
        val next = prefix + if (level >= 3) content else "#".repeat(level + 1) + " " + content
        text.replace(ls, le, next)
        view?.setSelection((ls + next.length).coerceAtMost(text.length))
    }

    /** Left → centre → right for the selected lines. */
    fun cycleAlign() {
        val next = when (alignAtCursor) {
            MdAlign.START -> MdAlign.CENTER
            MdAlign.CENTER -> MdAlign.END
            MdAlign.END -> MdAlign.START
        }
        align(next)
    }

    fun align(to: MdAlign) = edit { text, s, e ->
        val (ls, _) = lineBounds(text, s)
        val (_, le) = lineBounds(text, e)
        val prefix = when (to) {
            MdAlign.START -> ""
            MdAlign.CENTER -> ":-: "
            MdAlign.END -> "--: "
        }
        val out = text.substring(ls, le).split('\n').joinToString("\n") { line ->
            if (line.isBlank()) line else prefix + line.replaceFirst(Md.ALIGN, "")
        }
        text.replace(ls, le, out)
        view?.setSelection((ls + out.length).coerceAtMost(text.length))
        selectionChanged()
    }

    /**
     * Turns the selected lines into list items of [kind], or back into plain lines if they
     * already are. Other list markers are replaced.
     */
    fun list(kind: ListKind) = edit { text, s, e ->
        val (ls, _) = lineBounds(text, s)
        val (_, le) = lineBounds(text, e)
        val lines = text.substring(ls, le).split('\n')
        val all = lines.all { kind.matches(it) }
        var n = 0
        val out = lines.joinToString("\n") { line ->
            val bare = stripMarker(line)
            when {
                all -> bare
                line.isBlank() && lines.size > 1 -> line
                else -> kind.prefix(++n) + bare
            }
        }
        text.replace(ls, le, out)
        view?.setSelection((ls + out.length).coerceAtMost(text.length))
    }

    enum class ListKind {
        BULLET, NUMBERED, TASK, QUOTE;

        fun prefix(n: Int) = when (this) {
            BULLET -> "- "
            NUMBERED -> "$n. "
            TASK -> "- [ ] "
            QUOTE -> "> "
        }

        fun matches(line: String) = when (this) {
            BULLET -> Md.BULLET.matches(line) && !Md.TASK.matches(line)
            NUMBERED -> Md.NUMBERED.matches(line)
            TASK -> Md.TASK.matches(line)
            QUOTE -> Md.QUOTE.matches(line)
        }
    }

    private fun stripMarker(line: String) = line.replaceFirst(Md.ALIGN, "").trimStart()
        .replaceFirst(Regex("""^([-*+]\s+\[[ xX]]\s?|[-*+]\s+|\d{1,9}[.)]\s+|>\s?)"""), "")

    /**
     * After Enter at the end of a list item, start the next item; Enter on an empty item ends
     * the list instead.
     */
    internal fun continueList(text: Editable, newline: Int) {
        val (ls, _) = lineBounds(text, newline)
        val prev = text.substring(ls, newline)
        val task = Md.TASK.matchEntire(prev)
        val bullet = Md.BULLET.matchEntire(prev)
        val numbered = Md.NUMBERED.matchEntire(prev)
        val (indent, prefix, content) = when {
            task != null -> Triple(task.groupValues[1], "- [ ] ", task.groupValues[3])
            bullet != null -> Triple(bullet.groupValues[1], bullet.groupValues[2] + " ", bullet.groupValues[3])
            numbered != null -> Triple(
                numbered.groupValues[1],
                "${(numbered.groupValues[2].toLongOrNull() ?: 0) + 1}${numbered.groupValues[3]} ",
                numbered.groupValues[4],
            )
            else -> return
        }
        if (content.isBlank()) {
            text.replace(ls, newline + 1, "") // empty item: drop it and end the list
        } else {
            text.insert(newline + 1, indent + prefix)
        }
    }

    private fun lineBounds(text: CharSequence, at: Int): Pair<Int, Int> {
        val p = at.coerceIn(0, text.length)
        var s = p
        while (s > 0 && text[s - 1] != '\n') s--
        var e = p
        while (e < text.length && text[e] != '\n') e++
        return s to e
    }
}

/** The editor's EditText: reports cursor moves and focus, and Backspace at its very start. */
private class MarkdownEditText(context: Context) : SecureEditText(context) {
    var onCursor: (() -> Unit)? = null
    var onBackAtStart: (() -> Unit)? = null

    private val atStart get() = selectionStart == 0 && selectionEnd == 0

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onCursor?.invoke()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        onCursor?.invoke()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val back = onBackAtStart
                if (back != null && atStart && beforeLength > 0 && afterLength == 0) {
                    back()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val back = onBackAtStart
                if (back != null && atStart && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (event.action == KeyEvent.ACTION_DOWN) back()
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}

/** Where to put the cursor when a field should take focus (e.g. text moved down to it). */
data class FocusRequest(val key: Long, val offset: Int)

/**
 * Multi-line secure field with live Markdown styling, driven by [controller]. It grows with
 * its text (the screen scrolls, not the field) and keeps the cursor in view through
 * [bringIntoViewRequester]. With [hideMarkers] the Markdown symbols show only on the lines
 * holding the cursor. [onView] hands out the EditText, for placing dropped photos.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkdownEditField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    controller: MdController,
    hideMarkers: Boolean,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 18f,
    onFocused: () -> Unit = {},
    onBackspaceAtStart: (() -> Unit)? = null,
    focusAt: Int? = null,
    onFocusHandled: () -> Unit = {},
    onView: (EditText?) -> Unit = {},
    find: List<String> = emptyList(),
    onDoubleBracket: () -> Unit = {},
) {
    val terms by rememberUpdatedState(find)
    val brackets by rememberUpdatedState(onDoubleBracket)
    // Matches of a search inside the entry, so the find bar can jump to them.
    val hits = LocalFindHits.current
    val findKey = remember { Any() }
    DisposableEffect(hits) { onDispose { hits?.remove(findKey) } }
    val onChange by rememberUpdatedState(onValueChange)
    val focused by rememberUpdatedState(onFocused)
    val back by rememberUpdatedState(onBackspaceAtStart)
    val hide by rememberUpdatedState(hideMarkers)
    val scheme = MaterialTheme.colorScheme
    val colors = remember { HighlightColors() }
    colors.marker = scheme.outline.toArgb()
    colors.accent = scheme.primary.toArgb()
    colors.muted = scheme.onSurfaceVariant.toArgb()
    colors.code = scheme.surfaceContainerHighest.toArgb()
    colors.dim = scheme.onSurface.copy(alpha = 0.32f).toArgb()
    val textColor = scheme.onSurface.toArgb()
    val hintColor = scheme.onSurfaceVariant.copy(alpha = 0.7f).toArgb()
    val highlight = scheme.primary.copy(alpha = 0.3f).toArgb()

    val scope = rememberCoroutineScope()
    val requester = remember { BringIntoViewRequester() }
    val ref = remember { arrayOfNulls<MarkdownEditText>(1) }
    val echo = remember { EchoGuard() }
    val refresh = remember { arrayOf<() -> Unit>({}) }

    DisposableEffect(controller) {
        onDispose {
            if (controller.view === ref[0]) controller.view = null
            onView(null)
        }
    }
    LaunchedEffect(hideMarkers, find) { refresh[0]() }
    LaunchedEffect(focusAt) {
        val v = ref[0] ?: return@LaunchedEffect
        if (focusAt == null) return@LaunchedEffect
        v.requestFocus()
        v.setSelection(focusAt.coerceIn(0, v.length()))
        v.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0)
        onFocusHandled()
    }
    AndroidView(
        modifier = modifier.bringIntoViewRequester(requester),
        factory = { context ->
            MarkdownEditText(context).apply {
                ref[0] = this
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                gravity = Gravity.TOP or Gravity.START
                typeface = Typeface.SERIF
                textSize = textSizeSp
                setLineSpacing(0f, 1.35f)
                background = null
                setPadding(0, 0, 0, 0)
                isVerticalScrollBarEnabled = false
                this.hint = hint
                val view = this

                fun activeLines(): Pair<Int, Int> {
                    if (!hasFocus()) return -1 to -1
                    val t = text
                    var s = minOf(selectionStart, selectionEnd).coerceIn(0, t.length)
                    var e = maxOf(selectionStart, selectionEnd).coerceIn(0, t.length)
                    while (s > 0 && t[s - 1] != '\n') s--
                    while (e < t.length && t[e] != '\n') e++
                    return s to e
                }

                fun highlightNow() {
                    val (s, e) = activeLines()
                    Highlighter.apply(text, colors, hide, s, e, terms)
                    val now = text.toString()
                    hits?.text(findKey, now, if (terms.isEmpty()) emptyList() else Md.findRanges(now, terms))
                }
                refresh[0] = ::highlightNow

                /** Scrolls the screen so the cursor line stays above the keyboard. */
                fun revealCursor() {
                    if (!hasFocus()) return
                    post {
                        val l = layout ?: return@post
                        val line = l.getLineForOffset(selectionEnd.coerceAtLeast(0))
                        val pad = 24 * resources.displayMetrics.density
                        val rect = Rect(0f, l.getLineTop(line) - pad, width.toFloat(), l.getLineBottom(line) + pad)
                        scope.launch { requester.bringIntoView(rect) }
                    }
                }

                var lastActive = -2 to -2
                onCursor = {
                    if (hasFocus()) {
                        if (controller.view !== view) controller.view = view
                        focused()
                    }
                    controller.selectionChanged()
                    // Re-style only when the cursor moves to another line. Distance from the
                    // end, so typing within the line does not count as a move.
                    val (s, e) = activeLines()
                    val active = if (s < 0) s to e else s to text.length - e
                    if (active != lastActive) {
                        lastActive = active
                        highlightNow()
                    }
                    revealCursor()
                }
                onBackAtStart = { back?.invoke() }
                setText(value)
                echo.emitted = value
                controller.view = this
                onView(this)
                hits?.view(findKey, this)
                var newlineAt = -1
                var bracketsTyped = false
                var internal = false
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                        if (!internal) controller.beforeChange()
                    }

                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                        if (internal) return
                        newlineAt = if (count == 1 && before == 0 && s.getOrNull(start) == '\n') start else -1
                        // "[[" starts a link to another entry.
                        bracketsTyped = count == 1 && before == 0 && s.getOrNull(start) == '[' && s.getOrNull(start - 1) == '['
                    }

                    override fun afterTextChanged(s: Editable) {
                        if (internal) return
                        if (newlineAt >= 0) {
                            internal = true
                            try {
                                controller.continueList(s, newlineAt)
                            } finally {
                                internal = false
                            }
                            newlineAt = -1
                        }
                        highlightNow()
                        controller.selectionChanged()
                        val text = s.toString()
                        echo.emitted = text
                        onChange(text)
                        if (bracketsTyped) {
                            bracketsTyped = false
                            post { brackets() }
                        }
                    }
                })
                highlightNow()
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.setHintTextColor(hintColor)
            view.highlightColor = highlight
            view.onBackAtStart = if (onBackspaceAtStart != null) ({ back?.invoke() }) else null
            controller.quietly { echo.apply(view, value) }
        },
    )
}

/**
 * Formatting bar above the keyboard, with undo/redo and the word count. [tools], when given,
 * replace the text tools (a photo is selected).
 */
@Composable
fun FormatBar(
    controller: MdController?,
    words: Int,
    onAttach: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNoteLink: () -> Unit,
    modifier: Modifier = Modifier,
    tools: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            IconTool(AttachIcon, R.string.attach, onAttach)
            VerticalDivider(Modifier.height(24.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (tools != null) {
                    tools()
                } else if (controller != null) {
                    TextTool("H", stringResource(R.string.md_heading), FontWeight.Bold) { controller.heading() }
                    TextTool("B", stringResource(R.string.md_bold), FontWeight.Black) { controller.wrap("**") }
                    TextTool("I", stringResource(R.string.md_italic), FontWeight.Normal, FontStyle.Italic) { controller.wrap("*") }
                    TextTool("S", stringResource(R.string.md_strike), FontWeight.Normal, decoration = TextDecoration.LineThrough) { controller.wrap("~~") }
                    val alignIcon = when (controller.alignAtCursor) {
                        MdAlign.START -> AlignLeftIcon
                        MdAlign.CENTER -> AlignCenterIcon
                        MdAlign.END -> AlignRightIcon
                    }
                    IconTool(alignIcon, R.string.md_align, controller::cycleAlign)
                    IconTool(BulletListIcon, R.string.md_bullets) { controller.list(MdController.ListKind.BULLET) }
                    IconTool(NumberedListIcon, R.string.md_numbers) { controller.list(MdController.ListKind.NUMBERED) }
                    IconTool(CheckBoxIcon, R.string.md_tasks) { controller.list(MdController.ListKind.TASK) }
                    IconTool(QuoteIcon, R.string.md_quote) { controller.list(MdController.ListKind.QUOTE) }
                    IconTool(CodeIcon, R.string.md_code) { controller.wrap("`") }
                    IconTool(NoteLinkIcon, R.string.note_link, onNoteLink)
                    IconTool(LinkIcon, R.string.md_link) { controller.link() }
                }
            }
            VerticalDivider(Modifier.height(24.dp))
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(UndoIcon, stringResource(R.string.undo)) }
            IconButton(onClick = onRedo, enabled = canRedo) { Icon(RedoIcon, stringResource(R.string.redo)) }
            if (tools == null) {
                Text(
                    pluralStringResource(R.plurals.word_count, words, words),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TextTool(
    label: String,
    description: String,
    weight: FontWeight,
    style: FontStyle = FontStyle.Normal,
    decoration: TextDecoration? = null,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Text(
            label,
            fontWeight = weight,
            fontStyle = style,
            textDecoration = decoration,
            fontFamily = FontFamily.Serif,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(2.dp),
        )
    }
}

@Composable
internal fun IconTool(icon: androidx.compose.ui.graphics.vector.ImageVector, description: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, stringResource(description)) }
}

@Composable
internal fun ToggleTool(icon: androidx.compose.ui.graphics.vector.ImageVector, description: Int, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        FilledTonalIconButton(onClick = onClick) { Icon(icon, stringResource(description)) }
    } else {
        IconButton(onClick = onClick) { Icon(icon, stringResource(description)) }
    }
}

@Composable
internal fun SizeTool(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val content = @Composable {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
    val m = Modifier.semantics { contentDescription = description }
    if (selected) FilledTonalIconButton(onClick = onClick, modifier = m) { content() } else IconButton(onClick = onClick, modifier = m) { content() }
}
