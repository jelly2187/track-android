package com.jelly.habittrack

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.jelly.habittrack.data.*
import com.jelly.habittrack.reminder.SystemReceiver
import com.jelly.habittrack.update.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppSafetyTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Suppress("DEPRECATION")
    @Test fun manifestDoesNotRequestInstallerForegroundOrUnrelatedSensitivePermissions() {
        val permissions = requireNotNull(app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions).toSet()
        listOf("REQUEST_INSTALL_PACKAGES", "INSTALL_PACKAGES", "FOREGROUND_SERVICE", "WRITE_SETTINGS", "WRITE_SECURE_SETTINGS",
            "SEND_SMS", "READ_SMS", "READ_CONTACTS", "QUERY_ALL_PACKAGES", "SYSTEM_ALERT_WINDOW", "MANAGE_EXTERNAL_STORAGE"
        ).forEach { assertFalse("Unexpected permission: $it", "android.permission.$it" in permissions) }
        assertFalse(app.packageManager.getReceiverInfo(ComponentName(app, SystemReceiver::class.java), 0).exported)
    }

    @Test fun updateCheckOnlyFetchesManifestAndDownloadActionOnlyOpensHttpsPage() = runBlocking {
        val release = ReleaseManifest(1, BuildConfig.VERSION_CODE + 1L, "next", "https://github.com/jelly2187/track-android/releases/download/vNext/Track.apk", "a".repeat(64), 100)
        val requests = mutableListOf<String>()
        val updater = AppUpdater(app, UpdateTransport { url ->
            requests += url.toString()
            SafetyManifestConnection(url, Gson().toJson(release).toByteArray())
        })
        updater.check()
        assertTrue(updater.state.value is UpdateState.Available)
        assertEquals(listOf(updater.source()), requests)
        val intent = updateDownloadIntent(release)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/jelly2187/track-android/releases/tag/vNext", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.type)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun updateMigrationOnlyDeletesOldPrivateApkCache() {
        val directory = File(app.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, "release-4.apk").apply { writeText("old update") }
        val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        AppUpdater(app)
        assertFalse(apk.exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun repeatOptOutSurvivesSettingsAndBackupRoundTrip() {
        val preferences = Preferences(app)
        preferences.write(ReminderSettings(interval = 0))
        assertEquals(0, preferences.read().interval)
        assertEquals(0, Backup.decode(Backup.encode(Snapshot(), preferences.read())).settings.interval)
    }
}

private class SafetyManifestConnection(url: URL, private val bytes: ByteArray) : HttpsURLConnection(url) {
    override fun getResponseCode() = 200
    override fun getInputStream() = ByteArrayInputStream(bytes)
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun connect() {}
    override fun getCipherSuite() = "TLS_FAKE"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
