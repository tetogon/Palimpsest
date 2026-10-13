package app.palimpsest

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-wide settings, stored outside every vault. Allowed by spec §10 because they are the
 * same whatever vault is open and say nothing about how many vaults exist.
 */
class AppSettings(context: Context) {
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    /** Order of notes and to-dos; the diary is always by date. Pinned notes come first. */
    enum class SortOrder { UPDATED, CREATED, TITLE }

    data class Values(
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val sort: SortOrder = SortOrder.UPDATED,
        val lockOnScreenOff: Boolean = true,
        /** 0 = never. */
        val idleLockMinutes: Int = 5,
        /** 0 = immediately. */
        val backgroundLockSeconds: Int = 60,
        /** Clear the clipboard [ClipboardGuard.CLEAR_AFTER_MS] after copying from an entry. */
        val clearClipboard: Boolean = true,
        /** Disable copy and cut in entries altogether. */
        val blockCopy: Boolean = false,
        /** Hide Markdown markers (`**`, `#`, …) in the editor except on the line being edited. */
        val hideMarkdown: Boolean = true,
        /** Let the system take screenshots and record the screen (FLAG_SECURE off). */
        val allowScreenshots: Boolean = false,
        /** A horizontal swipe on the list switches between notes, diary and to-do. */
        val swipeSections: Boolean = true,
        /** The diary shows a month calendar instead of the timeline. */
        val diaryCalendar: Boolean = false,
    )

    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _values = MutableStateFlow(
        Values(
            theme = runCatching { ThemeMode.valueOf(prefs.getString(THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
            sort = runCatching { SortOrder.valueOf(prefs.getString(SORT, null) ?: "") }.getOrDefault(SortOrder.UPDATED),
            lockOnScreenOff = prefs.getBoolean(SCREEN_OFF, true),
            idleLockMinutes = prefs.getInt(IDLE, 5),
            backgroundLockSeconds = prefs.getInt(BACKGROUND, 60),
            clearClipboard = prefs.getBoolean(CLEAR_CLIPBOARD, true),
            blockCopy = prefs.getBoolean(BLOCK_COPY, false),
            hideMarkdown = prefs.getBoolean(HIDE_MARKDOWN, true),
            allowScreenshots = prefs.getBoolean(SCREENSHOTS, false),
            swipeSections = prefs.getBoolean(SWIPE, true),
            diaryCalendar = prefs.getBoolean(DIARY_CALENDAR, false),
        ),
    )
    val values: StateFlow<Values> = _values

    fun update(v: Values) {
        prefs.edit()
            .putString(THEME, v.theme.name)
            .putString(SORT, v.sort.name)
            .putBoolean(SCREEN_OFF, v.lockOnScreenOff)
            .putInt(IDLE, v.idleLockMinutes)
            .putInt(BACKGROUND, v.backgroundLockSeconds)
            .putBoolean(CLEAR_CLIPBOARD, v.clearClipboard)
            .putBoolean(BLOCK_COPY, v.blockCopy)
            .putBoolean(HIDE_MARKDOWN, v.hideMarkdown)
            .putBoolean(SCREENSHOTS, v.allowScreenshots)
            .putBoolean(SWIPE, v.swipeSections)
            .putBoolean(DIARY_CALENDAR, v.diaryCalendar)
            .apply()
        _values.value = v
    }

    companion object {
        val IDLE_OPTIONS = listOf(1, 5, 15, 0)
        val BACKGROUND_OPTIONS = listOf(0, 60, 300)
        private const val THEME = "theme"
        private const val SORT = "sort"
        private const val SCREEN_OFF = "lock_on_screen_off"
        private const val IDLE = "idle_lock_minutes"
        private const val BACKGROUND = "background_lock_seconds"
        private const val CLEAR_CLIPBOARD = "clear_clipboard"
        private const val BLOCK_COPY = "block_copy"
        private const val HIDE_MARKDOWN = "hide_markdown"
        private const val SCREENSHOTS = "allow_screenshots"
        private const val SWIPE = "swipe_sections"
        private const val DIARY_CALENDAR = "diary_calendar"
    }
}
