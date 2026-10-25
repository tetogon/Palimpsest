package app.palimpsest.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Formulas in read mode: `$…$` inside a line, `$$…$$` as a block of its own. Drawn natively
 * by JLaTeXMath, in the text's colour. Anything it cannot parse stays text.
 */
class MathRenderer(private val textPx: Float, private val color: Int) {
    private val cache = HashMap<String, JLatexMathDrawable?>()

    /** Null when the formula does not parse or is too long to be worth drawing. */
    fun render(tex: String, display: Boolean): JLatexMathDrawable? = cache.getOrPut((if (display) "D" else "I") + tex) {
        if (tex.isBlank() || tex.length > MAX_TEX) {
            null
        } else {
            try {
                JLatexMathDrawable.builder(if (display) "\\displaystyle $tex" else tex)
                    .textSize(if (display) textPx * 1.15f else textPx)
                    .color(color)
                    .padding(0)
                    .build()
                    .takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
            } catch (t: Throwable) {
                // Parse errors, and StackOverflowError on absurd nesting from a crafted vault.
                null
            }
        }
    }

    /** Placeholders for the inline formulas of [source] that draw, keyed as [inlineId] makes them. */
    fun inlineContent(source: String): Map<String, InlineTextContent> =
        inlineContent(Md.INLINE.findAll(source).mapNotNull { m -> m.groups[10]?.value }.toList())

    /** Placeholders for [formulas] drawn at text size, the ones that draw. */
    fun inlineContent(formulas: List<String>): Map<String, InlineTextContent> =
        formulas.distinct().mapNotNull { tex ->
            val d = render(tex, display = false) ?: return@mapNotNull null
            inlineId(tex) to InlineTextContent(
                // In em of the line's own font: a formula in a heading grows with it.
                Placeholder((d.intrinsicWidth / textPx).em, (d.intrinsicHeight / textPx).em, PlaceholderVerticalAlign.TextCenter),
            ) { Formula(d, Modifier.fillMaxSize()) }
        }.toMap()

    companion object {
        /** Longer formulas stay text: a crafted vault cannot stall the screen with one. */
        const val MAX_TEX = 2000

        fun inlineId(tex: String) = "tex:$tex"
    }
}

/** A renderer for text in [style], in [color]; one per screen or card. */
@Composable
fun rememberMath(style: TextStyle, color: Color): MathRenderer {
    val px = with(LocalDensity.current) { style.fontSize.toPx() }
    return remember(px, color) { MathRenderer(px, color.toArgb()) }
}

/** A list preview with its formulas drawn (see [previewText]). */
@Composable
fun PreviewText(preview: String, style: TextStyle, color: Color, maxLines: Int, modifier: Modifier = Modifier) {
    val math = rememberMath(style, color)
    val code = MaterialTheme.colorScheme.surfaceContainerHighest
    val text = remember(preview, math, code) { previewText(preview, code, math) }
    val content = remember(preview, math) { math.inlineContent(previewFormulas(preview)) }
    Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = content, modifier = modifier)
}

/** Draws [d] scaled to the space it is given. */
@Composable
private fun Formula(d: Drawable, modifier: Modifier) = Canvas(modifier) {
    drawIntoCanvas { c ->
        val scale = size.width / d.intrinsicWidth
        val canvas = c.nativeCanvas
        canvas.save()
        canvas.scale(scale, scale)
        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        d.draw(canvas)
        canvas.restore()
    }
}

/** A `$$…$$` block: centred, scrolling sideways when wider than the screen. */
@Composable
internal fun MathBlock(d: Drawable, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val width = with(density) { d.intrinsicWidth.toDp() }
    val height = with(density) { d.intrinsicHeight.toDp() }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Formula(d, Modifier.size(width, height))
        }
    }
}
