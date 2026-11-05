@file:android.annotation.SuppressLint("RestrictedApi")
package com.jelly.habittrack

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.ActionModifier
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.RemoteViewsRoot
import androidx.glance.appwidget.action.RunCallbackAction
import androidx.glance.layout.Column
import androidx.glance.text.EmittableText
import androidx.test.core.app.ApplicationProvider
import com.jelly.habittrack.data.*
import com.jelly.habittrack.widget.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.Executors

/** Keep the SAME composition alive across writes, reproducing Glance's warm-session behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WidgetTestApplication::class)
class WidgetSyncTest {
    private val app get() = ApplicationProvider.getApplicationContext<WidgetTestApplication>()
    private val today = LocalDate.now()
    private val count = Habit("water", "喝水", kind = "COUNT", unit = "杯", groupId = "health", createdDate = today.minusDays(3).toString())
    private val check = Habit("read", "阅读", groupId = "learn", createdDate = count.createdDate)

    @Before fun setup() = runBlocking {
        app.repository.saveHabit(count, EVERY_DAY, 3.0)
        app.repository.saveHabit(check, EVERY_DAY, 1.0)
        app.repository.dao.save(HabitPlan(count.id, count.createdDate, EVERY_DAY, 3.0))
        app.preferences.widget(2, count.id)
    }
    @After fun cleanup() { app.repository.db.close() }

    @Test fun widgetCheckinUpdatesBothWidgetsWithoutOpeningActivity() {
        val initial = runBlocking { app.repository.snapshot() }
        LiveWidgetComposition(app) {
            Column {
                HabitListWidget().Content(app, 1, initial, today)
                HabitCalendarWidget().Content(app, 2, initial, today)
            }
        }.use { ui ->
            ui.awaitText("0/3 杯")
            val parameters = ui.actionFor(count.id)
            // Use the real callback and the parameters from the displayed button.
            runBlocking { repeat(3) { WidgetCheckAction().onAction(app, object : GlanceId {}, parameters) } }
            ui.awaitText("1/3 杯")
            ui.awaitText("今日 1/3 杯")
            assertEquals(1.0, runBlocking { app.repository.snapshot().record(count, today)!!.value }, 0.0)
            val next = ui.actionFor(count.id)
            assertNotEquals(parameters[TokenKey], next[TokenKey])
            runBlocking { WidgetCheckAction().onAction(app, object : GlanceId {}, next) }
            ui.awaitText("2/3 杯")
            ui.awaitText("今日 2/3 杯")
        }
    }

    @Test fun appEditsAndHistoryChangesUpdateLiveListAndCalendar() {
        val initial = runBlocking { app.repository.snapshot() }
        LiveWidgetComposition(app) {
            Column {
                HabitListWidget().Content(app, 1, initial, today)
                HabitCalendarWidget().Content(app, 2, initial, today)
            }
        }.use { ui ->
            ui.awaitText("今日 0/3 杯")
            runBlocking { app.repository.act(count.id, today, "complete") }
            ui.awaitText("已完成 · 连续 1 次")
            ui.awaitText("今天已完成 ✓")
            runBlocking { app.repository.act(count.id, today.minusDays(1), "complete") }
            ui.awaitText("连续 2 次 · 最近28天")
            runBlocking { app.repository.act(count.id, today, "reset") }
            ui.awaitText("0/3 杯")
            ui.awaitText("今日 0/3 杯")
            runBlocking { app.repository.act(count.id, today, "fail") }
            ui.awaitText("今日未完成")
            runBlocking {
                app.repository.act(count.id, today, "reopen")
                app.repository.saveHabit(count.copy(name = "每天喝水"), EVERY_DAY, 5.0)
            }
            ui.awaitText("每天喝水")
            ui.awaitText("0/5 杯")
            ui.awaitText("今日 0/5 杯")
        }
    }

    @Test fun configurationAndOtherHabitChangesDoNotLeaveStaleContentOrRotateAction() {
        val initial = runBlocking { app.repository.snapshot() }
        LiveWidgetComposition(app) { HabitListWidget().Content(app, 1, initial, today) }.use { ui ->
            ui.awaitText("0/3 杯")
            val first = ui.actionFor(count.id)
            runBlocking { app.repository.act(check.id, today, "complete") }
            ui.awaitText("已完成 · 连续 1 次")
            assertEquals(first[TokenKey], ui.actionFor(count.id)[TokenKey])
            app.preferences.widget(1, "learn")
            ui.awaitAbsent("喝水")
            ui.awaitText("阅读")
            app.preferences.widget(1, "health")
            ui.awaitText("喝水")
            ui.awaitAbsent("阅读")
        }
    }

    @Test fun calendarReconfigurationAndNewSessionReadLatestRecords() {
        runBlocking { app.repository.act(check.id, today, "complete") }
        val initial = runBlocking { app.repository.snapshot() }
        LiveWidgetComposition(app) { HabitCalendarWidget().Content(app, 2, initial, today) }.use { ui ->
            ui.awaitText("今日 0/3 杯")
            app.preferences.widget(2, check.id)
            ui.awaitText("阅读")
            ui.awaitText("今天已完成 ✓")
            runBlocking { app.repository.act(check.id, today, "reset") }
            ui.awaitText("今日 0/1 次")
        }
        // Dispose the old composition, then create a cold session from persisted data.
        val fresh = runBlocking { app.repository.snapshot() }
        LiveWidgetComposition(app) { HabitCalendarWidget().Content(app, 2, fresh, today) }.use { ui ->
            ui.awaitText("阅读")
            ui.awaitText("今日 0/1 次")
        }
    }
}

/**
 * Compose real Glance nodes and inspect their current tree on a single dispatcher.
 * Glance 1.1.1's unit-test TestContext caches the first flattened tree, so it cannot
 * assert subsequent recompositions. This harness reads the live tree each time.
 */
private class LiveWidgetComposition(context: Context, content: @Composable () -> Unit) : AutoCloseable {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val clock = object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(System.nanoTime())
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + clock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val root = RemoteViewsRoot(50)
    private val composition = Composition(androidx.glance.Applier(root), recomposer)
    init {
        runBlocking(dispatcher) {
            composition.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalSize provides DpSize(300.dp, 320.dp),
                    LocalGlanceId provides object : GlanceId {},
                ) { content() }
            }
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
        }
    }
    private fun nodes(): List<Emittable> = runBlocking(dispatcher) {
        fun flatten(node: Emittable): List<Emittable> = listOf(node) +
            ((node as? EmittableWithChildren)?.children?.flatMap { flatten(it) } ?: emptyList())
        flatten(root.copy())
    }
    fun awaitText(text: String) = eventually {
        val texts = nodes().filterIsInstance<EmittableText>().map { it.text }
        assertTrue("Expected '$text', current texts: $texts", text in texts)
    }
    fun awaitAbsent(text: String) = eventually {
        assertFalse(nodes().filterIsInstance<EmittableText>().any { it.text == text })
    }
    fun actionFor(habitId: String): ActionParameters {
        val actions = nodes().flatMap { node ->
            node.modifier.foldIn(emptyList<RunCallbackAction>()) { actions, modifier ->
                val action = (modifier as? ActionModifier)?.action as? RunCallbackAction
                if (action == null) actions else actions + action
            }
        }
        return actions.single { it.parameters[HabitKey] == habitId }.parameters
    }
    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            try { assertion(); return }
            catch (error: AssertionError) {
                if (System.nanoTime() >= deadline) throw error
                Thread.sleep(5)
            }
        }
    }
    override fun close() {
        runBlocking(dispatcher) {
            composition.dispose()
            recomposer.cancel()
            recomposer.join()
            scope.cancel()
        }
        dispatcher.close()
    }
}

class WidgetTestApplication : HabitApplication() {
    override fun onCreate() { /* Keep periodic workers out of these live-composition tests. */ }
}
