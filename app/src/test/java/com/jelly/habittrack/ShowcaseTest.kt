package com.jelly.habittrack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jelly.habittrack.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate

/** Captures the actual app screens using synthetic habits, never personal data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ShowcaseApplication::class, qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShowcaseTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun showcaseRealAppScreens() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("读几页书").fetchSemanticsNodes().isNotEmpty() }
        capture("today")
        compose.onNodeWithText("读几页书").performClick()
        compose.onNodeWithText("记录今天").assertIsDisplayed()
        capture("history")
        compose.onNodeWithContentDescription("编辑习惯").performClick()
        compose.onNodeWithText("习惯名称").assertIsDisplayed()
        capture("editor", dialog = true)
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.onNodeWithText("今天").performClick()
        compose.onNodeWithText("好好喝水").assertIsDisplayed()
        capture("today-dark")
    }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view: View = if (dialog) ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val folder = File("../artifacts/showcase").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

class ShowcaseApplication : HabitApplication() {
    override fun onCreate() {
        updater.automatic(false)
        preferences.write(ReminderSettings(theme = "light"))
        runBlocking {
            val today = LocalDate.now(); val start = today.minusDays(27).toString()
            val health = HabitGroup("demo-health", "健康生活", PALETTE[0]); val learn = HabitGroup("demo-learn", "学习成长", PALETTE[1])
            repository.dao.save(health); repository.dao.save(learn)
            val habits = listOf(
                Habit("demo-water", "好好喝水", "照顾好自己，从一杯水开始。", "COUNT", "杯", 1.0, health.id, createdDate = start),
                Habit("demo-read", "读几页书", "给每天留一点安静阅读的时间。", "COUNT", "分钟", 10.0, learn.id, createdDate = start),
                Habit("demo-walk", "出门走一走", "看看天空，也给身体一点活动时间。", groupId = health.id, createdDate = start)
            )
            habits.forEachIndexed { i, h -> repository.dao.save(h.copy(position = i)); repository.dao.save(HabitPlan(h.id, start, 127, if (h.id == "demo-water") 8.0 else if (h.id == "demo-read") 30.0 else 1.0)) }
            (1..27).forEach { offset -> habits.forEachIndexed { index, habit ->
                if (offset % (index + 5) != 0) repository.dao.save(CheckIn(habit.id, today.minusDays(offset.toLong()).toString(), if (index == 0) 8.0 else if (index == 1) 30.0 else 1.0))
            } }
            repository.dao.save(CheckIn("demo-water", today.toString(), 3.0))
            repository.dao.save(CheckIn("demo-walk", today.toString(), 1.0))
        }
    }
}
