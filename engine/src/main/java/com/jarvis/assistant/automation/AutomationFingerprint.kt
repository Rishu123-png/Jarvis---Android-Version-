package com.jarvis.assistant.automation

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A routine's executable data, reduced to one hash.
 *
 * The point is narrow and worth being precise about: a routine is a program, and the phone runs it
 * later, possibly while the operator is asleep. Between saving it and firing it, the stored text can
 * change for reasons nobody chose — a partial write, a bad restore, a migration that misreads a
 * field. A routine that quietly became a *different* routine is the worst failure this module can
 * have, because it acts on its own.
 *
 * So the steps and the trigger are hashed when the routine is saved and every time it is about to
 * run. If the two disagree, the engine refuses to run it and says so. What is *not* hashed: the name,
 * the id, and the enabled switch. Renaming a routine or switching it off is not a change to what it
 * would do, and treating it as one would train everybody to ignore the warning.
 *
 * The hash is not a signature and is not a security boundary — anyone with write access to the
 * app's storage can compute a new one. It is a canary for drift, which is the failure that actually
 * happens.
 */
object AutomationFingerprint {

    private const val HEX = "0123456789abcdef"

    /** The exact bytes that will be executed, in a stable order. */
    fun material(workflow: Workflow): String = JSONObject().apply {
        put("trigger", workflow.trigger.toJson())
        put(
            "steps",
            JSONArray().apply {
                workflow.steps.forEach { put(it.toJson()) }
            },
        )
    }.toString()

    fun of(workflow: Workflow): String = sha256(material(workflow))

    /** First eight characters, for a screen that wants to show which version this is. */
    fun shortOf(workflow: Workflow): String = of(workflow).take(8)

    /**
     * True when the routine still matches its stored fingerprint.
     *
     * A blank fingerprint is not a mismatch: routines saved before this existed are stamped on
     * load, and a routine the operator composed in the editor is stamped on save. Refusing on blank
     * would break every routine already on the phone, which is a far worse trade than the one this
     * check exists to prevent.
     */
    fun matches(workflow: Workflow): Boolean =
        workflow.fingerprint.isBlank() || workflow.fingerprint == of(workflow)

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}
