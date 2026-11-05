@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.jelly.habittrack.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jelly.habittrack.*
import com.jelly.habittrack.R
import com.jelly.habittrack.data.*
import com.jelly.habittrack.reminder.ReminderEngine
import kotlinx.coroutines.*
import java.time.*

@Composable fun SettingsScreen(s: Snapshot, settings: ReminderSettings, modifier: Modifier, onSettings: (ReminderSettings) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current; val app = context.habitApp; val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }; val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }; owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) } }
    val engine = remember(revision) { ReminderEngine(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++; scope.launch { app.reconcile() } }
    var restoring by remember { mutableStateOf<BackupBundle?>(null) }; var busy by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<HabitGroup?>(null) }; var groupDialog by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    fun work(action: suspend () -> Unit) { scope.launch { busy = true; try { withContext(Dispatchers.IO) { action() } } catch (e: Exception) { onMessage("操作未成功：${e.message ?: "请检查文件"}") } finally { busy = false } } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) work {
        val text = Backup.encode(app.repository.snapshot(), app.preferences.read())
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法写入文件")
        onMessage("备份已导出")
    } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) work {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readTextBounded() } ?: error("无法读取文件")
        val bundle = Backup.decode(text)
        withContext(Dispatchers.Main) { restoring = bundle }
    } }
    fun open(intent: Intent) { try { context.startActivity(intent) } catch (_: Exception) { try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } catch (_: Exception) { onMessage("请手动打开系统设置中的应用详情") } } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("提醒状态", "先检查权限，再设置手机后台运行策略")
        SettingRow("通知", if (engine.notificationAllowed()) "已允许" else "未允许或通知渠道已关闭") {
            if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        SettingRow("准时提醒", if (engine.precise()) "已允许精确提醒" else "未授权，提醒可能延迟") { if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
        SettingRow("后台运行与耗电管理", if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "已豁免系统电池优化" else "检查省电限制") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        SettingRow("发送测试通知", "立即显示测试卡片，验证通知权限与声音") {
            scope.launch { withContext(Dispatchers.IO) {
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                manager.createNotificationChannel(android.app.NotificationChannel(ReminderEngine.CHANNEL, "习惯打卡提醒", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                if (engine.notificationAllowed()) manager.notify(101, androidx.core.app.NotificationCompat.Builder(context, ReminderEngine.CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Track · 测试提醒").setContentText("通知已成功创建。真实习惯提醒会在设定时间到达。").setAutoCancel(true).build())
                else onMessage("请先允许通知，并打开习惯打卡提醒渠道")
            } }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("按需允许提醒", style = MaterialTheme.typography.titleSmall)
                Text("只有开启了提醒的习惯会安排通知。不同手机的省电策略可能使提醒延迟，可按需要检查应用的通知、准时提醒和后台运行设置；Track 不会自行修改这些系统设置。", style = MaterialTheme.typography.bodySmall)
                Text("Android 14 及以上通知仍可被单独划掉；未确认会在下一次提醒时重新显示。强行停止或关闭通知后，需要重新打开应用或恢复权限。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("打开Track的系统设置") }
            }
        }
        SectionLabel("重复提醒", "未确认时是否继续跟进，由你决定")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 15, 30, 60).forEach { interval -> FilterChip(settings.interval == interval, { onSettings(settings.copy(interval = interval)) }, label = { Text(if (interval == 0) "不重复" else "每 $interval 分钟") }) } }
        SettingRow("夜间静音开始", settings.quietStart) { val t = LocalTime.parse(settings.quietStart); TimePickerDialog(context, { _, h, m -> onSettings(settings.copy(quietStart = LocalTime.of(h, m).toString())) }, t.hour, t.minute, true).show() }
        SettingRow("夜间静音结束", settings.quietEnd) { val t = LocalTime.parse(settings.quietEnd); TimePickerDialog(context, { _, h, m -> onSettings(settings.copy(quietEnd = LocalTime.of(h, m).toString())) }, t.hour, t.minute, true).show() }
        Text("静音时段保留通知，不发声、不振动。开始与结束相同表示关闭夜间静音。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("外观")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) -> FilterChip(settings.theme == key, { onSettings(settings.copy(theme = key)) }, label = { Text(label) }) } }
        SectionLabel("颜色分组", "点击分组可修改名称和颜色")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { s.groups.forEach { g -> AssistChip(onClick = { editingGroup = g; groupDialog = true }, label = { Text(g.name) }, leadingIcon = { Box(Modifier.size(10.dp).background(Color(g.color), CircleShape)) }) }; AssistChip(onClick = { editingGroup = null; groupDialog = true }, label = { Text("＋ 新建") }) }
        SectionLabel("备份与恢复", "包含习惯、分组、计划、打卡历史和设置，不包含桌面小组件实例")
        OutlinedButton(enabled = !busy, onClick = { export.launch("Track备份-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
        OutlinedButton(enabled = !busy, onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("从备份恢复") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        UpdateSettings()
        SectionLabel("关于Track", "版本 ${BuildConfig.VERSION_NAME} · 打卡数据保存在本机 · 无账号、无广告")
        TextButton(onClick = { privacy = true }) { Text("隐私与权限说明") }
        Text("当前设备：${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (groupDialog) GroupDialog(editingGroup, onDismiss = { groupDialog = false }, onSave = { g -> groupDialog = false; work { app.repository.dao.save(g); app.reconcile() } })
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text("隐私与权限") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("习惯、分组、打卡记录和设置保存在本机；无需账号，不接入广告或统计服务。卸载会删除本地记录，备份由你选择导出位置。")
            Text("通知与准时提醒权限用于你主动开启的习惯提醒；开机后恢复计划。未开启提醒或通知权限被关闭时，不安排提醒唤醒。系统设置需要你手动确认，Track 不会自行修改。")
            Text("小组件使用 Android 的后台任务完成短时渲染，不运行前台常驻服务。重复提醒可在设置中关闭；夜间停止重复唤醒，明确设置在静音时段的提醒仍会静默显示。")
            Text("检查版本与下载更新时会连接你配置的更新地址，服务器可接收到 IP 地址和 Track 版本等网络请求信息；请求中不包含习惯和打卡记录。可关闭启动检查，下载仅在你点击后进行。")
            Text("安装应用权限仅用于 Track 自身更新。下载与安装前检查文件、包名、版本及签名，由你在系统中确认安装，不在后台自动安装。Track 不申请短信、通讯录、位置或系统设置写入权限。完整说明位于开源仓库的 PRIVACY.md。")
        }
    }, confirmButton = { TextButton(onClick = { privacy = false }) { Text("知道了") } })
    restoring?.let { bundle -> AlertDialog(onDismissRequest = { restoring = null }, title = { Text("替换当前手机上的记录？") }, text = { Text("备份中有 ${bundle.habits.size} 个习惯和 ${bundle.records.size} 条记录。恢复将替换当前数据。建议先导出当前备份；恢复后请重新检查小组件选择的习惯。") }, confirmButton = { TextButton(onClick = { restoring = null; work { Backup.restore(app.repository, bundle); withContext(Dispatchers.Main) { onSettings(bundle.settings) }; app.reconcile(); onMessage("恢复完成") } }) { Text("确认替换") } }, dismissButton = { TextButton(onClick = { restoring = null }) { Text("取消") } }) }
}

@Composable private fun SettingRow(title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
private fun java.io.Reader.readTextBounded(): String { val out = StringBuilder(); val buffer = CharArray(8192); while (true) { val n = read(buffer); if (n < 0) return out.toString(); require(out.length + n <= 20_000_000) { "备份超过 20 MB" }; out.append(buffer, 0, n) } }
