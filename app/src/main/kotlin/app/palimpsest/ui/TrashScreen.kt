package app.palimpsest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.palimpsest.R
import app.palimpsest.ffi.EntryKind
import app.palimpsest.ffi.EntrySummary

private const val RETENTION_DAYS = 30L

/** Everything trashed, from all three sections. Tap or long press selects; nothing opens here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(ui: UiState, vm: AppViewModel) {
    val items = ui.trashed()
    val picker = rememberPicker(ui.selection, onOpen = vm::toggleSelect, onToggle = vm::toggleSelect)
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true = selected, false = everything
    BackHandler { if (picker.active) vm.clearSelection() else vm.closeTrash() }

    Scaffold(
        topBar = {
            if (picker.active) {
                SelectionBar(ui.selection.size, vm::clearSelection, vm::selectAll) {
                    IconButton(onClick = vm::restoreSelected) { Icon(RestoreIcon, stringResource(R.string.restore)) }
                    IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_forever)) }
                }
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.trash)) },
                    navigationIcon = {
                        IconButton(onClick = vm::closeTrash) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        if (items.isNotEmpty()) {
                            TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.empty_trash)) }
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) { EmptyState(Icons.Filled.Delete, R.string.trash_empty, R.string.trash_hint) }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.trash_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }
            items(items, key = { it.entryId }) { e -> TrashRow(e, picker) }
        }
    }

    confirm?.let { selectedOnly ->
        val n = if (selectedOnly) ui.selection.size else items.size
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(pluralStringResource(R.plurals.delete_selected_confirm, n, n)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (selectedOnly) vm.deleteSelected() else vm.emptyTrash()
                }) { Text(stringResource(R.string.delete_forever), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TrashRow(e: EntrySummary, picker: Picker) {
    val picked = picker.isPicked(e.entryId)
    val shape = RoundedCornerShape(16.dp)
    val daysLeft = e.trashedMs?.let { RETENTION_DAYS - (System.currentTimeMillis() - it) / 86_400_000L }?.coerceAtLeast(0) ?: 0
    val section = when (e.kind) {
        EntryKind.NOTE -> R.string.section_notes
        EntryKind.DIARY -> R.string.section_diary
        EntryKind.TODO -> R.string.section_todo
    }
    Surface(
        shape = shape,
        color = if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (picked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).pickable(picker, e.entryId),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    e.title.ifBlank { previewPlain(e.preview.lineSequence().firstOrNull().orEmpty()) }.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(section) + " · " + pluralStringResource(R.plurals.days_left, daysLeft.toInt(), daysLeft.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (picked) PickedBadge()
        }
    }
}
