package com.jarvis.assistant.automation

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * Persistence for routines.
 *
 * Stored as a JSON array in SharedPreferences: routines are small, and pulling in a database or
 * a serialization plugin for a handful of records is not worth the dependency.
 */
object AutomationStore {

    private const val PREFS = "jarvis_automation"
    private const val KEY_ROUTINES = "routines"
    private const val KEY_SEEDED = "seeded_v1"

    fun load(context: Context): List<Workflow> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            save(context, presets())
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }

        val raw = prefs.getString(KEY_ROUTINES, null) ?: return presets()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    Workflow.fromJson(obj)?.let { add(it) }
                }
            }.ifEmpty { presets() }
        } catch (t: Throwable) {
            presets()
        }
    }

    fun save(context: Context, workflows: List<Workflow>) {
        val array = JSONArray()
        workflows.forEach { array.put(it.toJson()) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROUTINES, array.toString())
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

    fun newWorkflow(): Workflow = Workflow(
        id = UUID.randomUUID().toString(),
        name = "New routine",
        steps = emptyList(),
    )

    /**
     * Starter routines.
     *
     * Deliberately small and useful: each one uses only actions a normal app is allowed to perform,
     * so they work before any special access is granted.
     */
    fun presets(): List<Workflow> = listOf(
        Workflow(
            id = "preset-night",
            name = "NIGHT MODE",
            builtIn = true,
            steps = listOf(
                WorkflowStep(ActionType.TORCH_OFF),
                WorkflowStep(ActionType.SET_VOLUME, "25"),
                WorkflowStep(ActionType.DISMISS_NOTIFICATIONS),
            ),
        ),
        Workflow(
            id = "preset-drive",
            name = "LEAVING THE HOUSE",
            builtIn = true,
            steps = listOf(
                WorkflowStep(ActionType.TORCH_OFF),
                WorkflowStep(ActionType.SET_VOLUME, "80"),
                WorkflowStep(ActionType.MEDIA_TOGGLE),
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
    )
}
