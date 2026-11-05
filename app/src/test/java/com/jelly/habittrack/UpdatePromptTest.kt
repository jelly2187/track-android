package com.jelly.habittrack

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.jelly.habittrack.ui.HabitTheme
import com.jelly.habittrack.ui.UpdateHost
import com.jelly.habittrack.update.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdatePromptStateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun dismissalSurvivesAutomaticRechecksAndUpdaterRecreation() = runBlocking {
        val fixture = PromptFixture(context)
        val updater = fixture.updater()
        updater.check()
        assertEquals(fixture.release.get(), updater.prompt.value)
        updater.dismissPrompt(fixture.release.get())
        repeat(3) { updater.check(); assertNull(updater.prompt.value) }
        assertTrue(updater.state.value is UpdateState.Available)
        val recreated = fixture.updater()
        recreated.check()
        assertNull(recreated.prompt.value)
        assertTrue(recreated.state.value is UpdateState.Available)
    }

    @Test fun explicitManualCheckCanReviewADismissedVersion() = runBlocking {
        val fixture = PromptFixture(context)
        val updater = fixture.updater()
        updater.check(); updater.dismissPrompt(fixture.release.get())
        updater.check(manual = true)
        assertEquals(fixture.release.get(), updater.prompt.value)
        updater.dismissPrompt(fixture.release.get())
        updater.check()
        assertNull(updater.prompt.value)
    }

    @Test fun newReleaseAndDifferentChannelAreNotSuppressedByPreviousDismissal() = runBlocking {
        val fixture = PromptFixture(context)
        val updater = fixture.updater()
        updater.check()
        val previous = fixture.release.get()
        updater.dismissPrompt(previous)
        fixture.release.set(previous.copy(versionCode = previous.versionCode + 1, versionName = "newer", sha256 = "b".repeat(64)))
        updater.check()
        assertEquals(fixture.release.get(), updater.prompt.value)
        updater.dismissPrompt(previous) // An old UI callback must not dismiss a newer release.
        assertEquals(fixture.release.get(), updater.prompt.value)
        updater.dismissPrompt(fixture.release.get())
        updater.source("https://example.com/another/latest.json")
        updater.check()
        assertEquals(fixture.release.get(), updater.prompt.value)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdatePromptUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun laterKeepsAppUsableAcrossNewResultsAndHostRecreation() {
        val fixture = PromptFixture(compose.activity)
        val updater = fixture.updater()
        val generation = mutableIntStateOf(0)
        compose.setContent {
            HabitTheme("light") {
                var count by remember { mutableIntStateOf(0) }
                Column { Button(onClick = { count++ }) { Text("记录 $count") } }
                key(generation.intValue) { UpdateHost(updater) }
            }
        }
        waitForPrompt()
        compose.onNodeWithText("稍后").performClick()
        runBlocking { repeat(3) { updater.check() } }
        compose.runOnIdle { generation.intValue++ }
        compose.waitForIdle()
        compose.onNodeWithText("稍后").assertDoesNotExist()
        compose.onNodeWithText("记录 0").performClick()
        compose.onNodeWithText("记录 1").assertIsDisplayed()
        runBlocking { updater.check(manual = true) }
        waitForPrompt()
    }

    @Test fun backgroundDownloadProgressAndCompletionDoNotReopenDialog() {
        DownloadFixture(compose.activity).use { fixture ->
            val updater = fixture.updater()
            val generation = mutableIntStateOf(0)
            compose.setContent { HabitTheme("light") { key(generation.intValue) { UpdateHost(updater) } } }
            waitForPrompt()
            compose.onNodeWithText("下载更新").performClick()
            runBlocking { fixture.awaitProgress(updater, 33) }
            compose.onNodeWithText("后台下载").performClick()
            fixture.advance()
            runBlocking { fixture.awaitProgress(updater, 66) }
            compose.waitForIdle()
            compose.onNodeWithText("后台下载").assertDoesNotExist()
            fixture.finish()
            compose.waitUntil(10_000) { updater.state.value is UpdateState.Ready }
            compose.runOnIdle { generation.intValue++ }
            compose.waitForIdle()
            compose.onNodeWithText("安装更新").assertDoesNotExist()
            assertNull(updater.prompt.value)
            runBlocking { updater.check(manual = true) }
            compose.onNodeWithText("安装更新").assertIsDisplayed()
        }
    }

    @Test fun longReleaseNotesKeepDismissAndDownloadButtonsReachable() {
        val fixture = PromptFixture(compose.activity)
        fixture.release.set(fixture.release.get().copy(notes = "较长的版本说明\n".repeat(400)))
        val updater = fixture.updater()
        compose.setContent { HabitTheme("light") { UpdateHost(updater) } }
        waitForPrompt()
        compose.onNodeWithText("稍后").assertIsDisplayed()
        compose.onNodeWithText("下载更新").assertIsDisplayed()
        compose.onNodeWithText("稍后").performClick()
        compose.onNodeWithText("稍后").assertDoesNotExist()
    }

    @Test fun installRequestsPermissionThenUsesTheReadOnlySystemInstaller() {
        DownloadFixture(compose.activity).use { fixture ->
            fixture.finish()
            val updater = fixture.updater()
            updater.automatic(false)
            runBlocking { updater.download(fixture.release).join(); updater.check(manual = true) }
            compose.setContent { HabitTheme("light") { UpdateHost(updater) } }
            val manager = shadowOf(compose.activity.packageManager)
            manager.setCanRequestPackageInstalls(false)
            compose.onNodeWithText("安装更新").performClick()
            compose.waitUntil(10_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
            val permission = shadowOf(compose.activity).nextStartedActivity
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.action)
            assertEquals("package:${compose.activity.packageName}", permission.dataString)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("请允许 Track 安装应用，返回后再次点击“安装更新”。").fetchSemanticsNodes().isNotEmpty() }
            manager.setCanRequestPackageInstalls(true)
            compose.onNodeWithText("安装更新").performClick()
            compose.waitUntil(10_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
            val install = shadowOf(compose.activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, install.action)
            assertEquals("application/vnd.android.package-archive", install.type)
            assertEquals("content", install.data!!.scheme)
            assertEquals("${compose.activity.packageName}.updates", install.data!!.authority)
            assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, install.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            compose.waitForIdle()
            assertNull(updater.prompt.value)
        }
    }

    @Test fun modifiedReadyFileCannotStartTheSystemInstaller() {
        DownloadFixture(compose.activity).use { fixture ->
            fixture.finish()
            val updater = fixture.updater()
            updater.automatic(false)
            runBlocking { updater.download(fixture.release).join(); updater.check(manual = true) }
            val file = (updater.state.value as UpdateState.Ready).file
            file.writeBytes(file.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
            compose.setContent { HabitTheme("light") { UpdateHost(updater) } }
            shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(true)
            compose.onNodeWithText("安装更新").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("更新包校验失败，请重新下载").fetchSemanticsNodes().isNotEmpty() }
            assertNull(shadowOf(compose.activity).peekNextStartedActivity())
            compose.onNodeWithText("下载更新").assertIsDisplayed()
        }
    }

    private fun waitForPrompt() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("稍后").fetchSemanticsNodes().isNotEmpty() }
    }
}

private class PromptFixture(private val context: Context) {
    val release = AtomicReference(ReleaseManifest(1, BuildConfig.VERSION_CODE + 1L, "next",
        "https://github.com/jelly2187/track-android/releases/download/vNext/Track.apk", "a".repeat(64), 100, notes = "测试更新"))
    fun updater() = AppUpdater(context, UpdateTransport { url -> PromptConnection(url, Gson().toJson(release.get()).toByteArray()) })
}

private class PromptConnection(url: URL, private val bytes: ByteArray) : HttpsURLConnection(url) {
    override fun getResponseCode() = 200
    override fun getInputStream() = ByteArrayInputStream(bytes)
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun connect() {}
    override fun getCipherSuite() = "TLS_FAKE"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
