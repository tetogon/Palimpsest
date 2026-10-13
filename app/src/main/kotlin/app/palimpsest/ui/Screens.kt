package app.palimpsest.ui

import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.palimpsest.AppSettings
import app.palimpsest.R

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Every screen paints the themed background itself: no grey window behind black text.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val screen = ui.screen) {
            Screen.Unlock -> UnlockScreen(ui.busy, ui.error, vm::unlock, vm::showCreate, vm::openAppSettings)
            Screen.CreateVault -> {
                BackHandler(onBack = vm::showUnlock)
                CreateVaultScreen(ui.busy, ui.error, vm::createVault, vm::showUnlock)
            }
            Screen.EntryList -> HomeScreen(ui, vm)
            Screen.Trash -> TrashScreen(ui, vm)
            is Screen.Editor -> {
                BackHandler(onBack = vm::closeEditor)
                // A fresh editor for every opening, also when a link leads from entry to entry.
                key(screen.visit) { EditorScreen(screen.draft, ui, vm) }
            }
            Screen.Graph -> {
                BackHandler(onBack = vm::closeGraph)
                GraphScreen(ui, vm)
            }
            Screen.Settings -> {
                BackHandler(onBack = vm::closeSettings)
                SettingsScreen(ui, withVault = true, vm, onBack = vm::closeSettings)
            }
            is Screen.AppPrefs -> {
                BackHandler(onBack = vm::closeAppSettings)
                SettingsScreen(ui, withVault = false, vm, onBack = vm::closeAppSettings)
            }
        }
    }
}

// ---- Building blocks ----------------------------------------------------------------------

private val FieldShape = RoundedCornerShape(16.dp)

/** Rounded tonal box around a [SecureTextField]: the look of the search bar, for forms. */
@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    Surface(shape = FieldShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), contentAlignment = Alignment.CenterStart) {
            content()
        }
    }
}

@Composable
private fun PasswordBox(value: String, onChange: (String) -> Unit, @StringRes hint: Int, onDone: (() -> Unit)? = null) {
    FieldBox {
        SecureTextField(value, onChange, stringResource(hint), Modifier.fillMaxWidth(), password = true, plain = true, onDone = onDone)
    }
}

@Composable
private fun BigButton(@StringRes text: Int, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = FieldShape,
        colors = if (danger) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(text), style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun ErrorText(@StringRes error: Int?) {
    if (error != null) {
        Text(
            stringResource(error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** Centered, width-limited column for the unlock and create screens. */
@Composable
private fun FormPage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Emblem() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(84.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

// ---- Unlock and create ---------------------------------------------------------------------

@Composable
private fun UnlockScreen(
    busy: Boolean,
    @StringRes error: Int?,
    onUnlock: (String) -> Unit,
    onCreate: () -> Unit,
    onAppSettings: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val submit: () -> Unit = {
        if (!busy && password.isNotEmpty()) {
            onUnlock(password)
            password = ""
        }
    }
    Box(Modifier.fillMaxSize()) {
        FormPage {
            Emblem()
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif))
            Text(
                stringResource(R.string.unlock_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            PasswordBox(password, { password = it }, R.string.password, onDone = submit)
            ErrorText(error)
            Spacer(Modifier.height(4.dp))
            if (busy) {
                Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                BigButton(R.string.unlock, submit, enabled = password.isNotEmpty())
            }
            TextButton(onClick = onCreate, enabled = !busy) {
                Text(stringResource(R.string.new_vault), style = MaterialTheme.typography.titleSmall)
            }
        }
        IconButton(
            onClick = onAppSettings,
            enabled = !busy,
            modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp),
        ) { Icon(Icons.Filled.Settings, stringResource(R.string.app_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun CreateVaultScreen(
    busy: Boolean,
    @StringRes error: Int?,
    onCreate: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    // Plain remember, not rememberSaveable: passwords must never land in a saved-state Bundle.
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        FormPage {
            Emblem()
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.create_vault_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.create_vault_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            PasswordBox(password, { password = it }, R.string.password)
            Column(Modifier.fillMaxWidth()) { PasswordStrengthMeter(password) }
            PasswordBox(repeat, { repeat = it }, R.string.repeat_password)
            if (password.isNotEmpty()) {
                Text(
                    stringResource(R.string.strength_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ErrorText(error)
            Spacer(Modifier.height(4.dp))
            if (busy) {
                Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                BigButton(R.string.create, { onCreate(password, repeat) }, enabled = password.isNotEmpty())
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.systemBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        }
    }
}

// ---- Settings: app first, this vault below -------------------------------------------------

private enum class SettingsDialog { IDLE, BACKGROUND, CHANGE_PASSWORD, WIPE, RECLAIM, DELETE_VAULT, RESTORE, IMPORT, EXPORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(ui: UiState, withVault: Boolean, vm: AppViewModel, onBack: () -> Unit) {
    val values = ui.appSettings
    val change = vm::updateAppSettings
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val message = ui.info ?: ui.error
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(context.getString(message))
            vm.clearMessages()
        }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Files leave and enter through the system file picker; the app needs no storage permission.
    val stamp = remember { java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }
    val binary = "application/octet-stream"
    val backupTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(binary)) { uri -> uri?.let(vm::backup) }
    val restoreFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::restore) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri
            dialog = SettingsDialog.IMPORT
        }
    }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(binary)) { uri -> vm.finishExport(uri) }
    LaunchedEffect(ui.exportReady) { if (ui.exportReady) exportTo.launch("notes-$stamp.bin") }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            GroupTitle(R.string.theme_title)
            Group {
                Box(Modifier.padding(16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf(
                            AppSettings.ThemeMode.SYSTEM to R.string.theme_system,
                            AppSettings.ThemeMode.LIGHT to R.string.theme_light,
                            AppSettings.ThemeMode.DARK to R.string.theme_dark,
                        )
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = values.theme == mode,
                                onClick = { change(values.copy(theme = mode)) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(stringResource(label), maxLines = 1) }
                        }
                    }
                }
            }

            GroupTitle(R.string.lock_title)
            Group {
                SwitchRow(Icons.Filled.Lock, R.string.lock_on_screen_off, null, values.lockOnScreenOff) {
                    change(values.copy(lockOnScreenOff = it))
                }
                ValueRow(null, R.string.lock_idle, idleLabel(values.idleLockMinutes)) { dialog = SettingsDialog.IDLE }
                ValueRow(null, R.string.lock_background, backgroundLabel(values.backgroundLockSeconds)) {
                    dialog = SettingsDialog.BACKGROUND
                }
            }

            GroupTitle(R.string.editor_title)
            Group {
                SwitchRow(CodeIcon, R.string.hide_markdown, R.string.hide_markdown_hint, values.hideMarkdown) {
                    change(values.copy(hideMarkdown = it))
                }
                SwitchRow(SwipeIcon, R.string.swipe_sections, R.string.swipe_sections_hint, values.swipeSections) {
                    change(values.copy(swipeSections = it))
                }
            }

            GroupTitle(R.string.privacy_title)
            Group {
                SwitchRow(ScreenshotIcon, R.string.allow_screenshots, R.string.allow_screenshots_hint, values.allowScreenshots) {
                    change(values.copy(allowScreenshots = it))
                }
            }

            GroupTitle(R.string.clipboard_title)
            Group {
                SwitchRow(Icons.Filled.Edit, R.string.clipboard_clear, R.string.clipboard_clear_hint, values.clearClipboard) {
                    change(values.copy(clearClipboard = it))
                }
                SwitchRow(null, R.string.clipboard_block, R.string.clipboard_hint, values.blockCopy) {
                    change(values.copy(blockCopy = it))
                }
            }

            GroupTitle(R.string.transfer_title)
            Group {
                ActionRow(UploadIcon, R.string.backup_all, R.string.backup_all_hint) { backupTo.launch("backup-$stamp.bin") }
                ActionRow(DownloadIcon, R.string.import_vault, R.string.import_vault_hint) { importFrom.launch(arrayOf("*/*")) }
                ActionRow(RestoreIcon, R.string.restore_all, R.string.restore_all_hint) { dialog = SettingsDialog.RESTORE }
            }

            if (withVault) {
                Spacer(Modifier.height(12.dp))
                GroupTitle(R.string.vault_settings)
                Group {
                    ActionRow(Icons.Filled.Person, R.string.change_password, null) { dialog = SettingsDialog.CHANGE_PASSWORD }
                    ActionRow(UploadIcon, R.string.export_vault, R.string.export_vault_hint) { dialog = SettingsDialog.EXPORT }
                    SwitchRow(Icons.Filled.Refresh, R.string.purge_manual, R.string.purge_hint_short, ui.purgeManual, vm::setPurgeManual)
                    if (ui.purgeManual) {
                        ValueRow(null, R.string.purge_now, stringResource(R.string.kb, ui.pendingPurgeBlocks * 4)) {
                            if (ui.pendingPurgeBlocks > 0) vm.purgeNow()
                        }
                    }
                    ActionRow(
                        Icons.Filled.Warning, R.string.wipe_title,
                        if (ui.wipeArmed) R.string.wipe_is_armed else R.string.wipe_row_hint,
                    ) { dialog = SettingsDialog.WIPE }
                    ValueRow(Icons.Filled.Build, R.string.reclaim_title, stringResource(R.string.kb, ui.reclaimableBlocks * 4)) {
                        if (ui.reclaimableBlocks > 0) dialog = SettingsDialog.RECLAIM
                    }
                    ActionRow(Icons.Filled.Delete, R.string.delete_vault, R.string.delete_vault_hint, danger = true) {
                        dialog = SettingsDialog.DELETE_VAULT
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    val close = { dialog = null }
    when (dialog) {
        SettingsDialog.IDLE -> ChoiceDialog(
            R.string.lock_idle, AppSettings.IDLE_OPTIONS.map { it to idleLabel(it) }, values.idleLockMinutes,
            onPick = { change(values.copy(idleLockMinutes = it)); close() }, onDismiss = close,
        )
        SettingsDialog.BACKGROUND -> ChoiceDialog(
            R.string.lock_background, AppSettings.BACKGROUND_OPTIONS.map { it to backgroundLabel(it) }, values.backgroundLockSeconds,
            onPick = { change(values.copy(backgroundLockSeconds = it)); close() }, onDismiss = close,
        )
        SettingsDialog.CHANGE_PASSWORD -> ChangePasswordDialog(onSubmit = { o, n, r -> vm.changePassword(o, n, r); close() }, onDismiss = close)
        SettingsDialog.WIPE -> WipeDialog(ui.wipeArmed, onArm = { vm.armWipe(it); close() }, onDisarm = { vm.disarmWipe(); close() }, onDismiss = close)
        SettingsDialog.RECLAIM -> ConfirmDialog(R.string.reclaim_confirm, R.string.reclaim_button, danger = false, onConfirm = { vm.reclaimSpace(); close() }, onDismiss = close)
        SettingsDialog.DELETE_VAULT -> ConfirmDialog(R.string.delete_vault_confirm, R.string.delete, danger = true, onConfirm = { vm.deleteVault(); close() }, onDismiss = close)
        SettingsDialog.RESTORE -> ConfirmDialog(R.string.restore_confirm, R.string.restore_button, danger = true, onConfirm = {
            close()
            restoreFrom.launch(arrayOf("*/*"))
        }, onDismiss = close)
        SettingsDialog.IMPORT -> ImportDialog(onSubmit = { pw ->
            importUri?.let { vm.importVault(it, pw) }
            importUri = null
            close()
        }, onDismiss = { importUri = null; close() })
        SettingsDialog.EXPORT -> ExportDialog(onSubmit = { p, r -> vm.prepareExport(p, r); close() }, onDismiss = close)
        null -> Unit
    }
}

@Composable
private fun idleLabel(minutes: Int) = if (minutes == 0) stringResource(R.string.never) else stringResource(R.string.minutes, minutes)

@Composable
private fun backgroundLabel(seconds: Int) =
    if (seconds == 0) stringResource(R.string.immediately) else stringResource(R.string.minutes, seconds / 60)

@Composable
private fun GroupTitle(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun RowIcon(icon: ImageVector?, danger: Boolean = false) {
    if (icon != null) {
        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun SwitchRow(icon: ImageVector?, @StringRes title: Int, @StringRes hint: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = hint?.let { { Text(stringResource(it)) } },
        leadingContent = { RowIcon(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = rowColors,
        modifier = Modifier.clickableRow { onChange(!checked) },
    )
}

@Composable
private fun ValueRow(icon: ImageVector?, @StringRes title: Int, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        leadingContent = { RowIcon(icon) },
        trailingContent = { Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) },
        colors = rowColors,
        modifier = Modifier.clickableRow(onClick),
    )
}

@Composable
private fun ActionRow(icon: ImageVector?, @StringRes title: Int, @StringRes hint: Int?, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(stringResource(title), color = color) },
        supportingContent = hint?.let { { Text(stringResource(it)) } },
        leadingContent = { RowIcon(icon, danger) },
        colors = rowColors,
        modifier = Modifier.clickableRow(onClick),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit) = clickable(onClick = onClick)

// ---- Dialogs ---------------------------------------------------------------------------------

@Composable
private fun <T> ChoiceDialog(@StringRes title: Int, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                for ((value, label) in options) {
                    Row(
                        Modifier.fillMaxWidth().clickableRow { onPick(value) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == current, onClick = { onPick(value) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(@StringRes text: Int, @StringRes confirm: Int, danger: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(text), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(confirm), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChangePasswordDialog(onSubmit: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordBox(old, { old = it }, R.string.current_password)
                PasswordBox(new, { new = it }, R.string.new_password)
                PasswordStrengthMeter(new)
                PasswordBox(repeat, { repeat = it }, R.string.repeat_password)
                Text(stringResource(R.string.password_change_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(old, new, repeat) }, enabled = old.isNotEmpty() && new.isNotEmpty()) {
                Text(stringResource(R.string.change_password))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ImportDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(DownloadIcon, null) },
        title = { Text(stringResource(R.string.import_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordBox(password, { password = it }, R.string.password)
                Text(stringResource(R.string.import_password_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.import_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ExportDialog(onSubmit: (String, String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(UploadIcon, null) },
        title = { Text(stringResource(R.string.export_vault)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordBox(password, { password = it }, R.string.password)
                PasswordStrengthMeter(password)
                PasswordBox(repeat, { repeat = it }, R.string.repeat_password)
                Text(stringResource(R.string.export_password_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password, repeat) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.export_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun WipeDialog(armed: Boolean, onArm: (String) -> Unit, onDisarm: () -> Unit, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null) },
        title = { Text(stringResource(R.string.wipe_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.wipe_hint), style = MaterialTheme.typography.bodyMedium)
                if (armed) {
                    Text(stringResource(R.string.wipe_is_armed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                } else {
                    PasswordBox(target, { target = it }, R.string.wipe_target_password)
                }
            }
        },
        confirmButton = {
            if (armed) {
                TextButton(onClick = onDisarm) { Text(stringResource(R.string.wipe_disarm)) }
            } else {
                TextButton(onClick = { onArm(target) }, enabled = target.isNotEmpty()) {
                    Text(stringResource(R.string.wipe_arm), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
