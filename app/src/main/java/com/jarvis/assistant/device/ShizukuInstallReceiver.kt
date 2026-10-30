package com.jarvis.assistant.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * The last link between the installer and the screen.
 *
 * When a package-installer session is committed, Android answers with `PENDING_USER_ACTION` and an
 * intent for its own confirmation dialog. A background receiver may not leave it unstarted — that is
 * the "installer appears to do nothing" bug — so this starts it with the flag a background context
 * needs. Nothing is installed here; the operator's tap does that.
 */
class ShizukuInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_INSTALL) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent ?: return
            try {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
                // The dialog could not be raised; the status row still says Shizuku is not installed.
            }
        }
    }

    private companion object {
        const val ACTION_INSTALL = "com.jarvis.assistant.INSTALL_SHIZUKU"
    }
}
