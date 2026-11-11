package app.palimpsest.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocTest {
    private val a = "a".repeat(32)
    private val b = "b".repeat(32)

    private fun texts(doc: List<DocBlock>) = doc.map {
        when (it) {
            is DocText -> "T:" + it.text
            is DocPhoto -> "P:" + it.id.take(1) + "/" + it.side
            is DocTable -> "G:" + it.cells.joinToString(";") { r -> r.joinToString(",") }
        }
    }

    @Test
    fun tables() {
        val body = "intro\n| a | b \\| c |\n|---|--:|\n| 1 | 2 |\n\nafter | not a row\n```\n| x | y |\n|---|---|\n```"
        val doc = Doc.parse(body)
        // The table in the code block stays text.
        assertEquals(listOf("T:intro", "G:a,b | c;1,2", "T:after | not a row\n```\n| x | y |\n|---|---|\n```"), texts(doc))
        assertEquals(body, Doc.serialize(doc))
        val t = doc[1] as DocTable
        assertEquals(listOf(MdAlign.START, MdAlign.END), t.align)
        val grown = t.addRow(t.rows).addCol(1).setCell(2, 1, "new").setAlign(1, MdAlign.CENTER)
        assertEquals(
            listOf("| a |  | b \\| c |", "|---|:-:|--:|", "| 1 |  | 2 |", "|  | new |  |"),
            grown.markdown(),
        )
        // The header row and the last column stay.
        assertEquals(t, t.removeRow(0))
        assertEquals(1, t.removeCol(0).removeCol(0).cols)
        // A table at the very start, and taken out again.
        val base = Doc.parse("x")
        val start = Doc.insert(base, DocTable.empty(Doc.key()), base[0].key, 0)
        assertEquals("|  |  |\n|---|---|\n|  |  |\n|  |  |\n\nx", Doc.serialize(start))
        assertEquals("x", Doc.serialize(Doc.remove(start, start[1].key)))
    }

    @Test
    fun roundTrips() {
        val bodies = listOf(
            "",
            "just text\n\nmore",
            "intro\n![center l](att:$a)\nafter",
            "![left s](att:$a)\nbeside\nstill beside\n\nbelow\n![right m](att:$b)\n\nno side text",
            "![left m](att:$a)\n![center l](att:$b)",
            "x\n\n![center l](att:$a)\n\n\ny",
        )
        for (body in bodies) assertEquals(body, Doc.serialize(Doc.parse(body)))
        val doc = Doc.parse(bodies[3])
        assertEquals(listOf("T:", "P:a/beside\nstill beside", "T:below", "P:b/", "T:no side text"), texts(doc))
    }

    @Test
    fun moveRemoveAndInsert() {
        val doc = Doc.parse("one\ntwo\n![center l](att:$a)\nthree")
        val photo = doc[1].key
        val first = doc[0].key
        // Between "one" and "two".
        val moved = Doc.move(doc, photo, first, 4)
        assertEquals("one\n![center l](att:$a)\ntwo\nthree", Doc.serialize(moved))
        // To the very end of the text after it.
        val last = doc[2].key
        assertEquals("one\ntwo\nthree\n![center l](att:$a)", Doc.serialize(Doc.move(doc, photo, last, 5)))
        // Back to the strip.
        assertEquals("one\ntwo\nthree", Doc.serialize(Doc.remove(doc, photo)))
        // A new one at the end of the first line.
        val base = Doc.parse("one\ntwo")
        val added = Doc.insert(base, DocPhoto(Doc.key(), b, MdAlign.START, MdSize.S), base[0].key, 3)
        assertEquals("one\n![left s](att:$b)\n\ntwo", Doc.serialize(added))
    }

    @Test
    fun wrapAndSideText() {
        val doc = Doc.parse("![left s](att:$a)\nbeside\n\nbelow")
        val photo = doc[1].key
        val unwrapped = Doc.update(doc, photo) { it.copy(size = MdSize.L) }
        assertEquals("![left l](att:$a)\nbeside\nbelow", Doc.serialize(unwrapped))
        val (split, moved) = Doc.setSide(doc, photo, "beside\n\nnew line")
        assertEquals(split[2].key, moved)
        assertEquals("![left s](att:$a)\nbeside\n\nnew line\nbelow", Doc.serialize(split))
        val (same, none) = Doc.setSide(doc, photo, "beside\nmore")
        assertNull(none)
        assertEquals("![left s](att:$a)\nbeside\nmore\n\nbelow", Doc.serialize(same))
    }
}
