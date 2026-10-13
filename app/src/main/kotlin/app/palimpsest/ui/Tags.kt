package app.palimpsest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.palimpsest.R
import app.palimpsest.ffi.TagCount

/** Small "#tag" chip. [onRemove] adds a ×; [selected] fills it. */
@Composable
fun TagChip(tag: String, selected: Boolean = false, onClick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (selected) colors.tertiaryContainer else colors.surfaceContainerHigh.copy(alpha = 0.7f),
        contentColor = if (selected) colors.onTertiaryContainer else colors.onSurfaceVariant,
        modifier = Modifier.clip(shape).let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
    ) {
        Row(Modifier.height(30.dp).padding(start = 10.dp, end = if (onRemove != null) 4.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#$tag", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (onRemove != null) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, stringResource(R.string.remove_tag), Modifier.size(14.dp))
                }
            }
        }
    }
}

/** Tags of the entry being edited, plus an "add" chip when [editable]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryTags(tags: List<String>, editable: Boolean, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    if (tags.isEmpty() && !editable) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        for (t in tags) TagChip(t, onRemove = if (editable) ({ onRemove(t) }) else null)
        if (editable) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAdd),
            ) {
                Row(Modifier.height(30.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.tag), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Tags turned on as filters in [query] (its `#tag` words), lower-cased. */
fun activeTags(query: String) = query.split(' ').filter { it.startsWith("#") && it.length > 1 }.map { it.drop(1).lowercase() }.toSet()

/**
 * Every tag of the vault with its count, opened from the search bar. Tap filters by a tag,
 * long press renames or deletes it everywhere.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TagPanel(tags: List<TagCount>, query: String, onToggle: (String) -> Unit, onEdit: (TagCount) -> Unit) {
    val active = activeTags(query)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (tags.isEmpty()) {
                Text(stringResource(R.string.tags_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in tags) {
                        val on = t.name.lowercase() in active
                        val shape = RoundedCornerShape(8.dp)
                        Surface(
                            shape = shape,
                            color = if (on) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (on) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(shape).combinedClickable(onClick = { onToggle(t.name) }, onLongClick = { onEdit(t) }),
                        ) {
                            Row(Modifier.height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("#${t.name}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Spacer(Modifier.width(6.dp))
                                Text("${t.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tags_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Active tag filters while the panel is closed; tap one to drop it. */
@Composable
fun ActiveTagRow(query: String, onToggle: (String) -> Unit) {
    val active = query.split(' ').filter { it.startsWith("#") && it.length > 1 }.map { it.drop(1) }
    if (active.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(active, key = { it }) { t -> TagChip(t, selected = true, onRemove = { onToggle(t) }) }
    }
}

/** Rename a tag in every entry (onto an existing one merges them), or delete it. */
@Composable
fun TagEditDialog(tag: TagCount, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(tag.name) }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(R.string.tag_delete_confirm, tag.name, tag.count.toInt())) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(TagIcon, null) },
        title = { Text("#${tag.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SecureTextField(name, { name = it }, stringResource(R.string.tag_name), Modifier.fillMaxWidth(), plain = true, onDone = {
                            if (name.isNotBlank()) onRename(name)
                        })
                    }
                }
                Text(stringResource(R.string.tag_rename_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
    )
}

/** Type a new tag or pick an existing one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagDialog(existing: List<TagCount>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val matches = existing.filter { text.isBlank() || it.name.contains(text.trim().removePrefix("#"), ignoreCase = true) }.take(12)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(TagIcon, null) },
        title = { Text(stringResource(R.string.add_tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SecureTextField(text, { text = it }, stringResource(R.string.tag_name), Modifier.fillMaxWidth(), plain = true, onDone = {
                            if (text.isNotBlank()) onAdd(text)
                        })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in matches) TagChip(t.name, onClick = { onAdd(t.name) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
