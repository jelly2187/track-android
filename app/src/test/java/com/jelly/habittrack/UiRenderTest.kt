package com.jelly.habittrack

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.jelly.habittrack.data.*
import com.jelly.habittrack.ui.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun lightHabitCardsAndCounterInteraction() = render("light")
    @Test fun darkHabitCardsAndCounterInteraction() = render("dark")
    private fun render(theme: String) {
        val today = LocalDate.of(2026, 11, 3)
        val groups = listOf(HabitGroup("health", "健康", PALETTE[0]), HabitGroup("learn", "成长", PALETTE[1]))
        val habits = listOf(Habit("water", "好好喝水", kind = "COUNT", unit = "杯", groupId = "health", createdDate = "2026-10-01"), Habit("read", "读几页书", groupId = "learn", createdDate = "2026-10-01"))
        val plans = listOf(HabitPlan("water", "2026-10-01", 127, 8.0), HabitPlan("read", "2026-10-01", 127, 1.0))
        compose.setContent {
            var value by remember { mutableDoubleStateOf(3.0) }
            val s = Snapshot(habits, groups, plans, (1..6).map { CheckIn("read", today.minusDays(it.toLong()).toString(), 1.0) } + CheckIn("water", today.toString(), value))
            HabitTheme(theme) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Track", style = MaterialTheme.typography.headlineLarge)
                        Text("每天一点，日渐成为", style = MaterialTheme.typography.titleMedium)
                        habits.forEach { h -> HabitRow(s, h, today, {}, { _, amount -> value += amount }, {}) }
                        SectionLabel("十一月的坚持")
                        CalendarGrid(s, habits[1], today, {})
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("累计 1 杯").performClick()
        compose.onNodeWithText("4 / 8 杯").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val directory = File("../artifacts/previews").apply { mkdirs() }
            File(directory, "habit-cards-$theme.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
