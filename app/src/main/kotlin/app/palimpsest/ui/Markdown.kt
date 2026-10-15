package app.palimpsest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

enum class MdAlign { START, CENTER, END }

enum class MdSize(val fraction: Float) { S(0.38f), M(0.62f), L(1f) }

/** Block-level Markdown, the subset the editor's toolbar produces. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String, val align: MdAlign = MdAlign.START) : MdBlock
    data class Paragraph(val text: String, val align: MdAlign = MdAlign.START) : MdBlock
    /** An attachment placed in the text: `![center m](att:<id>)` on its own line. */
    data class Embed(val id: String, val align: MdAlign, val size: MdSize) : MdBlock
    data class Bullet(val indent: Int, val text: String) : MdBlock
    data class Numbered(val indent: Int, val number: String, val text: String) : MdBlock
    /** [line] is the index of the source line, for ticking it in place. */
    data class Task(val indent: Int, val checked: Boolean, val text: String, val line: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

object Md {
    /** `:-: ` centres a paragraph, `--: ` aligns it right (as in Markdown table columns). */
    val ALIGN = Regex("""^(:-:|--:)\s""")
    val EMBED = Regex("""^!\[(left|center|right)?(?:\s+(s|m|l))?]\(att:([0-9a-f]{32})\)\s*$""")
    val HEADING = Regex("""^(#{1,3})\s+(.*)$""")

    fun alignOf(prefix: String?) = when (prefix) {
        ":-:" -> MdAlign.CENTER
        "--:" -> MdAlign.END
        else -> MdAlign.START
    }

    fun embedLine(id: String, align: MdAlign = MdAlign.CENTER, size: MdSize = MdSize.L): String {
        val a = when (align) {
            MdAlign.START -> "left"
            MdAlign.CENTER -> "center"
            MdAlign.END -> "right"
        }
        return "![$a ${size.name.lowercase()}](att:$id)"
    }

    fun parseEmbed(line: String): MdBlock.Embed? = EMBED.matchEntire(line)?.let { m ->
        val align = when (m.groupValues[1]) {
            "left" -> MdAlign.START
            "right" -> MdAlign.END
            else -> MdAlign.CENTER
        }
        val size = when (m.groupValues[2]) {
            "s" -> MdSize.S
            "m" -> MdSize.M
            else -> MdSize.L
        }
        MdBlock.Embed(m.groupValues[3], align, size)
    }

    val TASK = Regex("""^(\s*)[-*+]\s+\[([ xX])](?:\s+(.*))?$""")
    val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
    val NUMBERED = Regex("""^(\s*)(\d{1,9})([.)])\s+(.*)$""")
    val QUOTE = Regex("""^>\s?(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val FENCE = Regex("""^\s*```""")

    /** Inline tokens, earliest match wins: code, bold, strike, italic, link, bare URL. */
    val INLINE = Regex(
        """`([^`\n]+)`""" +
            """|\*\*(.+?)\*\*""" +
            """|__(.+?)__""" +
            """|~~(.+?)~~""" +
            """|\*(?![\s*])(.+?)(?<![\s*])\*""" +
            """|(?<![\p{L}\p{N}_])_(?![\s_])(.+?)(?<![\s_])_(?![\p{L}\p{N}_])""" +
            """|\[([^\]\n]+)]\(([^)\s]+)\)""" +
            """|(https?://[^\s<>()]+[^\s<>().,;:!?'"])""",
    )

    fun parse(body: String): List<MdBlock> {
        val lines = body.split('\n')
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        var paraAlign = MdAlign.START
        fun flush() {
            if (para.isNotEmpty()) out += MdBlock.Paragraph(para.toString(), paraAlign)
            para.clear()
            paraAlign = MdAlign.START
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (FENCE.containsMatchIn(line)) {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !FENCE.containsMatchIn(lines[i])) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += MdBlock.Code(code.toString())
                i++
                continue
            }
            val embed = parseEmbed(line)
            if (embed != null) {
                flush()
                out += embed
                i++
                continue
            }
            val alignMatch = ALIGN.find(line)
            val align = alignOf(alignMatch?.groupValues?.get(1))
            val text = if (alignMatch != null) line.substring(alignMatch.range.last + 1) else line
            val heading = HEADING.matchEntire(text)
            if (heading != null) {
                flush()
                out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2], align)
                i++
                continue
            }
            if (alignMatch != null && text.isNotBlank()) {
                if (para.isNotEmpty() && paraAlign != align) flush()
                if (para.isNotEmpty()) para.append('\n')
                para.append(text)
                paraAlign = align
                i++
                continue
            }
            val task = TASK.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            val quote = QUOTE.matchEntire(line)
            when {
                line.isBlank() -> flush()
                RULE.matches(line) -> { flush(); out += MdBlock.Rule }
                task != null -> {
                    flush()
                    out += MdBlock.Task(task.groupValues[1].length / 2, task.groupValues[2] != " ", task.groupValues[3], i)
                }
                bullet != null -> { flush(); out += MdBlock.Bullet(bullet.groupValues[1].length / 2, bullet.groupValues[3]) }
                numbered != null -> {
                    flush()
                    out += MdBlock.Numbered(numbered.groupValues[1].length / 2, numbered.groupValues[2], numbered.groupValues[4])
                }
                quote != null -> {
                    flush()
                    val last = out.lastOrNull()
                    if (last is MdBlock.Quote) out[out.lastIndex] = MdBlock.Quote(last.text + "\n" + quote.groupValues[1])
                    else out += MdBlock.Quote(quote.groupValues[1])
                }
                else -> {
                    if (para.isNotEmpty() && paraAlign != MdAlign.START) flush()
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                }
            }
            i++
        }
        flush()
        return out
    }

    /** Ticks or unticks the task on source line [line]. */
    fun toggleTask(body: String, line: Int): String {
        val lines = body.split('\n').toMutableList()
        if (line !in lines.indices || TASK.matchEntire(lines[line]) == null) return body
        val box = Regex("""\[([ xX])]""").find(lines[line]) ?: return body
        lines[line] = lines[line].replaceRange(box.range, if (box.groupValues[1] == " ") "[x]" else "[ ]")
        return lines.joinToString("\n")
    }

    fun wordCount(text: String) = Regex("""[\p{L}\p{N}]+""").findAll(text).count()

    /** Words of a search query to find inside an entry; `#tag` tokens are left out. */
    fun findTerms(query: String): List<String> =
        query.split(' ').filter { it.isNotBlank() && !it.startsWith("#") }.map(::fold).distinct()

    /** Case and ё/е do not matter; the length stays the same, so ranges map back. */
    private fun fold(s: String) = buildString(s.length) { for (c in s) append(if (c == 'ё' || c == 'Ё') 'е' else c.lowercaseChar()) }

    /** Ranges of [text] matching any of [terms], sorted and merged. */
    fun findRanges(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val folded = fold(text)
        val hits = mutableListOf<IntRange>()
        for (term in terms) {
            var at = folded.indexOf(term)
            while (at >= 0) {
                hits += at until at + term.length
                at = folded.indexOf(term, at + 1)
            }
        }
        hits.sortBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (h in hits) {
            val last = merged.lastOrNull()
            if (last != null && h.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, h.last) else merged += h
        }
        return merged
    }

    fun countMatches(text: String, terms: List<String>) = findRanges(text, terms).size
}

/** Search in an entry: everything but the matches fades (spec: no highlight colour). */
fun AnnotatedString.dimmed(terms: List<String>, dim: Color): AnnotatedString {
    if (terms.isEmpty()) return this
    val hits = Md.findRanges(text, terms)
    val base = this
    return buildAnnotatedString {
        append(base)
        var at = 0
        for (r in hits) {
            if (r.first > at) addStyle(SpanStyle(color = dim), at, r.first)
            at = r.last + 1
        }
        if (at < length) addStyle(SpanStyle(color = dim), at, length)
    }
}

/** [find] and [dim]: a link without a match fades like the text around it. */
private class InlineColors(val link: Color, val codeBackground: Color, val find: List<String> = emptyList(), val dim: Color = link)

/** [links] false: link text and bare URLs come out as plain text, nothing to tap. */
private fun inline(text: String, colors: InlineColors, onLink: (String) -> Unit, links: Boolean = true): AnnotatedString = buildAnnotatedString {
    fun walk(s: String) {
        var at = 0
        for (m in Md.INLINE.findAll(s)) {
            append(s.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground, fontSize = 0.9.em)) { append(g[1]) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(g[2]) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(g[3]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(g[4]) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(g[5]) }
                g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(g[6]) }
                g[7].isNotEmpty() -> if (links) link(g[8], g[7], colors, onLink) { walk(g[7]) } else walk(g[7])
                g[9].isNotEmpty() -> if (links) link(g[9], g[9], colors, onLink) { append(g[9]) } else append(g[9])
            }
            at = m.range.last + 1
        }
        append(s.substring(at))
    }

    walk(text)
}

/**
 * A list preview from the core, which keeps `#` headings and inline markers: drawn bold,
 * italic and so on like read mode, with headings in bold. Links are plain text here, so a tap
 * opens the card.
 */
fun previewText(preview: String, codeBackground: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    val colors = InlineColors(Color.Unspecified, codeBackground)
    preview.lines().forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val heading = Md.HEADING.matchEntire(line)
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(heading.groupValues[2], colors, {}, links = false)) }
        } else {
            append(inline(line, colors, {}, links = false))
        }
    }
}

/** [previewText] without the styles: for titles, labels and sorting. */
fun previewPlain(preview: String): String = previewText(preview).text

/** Links never open directly: [onLink] asks first. */
private fun AnnotatedString.Builder.link(
    url: String,
    label: String,
    colors: InlineColors,
    onLink: (String) -> Unit,
    content: AnnotatedString.Builder.() -> Unit,
) {
    val faded = colors.find.isNotEmpty() && Md.findRanges(label, colors.find).isEmpty()
    val annotation = LinkAnnotation.Clickable(
        tag = url,
        styles = TextLinkStyles(SpanStyle(color = if (faded) colors.dim else colors.link, textDecoration = TextDecoration.Underline)),
        linkInteractionListener = { onLink(url) },
    )
    withLink(annotation) { content() }
}

/**
 * Read mode. Deliberately not selectable: copying from here would bypass the clipboard guard;
 * switch to editing to copy.
 */
@Composable
fun MarkdownText(
    body: String,
    onToggleTask: (Int) -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    find: List<String> = emptyList(),
) {
    val blocks = remember(body) { Md.parse(body) }
    val scheme = MaterialTheme.colorScheme
    val body1 = ReadingStyle.copy(color = scheme.onSurface)
    val dim = scheme.onSurface.copy(alpha = 0.32f)
    val colors = remember(scheme.primary, scheme.surfaceContainerHighest, find, dim) {
        InlineColors(scheme.primary, scheme.surfaceContainerHighest, find, dim)
    }
    fun inl(t: String) = inline(t, colors, onLink).dimmed(find, dim)

    @Composable
    fun heading(block: MdBlock.Heading, m: Modifier = Modifier) = FindText(
        inl(block.text), find,
        style = when (block.level) {
            1 -> MaterialTheme.typography.headlineMedium
            2 -> MaterialTheme.typography.headlineSmall
            else -> MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif)
        },
        textAlign = block.align.textAlign(),
        modifier = m.padding(top = 6.dp),
    )

    @Composable
    fun paragraph(block: MdBlock.Paragraph, m: Modifier = Modifier) =
        FindText(inl(block.text), find, style = body1, textAlign = block.align.textAlign(), modifier = m)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Embed -> Unit // photos are blocks of their own (see Doc)
                is MdBlock.Heading -> heading(block, Modifier.fillMaxWidth())
                is MdBlock.Paragraph -> paragraph(block, Modifier.fillMaxWidth())
                is MdBlock.Bullet -> ListRow(block.indent, "•", inl(block.text), body1, find)
                is MdBlock.Numbered -> ListRow(block.indent, "${block.number}.", inl(block.text), body1, find)
                is MdBlock.Task -> Row(Modifier.padding(start = (block.indent * 20).dp), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = block.checked, onCheckedChange = { onToggleTask(block.line) }, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(6.dp))
                    FindText(
                        inl(block.text), find,
                        style = body1.copy(
                            color = if (block.checked) scheme.outline else scheme.onSurface,
                            textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(scheme.primary, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(12.dp))
                    FindText(inl(block.text), find, style = body1.copy(fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant))
                }
                is MdBlock.Code -> Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = scheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FindText(
                        AnnotatedString(block.text).dimmed(find, dim), find,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurface),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                        softWrap = false,
                    )
                }
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

private fun MdAlign.textAlign() = when (this) {
    MdAlign.START -> TextAlign.Start
    MdAlign.CENTER -> TextAlign.Center
    MdAlign.END -> TextAlign.End
}

@Composable
private fun ListRow(indent: Int, marker: String, text: AnnotatedString, style: TextStyle, find: List<String>) {
    Row(Modifier.padding(start = (indent * 20).dp)) {
        Text(marker, style = style.copy(color = MaterialTheme.colorScheme.primary), modifier = Modifier.width(28.dp))
        FindText(text, find, style = style)
    }
}

/** A Text whose search matches can be jumped to (see [FindHits]). */
@Composable
internal fun FindText(
    text: AnnotatedString,
    find: List<String>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
) {
    val ranges = remember(text, find) { Md.findRanges(text.text, find) }
    val (place, onLayout) = findSource(text.text, ranges)
    Text(text, style = style, textAlign = textAlign, softWrap = softWrap, modifier = modifier.then(place), onTextLayout = onLayout)
}
