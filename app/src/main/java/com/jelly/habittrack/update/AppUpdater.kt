package com.jelly.habittrack.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.jelly.habittrack.BuildConfig
import com.jelly.habittrack.habitApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class ReleaseManifest(
    val schemaVersion: Int,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int = 26,
    val notes: String = ""
) {
    fun validate(): ReleaseManifest {
        require(schemaVersion == 1 && versionCode > 0 && versionName.isNotBlank() && versionName.length <= 80) { "更新描述格式不正确" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "更新包缺少 SHA-256 校验" }
        require(sizeBytes in 1..MAX_APK_BYTES && minSdk in 26..100 && notes.length <= 12000) { "更新包大小或兼容信息不正确" }
        requireHttps(apkUrl)
        return this
    }
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
}

const val MAX_APK_BYTES = 150L * 1024 * 1024
fun requireHttps(raw: String): URI {
    val uri = URI(raw)
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "更新地址必须是有效的 HTTPS 链接" }
    return uri
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Latest : UpdateState
    data class Available(val release: ReleaseManifest) : UpdateState
    data class Downloading(val release: ReleaseManifest, val percent: Int) : UpdateState
    data class Ready(val release: ReleaseManifest, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** HTTPS-only transport. Redirects are validated individually; no credentials are sent. */
class UpdateTransport(private val connectionFactory: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    private fun open(raw: String): HttpsURLConnection {
        var url = requireHttps(raw).toURL()
        repeat(6) {
            val connection = connectionFactory(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Track/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "下载服务器未提供跳转地址" }
                url = requireHttps(URL(url, location).toString()).toURL()
            } else {
                if (status != 200) { connection.disconnect(); error(if (status == 404) "更新渠道尚未发布版本（404）" else "更新服务器暂不可用（$status）") }
                return connection
            }
        }
        error("更新服务器跳转次数过多")
    }
    suspend fun manifest(url: String): ReleaseManifest = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= 128 * 1024) { "更新描述文件过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            Gson().fromJson(bytes.toString(Charsets.UTF_8), ReleaseManifest::class.java).validate()
        } finally { connection.disconnect() }
    }
    suspend fun download(release: ReleaseManifest, temporary: File, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val connection = open(release.apkUrl)
        try {
            val reported = connection.contentLengthLong
            require(reported <= MAX_APK_BYTES && (reported < 0 || reported == release.sizeBytes)) { "更新包大小与发布信息不符" }
            val digest = MessageDigest.getInstance("SHA-256"); var total = 0L; var lastPercent = -1
            connection.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    total += count
                    require(total <= release.sizeBytes && total <= MAX_APK_BYTES) { "更新包超过声明大小" }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    val percent = (total * 100 / release.sizeBytes).toInt()
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            } }
            require(total == release.sizeBytes) { "更新包下载不完整，请重试" }
            require(digest.digest().hex().equals(release.sha256, true)) { "更新包校验失败，请重新下载" }
        } finally { connection.disconnect() }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun verifyUpdateFile(file: File, release: ReleaseManifest) {
    release.validate()
    require(file.isFile && file.length() == release.sizeBytes) { "安装包已失效，请重新下载" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    require(digest.digest().hex().equals(release.sha256, true)) { "更新包校验失败，请重新下载" }
}

fun resolvedUpdateSource(saved: String?, defaultSource: String): String {
    val legacy = "https://github.com/jelly2187/track-android-releases/releases/latest/download/latest.json"
    return if (saved.isNullOrBlank() || saved.trim().removeSuffix("/") == legacy) defaultSource else saved
}

class AppUpdater internal constructor(
    private val context: Context,
    private val transport: UpdateTransport = UpdateTransport(),
    private val downloadScope: CoroutineScope? = null,
    private val uriProvider: (File) -> Uri = { FileProvider.getUriForFile(context, "${context.packageName}.updates", it) }
) {
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val gate = Mutex()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutableState.asStateFlow()
    private val mutablePrompt = MutableStateFlow<ReleaseManifest?>(null)
    val prompt = mutablePrompt.asStateFlow()
    private var downloadJob: Job? = null
    fun source(): String {
        val saved = preferences.getString("source", null)
        val current = resolvedUpdateSource(saved, BuildConfig.UPDATE_MANIFEST_URL)
        if (saved != null && current != saved) preferences.edit().putString("source", current).apply()
        return current
    }
    fun automatic() = preferences.getBoolean("automatic", true)
    fun automatic(enabled: Boolean) { preferences.edit().putBoolean("automatic", enabled).apply() }
    fun source(url: String) {
        check(downloadJob?.isActive != true) { "请先取消正在进行的下载" }
        requireHttps(url.trim()); preferences.edit().putString("source", url.trim()).apply()
        mutablePrompt.value = null; mutableState.value = UpdateState.Idle
    }
    private fun noticeKey(release: ReleaseManifest) = "${source()}|${release.versionCode}|${release.sha256.lowercase()}"
    fun dismissPrompt(release: ReleaseManifest) {
        val key = noticeKey(release)
        preferences.edit().putString("dismissed_release", key).apply()
        if (mutablePrompt.value?.let { noticeKey(it) } == key) mutablePrompt.value = null
    }
    fun reviewUpdate() {
        mutablePrompt.value = when (val current = mutableState.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Downloading -> current.release
            is UpdateState.Ready -> current.release
            else -> null
        }
    }
    fun clearResult() {
        if (mutableState.value != UpdateState.Checking && mutableState.value !is UpdateState.Downloading && mutableState.value !is UpdateState.Ready) {
            mutablePrompt.value = null; mutableState.value = UpdateState.Idle
        }
    }
    suspend fun check(manual: Boolean = false) {
        if (mutableState.value is UpdateState.Downloading || mutableState.value is UpdateState.Ready) {
            if (manual) reviewUpdate()
            return
        }
        if (gate.isLocked) return
        gate.withLock {
            mutableState.value = UpdateState.Checking
            try {
                val release = transport.manifest(source())
                mutableState.value = when {
                    release.versionCode <= BuildConfig.VERSION_CODE -> UpdateState.Latest
                    release.minSdk > Build.VERSION.SDK_INT -> UpdateState.Error("新版需要 Android API ${release.minSdk}，当前手机不兼容")
                    else -> UpdateState.Available(release)
                }
                // A check result is not a command to open a dialog. User dismissal
                // survives new checks, Activity recreation, and process restarts.
                mutablePrompt.value = if (mutableState.value is UpdateState.Available &&
                    (manual || preferences.getString("dismissed_release", null) != noticeKey(release))) release else null
            } catch (cancelled: CancellationException) { mutableState.value = UpdateState.Idle; throw cancelled }
            catch (error: Exception) { mutableState.value = UpdateState.Error(error.message ?: "暂时无法检查更新，请稍后重试") }
        }
    }

    fun download(release: ReleaseManifest): Job {
        downloadJob?.takeIf { it.isActive }?.let { return it }
        return (downloadScope ?: context.habitApp.scope).launch {
            gate.withLock {
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val temporary = File(directory, "download.part.apk")
                val completed = File(directory, "release-${release.versionCode}.apk")
                try {
                    release.validate()
                    require(release.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)) { "此版本无需安装" }
                    if (completed.isFile) {
                        val cached = runCatching { verifyUpdateFile(completed, release); validateArchive(completed, release) }.isSuccess
                        if (cached) { mutableState.value = UpdateState.Ready(release, completed); return@withLock }
                    }
                    directory.listFiles()?.filter { it.name == "download.part.apk" || it.name.matches(Regex("release-\\d+\\.apk")) }?.forEach { it.delete() }
                    mutableState.value = UpdateState.Downloading(release, 0)
                    transport.download(release, temporary) { mutableState.value = UpdateState.Downloading(release, it) }
                    validateArchive(temporary, release)
                    check(temporary.renameTo(completed)) { "无法保存已验证的安装包" }
                    mutableState.value = UpdateState.Ready(release, completed)
                    // Download progress and completion never request a popup. A hidden
                    // download stays hidden until the user explicitly reviews it.
                } catch (cancelled: CancellationException) { mutableState.value = UpdateState.Available(release); throw cancelled }
                catch (error: Exception) { mutableState.value = UpdateState.Error(error.message ?: "下载失败，请重试") }
                finally { temporary.delete() }
            }
        }.also { downloadJob = it }
    }
    fun cancelDownload() { downloadJob?.cancel() }
    suspend fun install(activity: android.app.Activity, ready: UpdateState.Ready): Boolean {
        try { withContext(Dispatchers.IO) {
            require(ready.file.canonicalFile.parentFile == File(context.cacheDir, "updates").canonicalFile) { "更新文件位置无效，请重新下载" }
            require(ready.release.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)) { "此版本无需安装" }
            verifyUpdateFile(ready.file, ready.release)
            validateArchive(ready.file, ready.release)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (mutableState.value == ready) mutableState.value = UpdateState.Available(ready.release)
            throw error
        }
        return withContext(Dispatchers.Main.immediate) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                false
            } else {
                val uri = uriProvider(ready.file)
                require(uri.scheme == "content" && uri.authority == "${context.packageName}.updates") { "更新文件分享地址无效" }
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                true
            }
        }
    }
    @Suppress("DEPRECATION")
    internal fun validateArchive(file: File, release: ReleaseManifest) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法识别下载的 APK")
        val installed = manager.getPackageInfo(context.packageName, flags)
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        require(candidate.packageName == context.packageName && code == release.versionCode && candidate.versionName == release.versionName && candidate.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true) { "安装包的应用标识、版本或兼容信息不匹配" }
        fun signatures(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
        val trusted = signatures(installed)
        require(trusted.isNotEmpty() && signatures(candidate) == trusted) { "安装包签名不匹配，已阻止安装" }
    }
}
