package com.jelly.habittrack

import android.app.Application
import android.content.Context
import androidx.work.*
import com.jelly.habittrack.data.*
import com.jelly.habittrack.reminder.*
import com.jelly.habittrack.widget.refreshWidgets
import kotlinx.coroutines.*
import com.jelly.habittrack.update.AppUpdater

open class HabitApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> android.util.Log.e("HabitTrack", "Background reconciliation failed", error) })
    val repository by lazy { Repository(this) }
    val preferences by lazy { Preferences(this) }
    val updater by lazy { AppUpdater(this) }
    override fun onCreate() {
        super.onCreate()
        // Upgrade cleanup: state changes and actual reminders already trigger updates.
        // Do not poll every 30 minutes when there is no user-requested work to do.
        WorkManager.getInstance(this).cancelUniqueWork("reconcile")
        scope.launch { reconcile() }
    }
    suspend fun reconcile(alert: Boolean = false) {
        // A reminder permission/scheduling failure must not block desktop updates.
        try { refreshWidgets(this) }
        finally { ReminderEngine(this).reconcile(alert) }
    }
}
val Context.habitApp get() = applicationContext as HabitApplication

// Retained for a legacy task that may already be starting during an upgrade.
class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { applicationContext.habitApp.reconcile(); Result.success() } catch (_: Exception) { Result.retry() }
}
