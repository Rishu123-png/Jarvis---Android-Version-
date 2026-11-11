package com.jarvis.assistant.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One historical run, kept so the operator can see what fired and what it did. */
data class RunRecord(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val at: Long,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val stopped: Boolean,
    val triggerLabel: String,
) {
    val ok: Boolean get() = failed == 0 && !stopped

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workflowId", workflowId)
        put("workflowName", workflowName)
        put("at", at)
        put("succeeded", succeeded)
        put("failed", failed)
        put("skipped", skipped)
        put("stopped", stopped)
        put("triggerLabel", triggerLabel)
    }

    companion object {
        fun fromJson(obj: JSONObject): RunRecord? {
            val id = obj.optString("id").ifBlank { return null }
            return RunRecord(
                id = id,
                workflowId = obj.optString("workflowId"),
                workflowName = obj.optString("workflowName", "Routine"),
                at = obj.optLong("at"),
                succeeded = obj.optInt("succeeded"),
                failed = obj.optInt("failed"),
                skipped = obj.optInt("skipped"),
                stopped = obj.optBoolean("stopped"),
                triggerLabel = obj.optString("triggerLabel", "Manual"),
            )
        }
    }
}

/**
 * Persistence for routines and run history.
 *
 * Stored as JSON in SharedPreferences: routines are small, and pulling in a database or a
 * serialization plugin for a handful of records is not worth the dependency.
 *
 * The stored blob carries a schema number. Version 1 (a flat list of steps, no triggers) is
 * migrated on read rather than discarded, so an update can never wipe routines the operator built.
 */
object AutomationStore {

    private const val PREFS = "jarvis_automation"
    private const val KEY_ROUTINES = "routines"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_SEEDED = "seeded_v1"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 50

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -----------------------------------------------------------------------------------------
    // Routines
    // -----------------------------------------------------------------------------------------

    fun load(context: Context): List<Workflow> {
        val store = prefs(context)
        if (!store.getBoolean(KEY_SEEDED, false)) {
            save(context, presets())
            store.edit().putBoolean(KEY_SEEDED, true).apply()
        }

        val raw = store.getString(KEY_ROUTINES, null) ?: return presets()
        return try {
            val array = JSONArray(raw)
            val loaded = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    Workflow.fromJson(obj)?.let { add(it) }
                }
            }.ifEmpty { presets() }

            // Routines saved before fingerprints existed get one once, not on every read. Until
            // that happens their fingerprint is blank, which matches everything — the check only
            // starts protecting a routine after it has been written once by this build.
            if (loaded.any { it.fingerprint.isBlank() }) save(context, loaded)
            loaded
        } catch (t: Throwable) {
            // A corrupt blob must not brick the module: fall back to the presets.
            presets()
        }
    }

    fun save(context: Context, workflows: List<Workflow>) {
        val array = JSONArray()
        // Stamped at the moment of writing: from here on, the stored hash describes exactly what
        // the engine will load back, so a later disagreement means the data changed on its own.
        workflows.forEach { array.put(it.stamped().toJson()) }
        prefs(context).edit()
            .putString(KEY_ROUTINES, array.toString())
            .putInt(KEY_SCHEMA, Workflow.SCHEMA)
            .apply()
    }

    fun upsert(context: Context, workflow: Workflow) {
        val all = load(context).toMutableList()
        val index = all.indexOfFirst { it.id == workflow.id }
        if (index >= 0) all[index] = workflow else all.add(workflow)
        save(context, all)
    }

    fun delete(context: Context, workflowId: String) {
        save(context, load(context).filterNot { it.id == workflowId })
    }

    fun setEnabled(context: Context, workflowId: String, enabled: Boolean) {
        val all = load(context).map {
            if (it.id == workflowId) it.copy(enabled = enabled) else it
        }
        save(context, all)
    }

    fun newWorkflow(): Workflow = Workflow(
        id = UUID.randomUUID().toString(),
        name = "New routine",
        steps = emptyList(),
    )

    // -----------------------------------------------------------------------------------------
    // History
    // -----------------------------------------------------------------------------------------

    fun recordRun(context: Context, finished: RunState.Finished) {
        if (finished.workflowId.isBlank()) return
        RunHistory.record(
            context,
            RunRecord(
                id = UUID.randomUUID().toString(),
                workflowId = finished.workflowId,
                workflowName = finished.workflowName,
                at = System.currentTimeMillis(),
                succeeded = finished.succeeded,
                failed = finished.failed,
                skipped = finished.skipped,
                stopped = finished.stopped,
                triggerLabel = finished.triggerLabel,
            ),
        )
    }

    /**
     * The newest runs, newest first.
     *
     * The records themselves live in the database now ([RunHistory]); this is the one entry point the
     * screens call, kept so nothing above this layer had to change.
     */
    fun history(context: Context): List<RunRecord> = RunHistory.recent(context, MAX_HISTORY)

    fun clearHistory(context: Context) = RunHistory.clear(context)

    /** The pre-Room preference, read once by [RunHistory] when it imports. */
    internal fun legacyHistory(context: Context): String? = prefs(context).getString(KEY_HISTORY, null)

    internal fun forgetLegacyHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    // -----------------------------------------------------------------------------------------
    // Presets
    // -----------------------------------------------------------------------------------------

    /**
     * Starter routines.
     *
     * Deliberately small and useful: each one uses only actions a normal app is allowed to perform,
     * so they work before any special access is granted. The two condition-based ones demonstrate
     * branching without needing a single permission.
     */
    fun presets(): List<Workflow> = listOf(
        Workflow(
            id = "preset-night",
            name = "NIGHT MODE",
            builtIn = true,
            trigger = RoutineTrigger(TriggerKind.DAILY, "23:00"),
            steps = listOf(
                WorkflowStep(ActionType.TORCH_OFF),
                WorkflowStep(ActionType.SET_VOLUME, "25"),
                WorkflowStep(ActionType.DISMISS_NOTIFICATIONS),
            ),
        ),
        Workflow(
            id = "preset-focus",
            name = "FOCUS BLOCK",
            builtIn = true,
            steps = listOf(
                WorkflowStep(ActionType.SET_VOLUME, "10"),
                WorkflowStep(ActionType.DISMISS_NOTIFICATIONS),
                WorkflowStep(ActionType.OPEN_PANEL, "dnd"),
                WorkflowStep(ActionType.SET_TIMER, "1500"),
            ),
        ),
        Workflow(
            id = "preset-low-battery",
            name = "LOW BATTERY DISCIPLINE",
            builtIn = true,
            trigger = RoutineTrigger(TriggerKind.BATTERY_LOW, "20"),
            steps = listOf(
                WorkflowStep(ActionType.TORCH_OFF),
                WorkflowStep(ActionType.SET_VOLUME, "30"),
                WorkflowStep(
                    action = ActionType.SAY,
                    param = "Battery is low. I have dimmed things down.",
                ),
            ),
        ),
        Workflow(
            id = "preset-arrival",
            name = "ON WI-FI · SETTLE IN",
            builtIn = true,
            trigger = RoutineTrigger(TriggerKind.WIFI_CONNECTED),
            steps = listOf(
                WorkflowStep(
                    action = ActionType.IF_BLOCK,
                    condition = Condition(ConditionType.CHARGING),
                    children = listOf(WorkflowStep(ActionType.SET_VOLUME, "60")),
                    elseChildren = listOf(WorkflowStep(ActionType.SET_VOLUME, "35")),
                ),
                WorkflowStep(ActionType.DISMISS_NOTIFICATIONS),
            ),
        ),
    )
}
