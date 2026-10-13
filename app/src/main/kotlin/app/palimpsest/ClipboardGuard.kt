package app.palimpsest

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Copies with the "sensitive" flag (hidden from the Android 13+ copy preview and, by
 * well-behaved keyboards, from clipboard history) and clears the clipboard later.
 *
 * Android does not let a background app read the clipboard, so a timed clear cannot check
 * whether the clip is still ours: if something else was copied within the delay, it is
 * cleared too. The app settings say so. Clearing on lock happens in the foreground and
 * does check.
 */
class ClipboardGuard(private val context: Context, private val settings: AppSettings) {
    private val main = Handler(Looper.getMainLooper())
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private val timedClear = Runnable { clear(onlyIfOurs = isForeground()) }

    fun copy(text: CharSequence) = main.post {
        val clip = ClipData.newPlainText(LABEL, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        main.removeCallbacks(timedClear)
        if (settings.values.value.clearClipboard) main.postDelayed(timedClear, CLEAR_AFTER_MS)
    }

    /** On lock: wipe our clip if it is still there; never touch someone else's. */
    fun clearOnLock() = main.post {
        main.removeCallbacks(timedClear)
        if (isForeground()) clear(onlyIfOurs = true)
    }

    private fun clear(onlyIfOurs: Boolean) {
        val cm = clipboard
        if (onlyIfOurs && cm.primaryClipDescription?.label != LABEL) return
        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    private fun isForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    companion object {
        const val CLEAR_AFTER_MS = 60_000L
        private const val LABEL = "palimpsest"

        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); the same key is honoured by keyboards before 33. */
        private val EXTRA_IS_SENSITIVE =
            if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
    }
}
