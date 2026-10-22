package com.binnder.app

import android.app.Application
import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class BinnderApplication : Application() {
    val storage by lazy { StorageRepository(this) }
    val updates by lazy { UpdateRepository(this) }
    override fun onCreate() {
        super.onCreate()
        UndoClock.boot = android.provider.Settings.Global.getInt(contentResolver,android.provider.Settings.Global.BOOT_COUNT,0)
        val work = WorkManager.getInstance(this)
        work.enqueueUniquePeriodicWork("trash-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(1,TimeUnit.HOURS).build())
        work.enqueueUniquePeriodicWork("github-updates", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(15,TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

object UndoClock {
    var boot: Int = 0
    fun elapsed() = android.os.SystemClock.elapsedRealtime()
}

class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as BinnderApplication).storage.process()
        Result.success()
    } catch (_: Exception) { Result.retry() }
    companion object {
        fun schedule(context: Context, id: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("file-$id",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CleanupWorker>().setInitialDelay(ReviewPolicy.UNDO_MS,TimeUnit.MILLISECONDS).build())
        }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as BinnderApplication
        if(app.updates.automatic && ReviewPolicy.validRepository(app.updates.repository)) app.updates.check(force=false)
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
