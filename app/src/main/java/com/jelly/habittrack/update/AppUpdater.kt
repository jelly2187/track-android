package com.jelly.habittrack.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.jelly.habittrack.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.net.URL
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
}

fun resolvedUpdateSource(saved: String?, defaultSource: String): String {
    val legacy = "https://github.com/jelly2187/track-android-releases/releases/latest/download/latest.json"
    return if (saved.isNullOrBlank() || saved.trim().removeSuffix("/") == legacy) defaultSource else saved
}

/** Open a reviewable web download page; Track never invokes a package installer. */
fun updateDownloadPage(release: ReleaseManifest): String {
    val uri = requireHttps(release.validate().apkUrl)
    val match = Regex("^/([^/]+)/([^/]+)/releases/download/([^/]+)/[^/]+$").matchEntire(uri.rawPath)
    return if (uri.host.equals("github.com", true) && uri.port in listOf(-1, 443) && uri.rawQuery == null && match != null)
        "https://github.com/${match.groupValues[1]}/${match.groupValues[2]}/releases/tag/${match.groupValues[3]}"
    else uri.toString()
}

fun updateDownloadIntent(release: ReleaseManifest) = Intent(Intent.ACTION_VIEW, Uri.parse(updateDownloadPage(release)))
    .addCategory(Intent.CATEGORY_BROWSABLE)

class AppUpdater(private val context: Context, private val transport: UpdateTransport = UpdateTransport()) {
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val gate = Mutex()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutableState.asStateFlow()
    private val mutablePrompt = MutableStateFlow<ReleaseManifest?>(null)
    val prompt = mutablePrompt.asStateFlow()
    init {
        // Remove private APK caches left by the old in-app installer after an upgrade.
        File(context.cacheDir, "updates").let { directory ->
            directory.listFiles()?.filter { it.isFile && it.extension == "apk" }?.forEach { it.delete() }
            directory.delete()
        }
    }
    fun source(): String {
        val saved = preferences.getString("source", null)
        val current = resolvedUpdateSource(saved, BuildConfig.UPDATE_MANIFEST_URL)
        if (saved != null && current != saved) preferences.edit().putString("source", current).apply()
        return current
    }
    fun automatic() = preferences.getBoolean("automatic", true)
    fun automatic(enabled: Boolean) { preferences.edit().putBoolean("automatic", enabled).apply() }
    fun source(url: String) {
        requireHttps(url.trim()); preferences.edit().putString("source", url.trim()).apply()
        mutablePrompt.value = null; mutableState.value = UpdateState.Idle
    }
    private fun noticeKey(release: ReleaseManifest) = "${source()}|${release.versionCode}|${release.sha256.lowercase()}"
    fun dismissPrompt(release: ReleaseManifest) {
        val key = noticeKey(release)
        preferences.edit().putString("dismissed_release", key).apply()
        if (mutablePrompt.value?.let { noticeKey(it) } == key) mutablePrompt.value = null
    }
    fun clearResult() {
        if (mutableState.value != UpdateState.Checking) { mutablePrompt.value = null; mutableState.value = UpdateState.Idle }
    }
    suspend fun check(manual: Boolean = false) {
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
}
