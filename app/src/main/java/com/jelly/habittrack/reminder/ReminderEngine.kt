package com.jelly.habittrack.reminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jelly.habittrack.*
import com.jelly.habittrack.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.util.UUID

data class ReminderPlan(val pending: List<Habit>, val next: ZonedDateTime)

object ReminderPlanner {
    fun calculate(snapshot: Snapshot, settings: ReminderSettings, now: ZonedDateTime): ReminderPlan {
        val today = now.toLocalDate()
        var next = today.plusDays(1).atStartOfDay(now.zone)
        val pending = mutableListOf<Habit>()
        snapshot.habits.filter { !it.archived && it.reminderEnabled && snapshot.due(it, today) && Rules.includes(it.reminderDays, today) }.forEach { habit ->
            if (snapshot.complete(habit, today) || snapshot.record(habit, today)?.closed == true) return@forEach
            val times = Rules.times(habit.reminderTimes).map { today.atTime(it).atZone(now.zone) }
            val first = times.firstOrNull() ?: return@forEach
            times.filter { it > now }.minOrNull()?.let { next = minOf(next, it) }
            if (now >= first) {
                pending += habit
                val minutes = Duration.between(first, now).toMinutes()
                val repeat = first.plusMinutes((minutes / settings.interval + 1) * settings.interval.toLong())
                next = minOf(next, repeat)
            }
        }
        return ReminderPlan(pending, next)
    }
}

class ReminderEngine(private val context: Context) {
    companion object { private val mutex = Mutex(); const val NOTIFICATION_ID = 100; const val CHANNEL = "habit_reminders" }
    private val alarm get() = context.getSystemService(AlarmManager::class.java)
    fun precise() = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
    fun notificationAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    suspend fun reconcile(alert: Boolean) = mutex.withLock {
        val snapshot = context.habitApp.repository.snapshot()
        val settings = context.habitApp.preferences.read()
        val now = ZonedDateTime.now()
        val plan = ReminderPlanner.calculate(snapshot, settings, now)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "习惯打卡提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "到点提醒及未确认习惯的持续跟进"; enableVibration(true)
        })
        val intent = PendingIntent.getBroadcast(context, 1, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val trigger = plan.next.toInstant().toEpochMilli()
        try {
            if (precise()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
        } catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent) }
        if (plan.pending.isEmpty() || !notificationAllowed()) { manager.cancel(NOTIFICATION_ID); return@withLock }
        val quiet = Rules.quiet(now.toLocalTime(), LocalTime.parse(settings.quietStart), LocalTime.parse(settings.quietEnd))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).putExtra("pending", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.InboxStyle().setBigContentTitle("${plan.pending.size} 个习惯等待确认")
        plan.pending.take(6).forEach { h ->
            val value = snapshot.record(h, now.toLocalDate())?.value ?: 0.0
            val target = snapshot.plan(h, now.toLocalDate())!!.target
            style.addLine(if (h.kind == "COUNT") "${h.name}  ${Rules.number(value)}/${Rules.number(target)} ${h.unit}" else h.name)
        }
        if (plan.pending.size > 6) style.setSummaryText("还有 ${plan.pending.size - 6} 项，点击逐项确认")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Track · 今日还有 ${plan.pending.size} 项")
            .setContentText(plan.pending.take(3).joinToString(" · ") { it.name }).setStyle(style)
            .setContentIntent(open).setOngoing(true).setAutoCancel(false).setSilent(quiet || !alert)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (plan.pending.size == 1) {
            val h = plan.pending.first()
            fun action(mode: String): PendingIntent {
                val token = UUID.randomUUID().toString()
                val actionIntent = Intent(context, ActionReceiver::class.java).setData(android.net.Uri.parse("habittrack://action/$token"))
                    .putExtra("habit", h.id).putExtra("date", now.toLocalDate().toString()).putExtra("mode", mode).putExtra("token", token)
                return PendingIntent.getBroadcast(context, 0, actionIntent, PendingIntent.FLAG_IMMUTABLE)
            }
            notification.addAction(0, if (h.kind == "COUNT") "+${Rules.number(h.step)} ${h.unit}" else "已完成", action(if (h.kind == "COUNT") "increment" else "complete"))
                .addAction(0, "今日未完成", action("fail"))
        } else notification.addAction(0, "处理今日待办", open)
        try { manager.notify(NOTIFICATION_ID, notification.build()) } catch (_: SecurityException) { /* Permission changed while posting. */ }
    }
}
