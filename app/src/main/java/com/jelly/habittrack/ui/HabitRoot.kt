@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.jelly.habittrack.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.jelly.habittrack.*
import com.jelly.habittrack.data.*
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun HabitRoot(pendingOnly: Boolean = false, initialHabit: String? = null) {
    val app = LocalContext.current.habitApp
    val s by app.repository.changes.collectAsStateWithLifecycle(initialValue = Snapshot())
    val scope = rememberCoroutineScope(); val snackbar = remember { SnackbarHostState() }
    var settings by remember { mutableStateOf(app.preferences.read()) }
    var tab by remember { mutableIntStateOf(0) }; var group by remember { mutableStateOf<String?>(null) }
    var onlyPending by remember { mutableStateOf(pendingOnly) }
    var selected by remember { mutableStateOf(initialHabit) }; var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Habit?>(null) }; var recordDate by remember { mutableStateOf<LocalDate?>(null) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(30_000) } }
    fun execute(block: suspend () -> Unit) { scope.launch { try { withContext(Dispatchers.IO) { block(); app.reconcile() } } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "操作未成功，请重试") } } }
    HabitTheme(settings.theme) {
        val habit = s.habits.find { it.id == selected }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            if (habit != null) TopAppBar(title = { Text(habit.name) }, navigationIcon = { IconButton(onClick = { selected = null }) { Icon(Icons.Rounded.ArrowBack, "返回") } }, actions = { IconButton(onClick = { editing = habit; editor = true }) { Icon(Icons.Rounded.Edit, "编辑习惯") } })
            else TopAppBar(title = { Column { Text(if (tab == 0) "循习" else if (tab == 1) "坚持的痕迹" else "设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); if (tab == 0) Text(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = { if (tab == 0) IconButton(onClick = { editing = null; editor = true }) { Icon(Icons.Rounded.Add, "新建习惯") } })
        }, bottomBar = {
            if (habit == null) NavigationBar {
                listOf("今天" to Icons.Rounded.CheckCircleOutline, "统计" to Icons.Rounded.BarChart, "设置" to Icons.Rounded.Tune).forEachIndexed { i, (name, icon) -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, name) }, label = { Text(name) }) }
            }
        }, floatingActionButton = {
            if (habit == null && tab == 0 && s.habits.any { !it.archived }) FloatingActionButton(onClick = { editing = null; editor = true }) { Icon(Icons.Rounded.Add, "新建习惯") }
        }) { padding ->
            if (habit != null) DetailScreen(s, habit, today, Modifier.padding(padding), onDate = { recordDate = it }, onArchive = { execute { app.repository.dao.save(habit.copy(archived = !habit.archived)) }; selected = null })
            else when (tab) {
                0 -> {
                    val active = s.habits.filter { !it.archived }
                    val due = active.filter { s.due(it, today) }; val completed = due.count { s.complete(it, today) }
                    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(progress = { if (due.isEmpty()) 0f else completed.toFloat() / due.size }, modifier = Modifier.size(70.dp), strokeWidth = 6.dp, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f)); Text("$completed/${due.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                                    Column { Text(if (completed == due.size && due.isNotEmpty()) "今天的坚持，已完成" else "每天一点，日渐成为", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text(if (due.isEmpty()) "为想成为的自己，种下一个习惯" else "还有 ${due.size - completed} 个习惯等待你", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = group == null, onClick = { group = null }, label = { Text("全部") })
                            s.groups.forEach { g -> FilterChip(selected = group == g.id, onClick = { group = g.id }, label = { Text(g.name) }, leadingIcon = { Box(Modifier.size(8.dp).background(Color(g.color), androidx.compose.foundation.shape.CircleShape)) }) }
                        } }
                        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("我的习惯", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); FilterChip(selected = onlyPending, onClick = { onlyPending = !onlyPending }, label = { Text("只看待办") }) } }
                        val visible = active.filter { (group == null || it.groupId == group) && (!onlyPending || (s.due(it, today) && !s.complete(it, today) && s.record(it, today)?.closed != true)) }
                        if (active.isEmpty()) item { EmptyState("从一个小习惯开始", "不用追求完美，让每一次坚持都被看见。", "创建第一个习惯") { editing = null; editor = true } }
                        else if (visible.isEmpty()) item { EmptyState("这里已经清爽了", "当前筛选下没有待办，试试切换分组或查看全部。") }
                        items(visible, key = { it.id }) { h -> HabitRow(s, h, today, onOpen = { selected = h.id }, onAct = { mode, amount -> execute { app.repository.act(h.id, today, mode, amount) } }, onMove = { delta ->
                            val i = active.indexOf(h); val other = active.getOrNull(i + delta)
                            if (other != null) execute { app.repository.db.withTransactionCompat { active.forEachIndexed { index, item -> app.repository.dao.save(item.copy(position = when (item.id) { h.id -> i + delta; other.id -> i; else -> index })) } } }
                        }) }
                    }
                }
                1 -> LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("每一次微小的行动，都算数。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (s.habits.isEmpty()) item { EmptyState("还没有记录", "创建习惯后，这里会展示你的坚持情况。") }
                    items(s.habits, key = { it.id }) { h ->
                        Surface(onClick = { selected = h.id }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(h.name + if (h.archived) " · 已归档" else "", fontWeight = FontWeight.Bold, color = Color(s.color(h)))
                                Text("近30天完成率 ${s.rate(h, today.minusDays(29), today.minusDays(1))}%  ·  最长连续 ${s.streak(h, today).second} 次", style = MaterialTheme.typography.bodySmall)
                                RecentWeek(s, h, today, Color(s.color(h)))
                            }
                        }
                    }
                }
                else -> SettingsScreen(s, settings, Modifier.padding(padding), onSettings = { next -> settings = next; execute { app.preferences.write(next) } }, onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } })
            }
        }
        if (editor) HabitEditor(s, editing, onDismiss = { editor = false }, onSave = { h, days, target ->
            scope.launch { try { withContext(Dispatchers.IO) { app.repository.saveHabit(h, days, target); app.reconcile() }; editor = false } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "请检查输入") } }
        })
        if (habit != null && recordDate != null) RecordDialog(s, habit, recordDate!!, onDismiss = { recordDate = null }, onAction = { mode, value -> val date = recordDate!!; recordDate = null; execute { app.repository.act(habit.id, date, mode, value) } })
    }
}

private suspend fun androidx.room.RoomDatabase.withTransactionCompat(block: suspend () -> Unit) = withTransaction { block() }

@Composable private fun DetailScreen(s: Snapshot, h: Habit, today: LocalDate, modifier: Modifier, onDate: (LocalDate) -> Unit, onArchive: () -> Unit) {
    val context = LocalContext.current; val streak = s.streak(h, today)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (h.note.isBlank()) "让坚持成为生活的自然节奏。" else h.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("当前连续" to "${streak.first} 次", "最长连续" to "${streak.second} 次", "近30天" to "${s.rate(h, today.minusDays(30), today.minusDays(1))}%").forEach { (label, value) ->
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(value, style = MaterialTheme.typography.titleLarge, color = Color(s.color(h))); Text(label, style = MaterialTheme.typography.labelMedium) } }
            }
        }
        SectionLabel("${today.year}年${today.monthValue}月", "点击日期可修改记录；深色表示已完成，浅色表示已有进度")
        CalendarGrid(s, h, today, onDate)
        if (s.due(h, today)) Button(onClick = { onDate(today) }, modifier = Modifier.fillMaxWidth()) { Text("记录今天") }
        OutlinedButton(onClick = {
            DatePickerDialog(context, { _, y, m, d -> onDate(LocalDate.of(y, m + 1, d)) }, today.year, today.monthValue - 1, today.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis(); datePicker.minDate = LocalDate.parse(h.createdDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.show()
        }, modifier = Modifier.fillMaxWidth()) { Text("补记 / 修改其他日期") }
        Text("提醒：" + if (h.reminderEnabled) h.reminderTimes.replace(",", "、") else "已关闭", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onArchive) { Text(if (h.archived) "恢复这个习惯" else "归档这个习惯") }
    }
}

@Composable private fun RecordDialog(s: Snapshot, h: Habit, date: LocalDate, onDismiss: () -> Unit, onAction: (String, Double) -> Unit) {
    val due = s.due(h, date); val target = s.plan(h, date)?.target ?: 1.0
    var raw by remember(date) { mutableStateOf(Rules.number(s.record(h, date)?.value ?: 0.0)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${date.monthValue}月${date.dayOfMonth}日 · ${h.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!due) Text("这一天不在打卡计划中。")
            else {
                Text("目标：${Rules.number(target)} ${h.unit}")
                if (h.kind == "COUNT") OutlinedTextField(raw, { raw = it }, label = { Text("累计数值") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), singleLine = true)
                TextButton(onClick = { onAction("fail", 0.0) }) { Text("确认当天未完成（保留已有进度）") }
                TextButton(onClick = { onAction("reset", 0.0) }) { Text("清空并撤销确认") }
            }
        }
    }, confirmButton = { if (due) TextButton(enabled = h.kind != "COUNT" || raw.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..1_000_000.0 } == true, onClick = { onAction(if (h.kind == "COUNT") "set" else "complete", raw.toDoubleOrNull() ?: 0.0) }) { Text(if (h.kind == "COUNT") "保存进度" else "标记已完成") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
