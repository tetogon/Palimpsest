package app.palimpsest.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdTest {
    @Test
    fun blocks() {
        val body = """
            # Title
            Some *text*
            continues here

            - one
            - [ ] todo
            - [x] done
            2. second
            > quote
            > more
            ---
            ```
            code **not bold**
            ```
        """.trimIndent()
        val blocks = Md.parse(body)
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Paragraph("Some *text*\ncontinues here"), blocks[1])
        assertEquals(MdBlock.Bullet(0, "one"), blocks[2])
        assertEquals(MdBlock.Task(0, false, "todo", 5), blocks[3])
        assertEquals(MdBlock.Task(0, true, "done", 6), blocks[4])
        assertEquals(MdBlock.Numbered(0, "2", "second"), blocks[5])
        assertEquals(MdBlock.Quote("quote\nmore"), blocks[6])
        assertEquals(MdBlock.Rule, blocks[7])
        assertEquals(MdBlock.Code("code **not bold**"), blocks[8])
        assertEquals(9, blocks.size)
    }

    @Test
    fun emptyTaskAndBoldAreNotBullets() {
        assertEquals(listOf(MdBlock.Task(0, false, "", 0)), Md.parse("- [ ]"))
        assertTrue(Md.parse("**bold** start").single() is MdBlock.Paragraph)
    }

    @Test
    fun toggleTaskTouchesOnlyThatLine() {
        val body = "- [ ] a\nnot a [ ] task\n- [x] b"
        assertEquals("- [x] a\nnot a [ ] task\n- [x] b", Md.toggleTask(body, 0))
        assertEquals("- [ ] a\nnot a [ ] task\n- [ ] b", Md.toggleTask(body, 2))
        assertEquals(body, Md.toggleTask(body, 1))
        assertEquals(body, Md.toggleTask(body, 9))
    }

    @Test
    fun inlineTokens() {
        val found = Md.INLINE.findAll("a **b** *c* ~~d~~ `e` [f](https://x.y) https://z.org/p.").map { it.value }.toList()
        assertEquals(listOf("**b**", "*c*", "~~d~~", "`e`", "[f](https://x.y)", "https://z.org/p"), found)
    }

    @Test
    fun embedsAndAlignment() {
        val id = "0123456789abcdef0123456789abcdef"
        val body = listOf(
            ":-: # Centred title",
            Md.embedLine(id, MdAlign.START, MdSize.S),
            "plain",
            "--: right one",
            "--: right two",
            "![](att:$id)",
            "![left m](att:${id.dropLast(1)})",
        ).joinToString("\n")
        val blocks = Md.parse(body)
        assertEquals(MdBlock.Heading(1, "Centred title", MdAlign.CENTER), blocks[0])
        assertEquals(MdBlock.Embed(id, MdAlign.START, MdSize.S), blocks[1])
        assertEquals(MdBlock.Paragraph("plain"), blocks[2])
        assertEquals(MdBlock.Paragraph("right one\nright two", MdAlign.END), blocks[3])
        assertEquals(MdBlock.Embed(id, MdAlign.CENTER, MdSize.L), blocks[4])
        assertTrue(blocks[5] is MdBlock.Paragraph) // a bad id is just text
        assertEquals(6, blocks.size)
        assertEquals("![left s](att:$id)", Md.embedLine(id, MdAlign.START, MdSize.S))
    }

    @Test
    fun words() {
        assertEquals(4, Md.wordCount("Привет, мир! It's"))
    }
}
