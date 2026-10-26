package com.jarvis.assistant.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * A launchable app on the device: one entry per (package, launcher activity) pair.
 */
data class InstalledApp(
    val packageName: String,
    val activityName: String,
    val label: String,
) {
    val key: String get() = "$packageName/$activityName"

    /** Fallback glyph shown when an icon cannot be loaded. */
    val initial: String
        get() = label.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "?" }

    val sortKey: String get() = label.lowercase(Locale.getDefault())
}

/**
 * Reads the installed, launchable apps and their icons.
 *
 * Everything here is read-only: `queryIntentActivities` on the MAIN/LAUNCHER intent only returns
 * activities that already expose themselves on a home screen. The `<queries>` element in the
 * manifest is what makes that query legal on Android 11+.
 */
object AppRepository {

    private val iconCache = LruCache<String, ImageBitmap>(120)

    /** Cheap synchronous cache peek, so a list scroll never flashes a placeholder needlessly. */
    fun cachedIcon(app: InstalledApp): ImageBitmap? = iconCache.get(app.key)

    /** Loads every launchable app, deduplicated per package, sorted the way a launcher would. */
    suspend fun loadApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            emptyList()
        }

        val collator = Collator.getInstance(Locale.getDefault())

        resolved.asSequence()
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                // JARVIS is the home itself; it does not belong in its own drawer.
                if (activity.packageName == context.packageName) return@mapNotNull null
                val label = try {
                    info.loadLabel(pm)?.toString()?.trim()
                } catch (t: Throwable) {
                    null
                }
                InstalledApp(
                    packageName = activity.packageName,
                    activityName = activity.name,
                    label = label?.takeIf { it.isNotEmpty() } ?: activity.packageName,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .toList()
    }

    /** Loads (and caches) one app icon as a Compose [ImageBitmap]. Null means "use a fallback". */
    suspend fun iconFor(
        context: Context,
        app: InstalledApp,
        sizePx: Int = 144,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        iconCache.get(app.key)?.let { return@withContext it }

        val bitmap = try {
            val activityInfo = context.packageManager.getActivityInfo(
                ComponentName(app.packageName, app.activityName),
                0,
            )
            activityInfo.loadIcon(context.packageManager).toBitmap(sizePx, sizePx).asImageBitmap()
        } catch (t: Throwable) {
            null
        }

        if (bitmap != null) iconCache.put(app.key, bitmap)
        bitmap
    }

    /**
     * Common launcher packages, ordered per category. Used only to pick sensible dock slots until
     * the persisted dock can hold a real user-chosen app.
     */
    private val dockCandidates: List<List<String>> = listOf(
        listOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.oneplus.dialer",
            "com.android.contacts",
        ),
        listOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.android.messaging",
            "com.google.android.gm",
        ),
        listOf(
            "com.android.chrome",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
        ),
        listOf(
            "com.google.android.GoogleCamera",
            "com.android.camera",
            "com.sec.android.app.camera",
            "net.sourceforge.opencamera",
            "com.oneplus.camera",
        ),
        listOf("com.android.settings"),
    )

    /**
     * Picks up to [slots] dock apps: recognised system apps first, then the first installed apps,
     * so the dock is never empty on any device.
     */
    fun dock(apps: List<InstalledApp>, slots: Int = 5): List<InstalledApp> {
        if (apps.isEmpty()) return emptyList()
        val chosen = LinkedHashMap<String, InstalledApp>()

        for (group in dockCandidates) {
            if (chosen.size >= slots) break
            val hit = group.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
            if (hit != null) chosen.putIfAbsent(hit.packageName, hit)
        }
        for (app in apps) {
            if (chosen.size >= slots) break
            chosen.putIfAbsent(app.packageName, app)
        }
        return chosen.values.take(slots)
    }

    /** Groups the drawer alphabetically for the section headers. */
    fun sections(apps: List<InstalledApp>): List<Pair<Char, List<InstalledApp>>> =
        apps.groupBy { app ->
            val first = app.label.trim().firstOrNull()?.uppercaseChar() ?: '#'
            if (first.isLetter()) first else '#'
        }.toSortedMap().map { (letter, group) -> letter to group }
}
