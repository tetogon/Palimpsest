package app.palimpsest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.palimpsest.AppSettings
import app.palimpsest.R
import app.palimpsest.ffi.EntryKind
import app.palimpsest.ffi.EntrySummary
import app.palimpsest.ffi.Folder
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- Sections ------------------------------------------------------------------------------

private data class SectionInfo(val kind: EntryKind, val label: Int, val icon: ImageVector)

private val SECTIONS = listOf(
    SectionInfo(EntryKind.NOTE, R.string.section_notes, Icons.AutoMirrored.Filled.List),
    SectionInfo(EntryKind.DIARY, R.string.section_diary, Icons.Filled.DateRange),
    SectionInfo(EntryKind.TODO, R.string.section_todo, Icons.Filled.CheckCircle),
)

/** Tap opens, long press starts selection; while selecting, a tap toggles. */
internal class Picker(
    val selection: Set<String>,
    private val onOpen: (String) -> Unit,
    private val onToggle: (String) -> Unit,
    private val haptic: () -> Unit,
) {
    val active get() = selection.isNotEmpty()
    fun isPicked(id: String) = id in selection
    fun tap(id: String) = if (active) onToggle(id) else onOpen(id)
    fun longPress(id: String) {
        if (!active) haptic()
        onToggle(id)
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.pickable(picker: Picker, id: String) =
    combinedClickable(onClick = { picker.tap(id) }, onLongClick = { picker.longPress(id) })

@Composable
internal fun rememberPicker(selection: Set<String>, onOpen: (String) -> Unit, onToggle: (String) -> Unit): Picker {
    val haptic = LocalHapticFeedback.current
    return Picker(selection, onOpen, onToggle) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
}

private enum class HomeDialog { DELETE, COLOR, MOVE, NEW_FOLDER, SORT, TAG }

@Composable
fun HomeScreen(ui: UiState, vm: AppViewModel) {
    val items = ui.visible()
    val picker = rememberPicker(ui.selection, vm::openEntry, vm::toggleSelect)
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var editFolder by remember { mutableStateOf<Folder?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var calendarDay by remember { mutableStateOf(java.time.LocalDate.now()) }
    // The tag list stays folded away until the tag button opens it.
    var tagsOpen by remember { mutableStateOf(false) }
    var editTag by remember { mutableStateOf<app.palimpsest.ffi.TagCount?>(null) }
    BackHandler(enabled = picker.active, onBack = vm::clearSelection)

    LaunchedEffect(ui.justTrashed) {
        val n = ui.justTrashed.size
        if (n > 0) {
            val result = snackbar.showSnackbar(
                context.resources.getQuantityString(R.plurals.moved_to_trash, n, n),
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoTrash() else vm.trashMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (picker.active) {
                SelectionBar(ui.selection.size, vm::clearSelection, vm::selectAll) {
                    if (ui.section == EntryKind.NOTE) {
                        IconButton(onClick = vm::togglePinSelected) { Icon(PinIcon, stringResource(R.string.pin)) }
                        IconButton(onClick = { dialog = HomeDialog.COLOR }) { Icon(PaletteIcon, stringResource(R.string.color)) }
                        IconButton(onClick = { dialog = HomeDialog.MOVE }) { Icon(FolderIcon, stringResource(R.string.move_to_folder)) }
                    }
                    IconButton(onClick = { dialog = HomeDialog.TAG }) { Icon(TagIcon, stringResource(R.string.add_tag)) }
                    IconButton(onClick = vm::trashSelected) { Icon(Icons.Filled.Delete, stringResource(R.string.to_trash)) }
                }
            } else {
                SearchBar(
                    ui.query, vm::setQuery, vm::openSettings, vm::lock,
                    tagsOpen = tagsOpen, activeTags = activeTags(ui.query).size, onTags = { tagsOpen = !tagsOpen },
                    onSort = { dialog = HomeDialog.SORT }, onTrash = vm::openTrash, onGraph = vm::openGraph,
                )
            }
        },
        bottomBar = {
            NavigationBar {
                for (s in SECTIONS) {
                    NavigationBarItem(
                        selected = ui.section == s.kind,
                        onClick = { vm.setSection(s.kind) },
                        icon = { Icon(s.icon, null) },
                        label = { Text(stringResource(s.label)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (!picker.active) {
                FloatingActionButton(onClick = {
                    val onDay = ui.section == EntryKind.DIARY && ui.appSettings.diaryCalendar
                    vm.newEntry(ui.section, if (onDay) calendarDay else null)
                }) {
                    Icon(Icons.Filled.Add, stringResource(R.string.new_entry))
                }
            }
        },
    ) { padding ->
        // The three sections side by side: tap below or swipe across the list.
        val sectionIndex = SECTIONS.indexOfFirst { it.kind == ui.section }.coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = sectionIndex) { SECTIONS.size }
        LaunchedEffect(sectionIndex) { if (pager.currentPage != sectionIndex) pager.animateScrollToPage(sectionIndex) }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { page ->
                if (SECTIONS[page].kind != vm.ui.value.section) vm.setSection(SECTIONS[page].kind)
            }
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tagsOpen) {
                TagPanel(ui.tags, ui.query, vm::toggleTagFilter, onEdit = { editTag = it })
            } else {
                ActiveTagRow(ui.query, vm::toggleTagFilter)
            }
            HorizontalPager(
                pager,
                modifier = Modifier.weight(1f),
                userScrollEnabled = ui.appSettings.swipeSections && !picker.active,
                key = { SECTIONS[it].kind.name },
            ) { page ->
                val kind = SECTIONS[page].kind
                val pageItems = if (kind == ui.section) items else ui.copy(section = kind).visible()
                Column(Modifier.fillMaxSize()) {
                    if (kind == EntryKind.NOTE) {
                        FolderRow(
                            ui.folders, ui.folderFilter, vm::setFolderFilter,
                            onNew = { dialog = HomeDialog.NEW_FOLDER }, onEdit = { editFolder = it },
                            onReorder = vm::reorderFolders,
                        )
                    }
                    val calendar = kind == EntryKind.DIARY && ui.appSettings.diaryCalendar
                    if (kind == EntryKind.DIARY) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(stringResource(R.string.diary_timeline), !calendar, icon = SortIcon, onClick = { vm.setDiaryCalendar(false) })
                            Chip(stringResource(R.string.diary_calendar), calendar, icon = Icons.Filled.DateRange, onClick = { vm.setDiaryCalendar(true) })
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when {
                            pageItems.isEmpty() && ui.query.isNotBlank() -> EmptyState(Icons.Filled.Search, R.string.no_results, null)
                            kind == EntryKind.NOTE -> NotesGrid(pageItems, picker, vm::thumbnail)
                            calendar -> DiaryCalendar(pageItems, picker, calendarDay, { calendarDay = it }, onWrite = { vm.newEntry(EntryKind.DIARY, it) })
                            kind == EntryKind.DIARY -> DiaryTimeline(pageItems, picker)
                            else -> TodoList(pageItems, picker, vm::setDone, vm::quickAddTodo)
                        }
                    }
                }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        HomeDialog.COLOR -> ColorDialog(current = null, onPick = { vm.colorSelected(it); close() }, onDismiss = close)
        HomeDialog.MOVE -> FolderPickerDialog(ui.folders, current = null, onPick = { vm.moveSelected(it); close() }, onDismiss = close)
        HomeDialog.NEW_FOLDER -> NameDialog(R.string.new_folder, "", onDone = { vm.createFolder(it); close() }, onDismiss = close)
        HomeDialog.SORT -> SortDialog(ui.appSettings.sort, onPick = { vm.setSort(it); close() }, onDismiss = close)
        HomeDialog.TAG -> TagDialog(ui.tags, onAdd = { vm.tagSelected(it); close() }, onDismiss = close)
        HomeDialog.DELETE, null -> Unit
    }
    editTag?.let { t ->
        TagEditDialog(
            t,
            onRename = { vm.renameTag(t.name, it); editTag = null },
            onDelete = { vm.deleteTag(t.name); editTag = null },
            onDismiss = { editTag = null },
        )
    }
    editFolder?.let { f ->
        FolderEditDialog(
            f,
            onRename = { vm.renameFolder(f.folderId, it); editFolder = null },
            onDelete = { vm.deleteFolder(f.folderId); editFolder = null },
            onDismiss = { editFolder = null },
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onSort: () -> Unit,
    onTrash: () -> Unit,
    onGraph: () -> Unit,
    tagsOpen: Boolean,
    activeTags: Int,
    onTags: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            SecureTextField(query, onQuery, stringResource(R.string.search_hint), Modifier.weight(1f), textSizeSp = 16f, plain = true)
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear_search)) }
            }
            IconButton(onClick = onTags) {
                BadgedBox(badge = { if (activeTags > 0) Badge { Text("$activeTags") } }) {
                    Icon(
                        TagIcon, stringResource(R.string.tags),
                        tint = if (tagsOpen || activeTags > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onLock) { Icon(Icons.Filled.Lock, stringResource(R.string.lock)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort)) },
                        leadingIcon = { Icon(SortIcon, null) },
                        onClick = { menu = false; onSort() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.graph)) },
                        leadingIcon = { Icon(GraphIcon, null) },
                        onClick = { menu = false; onGraph() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trash)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; onTrash() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings)) },
                        leadingIcon = { Icon(Icons.Filled.Settings, null) },
                        onClick = { menu = false; onSettings() },
                    )
                }
            }
        }
    }
}

/** Replaces the search bar while entries are selected. */
@Composable
internal fun SelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, actions: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(Modifier.height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
            Text(
                pluralStringResource(R.plurals.selected_count, count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.Done, stringResource(R.string.select_all)) }
            actions()
        }
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: Int, hint: Int?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Small tick in the corner of a selected item. */
@Composable
internal fun PickedBadge(modifier: Modifier = Modifier) {
    Icon(Icons.Filled.CheckCircle, null, modifier = modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
}

// ---- Folders -------------------------------------------------------------------------------

/**
 * Folder chips. Long press and drag moves a folder along the row; long press without moving
 * opens it for renaming.
 */
@Composable
private fun FolderRow(
    folders: List<Folder>,
    current: String?,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
    onEdit: (Folder) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    // One state for the whole life of the row: a drag's handler must keep seeing it after a
    // swap. It follows the folders from outside whenever no drag is going on.
    var order by remember { mutableStateOf(folders) }
    var dragging by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(folders) { if (dragging == null) order = folders }
    val saved by rememberUpdatedState(folders)
    val edit by rememberUpdatedState(onEdit)
    val reorder by rememberUpdatedState(onReorder)
    var offset by remember { mutableFloatStateOf(0f) }
    var moved by remember { mutableStateOf(false) }
    val widths = remember { mutableMapOf<String, Float>() }
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val slop = with(density) { 10.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), enabled = dragging == null).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(stringResource(R.string.all_notes), current == null, onClick = { onSelect(null) })
        for (f in order) {
            key(f.folderId) {
                val lifted = dragging == f.folderId
                Box(
                    Modifier
                        .onGloballyPositioned { widths[f.folderId] = it.size.width.toFloat() }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            translationX = if (lifted) offset else 0f
                            scaleX = if (lifted) 1.08f else 1f
                            scaleY = if (lifted) 1.08f else 1f
                            shadowElevation = if (lifted) 10f else 0f
                        }
                        .pointerInput(f.folderId) { detectTapGestures(onTap = { onSelect(f.folderId) }) }
                        // Keyed by the folder only: a key that changed with the order restarted the
                        // handler at every swap and so cancelled the drag.
                        .pointerInput(f.folderId) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragging = f.folderId
                                    offset = 0f
                                    moved = false
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offset += amount.x
                                    if (kotlin.math.abs(offset) > slop) moved = true
                                    val i = order.indexOfFirst { it.folderId == f.folderId }
                                    val me = order.getOrNull(i) ?: return@detectDragGesturesAfterLongPress
                                    val next = order.getOrNull(i + 1)
                                    val prev = order.getOrNull(i - 1)
                                    if (next != null && offset > (widths[next.folderId] ?: 0f) / 2 + gap) {
                                        offset -= (widths[next.folderId] ?: 0f) + gap
                                        order = order.toMutableList().also { it[i] = next; it[i + 1] = me }
                                    } else if (prev != null && offset < -((widths[prev.folderId] ?: 0f) / 2 + gap)) {
                                        offset += (widths[prev.folderId] ?: 0f) + gap
                                        order = order.toMutableList().also { it[i] = prev; it[i - 1] = me }
                                    }
                                },
                                onDragEnd = {
                                    dragging = null
                                    offset = 0f
                                    val current = order.firstOrNull { it.folderId == f.folderId } ?: f
                                    when {
                                        !moved -> edit(current)
                                        order.map { it.folderId } != saved.map { it.folderId } -> reorder(order.map { it.folderId })
                                    }
                                },
                                onDragCancel = {
                                    dragging = null
                                    offset = 0f
                                    order = saved
                                },
                            )
                        },
                ) {
                    Chip(
                        if (f.noteCount > 0u) "${f.name}  ${f.noteCount}" else f.name,
                        current == f.folderId,
                        icon = FolderIcon,
                        onClick = null,
                    )
                }
            }
        }
        Chip(stringResource(R.string.new_folder), false, icon = Icons.Filled.Add, onClick = onNew)
    }
}

/** [onClick] null: the chip only draws, its parent handles touches (dragging folders). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(label: String, selected: Boolean, icon: ImageVector? = null, onClick: (() -> Unit)?, onLongClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.clip(shape).let { m -> if (onClick != null) m.combinedClickable(onClick = onClick, onLongClick = onLongClick) else m },
    ) {
        Row(Modifier.height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

// ---- Notes: staggered grid of cards, pinned first ------------------------------------------

@Composable
private fun NotesGrid(items: List<EntrySummary>, picker: Picker, loadThumb: suspend (String) -> android.graphics.Bitmap?) {
    if (items.isEmpty()) return EmptyState(Icons.AutoMirrored.Filled.List, R.string.empty_notes, R.string.empty_notes_hint)
    val pinned = items.filter { it.pinned }
    val others = items.filter { !it.pinned }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
        verticalItemSpacing = 10.dp,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pinned.isNotEmpty()) {
            item(key = "h-pinned", span = StaggeredGridItemSpan.FullLine) { GridHeader(R.string.pinned) }
            items(pinned, key = { it.entryId }) { e -> NoteCard(e, picker, loadThumb) }
            if (others.isNotEmpty()) {
                item(key = "h-others", span = StaggeredGridItemSpan.FullLine) { GridHeader(R.string.others) }
            }
        }
        items(others, key = { it.entryId }) { e -> NoteCard(e, picker, loadThumb) }
    }
}

@Composable
private fun GridHeader(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun NoteCard(e: EntrySummary, picker: Picker, loadThumb: suspend (String) -> android.graphics.Bitmap?) {
    val picked = picker.isPicked(e.entryId)
    val shape = RoundedCornerShape(16.dp)
    val tint = noteColor(e.color)
    Surface(
        shape = shape,
        color = when {
            picked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            tint != null -> tint
            else -> Color.Transparent
        },
        border = when {
            picked -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            tint != null -> null
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier.clip(shape).pickable(picker, e.entryId),
    ) {
        Box {
          Column {
            val photo = e.attachments.firstOrNull { it.isImage && it.hasThumbnail }
            if (photo != null) {
                val ratio = if (photo.width > 0 && photo.height > 0) (photo.width.toFloat() / photo.height).coerceIn(0.75f, 1.8f) else 1.3f
                Thumbnail(photo.attachmentId, loadThumb, Modifier.fillMaxWidth().aspectRatio(ratio))
            }
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                if (e.title.isNotBlank()) {
                    Text(
                        e.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                }
                if (e.preview.isNotBlank()) {
                    if (e.title.isNotBlank()) Spacer(Modifier.height(6.dp))
                    PreviewText(e.preview, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 10)
                }
                if (e.title.isBlank() && e.preview.isBlank()) {
                    Text(stringResource(R.string.untitled), color = MaterialTheme.colorScheme.outline)
                }
                if (e.tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        e.tags.take(4).joinToString("  ") { "#$it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shortWhen(e.updatedMs) + if (e.hasConflict) "  ⚠" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f),
                    )
                    val voices = e.attachments.count { it.isAudio }
                    val photos = e.attachments.count { it.isImage }
                    if (photos > 1) MediaBadge(ImageIcon, photos)
                    if (voices > 0) MediaBadge(MicIcon, voices)
                }
            }
          }
            when {
                picked -> PickedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
                e.pinned -> Icon(
                    PinIcon, null,
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- Diary: timeline grouped by month ------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiaryTimeline(items: List<EntrySummary>, picker: Picker) {
    if (items.isEmpty()) return EmptyState(Icons.Filled.DateRange, R.string.empty_diary, R.string.empty_diary_hint)
    val months = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
    val byMonth = items
        .sortedWith(compareByDescending<EntrySummary> { day(it.diaryDate, it.createdMs) }.thenByDescending { it.createdMs })
        .groupBy { day(it.diaryDate, it.createdMs).withDayOfMonth(1) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        for ((month, entries) in byMonth) {
            stickyHeader(key = "m$month") {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        month.format(months).capitalized(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
            }
            items(entries, key = { it.entryId }) { e -> DiaryRow(e, picker) }
        }
    }
}

@Composable
internal fun DiaryRow(e: EntrySummary, picker: Picker) {
    val date = day(e.diaryDate, e.createdMs)
    val picked = picker.isPicked(e.entryId)
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (picked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).pickable(picker, e.entryId),
    ) {
        Row(Modifier.padding(14.dp)) {
            Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.headlineMedium)
                Text(
                    date.format(DateTimeFormatter.ofPattern("EE", Locale.getDefault())).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = e.title.ifBlank { previewPlain(e.preview.lineSequence().firstOrNull().orEmpty()) }
                    .ifBlank { stringResource(R.string.untitled) }
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val rest = if (e.title.isBlank()) e.preview.lineSequence().drop(1).joinToString("\n") else e.preview
                if (rest.isNotBlank()) {
                    PreviewText(rest, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
            }
            if (picked) PickedBadge()
        }
    }
}

// ---- To-do: open first, done below ----------------------------------------------------------

@Composable
private fun TodoList(
    items: List<EntrySummary>,
    picker: Picker,
    onSetDone: (String, Boolean) -> Unit,
    onQuickAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val add = {
        onQuickAdd(text)
        text = ""
    }
    val open = items.filter { !it.done }
    val done = items.filter { it.done }
    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
        if (!picker.active) {
            item(key = "add") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecureTextField(
                            text, { text = it }, stringResource(R.string.new_task_hint), Modifier.weight(1f),
                            plain = true, onDone = add,
                        )
                        IconButton(onClick = add, enabled = text.isNotBlank()) {
                            Icon(Icons.Filled.Add, stringResource(R.string.add))
                        }
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    EmptyState(Icons.Filled.CheckCircle, R.string.empty_todo, R.string.empty_todo_hint)
                }
            }
        }
        items(open, key = { it.entryId }) { e -> TodoRow(e, picker, onSetDone) }
        if (done.isNotEmpty()) {
            item(key = "done-header") {
                Text(
                    stringResource(R.string.done_header, done.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            items(done, key = { it.entryId }) { e -> TodoRow(e, picker, onSetDone) }
        }
    }
}

@Composable
private fun TodoRow(e: EntrySummary, picker: Picker, onSetDone: (String, Boolean) -> Unit) {
    val picked = picker.isPicked(e.entryId)
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = if (picked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().clip(shape).pickable(picker, e.entryId),
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = e.done,
                onCheckedChange = { if (!picker.active) onSetDone(e.entryId, it) else picker.tap(e.entryId) },
            )
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    e.title.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (e.done) TextDecoration.LineThrough else null,
                    color = if (e.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (e.preview.isNotBlank()) {
                    PreviewText(e.preview.lineSequence().first(), MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (picked) PickedBadge(Modifier.padding(end = 8.dp))
        }
    }
}

// ---- Dialogs ---------------------------------------------------------------------------------

/** Palette of the 8 card colours plus "none". `current` null = several notes, nothing ticked. */
@Composable
internal fun ColorDialog(current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in (0..NOTE_COLORS).chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (c in row) {
                            val fill = noteColor(c) ?: MaterialTheme.colorScheme.surfaceContainerHigh
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = fill,
                                border = BorderStroke(
                                    if (c == current) 3.dp else 1.dp,
                                    if (c == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(50)).combinedClickableOnce { onPick(c) },
                            ) {
                                if (c == 0) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Clear, stringResource(R.string.no_color))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableOnce(onClick: () -> Unit) = combinedClickable(onClick = onClick)

/** "No folder" plus every folder. `current` null = several notes, nothing ticked. */
@Composable
internal fun FolderPickerDialog(folders: List<Folder>, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_folder)) },
        text = {
            LazyColumn {
                item {
                    PickRow(stringResource(R.string.no_folder), null, selected = false) { onPick(null) }
                }
                items(folders, key = { it.folderId }) { f ->
                    PickRow(f.name, FolderIcon, selected = f.folderId == current) { onPick(f.folderId) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PickRow(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickableOnce(onClick).padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) else Spacer(Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NameDialog(title: Int, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SecureTextField(name, { name = it }, stringResource(R.string.folder_name), Modifier.fillMaxWidth(), plain = true, onDone = { onDone(name) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FolderEditDialog(folder: Folder, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(folder.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.delete_folder_confirm, folder.name)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(FolderIcon, null) },
        title = { Text(stringResource(R.string.folder)) },
        text = {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SecureTextField(name, { name = it }, stringResource(R.string.folder_name), Modifier.fillMaxWidth(), plain = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete_folder), color = MaterialTheme.colorScheme.error) }
        },
    )
}

@Composable
private fun SortDialog(current: AppSettings.SortOrder, onPick: (AppSettings.SortOrder) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(SortIcon, null) },
        title = { Text(stringResource(R.string.sort)) },
        text = {
            Column {
                for ((order, label) in listOf(
                    AppSettings.SortOrder.UPDATED to R.string.sort_updated,
                    AppSettings.SortOrder.CREATED to R.string.sort_created,
                    AppSettings.SortOrder.TITLE to R.string.sort_title,
                )) {
                    PickRow(stringResource(label), null, selected = order == current) { onPick(order) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MediaBadge(icon: ImageVector, count: Int) {
    Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    if (count > 1) {
        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(6.dp))
}