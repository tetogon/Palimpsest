package app.palimpsest.ui

import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult

/**
 * Where the matches of a search inside an entry are on screen, so the find bar can jump to
 * one. Every piece of text that shows matches (a read-mode paragraph, an editor field)
 * reports itself here.
 */
@Stable
class FindHits {
    /** One match: a bit of text around it, where it is (window y), and how to go there. */
    class Hit(val before: String, val match: String, val after: String, val y: Float, val field: EditText?, val start: Int, val end: Int)

    private class Source(
        var text: String = "",
        var ranges: List<IntRange> = emptyList(),
        var coords: LayoutCoordinates? = null,
        var layout: TextLayoutResult? = null,
        var view: EditText? = null,
    )

    private val sources = HashMap<Any, Source>()
    // Match counts per source, as state, so the count in the bar follows what is shown.
    private val counts = mutableStateMapOf<Any, Int>()

    val count: Int get() = counts.values.sum()

    internal fun text(key: Any, text: String, ranges: List<IntRange>) {
        val s = sources.getOrPut(key) { Source() }
        s.text = text
        s.ranges = ranges
        if (counts[key] != ranges.size) counts[key] = ranges.size
    }

    internal fun place(key: Any, coords: LayoutCoordinates) {
        sources[key]?.coords = coords
    }

    internal fun layout(key: Any, layout: TextLayoutResult) {
        sources[key]?.layout = layout
    }

    internal fun view(key: Any, view: EditText?) {
        sources.getOrPut(key) { Source() }.view = view
    }

    internal fun remove(key: Any) {
        sources.remove(key)
        counts.remove(key)
    }

    /** Every match, top to bottom. */
    fun hits(): List<Hit> {
        val out = mutableListOf<Hit>()
        for (s in sources.values) {
            for (r in s.ranges) {
                val y = s.view?.let { v ->
                    val l = v.layout ?: return@let null
                    val loc = IntArray(2).also(v::getLocationInWindow)
                    loc[1] + v.totalPaddingTop + l.getLineTop(l.getLineForOffset(r.first)).toFloat()
                } ?: run {
                    val c = s.coords?.takeIf { it.isAttached } ?: return@run null
                    val l = s.layout ?: return@run null
                    c.positionInWindow().y + l.getLineTop(l.getLineForOffset(r.first.coerceAtMost(l.layoutInput.text.length)))
                } ?: continue
                val from = (r.first - 28).coerceAtLeast(0).let { a -> s.text.lastIndexOf('\n', r.first - 1).let { nl -> if (nl >= a) nl + 1 else a } }
                val to = (r.last + 1 + 40).coerceAtMost(s.text.length).let { b -> s.text.indexOf('\n', r.last + 1).let { nl -> if (nl in 0 until b) nl else b } }
                out += Hit(
                    (if (from > 0 && s.text[from - 1] != '\n') "…" else "") + s.text.substring(from, r.first),
                    s.text.substring(r.first, r.last + 1),
                    s.text.substring(r.last + 1, to) + if (to < s.text.length && s.text[to] != '\n') "…" else "",
                    y, s.view, r.first, r.last + 1,
                )
            }
        }
        return out.sortedBy { it.y }
    }
}

val LocalFindHits = compositionLocalOf<FindHits?> { null }

/**
 * Reports a read-mode text with its matches to [LocalFindHits]. Returns the modifier for the
 * Text and the callback for its onTextLayout.
 */
@Composable
internal fun findSource(shown: String, ranges: List<IntRange>): Pair<Modifier, (TextLayoutResult) -> Unit> {
    val hits = LocalFindHits.current ?: return Modifier to {}
    val key = remember { Any() }
    SideEffect { hits.text(key, shown, ranges) }
    DisposableEffect(hits, key) { onDispose { hits.remove(key) } }
    return Modifier.onGloballyPositioned { hits.place(key, it) } to { layout: TextLayoutResult -> hits.layout(key, layout) }
}
