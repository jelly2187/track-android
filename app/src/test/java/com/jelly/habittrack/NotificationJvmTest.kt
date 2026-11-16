package com.jelly.habittrack

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import com.jelly.habittrack.data.*
import com.jelly.habittrack.reminder.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NotificationTestApplication::class)
class NotificationJvmTest {
    @Test fun noAlarmWithoutRemindersAndDisablingRemindersCancelsExistingAlarm() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NotificationTestApplication>()
        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
        val engine = ReminderEngine(app)
        engine.reconcile(false)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS")
        val habit = Habit("cleanup", "提醒清理", reminderEnabled = true, reminderTimes = "00:00")
        app.repository.saveHabit(habit, 127, 1.0)
        engine.reconcile(false)
        assertEquals(1, alarms.scheduledAlarms.size)
        app.repository.saveHabit(habit.copy(reminderEnabled = false), 127, 1.0)
        engine.reconcile(false)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 100 })
        app.repository.db.close()
    }

    @Test fun deniedNotificationPermissionDoesNotKeepWakeupAlarm() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NotificationTestApplication>()
        val habit = Habit("denied", "关闭通知测试", reminderEnabled = true, reminderTimes = "00:00")
        app.repository.saveHabit(habit, 127, 1.0)
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS")
        val engine = ReminderEngine(app)
        engine.reconcile(false)
        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        Shadows.shadowOf(app).denyPermissions("android.permission.POST_NOTIFICATIONS")
        engine.reconcile(true)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 100 })
        app.repository.db.close()
    }
    @Test fun notificationsAreCreatedAndCancelledWithCheckin() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NotificationTestApplication>()
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS")
        val repo = app.repository
        val today = LocalDate.now()
        val h = Habit("notify", "提醒测试", reminderEnabled = true, reminderTimes = "00:00", createdDate = today.toString())
        repo.saveHabit(h, 127, 1.0)
        val engine = ReminderEngine(app)
        engine.reconcile(true)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single { it.id == 100 }.notification
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("Today's notification must expire without a midnight app wakeup", notification.timeoutAfter in 1..90_000_000)
        assertEquals(2, notification.actions.size)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("提醒测试 · 从今天开始，完成第 1 次打卡", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().endsWith("解锁首次完成徽章"))
        assertTrue(Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isNotEmpty())
        repo.act(h.id, today, "complete")
        engine.reconcile(false)
        assertTrue(manager.activeNotifications.none { it.id == 100 })
        repo.db.close()
    }

    @Test fun groupedReminderShowsFirstHabitAndLimitsExpandedRows() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NotificationTestApplication>()
        Shadows.shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS")
        val today = LocalDate.now()
        val habit = Habit(
            "water", "喝水", kind = "COUNT", unit = "杯", createdDate = today.minusDays(6).toString(),
            reminderEnabled = true, reminderTimes = "00:00"
        )
        val repo = app.repository
        repo.dao.save(habit)
        repo.dao.save(HabitPlan(habit.id, habit.createdDate, EVERY_DAY, 6.0))
        for (offset in 1L..6L) repo.dao.save(CheckIn(habit.id, today.minusDays(offset).toString(), 6.0))
        repo.dao.save(CheckIn(habit.id, today.toString(), 3.0))
        for (i in 1..6) repo.saveHabit(habit.copy(id = "h$i", name = "习惯 $i", kind = "CHECK", position = i), EVERY_DAY, 1.0)
        ReminderEngine(app).reconcile(false)
        val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        val lines = checkNotNull(notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
        assertEquals("Track · 今日还有 7 项", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("喝水 · 3/6 杯 · 已连续完成 6 次", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(6, lines.size)
        assertTrue(lines.first().toString().contains("达标解锁连续 7 次徽章"))
        assertEquals("还有 1 项，点击逐项确认", notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        assertEquals("处理今日待办", notification.actions.single().title)
        app.repository.db.close()
    }
}
class NotificationTestApplication : HabitApplication() { override fun onCreate() { /* Keep periodic workers out of this deterministic test. */ } }
