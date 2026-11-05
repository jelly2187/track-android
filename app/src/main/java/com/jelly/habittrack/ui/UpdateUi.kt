package com.jelly.habittrack.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

@Composable fun UpdateHost(updater: AppUpdater = LocalContext.current.habitApp.updater) {
    val context = LocalContext.current
    val release by updater.prompt.collectAsStateWithLifecycle()
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    LaunchedEffect(updater) { if (updater.automatic()) updater.check() }
    LaunchedEffect(release) { error = null }
    release?.let { update -> AlertDialog(onDismissRequest = { if (!installing) updater.dismissPrompt(update) }, title = { Text("Track ${update.versionName}") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(update.notes.ifBlank { "有新的应用版本可用。" })
            Text("${"%.1f".format(update.sizeBytes / 1024.0 / 1024.0)} MB · 覆盖安装保留现有习惯和记录", style = MaterialTheme.typography.bodySmall)
            when (val value = state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { value.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("下载中 ${value.percent}%")
                    Text("可收起进度继续使用，在设置中查看下载或安装。", style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.Ready -> Text(if (installing) "正在校验安装包…" else "下载与校验已完成。点击后由系统确认安装；首次需要允许 Track 安装应用，授权后返回再次点击安装。", style = MaterialTheme.typography.bodySmall)
                is UpdateState.Error -> Text(value.message, color = MaterialTheme.colorScheme.error)
                else -> Text("确认后在 App 内下载更新，校验通过后由你确认系统安装。", style = MaterialTheme.typography.bodySmall)
            }
            Text("收起后不会因下载进度或完成状态反复弹出；可随时在设置中查看。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        when (val value = state) {
            is UpdateState.Downloading -> TextButton(onClick = { updater.cancelDownload(); updater.dismissPrompt(update) }) { Text("取消下载") }
            is UpdateState.Ready -> TextButton(enabled = !installing, onClick = {
                installing = true; error = null
                scope.launch {
                    try {
                        val activity = context.findActivity() ?: throw IllegalStateException("请返回主界面安装")
                        if (updater.install(activity, value)) updater.dismissPrompt(update)
                        else error = "请允许 Track 安装应用，返回后再次点击“安装更新”。"
                    } catch (exception: Exception) { error = exception.message ?: "无法启动系统安装器" }
                    finally { installing = false }
                }
            }) { Text("安装更新") }
            else -> TextButton(onClick = { error = null; updater.download(update) }) { Text(if (state is UpdateState.Error) "重新下载" else "下载更新") }
        }
    }, dismissButton = { TextButton(enabled = !installing, onClick = { updater.dismissPrompt(update) }) { Text(if (state is UpdateState.Downloading) "后台下载" else "稍后") } }) }
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
        UpdateState.Idle -> "自动检查只获取版本信息，点击后才下载；不上传习惯记录。"
        UpdateState.Checking -> "正在检查更新…"
        UpdateState.Latest -> "当前已是最新版本。"
        is UpdateState.Available -> "发现新版 ${value.release.versionName}，点击下方按钮查看。"
        is UpdateState.Downloading -> "下载中 ${value.percent}% · 可继续使用应用。"
        is UpdateState.Ready -> "新版已下载并校验，可查看并安装。"
        is UpdateState.Error -> "检查或下载失败：${value.message}"
    }, style = MaterialTheme.typography.bodySmall, color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(enabled = state != UpdateState.Checking, onClick = {
        updater.clearResult(); scope.launch { updater.check(manual = true) }
    }, modifier = Modifier.fillMaxWidth()) { Text(when (state) { is UpdateState.Downloading -> "查看下载进度"; is UpdateState.Ready -> "查看已下载更新"; else -> "检查更新" }) }
    if (state is UpdateState.Downloading) {
        LinearProgressIndicator(progress = { (state as UpdateState.Downloading).percent / 100f }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { updater.cancelDownload() }) { Text("取消下载") }
    }
    TextButton(enabled = state != UpdateState.Checking && state !is UpdateState.Downloading, onClick = { input = source; error = null; edit = true }) { Text("配置更新地址") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("更新描述地址") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, label = { Text("HTTPS latest.json 链接") }, modifier = Modifier.fillMaxWidth())
            Text("默认使用 Track 开源仓库的发布信息；自定义渠道的安装包也必须通过文件、包名和签名校验。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { input = BuildConfig.UPDATE_MANIFEST_URL }) { Text("恢复默认地址") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try { updater.source(input); source = updater.source(); edit = false }
        catch (exception: Exception) { error = exception.message ?: "请输入有效 HTTPS 地址" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { edit = false }) { Text("取消") } })
}

private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }
