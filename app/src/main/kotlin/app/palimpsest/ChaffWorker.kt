package app.palimpsest

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Daily chaff, so the file grows even on days no vault is opened (spec §9). */
class ChaffWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        (applicationContext as PalimpsestApp).repository.container.addBackgroundChaff()
        return Result.success()
    }
}
