package com.jelly.habittrack

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import com.jelly.habittrack.ui.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeekPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun allSevenDaysFitAtNormalFont() = verify(1f)
    @Test fun allSevenDaysFitAtDoubleFont() = verify(2f)
    private fun verify(scale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HabitTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Track", style = MaterialTheme.typography.headlineSmall)
                            SectionLabel("打卡计划", "选中的星期才计入完成率和连续坚持")
                            var mask by remember { mutableIntStateOf(127) }
                            WeekPicker(mask) { mask = it }
                            SectionLabel("提醒日", "提醒计划与打卡计划分别设置")
                            var reminders by remember { mutableIntStateOf(31) }
                            WeekPicker(reminders) { reminders = it }
                        }
                    }
                }
            }
        }
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { day -> compose.onAllNodesWithContentDescription("星期$day").assertCountEquals(2); compose.onAllNodesWithContentDescription("星期$day")[0].assertIsDisplayed() }
        compose.onAllNodesWithContentDescription("星期日")[0].assertIsSelected().performClick().assertIsNotSelected()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val folder = File("../artifacts/previews").apply { mkdirs() }
            File(folder, "week-picker-${scale}x.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
}
