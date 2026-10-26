package com.jarvis.assistant.device

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import com.jarvis.assistant.data.InstalledApp

/**
 * Home-app plumbing: who currently owns the Home role, how to ask for it, and how to launch
 * another app the way a launcher must (with NEW_TASK, since launcher code is not in a task).
 */
object LauncherStatus {

    /**
     * True when JARVIS is the current Home app.
     *
     * Prefers the Android 10+ RoleManager check because it is unambiguous, and falls back to
     * resolving the HOME intent for devices/OEMs where the role is not reported reliably.
     */
    fun isDefaultLauncher(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) return true
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = try {
            context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (t: Throwable) {
            null
        }
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * The system dialog that asks the user to make JARVIS the Home app. Null when the device
     * does not expose the role; callers should fall back to [homeSettingsIntent].
     */
    fun homeRoleRequestIntent(context: Context): Intent? {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        return if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            null
        }
    }

    /** The list of installed Home apps in system settings — the universal fallback. */
    fun homeSettingsIntent(): Intent =
        Intent(Settings.ACTION_HOME_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** This app's own system info page. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Launches [app]. Launcher code runs outside a task, so `FLAG_ACTIVITY_NEW_TASK` is
     * mandatory; a fallback intent covers apps that expose a launch intent only.
     */
    fun launch(context: Context, app: InstalledApp): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        try {
            context.startActivity(intent)
            return true
        } catch (t: Throwable) {
            // Fall through to the package's generic launch intent.
        }

        val fallback = try {
            context.packageManager.getLaunchIntentForPackage(app.packageName)
        } catch (t: Throwable) {
            null
        }
        if (fallback != null) {
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(fallback)
                return true
            } catch (t: Throwable) {
                // Report below.
            }
        }

        Toast.makeText(context, "Cannot open ${app.label}", Toast.LENGTH_SHORT).show()
        return false
    }

    /** Opens a system settings screen, tolerating devices that do not implement it. */
    fun openSystemScreen(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(context, "Not available on this device", Toast.LENGTH_SHORT).show()
        }
    }
}
