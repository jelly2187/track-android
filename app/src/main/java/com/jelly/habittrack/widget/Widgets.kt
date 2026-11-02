package com.jelly.habittrack.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.jelly.habittrack.MainActivity
import com.jelly.habittrack.data.*
import com.jelly.habittrack.habitApp
import java.time.LocalDate
import java.util.UUID

private val Ink = ColorProvider(Color(0xFF20382E), Color(0xFFE2EEE5))
private val Muted = ColorProvider(Color(0xFF657A6E), Color(0xFFB1C5B8))
private val Paper = ColorProvider(Color(0xFFF6F8F5), Color(0xFF19271F))
private val Soft = ColorProvider(Color(0xFFE3EAE3), Color(0xFF35483A))
val HabitKey = ActionParameters.Key<String>("habit")
val DateKey = ActionParameters.Key<String>("date")
val TokenKey = ActionParameters.Key<String>("token")

suspend fun refreshWidgets(context: Context) { HabitListWidget().updateAll(context); HabitCalendarWidget().updateAll(context) }

class HabitListReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitListWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { super.onDeleted(context, appWidgetIds); appWidgetIds.forEach { context.habitApp.preferences.removeWidget(it) } }
}
class HabitCalendarReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitCalendarWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { super.onDeleted(context, appWidgetIds); appWidgetIds.forEach { context.habitApp.preferences.removeWidget(it) } }
}

class HabitListWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 110.dp), DpSize(250.dp, 220.dp), DpSize(300.dp, 320.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = context.habitApp.repository.snapshot(); val today = LocalDate.now()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val selection = context.habitApp.preferences.widget(widgetId)
        val habits = s.habits.filter { !it.archived && (selection.isEmpty() || it.groupId == selection) }
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(Paper).cornerRadius(22.dp).padding(14.dp)) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("循习 · ${today.monthValue}/${today.dayOfMonth}", style = TextStyle(color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp), modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()))
                    Text("⚙", modifier = GlanceModifier.padding(8.dp).clickable(configure(context, widgetId)), style = TextStyle(color = Muted))
                }
                if (habits.isEmpty()) Text("还没有习惯，点击添加或调整分组", modifier = GlanceModifier.padding(top = 14.dp).clickable(actionStartActivity<MainActivity>()), style = TextStyle(color = Muted, fontSize = 12.sp))
                else LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(habits, itemId = { it.id.hashCode().toLong() }) { h ->
                        val done = s.complete(h, today); val due = s.due(h, today); val closed = s.record(h, today)?.closed == true
                        val target = s.plan(h, today)?.target ?: 1.0; val value = s.record(h, today)?.value ?: 0.0
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(GlanceModifier.defaultWeight().clickable(openHabit(context, h.id))) {
                                Text(h.name, style = TextStyle(color = ColorProvider(Color(s.color(h))), fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
                                Text(when { !due -> "今日休息"; done -> "已完成 · 连续 ${s.streak(h, today).first} 次"; closed -> "今日未完成"; h.kind == "COUNT" -> "${Rules.number(value)}/${Rules.number(target)} ${h.unit}"; else -> "待打卡 · 连续 ${s.streak(h, today).first} 次" }, style = TextStyle(color = Muted, fontSize = 11.sp))
                            }
                            if (due) {
                                if (done || closed) Text(if (done) "✓" else "—", modifier = GlanceModifier.padding(12.dp).clickable(openHabit(context, h.id)), style = TextStyle(color = Ink, fontSize = 20.sp))
                                else Button(text = if (h.kind == "COUNT") "+${Rules.number(h.step)}" else "打卡", onClick = actionRunCallback<WidgetCheckAction>(actionParametersOf(HabitKey to h.id, DateKey to today.toString(), TokenKey to UUID.randomUUID().toString())))
                            }
                        }
                    }
                }
            }
        }
    }
}

class HabitCalendarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(160.dp, 160.dp), DpSize(250.dp, 220.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = context.habitApp.repository.snapshot(); val today = LocalDate.now()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val habit = s.habits.find { it.id == context.habitApp.preferences.widget(widgetId) }
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(Paper).cornerRadius(22.dp).padding(14.dp)) {
                if (habit == null) Text("点击选择一个习惯", modifier = GlanceModifier.fillMaxSize().clickable(configure(context, widgetId)), style = TextStyle(color = Ink, fontSize = 14.sp))
                else {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(habit.name, style = TextStyle(color = ColorProvider(Color(s.color(habit))), fontWeight = FontWeight.Bold, fontSize = 15.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight().clickable(openHabit(context, habit.id)))
                        Text("⚙", modifier = GlanceModifier.padding(6.dp).clickable(configure(context, widgetId)), style = TextStyle(color = Muted))
                    }
                    Text("连续 ${s.streak(habit, today).first} 次 · 最近28天", style = TextStyle(color = Muted, fontSize = 11.sp), modifier = GlanceModifier.padding(bottom = 8.dp))
                    (0 until 4).forEach { week ->
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            (0 until 7).forEach { day ->
                                val date = today.minusDays((27 - week * 7 - day).toLong())
                                val color = if (s.complete(habit, date)) ColorProvider(Color(s.color(habit))) else Soft
                                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(2.dp)) {
                                    Box(GlanceModifier.fillMaxSize().background(color).cornerRadius(4.dp).clickable(openHabit(context, habit.id)), contentAlignment = Alignment.Center) {
                                        Text(date.dayOfMonth.toString(), style = TextStyle(color = if (s.complete(habit, date)) ColorProvider(Color.White) else Muted, fontSize = 9.sp))
                                    }
                                }
                            }
                        }
                    }
                    val target = s.plan(habit, today)?.target ?: 1.0
                    Text(if (s.complete(habit, today)) "今天已完成 ✓" else "今日 ${Rules.number(s.record(habit, today)?.value ?: 0.0)}/${Rules.number(target)} ${habit.unit}", style = TextStyle(color = Ink, fontSize = 11.sp), modifier = GlanceModifier.padding(top = 7.dp).clickable(openHabit(context, habit.id)))
                }
            }
        }
    }
}

private fun openHabit(context: Context, id: String) = actionStartActivity(Intent(context, MainActivity::class.java).putExtra("habit", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
private fun configure(context: Context, id: Int) = actionStartActivity(Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))

class WidgetCheckAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[HabitKey] ?: return
        val date = parameters[DateKey]?.let { LocalDate.parse(it) } ?: return
        val token = parameters[TokenKey] ?: return
        val h = context.habitApp.repository.dao.habit(id) ?: return
        context.habitApp.repository.act(id, date, if (h.kind == "COUNT") "increment" else "complete", h.step, token, external = true)
        context.habitApp.reconcile()
    }
}
