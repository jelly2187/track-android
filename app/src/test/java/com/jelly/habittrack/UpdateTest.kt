package com.jelly.habittrack

import com.google.gson.Gson
import com.jelly.habittrack.update.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class UpdateTest {
    @Test fun migrateLegacySourceAndPreserveCustomAddress() {
        val current = "https://github.com/jelly2187/track-android/releases/latest/download/latest.json"
        val legacy = "https://github.com/jelly2187/track-android-releases/releases/latest/download/latest.json"
        assertEquals(current, resolvedUpdateSource(null, current))
        assertEquals(current, resolvedUpdateSource(legacy, current))
        assertEquals(current, resolvedUpdateSource("  $legacy/  ", current))
        val custom = "https://example.com/track/latest.json"
        assertEquals(custom, resolvedUpdateSource(custom, current))
    }
    private val data = "signed APK test fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun release() = ReleaseManifest(1, 3, "0.1.2", "https://example.com/app.apk", hash, data.size.toLong(), 26, "修复界面")
    @Test fun compareVersionCodesAndSdk() {
        assertTrue(release().validate().newerThan(2, 34))
        assertFalse(release().newerThan(3, 34)); assertFalse(release().newerThan(4, 34))
        assertFalse(release().copy(minSdk = 35).newerThan(2, 34))
    }
    @Test fun rejectInvalidMetadataAndInsecureUrls() {
        listOf("http://example.com/latest.json", "file:///sdcard/a.apk", "https://user:pass@example.com/a", "https:///no-host").forEach { url -> assertThrows(Exception::class.java) { requireHttps(url) } }
        assertThrows(IllegalArgumentException::class.java) { release().copy(sha256 = "bad").validate() }
        assertThrows(IllegalArgumentException::class.java) { release().copy(sizeBytes = MAX_APK_BYTES + 1).validate() }
        assertThrows(IllegalArgumentException::class.java) { release().copy(schemaVersion = 9).validate() }
    }
    @Test fun parsePublishedManifest() = runBlocking {
        val bytes = Gson().toJson(release()).toByteArray()
        val result = UpdateTransport { FakeConnection(it, bytes) }.manifest("https://example.com/latest.json")
        assertEquals(release(), result)
    }
    @Test fun rejectOversizedManifest() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { UpdateTransport { FakeConnection(it, ByteArray(128 * 1024 + 1)) }.manifest("https://example.com/latest.json") } }
    }
    @Test fun rejectHttpsDowngradeRedirect() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { UpdateTransport { FakeConnection(it, byteArrayOf(), 302, "http://example.com/insecure") }.manifest("https://example.com/latest.json") } }
    }
    @Test fun githubUpdatesOpenTheReviewableReleasePage() {
        val url = "https://github.com/jelly2187/track-android/releases/download/v0.1.4/Track-v0.1.4.apk"
        assertEquals("https://github.com/jelly2187/track-android/releases/tag/v0.1.4", updateDownloadPage(release().copy(apkUrl = url)))
    }
    @Test fun customDownloadLinksArePreservedWithoutTreatingLookalikesAsGithub() {
        assertEquals(release().apkUrl, updateDownloadPage(release()))
        val lookalike = "https://github.com.example.com/a/b/releases/download/v1/app.apk"
        assertEquals(lookalike, updateDownloadPage(release().copy(apkUrl = lookalike)))
        assertThrows(IllegalArgumentException::class.java) { updateDownloadPage(release().copy(apkUrl = "file:///sdcard/app.apk")) }
    }
}

private class FakeConnection(url: URL, private val bytes: ByteArray, private val code: Int = 200, private val location: String? = null, private val reportedSize: Long = bytes.size.toLong()) : HttpsURLConnection(url) {
    override fun getResponseCode() = code
    override fun getInputStream() = ByteArrayInputStream(bytes)
    override fun getContentLengthLong() = reportedSize
    override fun getHeaderField(name: String?) = if (name == "Location") location else null
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun connect() {}
    override fun getCipherSuite() = "TLS_FAKE"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
