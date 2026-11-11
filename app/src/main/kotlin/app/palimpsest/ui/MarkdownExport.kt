package app.palimpsest.ui

import app.palimpsest.ffi.EntryDetail
import app.palimpsest.ffi.EntryKind
import app.palimpsest.ffi.Session
import java.io.OutputStream
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One vault as plain Markdown, for leaving the app or reading the entries elsewhere: a ZIP of
 * `Notes/<folder>/…`, `Diary/…` and `To-do/…` files with YAML front matter, and the
 * attachments in `attachments/`. Photos placed in the text and links between entries point at
 * those files. Written straight into the stream: no plaintext file in the app's storage
 * (spec: nothing unencrypted on disk). Entries in the trash stay behind.
 */
object MarkdownExport {
    fun write(session: Session, out: OutputStream) {
        val folders = session.folders().associate { it.folderId to it.name }
        val entries = session.entries().filter { it.trashedMs == null }.mapNotNull { session.entry(it.entryId) }
        val paths = paths(entries, folders)
        ZipOutputStream(out).use { zip ->
            val attachments = mutableMapOf<String, String>()
            for (e in entries) {
                for (a in e.attachments) attachments[a.attachmentId] = "attachments/${a.attachmentId}.${extension(a.mime)}"
            }
            for (e in entries) {
                val path = paths.getValue(e.entryId)
                val up = "../".repeat(path.count { it == '/' })
                zip.putNextEntry(ZipEntry(path))
                zip.write(markdown(e, folders, up, attachments, paths).toByteArray())
                zip.closeEntry()
            }
            for ((id, path) in attachments) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(session.attachmentData(id))
                zip.closeEntry()
            }
        }
    }

    /** A unique path per entry: section, folder for notes, date first for the diary. */
    internal fun paths(entries: List<EntryDetail>, folders: Map<String, String>): Map<String, String> {
        val taken = mutableSetOf<String>()
        return entries.associate { e ->
            val dir = when (e.kind) {
                EntryKind.NOTE -> "Notes" + (e.folderId?.let { folders[it] }?.let { "/" + fileName(it) } ?: "")
                EntryKind.DIARY -> "Diary"
                EntryKind.TODO -> "To-do"
            }
            val title = fileName(previewPlain(e.title).ifBlank { e.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().let(::previewPlain) })
                .ifBlank { "untitled" }
            val base = if (e.kind == EntryKind.DIARY) "${day(e)} $title" else title
            var path = "$dir/$base.md"
            var n = 2
            while (!taken.add(path.lowercase())) path = "$dir/$base (${n++}).md"
            e.entryId to path
        }
    }

    internal fun markdown(
        e: EntryDetail,
        folders: Map<String, String>,
        up: String,
        attachments: Map<String, String>,
        paths: Map<String, String>,
    ): String = buildString {
        appendLine("---")
        appendLine("title: ${quote(e.title)}")
        appendLine("kind: ${e.kind.name.lowercase()}")
        if (e.kind == EntryKind.DIARY) appendLine("date: ${day(e)}")
        appendLine("created: ${Instant.ofEpochMilli(e.createdMs)}")
        appendLine("updated: ${Instant.ofEpochMilli(e.updatedMs)}")
        e.folderId?.let { folders[it] }?.let { appendLine("folder: ${quote(it)}") }
        if (e.tags.isNotEmpty()) appendLine("tags: [${e.tags.joinToString(", ") { quote(it) }}]")
        if (e.kind == EntryKind.TODO) appendLine("done: ${e.done}")
        if (e.pinned) appendLine("pinned: true")
        appendLine("---")
        appendLine()
        var body = e.body
        for ((id, path) in attachments) body = body.replace("](att:$id)", "](${link(up + path)})")
        for ((id, path) in paths) body = body.replace("](note:$id)", "](${link(up + path)})")
        append(body)
        if (!body.endsWith("\n")) appendLine()
        e.conflictBody?.let {
            appendLine()
            appendLine("<!-- A concurrent version of this entry: -->")
            appendLine()
            append(it)
            if (!it.endsWith("\n")) appendLine()
        }
    }

    private fun day(e: EntryDetail): String =
        e.diaryDate?.let { LocalDate.ofEpochDay(it.toLong()) }?.toString()
            ?: Instant.ofEpochMilli(e.createdMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /** Safe on every desktop system, at most 80 characters. */
    internal fun fileName(s: String) =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim().trimEnd('.').take(80).trim()

    private fun extension(mime: String) = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "audio/aac" -> "aac"
        "audio/mp4", "audio/m4a" -> "m4a"
        else -> "bin"
    }

    /** Spaces and the like escaped, slashes kept, so Markdown viewers follow the link. */
    private fun link(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
