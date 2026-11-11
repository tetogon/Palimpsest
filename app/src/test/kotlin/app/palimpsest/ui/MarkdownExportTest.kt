package app.palimpsest.ui

import app.palimpsest.ffi.EntryDetail
import app.palimpsest.ffi.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExportTest {
    private val photo = "a".repeat(32)

    private fun entry(id: String, kind: EntryKind, title: String, body: String, folder: String? = null, day: Int? = null) = EntryDetail(
        entryId = id, kind = kind, title = title, body = body, diaryDate = day, createdMs = 0, updatedMs = 0,
        done = false, folderId = folder, color = 0, pinned = false, trashedMs = null, attachments = emptyList(),
        tags = listOf("work"), conflictBody = null,
    )

    @Test
    fun pathsAreUniqueAndSafe() {
        val folders = mapOf("f" to "Work/Plans")
        val entries = listOf(
            entry("1", EntryKind.NOTE, "Plan: Q1?", "", folder = "f"),
            entry("2", EntryKind.NOTE, "plan  q1", "", folder = "f"),
            entry("3", EntryKind.DIARY, "", "**Dear** diary", day = 20_000),
            entry("4", EntryKind.TODO, "", ""),
        )
        val paths = MarkdownExport.paths(entries, folders)
        assertEquals("Notes/Work Plans/Plan Q1.md", paths["1"])
        assertEquals("Notes/Work Plans/plan q1 (2).md", paths["2"])
        assertEquals("Diary/2024-10-04 Dear diary.md", paths["3"])
        assertEquals("To-do/untitled.md", paths["4"])
    }

    @Test
    fun linksPointAtTheExportedFiles() {
        val note = entry("1", EntryKind.NOTE, "Ideas", "see [other](note:2)\n![left s](att:$photo)", folder = "f")
        val paths = mapOf("1" to "Notes/Work/Ideas.md", "2" to "Notes/Big plan.md")
        val md = MarkdownExport.markdown(note, mapOf("f" to "Work"), "../../", mapOf(photo to "attachments/$photo.jpg"), paths)
        assertTrue(md, md.startsWith("---\ntitle: \"Ideas\"\nkind: note\n"))
        assertTrue(md, "folder: \"Work\"" in md && "tags: [\"work\"]" in md)
        assertTrue(md, "[other](../../Notes/Big%20plan.md)" in md)
        assertTrue(md, "![left s](../../attachments/$photo.jpg)" in md)
    }
}
