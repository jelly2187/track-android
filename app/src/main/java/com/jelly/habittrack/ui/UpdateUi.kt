package com.jelly.habittrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jelly.habittrack.BuildConfig
import com.jelly.habittrack.habitApp
import com.jelly.habittrack.update.*
import kotlinx.coroutines.launch

@Composable fun UpdateHost() {
    val context = LocalContext.current
    val updater = context.habitApp.updater
    val state by updater.state.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (updater.automatic()) updater.check() }
    LaunchedEffect(state) { if (state is UpdateState.Available) { show = true; error = null } }
    val release = (state as? UpdateState.Available)?.release
    if (show && release != null) AlertDialog(onDismissRequest = { show = false }, title = { Text("Track ${release.versionName}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(release.notes.ifBlank { "有新的应用版本可用。" })
            Text("${"%.1f".format(release.sizeBytes / 1024.0 / 1024.0)} MB · 覆盖安装保留现有习惯和记录", style = MaterialTheme.typography.bodySmall)
            Text("前往浏览器查看版本并下载 APK，由你确认系统安装。Track 不申请安装其他应用的权限。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try { context.startActivity(updateDownloadIntent(release)); show = false }
            catch (_: Exception) { error = "无法打开浏览器，请在 GitHub Releases 页面下载更新。" }
        }) { Text("前往下载") }
    }, dismissButton = { TextButton(onClick = { show = false }) { Text("稍后") } })
}

@Composable fun UpdateSettings() {
    val context = LocalContext.current; val updater = context.habitApp.updater
    val scope = rememberCoroutineScope(); val state by updater.state.collectAsStateWithLifecycle()
    var automatic by remember { mutableStateOf(updater.automatic()) }
    var source by remember { mutableStateOf(updater.source()) }; var edit by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(source) }; var error by remember { mutableStateOf<String?>(null) }
    SectionLabel("应用更新", "当前版本 ${BuildConfig.VERSION_NAME} · GitHub Releases")
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("每次启动自动检查", modifier = Modifier.weight(1f))
        Switch(automatic, { automatic = it; updater.automatic(it) })
    }
    Text(when (val value = state) {
        UpdateState.Idle -> "仅检查版本信息；点击下载后打开浏览器，不上传习惯记录。"
        UpdateState.Checking -> "正在检查更新…"
        UpdateState.Latest -> "当前已是最新版本。"
        is UpdateState.Available -> "发现新版 ${value.release.versionName}，点击下方按钮查看。"
        is UpdateState.Error -> "检查失败：${value.message}"
    }, style = MaterialTheme.typography.bodySmall, color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(enabled = state != UpdateState.Checking, onClick = {
        updater.clearResult(); scope.launch { updater.check() }
    }, modifier = Modifier.fillMaxWidth()) { Text("检查更新") }
    TextButton(enabled = state != UpdateState.Checking, onClick = { input = source; error = null; edit = true }) { Text("配置更新地址") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("更新描述地址") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, label = { Text("HTTPS latest.json 链接") }, modifier = Modifier.fillMaxWidth())
            Text("默认使用 Track 开源仓库的发布信息；自定义地址仅检查版本，下载仍由浏览器处理。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { input = BuildConfig.UPDATE_MANIFEST_URL }) { Text("恢复默认地址") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try { updater.source(input); source = updater.source(); edit = false }
        catch (exception: Exception) { error = exception.message ?: "请输入有效 HTTPS 地址" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { edit = false }) { Text("取消") } })
}
