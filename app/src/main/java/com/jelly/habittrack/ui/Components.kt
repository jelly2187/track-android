package com.jelly.habittrack.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jelly.habittrack.data.*
import java.time.LocalDate

@Composable fun WeekPicker(mask: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, label ->
            FilterChip(selected = mask and (1 shl i) != 0, onClick = { onChange(mask xor (1 shl i)) }, label = { Text(label) })
        }
    }
}

@Composable fun SectionLabel(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Rounded.Spa, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable fun RecentWeek(s: Snapshot, h: Habit, today: LocalDate, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        (6 downTo 0).forEach { offset ->
            val date = today.minusDays(offset.toLong())
            val done = s.complete(h, date); val due = s.due(h, date)
            val progress = ((s.record(h, date)?.value ?: 0.0) / (s.plan(h, date)?.target ?: 1.0)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.size(15.dp).background(if (done) color else if (progress > 0) color.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                if (done) Icon(Icons.Rounded.Check, "${date} 已完成", Modifier.size(12.dp), tint = Color.White)
                else if (!due) Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable fun HabitRow(s: Snapshot, h: Habit, today: LocalDate, onOpen: () -> Unit, onAct: (String, Double) -> Unit, onMove: (Int) -> Unit) {
    val color = Color(s.color(h)); val done = s.complete(h, today); val record = s.record(h, today)
    val due = s.due(h, today); val target = s.plan(h, today)?.target ?: 1.0
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(4.dp).height(64.dp).background(color, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(h.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(when { !due -> "今日休息"; done -> "今日已完成 · 连续 ${s.streak(h, today).first} 次"; record?.closed == true -> "今日已确认未完成"; h.kind == "COUNT" -> "${Rules.number(record?.value ?: 0.0)} / ${Rules.number(target)} ${h.unit}"; else -> "连续 ${s.streak(h, today).first} 次 · 慢慢来，每天一点" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RecentWeek(s, h, today, color)
            }
            if (due) FilledIconButton(onClick = { if (done || record?.closed == true) onOpen() else onAct(if (h.kind == "COUNT") "increment" else "complete", h.step) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (done) color else color.copy(alpha = .13f), contentColor = if (done) Color.White else color), modifier = Modifier.size(48.dp)) {
                Icon(if (done) Icons.Rounded.Check else if (record?.closed == true) Icons.Rounded.Remove else if (h.kind == "COUNT") Icons.Rounded.Add else Icons.Rounded.Check, if (done) "查看完成记录" else if (h.kind == "COUNT") "累计 ${Rules.number(h.step)} ${h.unit}" else "完成打卡")
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; onMove(-1) })
                    DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; onMove(1) })
                    if (due) DropdownMenuItem(text = { Text(if (record?.closed == true || done) "撤销今日确认" else "今日未完成") }, onClick = { menu = false; onAct(if (record?.closed == true) "reopen" else if (done) "reset" else "fail", 0.0) })
                }
            }
        }
    }
}

@Composable fun CalendarGrid(s: Snapshot, h: Habit, today: LocalDate, onDay: (LocalDate) -> Unit) {
    val color = Color(s.color(h)); val first = today.withDayOfMonth(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
        (0 until 6).forEach { week ->
            if (start.plusDays(week * 7L).month == first.month || week == 0) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                (0 until 7).forEach { weekday ->
                    val date = start.plusDays(week * 7L + weekday)
                    val valid = date.month == first.month && date <= today && s.due(h, date)
                    val done = s.complete(h, date)
                    val partial = (s.record(h, date)?.value ?: 0.0) > 0
                    Surface(modifier = Modifier.weight(1f).aspectRatio(1f).clickable(enabled = valid) { onDay(date) }, shape = RoundedCornerShape(10.dp), color = if (date.month != first.month) Color.Transparent else if (done) color else if (partial) color.copy(alpha = .2f) else MaterialTheme.colorScheme.surfaceVariant) {
                        Box(contentAlignment = Alignment.Center) { Text(if (date.month == first.month) date.dayOfMonth.toString() else "", color = if (done) Color.White else if (valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .3f), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
