package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.automation.AccessibilityStatus
import com.jarvis.assistant.automation.JarvisAccessibilityService
import com.jarvis.assistant.data.PhraseBook
import com.jarvis.assistant.voice.Contacts
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.util.Locale

/**
 * Sending a message to a person, not to a number.
 *
 * The failure this fixes: "send message Khushi saying hi" did nothing, because the old path demanded
 * a saved contact and then trusted a tap on a button whose label changes with the keyboard and the
 * WhatsApp build.
 *
 * The order below is chosen by reliability, not by convenience:
 *
 *  1. **Reply through a live notification.** If Khushi has messaged recently, WhatsApp's own
 *     notification carries a `RemoteInput` reply action. Sending through it uses the supported API,
 *     works with WhatsApp closed, and cannot land in the wrong chat.
 *  2. **Open the chat by link** (`wa.me/<number>?text=…`). WhatsApp pre-fills the box, so nothing
 *     is typed twice and nothing can be typed into the wrong place.
 *  3. **Verify, then press send** through accessibility — and if the press cannot be confirmed,
 *     JARVIS says exactly that: the chat is open with the message typed, tap send.
 *
 * A name that matches nobody is never fatal: [resolve] returns near misses so JARVIS can ask
 * "which Khushi?" instead of refusing.
 */
object Messaging {

    data class Person(
        val name: String,
        val number: String?,
        val source: String,
    )

    data class Outcome(
        val ok: Boolean,
        /** What actually happened, in words JARVIS can speak without overstating it. */
        val detail: String,
        val suggestions: List<String> = emptyList(),
    )

    /** People whose name is close to [raw]. Best first; empty when nothing is close. */
    suspend fun resolve(context: Context, raw: String): List<Person> {
        val needle = PhraseBook.clean(raw)
        if (needle.isBlank()) return emptyList()

        val found = mutableListOf<Person>()

        Contacts.resolve(context, raw)?.let { match ->
            found.add(Person(match.name, match.number, if (match.how == "memory") "memory" else "contacts"))
        }

        // Someone who has messaged recently but is not in the address book — the common case for
        // chats that started from a group or a delivery app.
        if (NotifyBrain.findReplyTarget(raw) != null) {
            val sender = NotifyBrain.findReplyTarget(raw)?.let { NotifyBrain.senderOf(it) }.orEmpty()
            if (sender.isNotBlank() && found.none { PhraseBook.clean(it.name) == PhraseBook.clean(sender) }) {
                found.add(Person(sender, null, "notifications"))
            }
        }

        // Near misses, so a typo or a nickname still gets a "did you mean".
        if (found.isEmpty()) {
            val senderNames = NotifyBrain.replyableNewestFirst().map { NotifyBrain.senderOf(it) }
            senderNames.filter { it.isNotBlank() && PhraseBook.jaroWinkler(needle, PhraseBook.clean(it)) >= 0.84 }
                .forEach { name -> found.add(Person(name, null, "notifications")) }
        }
        return found.distinctBy { PhraseBook.clean(it.name) }
    }

    /**
     * Sends [text] to [raw] on [app] (whatsapp, sms or telegram).
     *
     * Never reports success for something that did not happen; [Outcome.detail] is the honest
     * sentence, and [Outcome.suggestions] carries the candidates when the name was ambiguous.
     */
    suspend fun send(
        context: Context,
        raw: String,
        text: String,
        app: String = "whatsapp",
    ): Outcome {
        val target = app.lowercase(Locale.US)
        val people = resolve(context, raw)
        if (people.isEmpty()) {
            return Outcome(
                ok = false,
                detail = "I could not find anyone called $raw.",
            )
        }
        if (people.size > 1 && people[0].source != "contacts") {
            return Outcome(
                ok = false,
                detail = "Which $raw?",
                suggestions = people.map { it.name }.take(4),
            )
        }

        val person = people.first()

        // 1. The reliable path: answer the notification that is already on the phone.
        if (text.isNotBlank() && (target == "whatsapp" || target == "sms")) {
            val live = NotifyBrain.findReplyTarget(person.name)
            if (live != null && NotifyBrain.sendReply(context, live, text)) {
                return Outcome(ok = true, detail = "Replied to ${person.name}.")
            }
        }

        // 2. Open the chat with the text pre-filled.
        val digits = person.number?.filter { it.isDigit() }.orEmpty()
        if (digits.length < 6) {
            return Outcome(
                ok = false,
                detail = "${person.name} is not in your contacts, so I have no number to write to. " +
                    "I can open WhatsApp to their chat if they messaged you recently.",
            )
        }

        return when (target) {
            "sms" -> {
                val uri = "smsto:$digits" + if (text.isNotBlank()) {
                    "?body=" + urlEncode(text)
                } else {
                    ""
                }
                openUri(context, Uri.parse(uri), person.name, text, verifyWith = null)
            }

            "telegram" -> Outcome(
                ok = false,
                detail = "Telegram has no link that opens a chat by phone number.",
            )

            else -> {
                val uri = "https://wa.me/$digits" + if (text.isNotBlank()) {
                    "?text=" + urlEncode(text)
                } else {
                    ""
                }
                openUri(context, Uri.parse(uri), person.name, text, verifyWith = "whatsapp")
            }
        }
    }

    /**
     * Starts the chat, then tries to confirm and send.
     *
     * The confirmation is what stops the old lie: if JARVIS cannot see the chat, it does not claim
     * the message was sent.
     */
    private suspend fun openUri(
        context: Context,
        uri: Uri,
        person: String,
        text: String,
        verifyWith: String?,
    ): Outcome {
        val started = try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            false
        }
        if (!started) return Outcome(ok = false, detail = "I could not open ${verifyWith ?: "the app"}.")
        if (text.isBlank()) return Outcome(ok = true, detail = "Opened the chat with $person.")

        if (!AccessibilityStatus.isServiceEnabled(context) ||
            !AccessibilityStatus.isScreenPressAllowed(context)
        ) {
            return Outcome(
                ok = false,
                detail = "The chat with $person is open with your message typed. Tap send — " +
                    "screen access would let me do that myself.",
            )
        }

        // WhatsApp needs a moment to compose the conversation from the link.
        delay(3_000)
        val labels = JarvisAccessibilityService.visibleLabels(limit = 60)
        val sawChat = labels.any { it.contains(person, ignoreCase = true) } ||
            labels.any { it.contains(text.take(24), ignoreCase = true) }
        if (!sawChat) {
            return Outcome(
                ok = false,
                detail = "I opened the chat but could not see it, so I did not press send. " +
                    "The message is ready for you to send.",
            )
        }

        val pressed = JarvisAccessibilityService.pressByText("Send") ||
            JarvisAccessibilityService.pressLoose("send") ||
            JarvisAccessibilityService.pressByText("SEND")
        return if (pressed) {
            Outcome(ok = true, detail = "Sent to $person.")
        } else {
            Outcome(
                ok = false,
                detail = "The message to $person is typed but I could not find send. Tap it once.",
            )
        }
    }

    fun whatsappLink(number: String, text: String): String {
        val digits = number.filter { it.isDigit() }
        val suffix = if (text.isBlank()) "" else "?text=" + urlEncode(text)
        return "https://wa.me/$digits$suffix"
    }

    private fun urlEncode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (t: Throwable) {
        value
    }
}
