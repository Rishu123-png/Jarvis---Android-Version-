package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs the one app JARVIS cannot work without.
 *
 * Shizuku is what turns "wifi off" into a switch that actually moves: it lends this app the same
 * identity `adb shell` has. Asking the operator to find a second download, install it, and come back
 * was the only rough edge left in that path, so the Shizuku APK now travels inside the JARVIS APK.
 *
 * Three honest notes about what this can and cannot do:
 *
 *  * **Android always shows its own install screen.** No app may install another silently, and that
 *    is a good thing. This opens the system installer with one tap to confirm.
 *  * **"Install unknown apps" must be on for JARVIS.** If it is off the system page is opened first,
 *    and the reason is returned rather than leaving a button that appears to do nothing.
 *  * **The bundle is pinned.** It is Shizuku [VERSION], Apache-2.0, fetched at build time from the
 *    official GitHub release and checked by size. A build with no network at build time simply ships
 *    without it, and this class downloads it when asked.
 */
object ShizukuInstaller {

    /** Shizuku's own package name. */
    const val PACKAGE = "moe.shizuku.manager"

    /** Where the build puts the APK inside the JARVIS APK, when it managed to fetch it. */
    private const val ASSET_DIR = "shizuku"
    private const val ASSET_FILE = "shizuku.apk"

    /** Pinned release. Keep in step with the `shizukuVersion` in app/build.gradle.kts. */
    const val VERSION = "13.6.0"
    const val APK_BYTES = 2_571_773L
    const val DOWNLOAD_URL =
        "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/" +
            "shizuku-v13.6.0.r1086.2650830c-release.apk"

    private const val ACTION_INSTALL = "com.jarvis.assistant.INSTALL_SHIZUKU"
    private const val SESSION_NAME = "shizuku.apk"

    sealed interface Outcome {
        data object AlreadyInstalled : Outcome
        /** The "install unknown apps" page was opened; the operator allows it, then asks again. */
        data object NeedsPermission : Outcome
        /** The system installer is on screen waiting for one tap. */
        data object InstallerOpened : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /** True when this APK carries the Shizuku APK inside it. */
    fun isBundled(context: Context): Boolean = try {
        context.assets.list(ASSET_DIR)?.any { it == ASSET_FILE } == true
    } catch (t: Throwable) {
        false
    }

    fun needsInstallPermission(context: Context): Boolean = try {
        !context.packageManager.canRequestPackageInstalls()
    } catch (t: Throwable) {
        // Below Android 8 there is no such permission and installing simply works.
        false
    }

    fun openInstallPermission(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (ignored: Throwable) {
                // Nothing else to try; the caller reports it.
            }
        }
    }

    /** Opens Shizuku itself, so the operator can start the service and authorise JARVIS. */
    fun openShizuku(context: Context): Boolean {
        val intent = try {
            context.packageManager.getLaunchIntentForPackage(PACKAGE)
        } catch (t: Throwable) {
            null
        } ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun pendingIntent(context: Context) = android.app.PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ShizukuInstallReceiver::class.java).setAction(ACTION_INSTALL),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Brings Shizuku to the phone: bundled copy first, download second, system installer always.
     * Nothing here claims success — the installer is what actually installs.
     */
    suspend fun install(context: Context, allowDownload: Boolean = true): Outcome = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext Outcome.AlreadyInstalled
        if (needsInstallPermission(context)) {
            openInstallPermission(context)
            return@withContext Outcome.NeedsPermission
        }

        if (isBundled(context)) {
            val opened = try {
                commitSession(context) { output ->
                    context.assets.open("$ASSET_DIR/$ASSET_FILE").use { it.copyTo(output) }
                }
            } catch (t: Throwable) {
                null
            }
            if (opened != null) return@withContext opened
            // The bundled copy failed for a reason we cannot see; the download may still work.
        }

        if (!allowDownload) {
            return@withContext Outcome.Failed("Shizuku is not in this build and I was told not to download it.")
        }

        val file = try {
            download(context)
        } catch (t: Throwable) {
            return@withContext Outcome.Failed("I could not download Shizuku (${t.javaClass.simpleName}).")
        } ?: return@withContext Outcome.Failed("I could not download Shizuku. Check the connection.")

        try {
            commitSession(context) { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: Outcome.Failed("The installer did not start.")
        } finally {
            file.delete()
        }
    }

    /**
     * Writes one APK into a package-installer session and commits it. The system shows its own
     * confirmation; this returns as soon as that is queued.
     */
    private fun commitSession(context: Context, write: (java.io.OutputStream) -> Unit): Outcome? = try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Declaring the package stops a session from being pointed at some other app by accident.
        params.setAppPackageName(PACKAGE)

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(SESSION_NAME, 0, -1).use { output ->
                write(output)
                session.fsync(output)
            }
            session.commit(pendingIntent(context))
        }
        Outcome.InstallerOpened
    } catch (t: Throwable) {
        Outcome.Failed("The installer refused to start (${t.javaClass.simpleName}).")
    }

    /** Fetches the pinned release, following GitHub's redirects by hand. */
    private fun download(context: Context): File? {
        var target = DOWNLOAD_URL

        for (hop in 0 until 6) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/octet-stream")
            }

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    target = connection.getHeaderField("Location") ?: return null
                    continue
                }
                if (code !in 200..299) return null

                val file = File(context.cacheDir, "shizuku-download.apk")
                var written = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (written > APK_BYTES * 2) break
                        }
                    }
                }

                // A short file, or an HTML error page saved as .apk, is worse than no file: the
                // installer would fail obscurely. Both are caught here instead.
                val head = ByteArray(4)
                val read = file.inputStream().use { it.read(head) }
                if (written < APK_BYTES / 2 || read < 4 || !looksLikeApk(head)) {
                    file.delete()
                    return null
                }
                return file
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /** APKs are only installable if they look like one; used before wasting a session attempt. */
    fun looksLikeApk(bytes: ByteArray): Boolean = try {
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    } catch (t: Throwable) {
        false
    }

}
