package com.jelly.habittrack.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jelly.habittrack.habitApp
import com.jelly.habittrack.ui.HabitTheme
import kotlinx.coroutines.launch

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val calendar = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className?.endsWith("HabitCalendarReceiver") == true
        setContent {
            val scope = rememberCoroutineScope(); var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            LaunchedEffect(Unit) {
                val s = habitApp.repository.snapshot()
                options = if (calendar) s.habits.filter { !it.archived }.map { it.id to it.name } else listOf("" to "全部习惯") + s.groups.map { it.id to it.name }
            }
            HabitTheme(habitApp.preferences.read().theme) {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text(if (calendar) "选择要展示的习惯" else "选择列表分组", style = MaterialTheme.typography.headlineSmall) }
                        if (options.isEmpty()) item { Text("请先打开循习创建习惯，再添加小组件。") }
                        items(options) { (key, title) -> OutlinedButton(onClick = {
                            habitApp.preferences.widget(id, key)
                            scope.launch { refreshWidgets(this@WidgetConfigurationActivity); setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish() }
                        }, modifier = Modifier.fillMaxWidth()) { Text(title) } }
                        item { TextButton(onClick = { finish() }) { Text("取消") } }
                    }
                }
            }
        }
    }
}
