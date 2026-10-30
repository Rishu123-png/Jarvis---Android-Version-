package com.jarvis.assistant

import com.jarvis.assistant.device.ShizukuInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled Shizuku APK.
 *
 * The download itself cannot run on the JVM, but the checks that decide whether a downloaded file is
 * an APK at all can — and they are what stops an HTML error page from being handed to the installer.
 */
class ShizukuInstallerTest {

    @Test
    fun aRealApkStartsWithTheZipMagic() {
        // Every APK is a ZIP: 'P' 'K' 0x03 0x04.
        assertTrue(ShizukuInstaller.looksLikeApk(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertTrue(ShizukuInstaller.looksLikeApk(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
    }

    @Test
    fun anErrorPageIsNotAnApk() {
        val html = "<!DOCTYPE html><html>404</html>".toByteArray()
        assertFalse(ShizukuInstaller.looksLikeApk(html))
        assertFalse(ShizukuInstaller.looksLikeApk("PK".toByteArray()))
        assertFalse(ShizukuInstaller.looksLikeApk(ByteArray(0)))
    }

    @Test
    fun thePinnedDownloadMatchesThePinnedVersion() {
        // A version bump that only touches one of the two constants is caught here.
        assertTrue(ShizukuInstaller.DOWNLOAD_URL.contains(ShizukuInstaller.VERSION))
        assertTrue(ShizukuInstaller.DOWNLOAD_URL.endsWith(".apk"))
        assertTrue(ShizukuInstaller.DOWNLOAD_URL.startsWith("https://github.com/"))
        assertTrue(ShizukuInstaller.APK_BYTES > 1_000_000L)
    }

    @Test
    fun theShizukuPackageNameIsTheManagerApp() {
        // `privileged.api` is the provider library inside other apps, not the manager.
        assertTrue(ShizukuInstaller.PACKAGE == "moe.shizuku.manager")
    }
}
