package app.palimpsest.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * An entry body as the editor sees it: text and photos, always alternating, starting and
 * ending with text (possibly empty). Stored as Markdown: a photo is an `![left s](att:…)`
 * line; a photo with text beside it is followed by that text and then a blank line.
 */
sealed interface DocBlock {
    /** Stable for the life of the editor, so each text keeps its own field. */
    val key: Long
}

data class DocText(override val key: Long, val text: String) : DocBlock

data class DocPhoto(
    override val key: Long,
    val id: String,
    val align: MdAlign,
    val size: MdSize,
    /** Text beside the photo; only used when [wraps]. One paragraph: no blank lines. */
    val side: String = "",
) : DocBlock {
    val wraps get() = size != MdSize.L && align != MdAlign.CENTER
}

object Doc {
    private val keys = AtomicLong()

    fun key() = keys.incrementAndGet()

    fun parse(body: String): List<DocBlock> {
        val lines = body.split('\n')
        val out = mutableListOf<DocBlock>()
        val text = mutableListOf<String>()
        fun flush() {
            out += DocText(key(), text.joinToString("\n"))
            text.clear()
        }
        var i = 0
        while (i < lines.size) {
            val e = Md.parseEmbed(lines[i])
            if (e == null) {
                text += lines[i++]
                continue
            }
            flush()
            i++
            val photo = DocPhoto(key(), e.id, e.align, e.size)
            if (photo.wraps) {
                val side = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && Md.parseEmbed(lines[i]) == null) side += lines[i++]
                if (i < lines.size && lines[i].isBlank()) i++ // the blank line that ends the side text
                out += photo.copy(side = side.joinToString("\n"))
            } else {
                out += photo
            }
        }
        flush()
        return out
    }

    fun serialize(blocks: List<DocBlock>): String {
        val parts = mutableListOf<String>()
        blocks.forEachIndexed { i, b ->
            when (b) {
                is DocText -> if (b.text.isNotEmpty()) parts += b.text
                is DocPhoto -> {
                    parts += Md.embedLine(b.id, b.align, b.size)
                    if (b.wraps) {
                        val side = b.side.trimEnd('\n')
                        if (side.isNotEmpty()) parts += side
                        if ((blocks.getOrNull(i + 1) as? DocText)?.text?.isNotEmpty() == true) parts += ""
                    }
                }
            }
        }
        return parts.joinToString("\n")
    }

    private fun join(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("\n")

    /** Puts [photo] into text block [textKey] at [offset] (a line start or the end). */
    fun insert(blocks: List<DocBlock>, photo: DocPhoto, textKey: Long, offset: Int): List<DocBlock> {
        val i = blocks.indexOfFirst { it.key == textKey }
        val t = blocks.getOrNull(i) as? DocText ?: return blocks
        val at = offset.coerceIn(0, t.text.length)
        val lineStart = at == 0 || t.text[at - 1] == '\n'
        val head = DocText(t.key, t.text.substring(0, at).removeSuffix("\n"))
        // At the end of a line (not a line start), its line break goes with the photo.
        val tail = DocText(key(), t.text.substring(at).let { if (!lineStart) it.removePrefix("\n") else it })
        return blocks.subList(0, i) + listOf(head, photo, tail) + blocks.subList(i + 1, blocks.size)
    }

    /** Takes photo [key] out of the text; the text around it (and beside it) joins up. */
    fun remove(blocks: List<DocBlock>, key: Long): List<DocBlock> {
        val i = blocks.indexOfFirst { it.key == key }
        val p = blocks.getOrNull(i) as? DocPhoto ?: return blocks
        val before = blocks[i - 1] as DocText
        val after = blocks[i + 1] as DocText
        val merged = DocText(before.key, join(before.text, if (p.wraps) p.side else "", after.text))
        return blocks.subList(0, i - 1) + merged + blocks.subList(i + 2, blocks.size)
    }

    /** Moves photo [key] into text block [textKey] at [offset]. */
    fun move(blocks: List<DocBlock>, key: Long, textKey: Long, offset: Int): List<DocBlock> {
        val i = blocks.indexOfFirst { it.key == key }
        val p = blocks.getOrNull(i) as? DocPhoto ?: return blocks
        val before = blocks[i - 1] as DocText
        val after = blocks[i + 1] as DocText
        val rest = remove(blocks, key)
        val merged = rest[i - 1] as DocText
        // The target may be one of the two texts that just joined.
        val (target, at) = when (textKey) {
            before.key -> before.key to offset
            after.key -> merged.key to merged.text.length - after.text.length + offset
            else -> textKey to offset
        }
        return insert(rest, p, target, at)
    }

    /** Changes a photo; text beside it moves below when it stops wrapping. */
    fun update(blocks: List<DocBlock>, key: Long, change: (DocPhoto) -> DocPhoto): List<DocBlock> {
        val i = blocks.indexOfFirst { it.key == key }
        val p = blocks.getOrNull(i) as? DocPhoto ?: return blocks
        var next = change(p)
        val out = blocks.toMutableList()
        if (p.wraps && !next.wraps && p.side.isNotEmpty()) {
            val after = out[i + 1] as DocText
            out[i + 1] = after.copy(text = join(p.side, after.text))
            next = next.copy(side = "")
        } else if (!next.wraps) {
            next = next.copy(side = "")
        }
        out[i] = next
        return out
    }

    fun setText(blocks: List<DocBlock>, key: Long, text: String): List<DocBlock> =
        blocks.map { if (it.key == key && it is DocText) it.copy(text = text) else it }

    /**
     * New text beside photo [key]. A blank line ends it: whatever follows goes to the start of
     * the text below. Returns the blocks and, if text moved down, that block's key.
     */
    fun setSide(blocks: List<DocBlock>, key: Long, side: String): Pair<List<DocBlock>, Long?> {
        val i = blocks.indexOfFirst { it.key == key }
        val p = blocks.getOrNull(i) as? DocPhoto ?: return blocks to null
        val out = blocks.toMutableList()
        val cut = side.indexOf("\n\n")
        if (cut < 0) {
            out[i] = p.copy(side = side)
            return out to null
        }
        val after = out[i + 1] as DocText
        out[i] = p.copy(side = side.substring(0, cut))
        val overflow = side.substring(cut + 2)
        out[i + 1] = after.copy(text = if (overflow.isEmpty()) after.text else join(overflow, after.text))
        return out to after.key
    }

    fun embeddedIds(blocks: List<DocBlock>) = blocks.filterIsInstance<DocPhoto>().map { it.id }.toSet()
}
