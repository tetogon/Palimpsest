package app.palimpsest.ui

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import app.palimpsest.R

/** A cell of table [key] in the editor. */
data class TableCell(val key: Long, val row: Int, val col: Int)

private const val CELL_TEXT_SP = 16f

/**
 * A table while editing: a grid of one-line fields, the header row on a tinted background.
 * A column is as wide as its longest text (within limits) and grows while typing; a wide
 * table scrolls sideways. Rows and columns are added and removed from the format bar
 * ([TableTools]) while a cell has focus.
 */
@Composable
fun TableEditor(
    table: DocTable,
    focus: TableCell?,
    onFocusHandled: () -> Unit,
    onCell: (TableCell, String) -> Unit,
    onFocused: (TableCell) -> Unit,
    onNext: (TableCell) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontSize = CELL_TEXT_SP.sp, fontWeight = FontWeight.Bold)
    val widths = (0 until table.cols).map { c ->
        val longest = table.cells.maxOf { row -> measurer.measure(row[c], style, maxLines = 1).size.width }
        with(density) { longest.toDp() + 28.dp }.coerceIn(88.dp, 260.dp)
    }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.clip(shape).border(1.dp, scheme.outlineVariant, shape)) {
            table.cells.forEachIndexed { r, row ->
                Row {
                    row.forEachIndexed { c, text ->
                        val cell = TableCell(table.key, r, c)
                        CellField(
                            text,
                            header = r == 0,
                            align = table.align[c],
                            focus = focus == cell,
                            onFocusHandled = onFocusHandled,
                            onChange = { onCell(cell, it) },
                            onFocused = { onFocused(cell) },
                            onNext = { onNext(cell) },
                            modifier = Modifier
                                .width(widths[c])
                                .height(44.dp)
                                .then(if (r == 0) Modifier.background(scheme.surfaceContainerHighest) else Modifier)
                                .border(0.5.dp, scheme.outlineVariant)
                                .padding(horizontal = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One cell: a secure one-line EditText (incognito keyboard, no autofill, guarded clipboard). */
@Composable
private fun CellField(
    value: String,
    header: Boolean,
    align: MdAlign,
    focus: Boolean,
    onFocusHandled: () -> Unit,
    onChange: (String) -> Unit,
    onFocused: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val focused by rememberUpdatedState(onFocused)
    val next by rememberUpdatedState(onNext)
    val echo = remember { EchoGuard() }
    val ref = remember { arrayOfNulls<EditText>(1) }
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurface.toArgb()
    val highlight = scheme.primary.copy(alpha = 0.3f).toArgb()
    LaunchedEffect(focus) {
        val v = ref[0] ?: return@LaunchedEffect
        if (!focus) return@LaunchedEffect
        v.requestFocus()
        v.setSelection(v.length())
        v.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0)
        onFocusHandled()
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SecureEditText(context).apply {
                ref[0] = this
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NEXT
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                background = null
                setPadding(0, 0, 0, 0)
                textSize = CELL_TEXT_SP
                setText(value)
                echo.emitted = value
                doAfterTextChanged {
                    val text = it?.toString().orEmpty()
                    echo.emitted = text
                    change(text)
                }
                setOnFocusChangeListener { _, has -> if (has) focused() }
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_NEXT) {
                        next()
                        true
                    } else {
                        false
                    }
                }
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.highlightColor = highlight
            view.typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            view.gravity = Gravity.CENTER_VERTICAL or when (align) {
                MdAlign.START -> Gravity.START
                MdAlign.CENTER -> Gravity.CENTER_HORIZONTAL
                MdAlign.END -> Gravity.END
            }
            echo.apply(view, value)
        },
    )
}

/** The format bar's tools while a cell of [table] has focus: rows, columns, alignment. */
@Composable
fun RowScope.TableTools(
    table: DocTable,
    cell: TableCell,
    onChange: (DocTable, TableCell) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val (r, c) = cell.row to cell.col
    TableTool(R.string.table_row_add) { onChange(table.addRow(r + 1), cell.copy(row = maxOf(r + 1, 1))) }
    TableTool(R.string.table_col_add) { onChange(table.addCol(c + 1), cell.copy(col = c + 1)) }
    TableTool(R.string.table_row_remove, enabled = r > 0) {
        val t = table.removeRow(r)
        onChange(t, cell.copy(row = minOf(r, t.rows - 1)))
    }
    TableTool(R.string.table_col_remove, enabled = table.cols > 1) {
        val t = table.removeCol(c)
        onChange(t, cell.copy(col = minOf(c, t.cols - 1)))
    }
    VerticalDivider(Modifier.height(24.dp))
    val a = table.align.getOrElse(c) { MdAlign.START }
    ToggleTool(AlignLeftIcon, R.string.align_left, a == MdAlign.START) { onChange(table.setAlign(c, MdAlign.START), cell) }
    ToggleTool(AlignCenterIcon, R.string.align_center, a == MdAlign.CENTER) { onChange(table.setAlign(c, MdAlign.CENTER), cell) }
    ToggleTool(AlignRightIcon, R.string.align_right, a == MdAlign.END) { onChange(table.setAlign(c, MdAlign.END), cell) }
    VerticalDivider(Modifier.height(24.dp))
    IconTool(Icons.Filled.Delete, R.string.table_delete, onDelete)
    IconTool(Icons.Filled.Check, R.string.done, onDone)
}

@Composable
private fun TableTool(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }
}
