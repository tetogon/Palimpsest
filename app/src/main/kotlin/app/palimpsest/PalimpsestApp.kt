package app.palimpsest

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class PalimpsestApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repository: VaultRepository
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var clipboard: ClipboardGuard
        private set

    private var backgroundLock: Job? = null

    override fun onCreate() {
        super.onCreate()
        repository = VaultRepository(File(filesDir, "data.bin"), File(cacheDir, "transfer"))
        settings = AppSettings(this)
        clipboard = ClipboardGuard(this, settings)
        repository.onLocked = { clipboard.clearOnLock() }
        // Create the container (and its initial chaff) on first launch, before any vault exists.
        scope.launch { repository.container }

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "chaff",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ChaffWorker>(1, TimeUnit.DAYS).build(),
        )

        lockWhenLeavingApp()
        lockWhenScreenTurnsOff()
        lockWhenIdle()
    }

    private fun lock() {
        scope.launch { repository.lock() }
    }

    private fun lockWhenLeavingApp() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val seconds = settings.values.value.backgroundLockSeconds
                backgroundLock = scope.launch {
                    delay(seconds * 1000L)
                    repository.lock()
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                backgroundLock?.cancel()
                backgroundLock = null
                repository.touch()
            }
        })
    }

    private fun lockWhenScreenTurnsOff() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (settings.values.value.lockOnScreenOff) lock()
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun lockWhenIdle() {
        scope.launch {
            while (true) {
                delay(5_000)
                val minutes = settings.values.value.idleLockMinutes
                val idle = SystemClock.elapsedRealtime() - repository.lastActivity
                if (minutes > 0 && repository.session.value != null && idle > minutes * 60_000L) repository.lock()
            }
        }
    }
}
