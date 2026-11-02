package com.jelly.habittrack

import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jelly.habittrack.data.*
import com.jelly.habittrack.reminder.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun counterNotificationAndScreenStayConsistent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.habitApp
        val today = LocalDate.now()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        val h = Habit("ui-fixture", "测试喝水", kind = "COUNT", unit = "杯", reminderEnabled = true, reminderTimes = "00:00", createdDate = today.toString())
        app.repository.saveHabit(h, 127, 3.0)
        app.reconcile()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("测试喝水").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("累计 1 杯").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("1 / 3 杯").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1.0, app.repository.snapshot().record(h, today)!!.value, 0.0)
        ReminderEngine(context).reconcile(true)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 100 })
        compose.onNodeWithContentDescription("累计 1 杯").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("2 / 3 杯").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("累计 1 杯").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("查看完成记录").fetchSemanticsNodes().isNotEmpty() }
        app.reconcile()
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 100 })
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap -> File(context.filesDir, "screen-today.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
