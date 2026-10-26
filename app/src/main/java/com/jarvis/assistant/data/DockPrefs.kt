package com.jarvis.assistant.data

import android.content.Context

/**
 * Persisted dock layout.
 *
 * Stored as a small ordered list of `package/activity` keys in SharedPreferences — no database, no
 * serialization library, and it survives uninstall/reinstall only if the user backs up app data.
 * An empty list means "auto": the recognisable system apps chosen by [AppRepository.dock].
 */
object DockPrefs {

    private const val PREFS = "jarvis_dock"
    private const val KEY_SLOTS = "slots"
    const val SLOT_COUNT = 5

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readKeys(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SLOTS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    fun isCustom(context: Context): Boolean = readKeys(context).isNotEmpty()

    fun write(context: Context, keys: List<String>) {
        prefs(context).edit()
            .putString(KEY_SLOTS, keys.take(SLOT_COUNT).joinToString("|"))
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SLOTS).apply()
    }

    /**
     * Replaces one slot. Slots are positional: an index beyond the current list appends, and a null
     * value clears that slot so the auto-picker can fill it again.
     */
    fun setSlot(context: Context, index: Int, app: InstalledApp?) {
        val current = readKeys(context).toMutableList()
        while (current.size <= index) current.add("")
        current[index] = app?.key.orEmpty()
        // Trim trailing empties so a cleared tail returns to auto-fill.
        while (current.isNotEmpty() && current.last().isBlank()) current.removeAt(current.lastIndex)
        write(context, current.filter { it.isNotBlank() })
    }

    /**
     * Resolves the persisted keys against the apps that are actually installed right now. Missing
     * apps (uninstalled since the dock was saved) are skipped, and the remaining slots are filled
     * from [AppRepository.dock] so the dock is never short.
     */
    fun resolve(context: Context, installed: List<InstalledApp>): List<InstalledApp> {
        val keys = readKeys(context)
        if (keys.isEmpty()) return AppRepository.dock(installed, SLOT_COUNT)

        val byKey = installed.associateBy { it.key }
        val chosen = keys.mapNotNull { byKey[it] }.toMutableList()

        if (chosen.size < SLOT_COUNT) {
            for (app in AppRepository.dock(installed, SLOT_COUNT)) {
                if (chosen.size >= SLOT_COUNT) break
                if (chosen.none { it.key == app.key }) chosen.add(app)
            }
        }
        return chosen.take(SLOT_COUNT)
    }
}
