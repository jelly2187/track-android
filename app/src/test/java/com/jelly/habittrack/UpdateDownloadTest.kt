package com.jelly.habittrack

import android.app.Application
import android.content.Context
import android.net.Uri
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.jelly.habittrack.update.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDownloadTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun backgroundCompletionStaysHiddenAndVerifiedCacheCanBeReused() = runBlocking {
        DownloadFixture(context).use { fixture ->
            val updater = fixture.updater()
            updater.check()
            val job = updater.download(fixture.release)
            fixture.awaitProgress(updater, 33)
            assertSame(job, updater.download(fixture.release))
            updater.dismissPrompt(fixture.release)
            fixture.advance()
            fixture.awaitProgress(updater, 66)
            assertNull(updater.prompt.value)
            fixture.finish(); job.join()
            assertTrue(updater.state.value is UpdateState.Ready)
            assertNull(updater.prompt.value)
            updater.check(); assertNull(updater.prompt.value)
            updater.check(manual = true); assertEquals(fixture.release, updater.prompt.value)
            val recreated = fixture.updater()
            recreated.download(fixture.release).join()
            assertTrue(recreated.state.value is UpdateState.Ready)
            assertEquals(1, fixture.downloadRequests.get())
        }
    }

    @Test fun wrongSignerCannotProduceAnInstallableReadyState() = runBlocking {
        DownloadFixture(context, trustedArchive = false).use { fixture ->
            fixture.finish()
            val updater = fixture.updater()
            updater.download(fixture.release).join()
            assertTrue((updater.state.value as UpdateState.Error).message.contains("签名"))
            assertFalse(File(context.cacheDir, "updates/download.part.apk").exists())
            assertFalse(File(context.cacheDir, "updates/release-${fixture.release.versionCode}.apk").exists())
        }
    }

    @Test fun cancellingDownloadRemovesPartialFileAndDoesNotOpenAPrompt() = runBlocking {
        DownloadFixture(context).use { fixture ->
            val updater = fixture.updater()
            updater.check()
            val job = updater.download(fixture.release)
            fixture.awaitProgress(updater, 33)
            updater.dismissPrompt(fixture.release)
            updater.cancelDownload(); fixture.finish(); job.join()
            assertTrue(updater.state.value is UpdateState.Available)
            assertNull(updater.prompt.value)
            assertFalse(File(context.cacheDir, "updates/download.part.apk").exists())
        }
    }

    @Test fun wrongPackageAndVersionAreRejectedByArchiveValidation() {
        DownloadFixture(context).use { fixture ->
            val updater = fixture.updater()
            val file = File(context.cacheDir, "updates/download.part.apk")
            val manager = shadowOf(context.packageManager)
            manager.setPackageArchiveInfo(file.absolutePath, fixture.archive("other.app"))
            assertThrows(IllegalArgumentException::class.java) { updater.validateArchive(file, fixture.release) }
            manager.setPackageArchiveInfo(file.absolutePath, fixture.archive().apply { setLongVersionCode(fixture.release.versionCode + 1) })
            assertThrows(IllegalArgumentException::class.java) { updater.validateArchive(file, fixture.release) }
        }
    }
}

/** Controlled bytes and package metadata; this tests policy, not actual Android APK installation. */
internal class DownloadFixture(private val context: Context, trustedArchive: Boolean = true) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(0)
    private val bytes = ByteArray(192 * 1024) { (it % 251).toByte() }
    val release = ReleaseManifest(1, BuildConfig.VERSION_CODE + 1L, "next", "https://example.com/next.apk",
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, bytes.size.toLong(), notes = "测试更新")
    val downloadRequests = AtomicInteger()
    private val signer = Signature("track-test-signer".toByteArray())
    private val archiveSigner = if (trustedArchive) signer else Signature("wrong-signer".toByteArray())
    init {
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES)
        installed.signingInfo = signing(signer)
        val manager = shadowOf(context.packageManager)
        manager.installPackage(installed)
        manager.setPackageArchiveInfo(File(context.cacheDir, "updates/download.part.apk").absolutePath, archive())
        manager.setPackageArchiveInfo(File(context.cacheDir, "updates/release-${release.versionCode}.apk").absolutePath, archive())
    }
    private fun signing(value: Signature) = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(value)) }
    fun archive(packageId: String = context.packageName) = PackageInfo().apply {
        packageName = packageId; versionName = release.versionName; setLongVersionCode(release.versionCode)
        applicationInfo = ApplicationInfo().apply { packageName = packageId; minSdkVersion = 26 }
        signingInfo = signing(archiveSigner)
    }
    fun updater() = AppUpdater(context, UpdateTransport { url ->
        if (url.path.endsWith(".apk")) {
            downloadRequests.incrementAndGet()
            FixtureConnection(url, bytes.size.toLong()) {
                object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (pos >= count) return -1
                        if (pos > 0) check(permits.tryAcquire(10, TimeUnit.SECONDS)) { "Test did not release the download gate" }
                        return super.read(buffer, offset, minOf(length, 64 * 1024))
                    }
                }
            }
        } else {
            val manifest = Gson().toJson(release).toByteArray()
            FixtureConnection(url, manifest.size.toLong()) { ByteArrayInputStream(manifest) }
        }
    }, scope) { file ->
        // FileProvider assumes Android '/' paths. The Windows host uses '\\',
        // so simulate only that platform boundary; actual XML scope is tested separately.
        Uri.Builder().scheme("content").authority("${context.packageName}.updates")
            .appendPath("verified_updates").appendPath(file.name).build()
    }
    fun advance() { permits.release() }
    fun finish() { permits.release(10) }
    suspend fun awaitProgress(updater: AppUpdater, percent: Int) = withTimeout(10_000) {
        while ((updater.state.value as? UpdateState.Downloading)?.percent != percent) delay(5)
    }
    override fun close() { finish(); scope.cancel() }
}

private class FixtureConnection(url: URL, private val size: Long, private val input: () -> InputStream) : HttpsURLConnection(url) {
    override fun getResponseCode() = 200
    override fun getContentLengthLong() = size
    override fun getInputStream() = input()
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun connect() {}
    override fun getCipherSuite() = "TLS_FAKE"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
