package app.palimpsest.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Material glyphs (Apache 2.0) that are not in material-icons-core, inlined instead of
// pulling in the multi-megabyte extended icon set.
internal fun glyph(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
    .build()

val PinIcon = glyph(
    "PushPin",
    "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5" +
        "c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1l1,-1v-7H19v-2h0C17.34,12 16,10.66 16,9z",
)

val FolderIcon = glyph(
    "Folder",
    "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
)

val PaletteIcon = glyph(
    "Palette",
    "M12,2C6.49,2 2,6.49 2,12s4.49,10 10,10c1.38,0 2.5,-1.12 2.5,-2.5c0,-0.61 -0.23,-1.2 -0.64,-1.67" +
        "c-0.08,-0.1 -0.13,-0.21 -0.13,-0.33c0,-0.28 0.22,-0.5 0.5,-0.5H16c3.31,0 6,-2.69 6,-6C22,6.04 17.51,2 12,2z" +
        "M17.5,13c-0.83,0 -1.5,-0.67 -1.5,-1.5c0,-0.83 0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5C19,12.33 18.33,13 17.5,13z" +
        "M14.5,9C13.67,9 13,8.33 13,7.5C13,6.67 13.67,6 14.5,6S16,6.67 16,7.5C16,8.33 15.33,9 14.5,9z" +
        "M5,11.5C5,10.67 5.67,10 6.5,10S8,10.67 8,11.5C8,12.33 7.33,13 6.5,13S5,12.33 5,11.5z" +
        "M11,7.5C11,8.33 10.33,9 9.5,9S8,8.33 8,7.5C8,6.67 8.67,6 9.5,6S11,6.67 11,7.5z",
)

val RestoreIcon = glyph(
    "Restore",
    "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7" +
        "c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z" +
        "M12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
)

val SortIcon = glyph("Sort", "M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z")

val AttachIcon = glyph(
    "Attach",
    "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5" +
        "c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4" +
        "S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z",
)

val CameraIcon = glyph(
    "Camera",
    "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0" +
        "M9,2L7.17,4L4,4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2L9,2z" +
        "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
)

val ImageIcon = glyph(
    "Image",
    "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
        "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
)

val MicIcon = glyph(
    "Mic",
    "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
        "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
)

val StopIcon = glyph("Stop", "M6,6h12v12H6z")

val TagIcon = glyph(
    "Label",
    "M17.63,5.84C17.27,5.33 16.67,5 16,5L5,5.01C3.9,5.01 3,5.9 3,7v10c0,1.1 0.9,1.99 2,1.99L16,19" +
        "c0.67,0 1.27,-0.33 1.63,-0.84L22,12l-4.37,-6.16z",
)

val PauseIcon = glyph("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")

val BulletListIcon = glyph(
    "BulletList",
    "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5z" +
        "M4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5z" +
        "M4,16.67c-0.74,0 -1.33,0.6 -1.33,1.33s0.6,1.33 1.33,1.33 1.33,-0.6 1.33,-1.33 -0.59,-1.33 -1.33,-1.33z" +
        "M7,19h14v-2H7v2zM7,13h14v-2H7v2zM7,5v2h14V5H7z",
)

val NumberedListIcon = glyph(
    "NumberedList",
    "M2,17h2v0.5H3v1h1v0.5H2v1h3v-4H2v1zM3,8h1V4H2v1h1v3zM2,11h1.8L2,13.1v0.9h3v-1H3.2L5,10.9V10H2v1z" +
        "M7,5v2h14V5H7zM7,19h14v-2H7v2zM7,13h14v-2H7v2z",
)

val CheckBoxIcon = glyph(
    "CheckBox",
    "M19,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.11,0 2,-0.9 2,-2V5c0,-1.1 -0.89,-2 -2,-2z" +
        "M10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z",
)

val QuoteIcon = glyph("Quote", "M6,17h3l2,-4V7H5v6h3zM14,17h3l2,-4V7h-6v6h3z")

val CodeIcon = glyph(
    "Code",
    "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z",
)

val LinkIcon = glyph(
    "Link",
    "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1z" +
        "M8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z",
)

val UndoIcon = glyph(
    "Undo",
    "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 " +
        "7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
)

val RedoIcon = glyph(
    "Redo",
    "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 " +
        "1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z",
)

val AlignLeftIcon = glyph("AlignLeft", "M15,15H3v2h12v-2zM15,7H3v2h12V7zM3,13h18v-2H3v2zM3,21h18v-2H3v2zM3,3v2h18V3H3z")

val AlignCenterIcon = glyph("AlignCenter", "M7,15v2h10v-2H7zM3,21h18v-2H3v2zM3,13h18v-2H3v2zM7,7v2h10V7H7zM3,3v2h18V3H3z")

val AlignRightIcon = glyph("AlignRight", "M3,21h18v-2H3v2zM9,17h12v-2H9v2zM3,13h18v-2H3v2zM9,9h12V7H9v2zM3,3v2h18V3H3z")

/** Material "screenshot". */
val ScreenshotIcon = glyph(
    "Screenshot",
    "M17,1.01L7,1C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z" +
        "M9.5,8.5H12V7H8v4h1.5V8.5zM12,17h4v-4h-1.5v2.5H12V17z",
)

/** Material "swipe" (simplified: two-way arrow). */
val SwipeIcon = glyph("Swipe", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z")

val RotateLeftIcon = glyph(
    "RotateLeft",
    "M7.11,8.53L5.7,7.11C4.8,8.27 4.24,9.61 4.07,11h2.02c0.14,-0.87 0.49,-1.72 1.02,-2.47zM6.09,13H4.07c0.17,1.39 0.72,2.73 1.62,3.89" +
        "l1.41,-1.42c-0.52,-0.75 -0.87,-1.59 -1.01,-2.47zM7.1,18.32c1.16,0.9 2.51,1.44 3.9,1.61V17.9c-0.87,-0.15 -1.71,-0.49 -2.46,-1.03" +
        "L7.1,18.32zM13,4.07V1L8.45,5.55 13,10V6.09c2.84,0.48 5,2.94 5,5.91s-2.16,5.43 -5,5.91v2.02c3.95,-0.49 7,-3.85 7,-7.93" +
        "s-3.05,-7.44 -7,-7.93z",
)

val RotateRightIcon = glyph(
    "RotateRight",
    "M15.55,5.55L11,1v3.07C7.06,4.56 4,7.92 4,12s3.05,7.44 7,7.93v-2.02c-2.84,-0.48 -5,-2.94 -5,-5.91s2.16,-5.43 5,-5.91V10" +
        "l4.55,-4.45zM19.93,11c-0.17,-1.39 -0.72,-2.73 -1.62,-3.89l-1.42,1.42c0.54,0.75 0.88,1.6 1.02,2.47h2.02zM13,17.9v2.02" +
        "c1.39,-0.17 2.74,-0.71 3.9,-1.61l-1.44,-1.44c-0.75,0.54 -1.59,0.89 -2.46,1.03zM16.89,15.48l1.42,1.41c0.9,-1.16 1.45,-2.5 " +
        "1.62,-3.89h-2.02c-0.14,0.87 -0.48,1.72 -1.02,2.48z",
)

/** Material "hub": entries and the links between them. */
val GraphIcon = glyph(
    "Hub",
    "M8.4,18.2C8.78,18.7 9,19.32 9,20c0,1.66 -1.34,3 -3,3s-3,-1.34 -3,-3s1.34,-3 3,-3c0.44,0 0.85,0.09 1.23,0.26l1.41,-1.77" +
        "c-0.92,-1.03 -1.29,-2.39 -1.09,-3.69l-2.03,-0.68C4.98,11.95 4.06,12.5 3,12.5c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3s3,1.34 3,3" +
        "c0,0.07 0,0.14 -0.01,0.21l2.03,0.68c0.64,-1.21 1.82,-2.09 3.22,-2.32l0,-2.16C9.96,5.57 9,4.4 9,3c0,-1.66 1.34,-3 3,-3" +
        "s3,1.34 3,3c0,1.4 -0.96,2.57 -2.25,2.91v2.16c1.4,0.23 2.58,1.11 3.22,2.32l2.03,-0.68C18,9.64 18,9.57 18,9.5c0,-1.66 1.34,-3 3,-3" +
        "s3,1.34 3,3s-1.34,3 -3,3c-1.06,0 -1.98,-0.55 -2.52,-1.37l-2.03,0.68c0.2,1.29 -0.16,2.65 -1.09,3.69l1.41,1.77" +
        "C17.15,17.09 17.56,17 18,17c1.66,0 3,1.34 3,3s-1.34,3 -3,3s-3,-1.34 -3,-3c0,-0.68 0.22,-1.3 0.6,-1.8l-1.41,-1.77" +
        "c-1.35,0.75 -3.01,0.76 -4.37,0L8.4,18.2z",
)

/** Material "link" with a page: a link to another entry. */
val NoteLinkIcon = glyph(
    "NoteLink",
    "M14,2H6C4.9,2 4.01,2.9 4.01,4L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2z" +
        "M13,9V3.5L18.5,9H13z",
)

/** Material "fit_screen". */
val FitIcon = glyph(
    "FitScreen",
    "M17,4h3c1.1,0 2,0.9 2,2v2h-2V6h-3V4zM4,8V6h3V4H4C2.9,4 2,4.9 2,6v2H4zM20,16v2h-3v2h3c1.1,0 2,-0.9 2,-2v-2H20z" +
        "M7,18H4v-2H2v2c0,1.1 0.9,2 2,2h3V18zM18,8H6v8h12V8z",
)

val UploadIcon = glyph("Upload", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z")

val DownloadIcon = glyph("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")

/** Keep-style card colours: index 1..8; light and dark tones. 0 = no colour. */
private val LIGHT = listOf(
    0xFFFAAFA8, 0xFFF39F76, 0xFFFFF8B8, 0xFFE2F6D3, 0xFFB4DDD3, 0xFFD4E4ED, 0xFFD3BFDB, 0xFFF6E2DD,
)
private val DARK = listOf(
    0xFF77172E, 0xFF692B17, 0xFF7C4A03, 0xFF264D3B, 0xFF0C625D, 0xFF256377, 0xFF472E5B, 0xFF6C394F,
)

const val NOTE_COLORS = 8

/** Card colour for palette index [index], or null for "no colour". */
@Composable
fun noteColor(index: Int): Color? {
    if (index !in 1..NOTE_COLORS) return null
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return Color((if (dark) DARK else LIGHT)[index - 1])
}
