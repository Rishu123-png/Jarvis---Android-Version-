package com.jarvis.assistant.device

import android.content.Context

/**
 * Removes the stored tokens of the account connections that were taken out of JARVIS.
 *
 * Instagram and Google (Drive, Gmail) logins used to keep their tokens in two preference files. The
 * screens that wrote them are gone, so the tokens would otherwise sit on the phone with no way to
 * see or revoke them from inside the app. Deleting the files is idempotent: on a clean install they
 * simply do not exist.
 */
object LegacyAccounts {

    private val FILES = listOf("jarvis_instagram", "jarvis_connectors")

    fun wipe(context: Context) {
        FILES.forEach { name ->
            try {
                context.deleteSharedPreferences(name)
            } catch (t: Throwable) {
                // Nothing to remove, or the system refused. Either way JARVIS keeps running.
            }
        }
    }
}
