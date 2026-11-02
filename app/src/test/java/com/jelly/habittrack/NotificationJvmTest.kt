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
        assertEquals(2, notification.actions.size)
        assertTrue(Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isNotEmpty())
        repo.act(h.id, today, "complete")
        engine.reconcile(false)
        assertTrue(manager.activeNotifications.none { it.id == 100 })
        repo.db.close()
    }
}
class NotificationTestApplication : HabitApplication() { override fun onCreate() { /* Keep periodic workers out of this deterministic test. */ } }
