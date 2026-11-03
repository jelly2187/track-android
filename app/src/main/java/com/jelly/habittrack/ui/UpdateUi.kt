package com.jelly.habittrack.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
    var installMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (updater.automatic()) updater.check() }
    LaunchedEffect(state) { if (state is UpdateState.Available || state is UpdateState.Ready || state is UpdateState.Downloading) show = true }
    val release = when (val value = state) { is UpdateState.Available -> value.release; is UpdateState.Ready -> value.release; is UpdateState.Downloading -> value.release; else -> null }
    if (show && release != null) AlertDialog(onDismissRequest = { show = false }, title = { Text(if (state is UpdateState.Ready) "新版已下载" else "Track ${release.versionName}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(release.notes.ifBlank { "有新的应用版本可用。" })
            Text("${"%.1f".format(release.sizeBytes / 1024.0 / 1024.0)} MB · 保留现有习惯和记录", style = MaterialTheme.typography.bodySmall)
            when (val value = state) {
                is UpdateState.Downloading -> { LinearProgressIndicator(progress = { value.percent / 100f }, modifier = Modifier.fillMaxWidth()); Text("下载中 ${value.percent}%") }
                is UpdateState.Ready -> Text("已校验文件、应用标识和签名。首次安装更新时，请允许 Track 安装应用，返回后再次点击安装；最后由系统确认。", style = MaterialTheme.typography.bodySmall)
                else -> Text("从发布渠道下载完整 APK；未确认前不会安装。", style = MaterialTheme.typography.bodySmall)
            }
            installMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        when (val value = state) {
            is UpdateState.Available -> TextButton(onClick = { updater.download(value.release) }) { Text("下载更新") }
            is UpdateState.Ready -> TextButton(onClick = {
                try { val activity = context.findActivity() ?: error("请返回应用主界面安装"); if (updater.install(activity, value)) show = false }
                catch (error: Exception) { installMessage = error.message ?: "无法启动系统安装器" }
            }) { Text("安装更新") }
            is UpdateState.Downloading -> TextButton(onClick = { updater.cancelDownload(); show = false }) { Text("取消下载") }
            else -> Unit
        }
    }, dismissButton = { TextButton(onClick = { show = false }) { Text(if (state is UpdateState.Downloading) "后台下载" else "稍后") } })
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
        UpdateState.Idle -> "检测仅访问更新描述，不上传习惯记录。"
        UpdateState.Checking -> "正在检查更新…"
        UpdateState.Latest -> "当前已是最新版本。"
        is UpdateState.Available -> "发现新版 ${value.release.versionName}，点击下方按钮查看。"
        is UpdateState.Downloading -> "正在下载：${value.percent}%"
        is UpdateState.Ready -> "新版已下载，等待系统安装确认。"
        is UpdateState.Error -> "检查或下载失败：${value.message}"
    }, style = MaterialTheme.typography.bodySmall, color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(enabled = state !is UpdateState.Downloading && state != UpdateState.Checking, onClick = {
        if (state is UpdateState.Ready) {
            try { context.findActivity()?.let { updater.install(it, state as UpdateState.Ready) } }
            catch (exception: Exception) { error = exception.message }
        } else { updater.clearResult(); scope.launch { updater.check() } }
    }, modifier = Modifier.fillMaxWidth()) { Text(if (state is UpdateState.Ready) "安装已下载的更新" else "检查更新") }
    TextButton(enabled = state !is UpdateState.Downloading && state != UpdateState.Checking, onClick = { input = source; error = null; edit = true }) { Text("配置更新地址") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("更新描述地址") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, label = { Text("HTTPS latest.json 链接") }, modifier = Modifier.fillMaxWidth())
            Text("默认使用 APK 专用公开发布仓库；可以改为你自己的 HTTPS 发布地址。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { input = BuildConfig.UPDATE_MANIFEST_URL }) { Text("恢复默认地址") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try { updater.source(input); source = updater.source(); edit = false }
        catch (exception: Exception) { error = exception.message ?: "请输入有效 HTTPS 地址" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { edit = false }) { Text("取消") } })
}

private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }
