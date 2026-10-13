package app.palimpsest.ui

import android.graphics.Typeface
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.launch
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.AnnotatedString
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import app.palimpsest.ffi.AttachmentInfo
import androidx.core.content.ContextCompat
import androidx.compose.material3.LinearProgressIndicator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.content.Context
import android.Manifest
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.palimpsest.R
import app.palimpsest.ffi.EntryKind
import app.palimpsest.ffi.EntrySummary
import app.palimpsest.ffi.Folder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// ---- Editor -------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(draft: Draft, ui: UiState, vm: AppViewModel) {
    val folders = ui.folders
    var dialog by remember { mutableStateOf<String?>(null) } // "color" | "folder" | "attach"
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<AttachmentInfo?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) camera = true }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) recording = true }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attachFromGallery) }
    // Existing entries open for reading; new or empty ones straight into editing.
    var editing by remember { mutableStateOf(draft.entryId == null || draft.body.isBlank()) }
    val context = LocalContext.current
    // The colour marks the card in the list; inside, the entry keeps the plain background.
    val tint = noteColor(draft.color)
    val background = MaterialTheme.colorScheme.surface
    val isNote = draft.kind == EntryKind.NOTE

    // The body as blocks. Our own edits come back through the view model; anything else
    // (an attachment removed, a conflict merged) rebuilds the blocks.
    // Compared with the body the blocks were last built from (not with their serialization,
    // which may tidy old bodies) and with bodies we sent that may still be on their way.
    var doc by remember { mutableStateOf(Doc.parse(draft.body)) }
    var source by remember { mutableStateOf(draft.body) }
    val sent = remember { ArrayDeque<String>() }
    if (draft.body != source && draft.body !in sent) {
        doc = Doc.parse(draft.body)
        source = draft.body
        sent.clear()
    }
    fun commit(next: List<DocBlock>) {
        doc = next
        val body = Doc.serialize(next)
        source = body
        sent.addLast(body)
        if (sent.size > 64) sent.removeFirst()
        vm.editBody(body)
    }

    // One history for the whole entry: typing in any field and changes to the layout.
    val undoLog = remember { mutableStateListOf<EditStep>() }
    val redoLog = remember { mutableStateListOf<EditStep>() }
    val controllers = remember { HashMap<Long, MdController>() }
    fun controllerFor(key: Long) = controllers.getOrPut(key) {
        MdController().also { c ->
            c.onSnapshot = {
                undoLog.add(EditStep.Typing(c))
                if (undoLog.size > 300) undoLog.removeAt(0)
                redoLog.clear()
            }
        }
    }
    fun commitLayout(next: List<DocBlock>) {
        undoLog.add(EditStep.Layout(doc))
        redoLog.clear()
        commit(next)
    }
    fun undo() {
        while (undoLog.isNotEmpty()) {
            when (val s = undoLog.removeAt(undoLog.lastIndex)) {
                is EditStep.Typing -> if (s.field.undo()) {
                    redoLog.add(s)
                    return
                }
                is EditStep.Layout -> {
                    redoLog.add(EditStep.Layout(doc))
                    commit(s.doc)
                    return
                }
            }
        }
    }
    fun redo() {
        while (redoLog.isNotEmpty()) {
            when (val s = redoLog.removeAt(redoLog.lastIndex)) {
                is EditStep.Typing -> if (s.field.redo()) {
                    undoLog.add(s)
                    return
                }
                is EditStep.Layout -> {
                    undoLog.add(EditStep.Layout(doc))
                    commit(s.doc)
                    return
                }
            }
        }
    }
    var active by remember { mutableStateOf<MdController?>(null) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var focus by remember { mutableStateOf<FocusRequest?>(null) }
    val drag = remember { PhotoDrag() }
    val scroll = rememberScrollState()
    val embedded = remember(doc) { Doc.embeddedIds(doc) }
    val loose = draft.attachments.filter { it.attachmentId !in embedded }
    val selectedPhoto = doc.firstOrNull { it.key == selected } as? DocPhoto
    if (selected != null && selectedPhoto == null) selected = null
    val words = remember(draft.title, doc) {
        Md.wordCount(draft.title + " " + doc.joinToString(" ") { if (it is DocText) it.text else (it as DocPhoto).side })
    }

    fun select(key: Long?) {
        selected = key
        if (key != null) {
            // The photo takes over: the keyboard goes away and the bar shows the photo's options.
            active?.view?.let { v ->
                v.clearFocus()
                v.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }
    }

    drag.doc = doc
    drag.allowText = editing
    drag.onDrop = { item, target ->
        when (target) {
            is DropTarget.Text -> {
                val photo = item.docKey ?: Doc.key()
                commitLayout(
                    if (item.docKey != null) Doc.move(doc, item.docKey, target.key, target.offset)
                    else Doc.insert(doc, DocPhoto(photo, item.id, MdAlign.CENTER, MdSize.L), target.key, target.offset),
                )
                select(photo)
            }
            is DropTarget.Strip -> {
                val rest = if (item.docKey != null) Doc.remove(doc, item.docKey) else doc
                if (item.docKey != null) {
                    commitLayout(rest)
                    selected = null
                }
                val inText = Doc.embeddedIds(rest)
                val strip = draft.attachments.map { it.attachmentId }.filter { it !in inText && it != item.id }.toMutableList()
                strip.add(target.index.coerceIn(0, strip.size), item.id)
                val order = strip + draft.attachments.map { it.attachmentId }.filter { it in inText }
                if (order != draft.attachments.map { it.attachmentId }) vm.reorderAttachments(order)
            }
        }
    }
    // Near the top or bottom edge a held photo scrolls the page.
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(drag.item != null) {
        while (drag.item != null) {
            withFrameNanos { }
            val v = drag.viewport
            val y = drag.pointer.y
            val dy = when {
                y < v.top + edge -> -(v.top + edge - y).coerceAtMost(edge) / 4
                y > v.bottom - edge -> (y - v.bottom + edge).coerceAtMost(edge) / 4
                else -> 0f
            }
            if (dy != 0f) scroll.scrollBy(dy)
            drag.refresh()
        }
    }
    BackHandler(enabled = selected != null) { selected = null }
    // Picking an entry to link to: the field to insert into, and whether "[[" was typed.
    var linking by remember { mutableStateOf<Pair<MdController, Boolean>?>(null) }
    val followLink: (String) -> Unit = { url ->
        if (url.startsWith("note:")) vm.openLinked(url.removePrefix("note:")) else pendingLink = url
    }

    // Search inside the entry; opened from a search in the list, it starts with that query.
    var finding by remember { mutableStateOf(Md.findTerms(ui.query).isNotEmpty()) }
    var findText by remember { mutableStateOf(ui.query.split(' ').filterNot { it.startsWith("#") }.joinToString(" ").trim()) }
    val find = if (finding) Md.findTerms(findText) else emptyList()
    val findHits = remember { FindHits() }
    var readTop by remember { mutableStateOf(0f) }
    val jumpMargin = with(LocalDensity.current) { 120.dp.toPx() }
    val scope = rememberCoroutineScope()
    /** Puts a match in view: in the editor the cursor selects it, in read mode the page scrolls. */
    fun jump(hit: FindHits.Hit) {
        val field = hit.field
        if (field != null) {
            field.requestFocus()
            field.setSelection(hit.start.coerceAtMost(field.length()), hit.end.coerceAtMost(field.length()))
        } else {
            scope.launch { scroll.animateScrollTo((scroll.value + hit.y - readTop - jumpMargin).toInt().coerceAtLeast(0)) }
        }
    }
    BackHandler(enabled = finding && selected == null) { finding = false }

    CompositionLocalProvider(LocalFindHits provides findHits) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = background,
            topBar = {
                TopAppBar(
                    title = {
                        val folder = folders.firstOrNull { it.folderId == draft.folderId }
                        if (isNote && folder != null) {
                            Text(folder.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                    navigationIcon = {
                        IconButton(onClick = vm::closeEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    },
                    actions = {
                        IconButton(onClick = { finding = !finding }) { Icon(Icons.Filled.Search, stringResource(R.string.find_in_entry)) }
                        IconButton(onClick = { editing = !editing; selected = null }) {
                            if (editing) Icon(EyeIcon, stringResource(R.string.md_read)) else Icon(Icons.Filled.Edit, stringResource(R.string.md_edit))
                        }
                        if (draft.kind == EntryKind.TODO) {
                            Checkbox(checked = draft.done, onCheckedChange = { vm.toggleDraftDone() })
                        }
                        if (isNote) {
                            IconButton(onClick = vm::toggleDraftPinned) {
                                Icon(
                                    PinIcon, stringResource(R.string.pin),
                                    tint = if (draft.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { dialog = "color" }) {
                                Icon(PaletteIcon, stringResource(R.string.color), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { dialog = "folder" }) { Icon(FolderIcon, stringResource(R.string.move_to_folder)) }
                        }
                        if (draft.entryId != null) {
                            IconButton(onClick = vm::deleteEntry) { Icon(Icons.Filled.Delete, stringResource(R.string.to_trash)) }
                        }
                    },
                )
            },
        ) { padding ->
            val meta = when {
                draft.kind == EntryKind.DIARY && draft.diaryDate != null ->
                    LocalDate.ofEpochDay(draft.diaryDate.toLong())
                        .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())).capitalized()
                draft.updatedMs != null -> stringResource(R.string.edited_at, shortWhen(draft.updatedMs))
                else -> null
            }

            @Composable
            fun header() {
                if (finding) {
                    FindBar(findText, { findText = it }, findHits.count, findHits::hits, ::jump, onClose = { finding = false })
                    Spacer(Modifier.height(8.dp))
                }
                if (draft.conflictBody != null) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.conflict), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = vm::appendConflict) { Text(stringResource(R.string.conflict_append)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (meta != null) {
                    val isDiary = draft.kind == EntryKind.DIARY
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = if (isDiary) Modifier.clip(RoundedCornerShape(8.dp)).clickable { dialog = "date" }.padding(vertical = 4.dp) else Modifier,
                    )
                }
            }

            @Composable
            fun attachments() {
                if (ui.attaching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                EntryTags(draft.tags, editable = editing, onAdd = { dialog = "tag" }, onRemove = vm::removeDraftTag)
                AttachmentStrip(
                    loose, ui.playingId, vm::thumbnail, editable = editing, drag = drag,
                    onOpenImage = { viewing = it.attachmentId },
                    onToggleVoice = vm::toggleVoice,
                    onRemove = { removing = it },
                )
                if (editing && loose.any { it.isImage } && drag.item == null) {
                    Text(
                        stringResource(R.string.strip_drag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            if (editing) {
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .onGloballyPositioned { drag.viewport = it.boundsInRoot() }
                            .verticalScroll(scroll)
                            .padding(horizontal = 20.dp),
                    ) {
                        header()
                        SecureTextField(
                            draft.title, vm::editTitle,
                            stringResource(if (draft.kind == EntryKind.TODO) R.string.task_hint else R.string.title),
                            Modifier.fillMaxWidth(), textSizeSp = 26f, plain = true,
                            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD),
                        )
                        attachments()
                        DocumentEditor(
                            doc, draft.attachments,
                            hint = stringResource(if (draft.kind == EntryKind.TODO) R.string.task_body_hint else R.string.body),
                            sideHint = stringResource(R.string.side_hint),
                            hideMarkers = ui.appSettings.hideMarkdown,
                            selected = selected,
                            drag = drag,
                            focus = focus,
                            controller = ::controllerFor,
                            loadImage = vm::embedImage,
                            onText = { key, text -> commit(Doc.setText(doc, key, text)) },
                            onSide = { key, text ->
                                val (next, moved) = Doc.setSide(doc, key, text)
                                commit(next)
                                if (moved != null) focus = FocusRequest(moved, 0)
                            },
                            onFocused = { c ->
                                active = c
                                selected = null
                            },
                            onSelect = ::select,
                            onFocusHandled = { focus = null },
                            find = find,
                            onDoubleBracket = { c -> linking = c to true },
                        )
                        // Room below the text; a tap there continues the last paragraph.
                        Box(
                            Modifier.fillMaxWidth().height(160.dp).clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) {
                                (doc.lastOrNull() as? DocText)?.let { focus = FocusRequest(it.key, it.text.length) }
                            },
                        )
                    }
                    FormatBar(
                        active, words, onAttach = { dialog = "attach" },
                        canUndo = undoLog.isNotEmpty(), canRedo = redoLog.isNotEmpty(),
                        onUndo = ::undo, onRedo = ::redo,
                        onNoteLink = { active?.let { linking = it to false } },
                        tools = selectedPhoto?.let { p ->
                            {
                                PhotoTools(
                                    p,
                                    onChange = { change -> commitLayout(Doc.update(doc, p.key, change)) },
                                    onOpen = { viewing = p.id },
                                    onToStrip = {
                                        commitLayout(Doc.remove(doc, p.key))
                                        selected = null
                                    },
                                    onDelete = { removing = draft.attachments.firstOrNull { it.attachmentId == p.id } },
                                    onDone = { selected = null },
                                )
                            }
                        },
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(padding)
                        .onGloballyPositioned { readTop = it.positionInWindow().y }
                        .verticalScroll(scroll)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { editing = true }
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    header()
                    if (draft.title.isNotBlank()) {
                        FindText(
                            AnnotatedString(draft.title).dimmed(find, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)), find,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                    }
                    attachments()
                    if (doc.all { it is DocText && it.text.isBlank() }) {
                        Text(stringResource(R.string.md_empty), color = MaterialTheme.colorScheme.outline)
                    } else {
                        DocumentView(
                            doc, draft.attachments, vm::embedImage,
                            onOpen = { viewing = it },
                            onToggleTask = { key, line ->
                                commit(
                                    doc.map { b ->
                                        when {
                                            b is DocText && b.key == key -> b.copy(text = Md.toggleTask(b.text, line))
                                            b is DocPhoto && -b.key == key -> b.copy(side = Md.toggleTask(b.side, line))
                                            else -> b
                                        }
                                    },
                                )
                            },
                            onLink = followLink,
                            find = find,
                        )
                    }
                    Backlinks(draft.entryId, ui, onOpen = vm::openLinked)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        pluralStringResource(R.plurals.word_count, words, words),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
        DragOverlay(drag, vm::thumbnail)
    }
    }
    when (dialog) {
        "color" -> ColorDialog(draft.color, onPick = { vm.setDraftColor(it); dialog = null }, onDismiss = { dialog = null })
        "folder" -> FolderPickerDialog(folders, draft.folderId, onPick = { vm.setDraftFolder(it); dialog = null }, onDismiss = { dialog = null })
        "tag" -> TagDialog(ui.tags, onAdd = { vm.addDraftTag(it); dialog = null }, onDismiss = { dialog = null })
        "date" -> DiaryDateDialog(draft.diaryDate, onPick = { vm.setDraftDiaryDate(it); dialog = null }, onDismiss = { dialog = null })
        "attach" -> AttachDialog(
            onCamera = {
                dialog = null
                if (granted(context, Manifest.permission.CAMERA)) camera = true else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            onGallery = {
                dialog = null
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onVoice = {
                dialog = null
                if (granted(context, Manifest.permission.RECORD_AUDIO)) recording = true else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { dialog = null },
        )
    }
    if (camera) {
        CameraCapture(onCaptured = { bytes, rotation -> camera = false; vm.attachPhoto(bytes, rotation) }, onClose = { camera = false })
    }
    if (recording) {
        VoiceRecordDialog(onDone = { audio, ms -> recording = false; vm.attachVoice(audio, ms) }, onDismiss = { recording = false })
    }
    ui.cropping?.let { CropDialog(it, onDone = vm::finishCrop, onCancel = vm::cancelCrop) }
    linking?.let { (field, afterBrackets) ->
        NoteLinkDialog(
            ui.allEntries.filter { it.trashedMs == null && it.entryId != draft.entryId },
            onPick = { e ->
                linking = null
                field.noteLink(e.displayTitle(context), e.entryId, afterBrackets)
            },
            onDismiss = { linking = null },
        )
    }
    viewing?.let { id -> ImageViewer(id, vm::fullImage, onClose = { viewing = null }) }
    removing?.let { a ->
        RemoveAttachmentDialog(onConfirm = { removing = null; selected = null; vm.removeAttachment(a) }, onDismiss = { removing = null })
    }
    pendingLink?.let { url ->
        val safe = url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(R.string.open_link)) },
            text = { Text(if (safe) url else stringResource(R.string.link_blocked, url)) },
            confirmButton = {
                if (safe) {
                    TextButton(onClick = {
                        pendingLink = null
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }) { Text(stringResource(R.string.open)) }
                }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal fun EntrySummary.displayTitle(context: Context) =
    title.ifBlank { preview.lineSequence().firstOrNull().orEmpty() }.ifBlank { context.getString(R.string.untitled) }

/** Entries that link to [entryId], at the bottom of read mode. */
@Composable
private fun Backlinks(entryId: String?, ui: UiState, onOpen: (String) -> Unit) {
    if (entryId == null) return
    val from = ui.links.filter { it.toId == entryId }.map { it.fromId }.toSet()
    val entries = ui.allEntries.filter { it.entryId in from }
    if (entries.isEmpty()) return
    val context = LocalContext.current
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.backlinks), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    for (e in entries) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(e.entryId) }.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NoteLinkIcon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(e.displayTitle(context), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Search the vault's entries and pick one to link to. */
@Composable
private fun NoteLinkDialog(entries: List<EntrySummary>, onPick: (EntrySummary) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val terms = Md.findTerms(query)
    val shown = entries.filter { e -> terms.isEmpty() || Md.countMatches(e.displayTitle(context) + "\n" + e.preview, terms) > 0 }.take(50)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NoteLinkIcon, null) },
        title = { Text(stringResource(R.string.note_link_pick)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SecureTextField(query, { query = it }, stringResource(R.string.note_link_search), Modifier.fillMaxWidth(), plain = true)
                    }
                }
                LazyColumn(Modifier.height(320.dp)) {
                    items(shown, key = { it.entryId }) { e ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(e) }.padding(vertical = 10.dp, horizontal = 8.dp),
                        ) {
                            Text(e.displayTitle(context), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(
                                    when (e.kind) {
                                        EntryKind.NOTE -> R.string.section_notes
                                        EntryKind.DIARY -> R.string.section_diary
                                        EntryKind.TODO -> R.string.section_todo
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Picks the day a diary entry belongs to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryDateDialog(current: Int?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = (current?.toLong() ?: LocalDate.now().toEpochDay()) * 86_400_000L)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / 86_400_000L)) } ?: onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state, title = { Text(stringResource(R.string.change_date), Modifier.padding(start = 24.dp, top = 16.dp)) }) }
}

/** One step of the entry's undo history. */
private sealed interface EditStep {
    /** Typing in one field; that field keeps the text itself. */
    data class Typing(val field: MdController) : EditStep

    /** A change to the layout (a photo moved, resized, taken out); [doc] is the state before. */
    data class Layout(val doc: List<DocBlock>) : EditStep
}

/**
 * Search field for the open entry. The number of matches is a button: with one match it goes
 * there, with more it lists them to pick from.
 */
@Composable
private fun FindBar(
    text: String,
    onText: (String) -> Unit,
    matches: Int,
    hits: () -> List<FindHits.Hit>,
    onJump: (FindHits.Hit) -> Unit,
    onClose: () -> Unit,
) {
    var list by remember { mutableStateOf<List<FindHits.Hit>>(emptyList()) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.height(48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            SecureTextField(text, onText, stringResource(R.string.find_in_entry), Modifier.weight(1f), textSizeSp = 16f, plain = true)
            if (text.isNotBlank()) {
                Box {
                    TextButton(onClick = {
                        val all = hits()
                        when (all.size) {
                            0 -> Unit
                            1 -> onJump(all[0])
                            else -> list = all
                        }
                    }, enabled = matches > 0) {
                        Text(pluralStringResource(R.plurals.matches, matches, matches), style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = list.isNotEmpty(), onDismissRequest = { list = emptyList() }) {
                        for (h in list.take(60)) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        buildAnnotatedString {
                                            append(h.before)
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(h.match) }
                                            append(h.after)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 300.dp),
                                    )
                                },
                                onClick = {
                                    list = emptyList()
                                    onJump(h)
                                },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
        }
    }
}

/** The bar's tools while a photo in the text is selected. */
@Composable
private fun RowScope.PhotoTools(
    p: DocPhoto,
    onChange: ((DocPhoto) -> DocPhoto) -> Unit,
    onOpen: () -> Unit,
    onToStrip: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    ToggleTool(AlignLeftIcon, R.string.align_left, p.align == MdAlign.START) { onChange { it.copy(align = MdAlign.START) } }
    ToggleTool(AlignCenterIcon, R.string.align_center, p.align == MdAlign.CENTER) { onChange { it.copy(align = MdAlign.CENTER) } }
    ToggleTool(AlignRightIcon, R.string.align_right, p.align == MdAlign.END) { onChange { it.copy(align = MdAlign.END) } }
    VerticalDivider(Modifier.height(24.dp))
    val sizes = listOf(MdSize.S to R.string.size_small, MdSize.M to R.string.size_medium, MdSize.L to R.string.size_large)
    for ((size, label) in sizes) {
        SizeTool(size.name, stringResource(label), p.size == size) { onChange { it.copy(size = size) } }
    }
    VerticalDivider(Modifier.height(24.dp))
    IconTool(EyeIcon, R.string.open, onOpen)
    IconTool(UploadIcon, R.string.embed_remove, onToStrip)
    IconTool(Icons.Filled.Delete, R.string.delete, onDelete)
    IconTool(Icons.Filled.Check, R.string.done, onDone)
}
private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun AttachDialog(onCamera: () -> Unit, onGallery: () -> Unit, onVoice: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AttachIcon, null) },
        title = { Text(stringResource(R.string.attach)) },
        text = {
            Column {
                AttachRow(CameraIcon, R.string.attach_camera, onCamera)
                AttachRow(ImageIcon, R.string.attach_gallery, onGallery)
                AttachRow(MicIcon, R.string.attach_voice, onVoice)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.attach_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AttachRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}