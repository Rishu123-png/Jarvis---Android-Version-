package com.jarvis.assistant.device

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.jarvis.assistant.data.PhraseBook
import com.jarvis.assistant.voice.Lang
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What a notification actually is, once the noise is stripped. */
enum class NotificationKind { MESSAGE, EMAIL, OTP, PROMO, SYSTEM, OTHER }

data class Verdict(
    val kind: NotificationKind,
    /** 0 = clearly personal, 100 = clearly an advertisement. */
    val promoScore: Int,
    val reason: String,
) {
    val worthTelling: Boolean
        get() = kind == NotificationKind.MESSAGE ||
            kind == NotificationKind.EMAIL ||
            kind == NotificationKind.OTP

    /** One dry line instead of reading an advert aloud. */
    val isPromo: Boolean
        get() = kind == NotificationKind.PROMO
}

/**
 * Reads notifications the way a person would.
 *
 * The operator's rule, in their words: advertisements should be *seen* but not read out in full;
 * a WhatsApp message should be read properly, with a suggestion of what to reply. So everything here
 * is about the decision **"does this deserve your attention?"** — and that decision is a pure
 * function, which is why `NotifyBrainTest` can cover it without a phone.
 *
 * The reply path is the part that makes this reliable: WhatsApp, SMS, Telegram and Instagram all
 * attach a `RemoteInput` action to their notifications, and that action is the *supported* way to
 * answer a message. No accessibility taps, no opening the app, no guessing at a "Send" button.
 */
object NotifyBrain {

    private const val PREFS = "jarvis_notify_brain"

    private val MESSAGING_PACKAGES = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.plus",
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
        "org.thoughtcrime.securesms", "com.instagram.android", "com.discord",
        "com.slack", "com.microsoft.teams", "com.google.android.gm", "com.microsoft.office.outlook",
        "com.viber.voip", "com.facebook.orca", "com.snapchat.android", "jp.naver.line.android",
    )

    private val EMAIL_PACKAGES = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
        "ru.yandex.mail", "com.samsung.android.email.provider",
    )

    private val SHOPPING_PACKAGES = setOf(
        "com.flipkart.android", "in.amazon.mShop.android.shopping", "com.myntra.android",
        "com.ajio.consumerapp", "com.meesho.app", "com.snapdeal.main", "com.ebay.mobile",
        "com.alibaba.aliexpresshd", "com.olx.southasia", "com.quikr", "com.swiggy.android",
        "com.application.zomato", "com.ubercab", "com.dreamplug.androidapp",
    )

    private val PROMO_WORDS = listOf(
        "offer", "sale", "discount", "coupon", "deal", "hurry", "limited time", "limited period",
        "cashback", "flat off", "% off", "free delivery", "buy now", "shop now", "wishlist",
        "cart", "price drop", "lowest price", "new arrival", "last chance", "mega", "clearance",
        "big billion", "great indian", "festive", "grab", "upgrade now", "recharge",
        "आपके लिए", "ऑफर", "सेल", "छूट",
    )

    private val OTP_WORDS = listOf(
        "otp", "one time password", "verification code", "security code", "login code", "passcode",
    )

    // ---------------------------------------------------------------------------------------------
    // Judgement (pure)
    // ---------------------------------------------------------------------------------------------

    /**
     * Decides what a notification is.
     *
     * Order matters. A reply action wins outright — an advert never has one, and a chat always does.
     * After that an OTP is treated as important (it is time-sensitive and personal), then a
     * shopping/offer notification is a promo, then plain system noise, then a message from an
     * unknown app.
     */
    fun classify(
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        hasReplyAction: Boolean,
    ): Verdict {
        val haystack = PhraseBook.clean("$title $text $appLabel")
        val pkg = packageName.lowercase(Locale.US)

        if (hasReplyAction && pkg in MESSAGING_PACKAGES) {
            return Verdict(NotificationKind.MESSAGE, 0, "chat with a reply action")
        }
        if (pkg in EMAIL_PACKAGES) {
            return Verdict(NotificationKind.EMAIL, 0, "mail")
        }
        if (OTP_WORDS.any { haystack.contains(it) } || extractOtp(text) != null) {
            return Verdict(NotificationKind.OTP, 10, "one-time code")
        }

        val promoHits = PROMO_WORDS.count { haystack.contains(it) }
        val score = promoHits * 25 + if (pkg in SHOPPING_PACKAGES) 40 else 0 +
            if (haystack.contains("₹") || haystack.contains("rs ")) 15 else 0

        if (hasReplyAction) {
            // A chat inside an app that is not on the messaging list (a delivery app's support chat).
            return Verdict(NotificationKind.MESSAGE, 0, "reply action present")
        }
        if (pkg in SHOPPING_PACKAGES || score >= 40) {
            return Verdict(NotificationKind.PROMO, score.coerceIn(0, 100), "advertisement")
        }
        if (pkg == "android" || pkg.contains("system") || pkg.contains("settings")) {
            return Verdict(NotificationKind.SYSTEM, score.coerceIn(0, 100), "system notice")
        }
        return Verdict(NotificationKind.OTHER, score.coerceIn(0, 100), "unclassified")
    }

    /** The 4–8 digit code inside an OTP message, if there is one. */
    fun extractOtp(text: String): String? {
        val lower = text.lowercase(Locale.US)
        if (OTP_WORDS.none { lower.contains(it) }) return null
        return Regex("\\b(\\d{4,8})\\b").find(text)?.groupValues?.getOrNull(1)
    }

    /**
     * Three replies that fit the message.
     *
     * Deliberately short and human: these are chips on a card and lines to be spoken, not paragraphs.
     * The AI, when a key is set, replaces them with something better — these are the floor, not the
     * ceiling.
     */
    fun suggestions(text: String, sender: String, lang: Lang = Lang.EN): List<String> {
        val lower = text.lowercase(Locale.US)
        val question = lower.contains("?")

        return when (lang.replyLang) {
            Lang.HI -> when {
                lower.contains("call") || lower.contains("फोन") ->
                    listOf("कॉल कर रहा हूँ", "अभी व्यस्त हूँ, बाद में", "हाँ, अभी")
                question -> listOf("हाँ, बताइए", "अभी व्यस्त हूँ", "थोड़ी देर में बात करते हैं")
                else -> listOf("ठीक है", "मिल लेते हैं", "बाद में बात करते हैं")
            }
            Lang.HINGLISH -> when {
                lower.contains("call") || lower.contains("phone") ->
                    listOf("Call kar raha hoon", "Abhi busy hoon, baad me", "Haan, abhi")
                question -> listOf("Haan batao", "Abhi busy hoon", "Thodi der me baat karte hain")
                else -> listOf("Theek hai", "Milte hain", "Baad me baat karte hain")
            }
            else -> when {
                lower.contains("call") || lower.contains("phone") ->
                    listOf("Calling you now", "Busy right now, later", "Yes, in a minute")
                question -> listOf("Yes, tell me", "Busy right now", "Let us talk in a bit")
                else -> listOf("Okay", "See you soon", "Talk later")
            }
        }.map { it.replace("%s", sender) }.take(3)
    }

    /** The short line JARVIS speaks for a notification. */
    fun announceLine(
        verdict: Verdict,
        appLabel: String,
        sender: String,
        text: String,
        lang: Lang,
    ): String {
        val body = text.trim().take(220)
        return when (verdict.kind) {
            NotificationKind.PROMO -> when (lang.replyLang) {
                Lang.HI -> "$appLabel की तरफ़ से एक ऑफ़र है। नज़रअंदाज़ कर रहा हूँ।"
                Lang.HINGLISH -> "$appLabel ki taraf se offer hai. Ignore kar raha hoon."
                else -> "A $appLabel offer. Ignoring it."
            }
            NotificationKind.OTP -> {
                val code = extractOtp(text)
                when (lang.replyLang) {
                    Lang.HI -> "$sender से कोड आया है" + if (code != null) ": $code।" else "।"
                    Lang.HINGLISH -> "$sender se code aaya hai" + if (code != null) ": $code." else "."
                    else -> "A code from $sender" + if (code != null) ": $code." else "."
                }
            }
            NotificationKind.MESSAGE -> when (lang.replyLang) {
                Lang.HI -> "$sender का संदेश: $body"
                Lang.HINGLISH -> "$sender ka message: $body"
                else -> "Message from $sender: $body"
            }
            NotificationKind.EMAIL -> when (lang.replyLang) {
                Lang.HI -> "$sender से मेल: $body"
                Lang.HINGLISH -> "$sender se mail: $body"
                else -> "Mail from $sender: $body"
            }
            NotificationKind.SYSTEM, NotificationKind.OTHER ->
                "$appLabel: ${if (body.isBlank()) sender else body}"
        }
    }

    /** Quiet hours, inclusive of the start and exclusive of the end, wrapping past midnight. */
    fun isQuietHour(hour: Int, from: Int, to: Int): Boolean {
        if (from == to) return false
        return if (from < to) hour in from until to else hour >= from || hour < to
    }

    fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    // ---------------------------------------------------------------------------------------------
    // Preferences
    // ---------------------------------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Off by default: an assistant that starts talking on its own must be invited first. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
    }

    fun quietFrom(context: Context): Int = prefs(context).getInt("quiet_from", 23)

    fun quietTo(context: Context): Int = prefs(context).getInt("quiet_to", 7)

    fun setQuietHours(context: Context, from: Int, to: Int) {
        prefs(context).edit().putInt("quiet_from", from).putInt("quiet_to", to).apply()
    }

    /** Apps the operator asked never to be told about (usually one noisy shopping app). */
    fun mutedApps(context: Context): Set<String> =
        prefs(context).getStringSet("muted", emptySet()).orEmpty()

    fun muteApp(context: Context, packageName: String) {
        val current = mutedApps(context).toMutableSet()
        current.add(packageName)
        prefs(context).edit().putStringSet("muted", current).apply()
    }

    fun unmuteAll(context: Context) {
        prefs(context).edit().remove("muted").apply()
    }

    /** True once per notification key, so a reposted ad is not announced five times. */
    fun markSeen(context: Context, key: String, postedAt: Long): Boolean {
        val store = prefs(context)
        val lastKey = store.getString("last_key", null)
        val lastAt = store.getLong("last_at", 0L)
        if (lastKey == key && postedAt <= lastAt) return false
        store.edit().putString("last_key", key).putLong("last_at", postedAt).apply()
        return true
    }

    /** One advertisement per app per day is plenty; the rest are not even mentioned. */
    fun promoAlreadyTold(context: Context, packageName: String): Boolean {
        val store = prefs(context)
        return store.getString("promo_$packageName", null) ==
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun markPromoTold(context: Context, packageName: String) {
        prefs(context).edit()
            .putString("promo_$packageName", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
            .apply()
    }

    /** The bundle of judgements one announcement needs, so the listener stays thin. */
    fun verdictFor(
        context: Context,
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
    ): Verdict {
        val hasReply = live.values.any { candidate ->
            candidate.packageName == packageName &&
                candidate.notification?.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
        }
        return classify(packageName, appLabel, title, text, hasReply)
    }

    fun lastSummary(context: Context, limit: Int = 5): List<JarvisNotification> {
        if (!NotificationRepository.hasAccess(context)) return emptyList()
        return NotificationRepository.notifications.value
            .filter { it.packageName !in mutedApps(context) }
            .sortedByDescending { it.postedAt }
            .take(limit)
    }

    fun countUnreadMessages(context: Context): Int = lastSummary(context, 40).count { item ->
        val verdict = classify(item.packageName, item.appLabel, item.title, item.text, false)
        verdict.kind == NotificationKind.MESSAGE
    }

    // ---------------------------------------------------------------------------------------------
    // Replying through the notification itself
    // ---------------------------------------------------------------------------------------------

    private val live = LinkedHashMap<String, StatusBarNotification>()

    /** Called by the listener on every change; the map is process-lifetime only. */
    fun capture(active: List<StatusBarNotification>) {
        live.clear()
        for (sbn in active) {
            live[sbn.key] = sbn
        }
    }

    private fun replyAction(sbn: StatusBarNotification): Notification.Action? {
        val actions = sbn.notification?.actions ?: return null
        return actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }

    /** Notifications that carry a reply action, newest first. */
    fun replyableNewestFirst(): List<StatusBarNotification> = live.values
        .filter { replyAction(it) != null }
        .sortedByDescending { it.postTime }

    fun senderOf(sbn: StatusBarNotification): String = try {
        val extras = sbn.notification?.extras
        sequenceOf(
            extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras?.getCharSequence(Notification.EXTRA_TITLE),
        ).firstOrNull { !it.isNullOrBlank() }?.toString()?.trim().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /** Cached app label, so the listener does not hit PackageManager per notification. */
    fun appLabelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: Throwable) {
        packageName
    }

    fun textOf(sbn: StatusBarNotification): String = try {
        val extras = sbn.notification?.extras
        sequenceOf(
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras?.getCharSequence(Notification.EXTRA_TEXT),
        ).firstOrNull { !it.isNullOrBlank() }?.toString()?.trim().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /** The live notification from a person whose name looks like [name], if one exists. */
    fun findReplyTarget(name: String): StatusBarNotification? {
        val needle = PhraseBook.clean(name)
        if (needle.isBlank()) return null

        val candidates = replyableNewestFirst()
        return candidates.firstOrNull { PhraseBook.clean(senderOf(it)).contains(needle) }
            ?: candidates.firstOrNull {
                val sender = PhraseBook.clean(senderOf(it))
                sender.isNotBlank() && PhraseBook.jaroWinkler(needle, sender) >= 0.86
            }
            ?: candidates.firstOrNull {
                PhraseBook.clean(textOf(it)).contains(needle)
            }
    }

    /**
     * Sends [text] as a reply to a live notification.
     *
     * This is the supported Android path: build the results bundle for the action's `RemoteInput`s
     * and fire its `PendingIntent`. Returns false when the notification has no reply action or the
     * system refuses — never a silent success.
     */
    fun sendReply(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        val action = replyAction(sbn) ?: return false
        val inputs = action.remoteInputs ?: return false
        if (text.isBlank()) return false

        return try {
            val results = Bundle()
            for (input in inputs) {
                results.putCharSequence(input.resultKey, text)
            }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Human-readable time for a notification row. */
    fun clock(millis: Long): String = try {
        SimpleDateFormat("h:mm a", Locale.US).format(Date(millis))
    } catch (t: Throwable) {
        ""
    }
}
