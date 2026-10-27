package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.assistant.data.PhraseBook
import com.jarvis.assistant.voice.VoiceMemory

/**
 * "Open X" for every X that actually exists on a phone.
 *
 * The old path only looked at installed-app labels, so "open gallery" failed the moment the gallery
 * was called *Photos*, *Album* or *HeyTap Photos* — which is what most phones call it. Three stages
 * now, in this order:
 *
 *  1. **System intents.** Gallery, camera, files, settings pages and similar are reached through the
 *     documented `Intent`/`Settings.ACTION_*` constants, so the name on the launcher is irrelevant.
 *  2. **Known gallery/dialer/clock packages** for the skins that ship under a different label
 *     (ColorOS, MIUI, One UI, stock Android, Google).
 *  3. **Fuzzy label search** over every launchable app — "galery", "whatsap", "insta" all land.
 *
 * When nothing matches, [Outcome.Unknown] carries the closest real app names so JARVIS can ask
 * "did you mean Photos?" instead of saying it does not understand.
 */
object AppLaunch {

    sealed interface Outcome {
        data class Opened(val label: String) : Outcome
        data class Unknown(val suggestions: List<String>) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Skins rename the gallery; the package name does not change. */
    private val GALLERY_PACKAGES = listOf(
        "com.coloros.gallery3d",
        "com.oplus.gallery3d",
        "com.oppo.gallery3d",
        "com.google.android.apps.photos",
        "com.miui.gallery",
        "com.samsung.android.gallery3d",
        "com.oneplus.gallery",
        "com.motorola.gallery",
        "com.huawei.photos",
        "com.transsion.gallery",
        "com.sec.android.gallery3d",
        "com.android.gallery3d",
        "com.realme.gallery3d",
        "com.vivo.gallery",
    )

    fun open(context: Context, raw: String): Outcome {
        val target = PhraseBook.clean(raw)
        if (target.isBlank()) return Outcome.Failed("Nothing to open")

        // 1. A documented system surface.
        systemIntent(context, target)?.let { intent ->
            return start(context, intent, labelFor(target))
        }

        // 2. A known package under a skin-specific label.
        if (looksLikeGallery(target)) {
            for (pkg in GALLERY_PACKAGES) {
                val launch = launchIntent(context, pkg)
                if (launch != null) return start(context, launch, "Gallery")
            }
            // No gallery app: show the newest picture, which is what "open gallery" meant anyway.
            val problem = Gallery.openLatest(context)
            if (problem == null) return Outcome.Opened("your last photo")
        }

        // 3. A launchable app, matched loosely.
        fuzzyApp(context, target)?.let { (label, intent) ->
            return start(context, intent, label)
        }

        val guesses = closestLabels(context, target)
        return if (guesses.isEmpty()) {
            Outcome.Unknown(emptyList())
        } else {
            Outcome.Unknown(guesses)
        }
    }

    /** Launches a package by name (used by the OPEN_APP action). */
    fun launchPackage(context: Context, pkg: String, activity: String?): Outcome {
        val intent = if (!activity.isNullOrBlank()) {
            Intent(Intent.ACTION_MAIN)
                .setClassName(pkg, activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            launchIntent(context, pkg)
        } ?: return Outcome.Failed("$pkg is not installed")

        return start(context, intent, appLabel(context, pkg))
    }

    fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    /** "galery", "gallery kholo", "my photos" — all mean the same thing. */
    private fun looksLikeGallery(target: String): Boolean =
        target in GALLERY_WORDS ||
            GALLERY_WORDS.any { PhraseBook.jaroWinkler(target, it) >= 0.88 }

    private val GALLERY_WORDS = setOf(
        "gallery", "photo gallery", "photos", "my photos", "pictures", "album", "images",
        "meri gallery", "photo album",
    )

    /**
     * Documented system surfaces, keyed by the words people use for them.
     *
     * An exact phrase wins; otherwise the closest known key is tried, so "settngs" and "blutooth"
     * still land on the right page instead of failing on a typo.
     */
    fun systemIntent(context: Context, targetRaw: String): Intent? {
        val target = PhraseBook.clean(targetRaw)
        systemIntentExact(context, target)?.let { return it }
        if (target.length < 4) return null
        val closest = SYSTEM_KEYS
            .map { it to PhraseBook.jaroWinkler(target, it) }
            .filter { it.second >= 0.9 }
            .maxByOrNull { it.second }
            ?.first
            ?: return null
        return systemIntentExact(context, closest)
    }

    private val SYSTEM_KEYS = listOf(
        "camera", "files", "settings", "wifi", "bluetooth", "hotspot", "battery", "storage",
        "apps", "location", "display", "sound", "notification access", "accessibility",
        "date and time", "language", "play store", "browser",
    )

    private fun systemIntentExact(context: Context, target: String): Intent? {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK

        return when {
            target in GALLERY_WORDS -> Intent(Intent.ACTION_VIEW)
                .setType("image/*")
                .addFlags(flags)

            target in setOf("camera", "selfie camera", "the camera") ->
                Intent("android.media.action.STILL_IMAGE_CAMERA").addFlags(flags)

            target in setOf("files", "my files", "file manager", "file explorer", "documents") ->
                // DocumentsUI is the system file browser and is present on every Android 10+ phone.
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(flags or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

            target in setOf("settings", "phone settings", "system settings", "setting") ->
                Intent(Settings.ACTION_SETTINGS).addFlags(flags)

            target in setOf("wifi", "wi fi", "wireless") ->
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(flags)

            target in setOf("bluetooth", "bt") ->
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(flags)

            target in setOf("hotspot", "hotspot settings", "tethering") ->
                Intent(Settings.ACTION_TETHER_SETTINGS).addFlags(flags)

            target in setOf("battery", "battery settings", "battery saver") ->
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(flags)

            target in setOf("storage", "storage settings") ->
                Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(flags)

            target in setOf("apps", "app info", "installed apps", "app settings") ->
                Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(flags)

            target in setOf("location", "location settings", "gps") ->
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(flags)

            target in setOf("display", "display settings", "brightness") ->
                Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(flags)

            target in setOf("sound", "sound settings") ->
                Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(flags)

            target in setOf("notification access", "notification listener") ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(flags)

            target in setOf("accessibility", "accessibility settings") ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(flags)

            target in setOf("date", "date and time", "time settings") ->
                Intent(Settings.ACTION_DATE_SETTINGS).addFlags(flags)

            target in setOf("language", "language settings", "keyboard") ->
                Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(flags)

            target in setOf("play store", "google play") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=apps")).addFlags(flags)

            target in setOf("web", "browser", "internet") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).addFlags(flags)

            target.startsWith("app info for ") -> {
                val name = target.removePrefix("app info for ").trim()
                val pkg = fuzzyPackage(context, name)
                if (pkg == null) {
                    Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(flags)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$pkg"))
                        .addFlags(flags)
                }
            }

            else -> null
        }
    }

    private fun labelFor(target: String): String = target
        .split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun start(context: Context, intent: Intent, label: String): Outcome = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Outcome.Opened(label)
    } catch (t: Throwable) {
        Outcome.Failed("Nothing on this phone can open $label")
    }

    private fun launchIntent(context: Context, pkg: String): Intent? = try {
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } catch (t: Throwable) {
        null
    }

    data class Candidate(val label: String, val packageName: String, val activity: String)

    /** Every launchable app. Cheap enough at this size, and cached nowhere on purpose. */
    fun candidates(context: Context): List<Candidate> = try {
        val pm = context.packageManager
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            if (activity.packageName == context.packageName) return@mapNotNull null
            val label = try {
                info.loadLabel(pm)?.toString()?.trim().orEmpty()
            } catch (t: Throwable) {
                ""
            }
            Candidate(label.ifBlank { activity.packageName }, activity.packageName, activity.name)
        }.distinctBy { it.packageName to it.activity }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun fuzzyApp(context: Context, needle: String): Pair<String, Intent>? {
        VoiceMemory.appAlias(context, needle)?.let { pkg ->
            launchIntent(context, pkg)?.let { return AppLaunch.appLabel(context, pkg) to it }
        }

        val all = candidates(context)
        val scored = all.map { it to score(needle, PhraseBook.clean(it.label)) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return null
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(best.first.packageName, best.first.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return best.first.label to intent
    }

    private fun fuzzyPackage(context: Context, needle: String): String? {
        val clean = PhraseBook.clean(needle)
        val all = candidates(context)
        return all.maxByOrNull { score(clean, PhraseBook.clean(it.label)) }
            ?.takeIf { score(clean, PhraseBook.clean(it.label)) > 0.0 }
            ?.packageName
    }

    /** Names close enough to offer as "did you mean". */
    fun closestLabels(context: Context, needle: String, limit: Int = 3): List<String> {
        val clean = PhraseBook.clean(needle)
        return candidates(context)
            .map { it.label to score(clean, PhraseBook.clean(it.label)) }
            .filter { it.second >= 0.72 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(limit)
    }

    /**
     * 1.0 for an exact label, then prefix, then word-containment, then Jaro-Winkler.
     *
     * The containment rule is what connects "gallery" to a label called "Photos by Google"? It does
     * not — that needs the package list above. What it *does* connect is "insta" to "Instagram",
     * which is the common case.
     */
    private fun score(needle: String, label: String): Double {
        if (needle.isBlank() || label.isBlank()) return 0.0
        if (needle == label) return 1.0
        if (label.startsWith(needle)) return 0.95
        if (needle.length >= 3 && label.contains(needle)) return 0.9
        if (needle.length >= 3 && needle.contains(label)) return 0.85
        val jw = PhraseBook.jaroWinkler(needle, label)
        return when {
            jw >= 0.92 -> jw
            jw >= 0.86 && needle.length >= 4 -> jw * 0.95
            // "whatsapp" vs "WhatsApp Messenger": a word of the label matches a word of the needle.
            label.split(' ').any { word -> word.length >= 3 && PhraseBook.jaroWinkler(needle, word) >= 0.9 } -> 0.88
            else -> 0.0
        }
    }
}
