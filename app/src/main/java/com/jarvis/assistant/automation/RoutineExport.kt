package com.jarvis.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * A routine, outside the app.
 *
 * Two questions live here, and they are the same question asked in two directions: what does this
 * routine look like to something that is not JARVIS (share), and what happens when a link points at
 * one (run)?
 *
 * The link is `jarvis://run/<id>`. It is not a web URL and nothing resolves it on a browser — it is a
 * handle the operator can drop into a shortcut, a widget, a Tasker action or a QR code, and the app
 * answers it. The exported text is the routine's own JSON, so what is shared can be read by a human
 * and fed back to [AutomationStore] by a machine.
 */
object RoutineLinks {

    const val SCHEME = "jarvis"
    const val HOST_RUN = "run"

    fun linkFor(workflow: Workflow): String = "$SCHEME://$HOST_RUN/${workflow.id}"

    /**
     * Opens the share sheet with the routine's JSON and its link.
     *
     * Plain text, deliberately: a file would need a provider, a MIME type and a cache directory, and
     * the payload is a few hundred bytes of JSON that anyone can paste into a message.
     */
    fun share(context: Context, workflow: Workflow) {
        val body = buildString {
            appendLine(workflow.name)
            appendLine()
            appendLine("${workflow.trigger.display} · ${workflow.totalSteps} steps")
            appendLine(linkFor(workflow))
            appendLine()
            append(workflow.toJson().toString(2))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, workflow.name)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(
            Intent.createChooser(send, "Share routine").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Runs the routine a link points at.
     *
     * Returns false when the id is unknown, so the caller can say so instead of opening the board onto
     * a routine that no longer exists — ids are stable across edits, but a link can outlive one.
     */
    fun run(context: Context, id: String?): Boolean {
        val wanted = id?.takeIf { it.isNotBlank() } ?: return false
        val workflow = AutomationStore.load(context).firstOrNull { it.id == wanted } ?: return false
        if (!workflow.enabled) {
            Toast.makeText(
                context,
                "\"${workflow.name}\" is switched off — enable it before a link can run it.",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        AutomationEngine.run(context, workflow, triggerLabel = "Link")
        return true
    }

    /** True when this URI is one of ours and names a run. */
    fun handles(uri: Uri?): Boolean =
        uri != null && uri.scheme == SCHEME && uri.host == HOST_RUN

    fun idFrom(uri: Uri?): String? = if (handles(uri)) uri?.lastPathSegment else null
}
