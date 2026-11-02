@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.jelly.habittrack.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jelly.habittrack.data.*
import com.jelly.habittrack.habitApp
import kotlinx.coroutines.launch
import java.time.*
import java.util.UUID

@Composable fun HabitEditor(s: Snapshot, initial: Habit?, onDismiss: () -> Unit, onSave: (Habit, Int, Double) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val plan = initial?.let { s.plan(it, LocalDate.now()) }
    var name by remember { mutableStateOf(initial?.name ?: "") }; var note by remember { mutableStateOf(initial?.note ?: "") }
    var count by remember { mutableStateOf(initial?.kind == "COUNT") }
    var target by remember { mutableStateOf(Rules.number(plan?.target ?: 1.0)) }
    var unit by remember { mutableStateOf(initial?.unit ?: "次") }; var step by remember { mutableStateOf(Rules.number(initial?.step ?: 1.0)) }
    var days by remember { mutableIntStateOf(plan?.days ?: 127) }; var reminderDays by remember { mutableIntStateOf(initial?.reminderDays ?: 127) }
    var reminder by remember { mutableStateOf(initial?.reminderEnabled ?: false) }
    var times by remember { mutableStateOf(Rules.times(initial?.reminderTimes ?: "09:00")) }
    var group by remember { mutableStateOf(initial?.groupId) }; var addGroup by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(if (initial == null) "种下一个新习惯" else "编辑习惯") }, navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭") } }, actions = {
            TextButton(onClick = {
                val goal = if (count) target.toDoubleOrNull() else 1.0
                val increment = if (count) step.toDoubleOrNull() else 1.0
                error = when {
                    name.isBlank() || name.length > 60 -> "请输入 1—60 字的习惯名称"
                    days == 0 -> "至少选择一个打卡日"
                    goal == null || !goal.isFinite() || goal <= 0 || goal > 1_000_000 -> "请输入有效目标（大于 0，不超过 1000000）"
                    increment == null || !increment.isFinite() || increment <= 0 || increment > 1_000_000 -> "请输入有效快捷增量"
                    reminder && (times.isEmpty() || reminderDays and days == 0) -> "提醒时间不能为空，提醒日须包含至少一个打卡日"
                    else -> null
                }
                if (error == null) onSave(Habit(initial?.id ?: UUID.randomUUID().toString(), name.trim(), note.trim(), if (count) "COUNT" else "CHECK", if (count) unit.ifBlank { "次" } else "次", increment!!, group, reminderDays, times.sorted().joinToString(","), reminder, initial?.archived ?: false, initial?.position ?: ((s.habits.maxOfOrNull { it.position } ?: -1) + 1), initial?.createdDate ?: LocalDate.now().toString()), days, goal!!)
            }) { Text("保存") }
        }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("习惯名称") }, placeholder = { Text("例如：每天阅读") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("给自己的小提示（选填）") }, modifier = Modifier.fillMaxWidth())
                SectionLabel("如何记录")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { FilterChip(!count, { count = false }, label = { Text("完成 / 未完成") }); FilterChip(count, { count = true }, label = { Text("累计目标") }) }
                if (count) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(target, { target = it }, label = { Text("每日目标") }, modifier = Modifier.weight(1f), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(unit, { unit = it.take(12) }, label = { Text("单位，如分钟") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    OutlinedTextField(step, { step = it }, label = { Text("每次快捷增加多少") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), singleLine = true)
                }
                SectionLabel("打卡计划", "选中的星期才计入完成率和连续坚持")
                WeekPicker(days) { days = it }
                SectionLabel("颜色分组")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(group == null, { group = null }, label = { Text("不分组") })
                    s.groups.forEach { g -> FilterChip(group == g.id, { group = g.id }, label = { Text(g.name) }, leadingIcon = { Box(Modifier.size(10.dp).background(Color(g.color), CircleShape)) }) }
                    AssistChip(onClick = { addGroup = true }, label = { Text("新建分组") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { SectionLabel("提醒我坚持", "未确认默认每15分钟再次提醒") }; Switch(reminder, { reminder = it }) }
                if (reminder) {
                    Text("提醒日（与打卡日分别设置）", style = MaterialTheme.typography.bodySmall)
                    WeekPicker(reminderDays) { reminderDays = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        times.sorted().forEach { t -> InputChip(selected = false, onClick = { times = times - t }, label = { Text(t.toString()) }, trailingIcon = { Icon(Icons.Rounded.Close, "删除提醒时间", Modifier.size(14.dp)) }) }
                        AssistChip(onClick = { TimePickerDialog(context, { _, hour, minute -> times = (times + LocalTime.of(hour, minute)).distinct().sorted() }, 9, 0, true).show() }, label = { Text("添加时间") })
                    }
                    Text("点击时间可删除。当天达标或确认未完成后，停止当天剩余提醒。夜间默认静音，可在设置修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (addGroup) GroupDialog(null, onDismiss = { addGroup = false }, onSave = { g -> scope.launch { context.habitApp.repository.dao.save(g); group = g.id; addGroup = false } })
}

@Composable fun GroupDialog(initial: HabitGroup?, onDismiss: () -> Unit, onSave: (HabitGroup) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }; var color by remember { mutableLongStateOf(initial?.color ?: PALETTE.first()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "新建颜色分组" else "编辑颜色分组") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("分组名称") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PALETTE.forEach { c -> Box(Modifier.size(42.dp).background(Color(c), CircleShape).clickable { color = c }, contentAlignment = Alignment.Center) { if (c == color) Icon(Icons.Rounded.Check, "已选择", tint = Color.White) } }
            }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(HabitGroup(initial?.id ?: UUID.randomUUID().toString(), name.trim(), color)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
