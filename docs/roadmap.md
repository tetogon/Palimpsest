# Roadmap: notes and design

Decisions (2026-10): Markdown, a grid of cards by default, three sections, **Notes**,
**Diary** and **To-do**, in the bottom navigation.

Ground rules:
* Everything lives inside the vault: folders, colours, pins, the trash and to-dos are fields
  of format v1 entries. There is no separate database or index on disk; one would show how
  many vaults exist.
* Search runs in Rust, in memory; full texts do not go to the JVM.
* No temporary files with plaintext, photos and voice included.
* Every stage comes with tests and an updated spec.

## Stage 1. Design and frame
- [x] Material 3, colours from the wallpaper (Material You), dark / light / system theme in the settings
- [x] A reading typeface; input fields in the theme's colours
- [x] Three sections in the bottom navigation: Notes (a grid of cards), Diary (a timeline by date),
      To-do (a checklist, quick add, done items below)
- [x] Search at the top: in Rust, ignoring case and diacritics, "ё" = "е"
- [x] Entry types in the format: diary / note / to-do; a "done" flag
- [x] Empty states, edge-to-edge under the system bars

## Stage 2. Organisation
- [x] Folders (entries of their own in the vault): create, rename, colour, delete, move, reorder
- [x] Pins, 8 card colours, sort (edited / created / title)
- [x] A 30-day trash: Undo, restore, delete for good
- [x] Multi-select: move, pin, colour, to the trash

## Stage 3. Search
- [x] Search inside an entry that fades everything but the matches, and jumps to them
- [ ] Snippets around the match in the list, filter by folder and section

## Stage 4. Editor
- [x] Markdown: headings, bold, italic, strikethrough, lists, checkboxes, quotes, code, links
- [x] Formatting bar above the keyboard
- [x] Read mode without WebView, checkboxes by tap
- [x] Live styling while typing, undo / redo, word count
- [x] Markdown markers hidden away from the cursor's line, paragraph alignment
- [x] Photos in the text: left / centre / right, three sizes, text beside them
- [x] One undo history for the whole entry, layout changes included
- [x] LaTeX formulas (`$…$`, `$$…$$`) drawn natively in read mode and on the cards

## Stage 5. Attachments
- [x] BLOB objects (§6.4), thumbnails, a size limit
- [x] Photos: Camera2 into memory, import from the gallery, crop and rotate, compression, EXIF removed, viewer
- [x] Voice: AAC recording into memory, player, duration
- [x] A warning about file growth for hidden vaults (T5)

## Stage 6. Transfer
- [x] Tags, tag filters, `#tag` in search, renaming and deleting tags everywhere
- [x] A copy of the whole container and restore (§11.7)
- [x] Export of one vault under a fresh key, import with merging

## Stage 7. Connections
- [x] Diary calendar with "on this day"
- [x] Links between entries, backlinks, a graph of links (link and unlink by dragging)

## Stage 8. Publishing
- [x] Release builds from tags
- [x] F-Droid listing (fastlane metadata)
- [ ] F-Droid release
- [ ] Reproducible builds

## Later
Export to `.md`/ZIP in plaintext (with a warning).
