package com.jelly.habittrack

import android.app.Application
import android.content.Context
import androidx.work.*
import com.jelly.habittrack.data.*
import com.jelly.habittrack.reminder.*
import com.jelly.habittrack.widget.refreshWidgets
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import com.jelly.habittrack.update.AppUpdater

open class HabitApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> android.util.Log.e("HabitTrack", "Background reconciliation failed", error) })
    val repository by lazy { Repository(this) }
    val preferences by lazy { Preferences(this) }
    val updater by lazy { AppUpdater(this) }
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("reconcile", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReconcileWorker>(30, TimeUnit.MINUTES).build())
        scope.launch { reconcile() }
    }
    suspend fun reconcile(alert: Boolean = false) {
        // A reminder permission/scheduling failure must not block desktop updates.
        try { refreshWidgets(this) }
        finally { ReminderEngine(this).reconcile(alert) }
    }
}
val Context.habitApp get() = applicationContext as HabitApplication

class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { applicationContext.habitApp.reconcile(); Result.success() } catch (_: Exception) { Result.retry() }
}
