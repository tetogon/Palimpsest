package app.palimpsest

import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.palimpsest.ui.AppRoot
import app.palimpsest.ui.PalimpsestTheme

class MainActivity : ComponentActivity() {
    /** Any touch counts as activity for the idle lock; typing is reported by the editor. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        (application as PalimpsestApp).repository.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = (application as PalimpsestApp).settings
        // No screenshots or screen recording unless allowed in the settings; never a preview
        // in the recents switcher.
        applyScreenshots(settings.values.value.allowScreenshots)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContent {
            val values by settings.values.collectAsStateWithLifecycle()
            SideEffect { applyScreenshots(values.allowScreenshots) }
            PalimpsestTheme(values.theme) { AppRoot() }
        }
    }

    private fun applyScreenshots(allowed: Boolean) {
        if (allowed) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
