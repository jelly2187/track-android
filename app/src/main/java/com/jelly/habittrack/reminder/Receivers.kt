package com.jelly.habittrack.reminder

import android.content.*
import com.jelly.habittrack.habitApp
import kotlinx.coroutines.launch
import java.time.LocalDate

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.habitApp.scope.launch { try { context.habitApp.reconcile(alert = true) } finally { pending.finish() } }
    }
}
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.habitApp.scope.launch { try { context.habitApp.reconcile() } finally { pending.finish() } }
    }
}
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.habitApp.scope.launch {
            try {
                val id = intent.getStringExtra("habit") ?: return@launch
                val date = LocalDate.parse(intent.getStringExtra("date"))
                val mode = intent.getStringExtra("mode") ?: return@launch
                val token = intent.getStringExtra("token") ?: return@launch
                val habit = context.habitApp.repository.dao.habit(id) ?: return@launch
                context.habitApp.repository.act(id, date, mode, habit.step, token, external = true)
                context.habitApp.reconcile()
            } catch (_: IllegalArgumentException) { /* Ignore malformed or expired actions. */ }
            finally { pending.finish() }
        }
    }
}
