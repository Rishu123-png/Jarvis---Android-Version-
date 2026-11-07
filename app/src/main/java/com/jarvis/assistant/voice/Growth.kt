package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.ai.AiPlanner
import com.jarvis.assistant.ai.PlanResult
import com.jarvis.assistant.automation.AutomationStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * How he gets better at his job, with permission.
 *
 * The one thing he must never do is rewrite himself. An assistant that can change its own code is
 * an assistant nobody can audit, and "it decided to improve itself" is the start of every story
 * where the machine stops being on your side. So growth here is deliberately narrow, and every
 * route through it ends at a human decision:
 *
 *  1. **He writes down what he could not do.** A sentence he did not understand, a permission that
 *     was missing, a feature the phone does not have. That is a [Gap], kept on the phone, visible.
 *  2. **He asks before he learns.** The next time you talk about it, he offers to be taught rather
 *     than assuming: "batao kaise karun".
 *  3. **What he learns is data, not code.** A routine built from your own description, saved
 *     **disabled** in AUTOMATION. He cannot run it, cannot enable it, and cannot delete the record
 *     that it was added. You switch it on, or you never do.
 *
 * The honest ceiling: he cannot add a capability the APK does not contain. What he can do is stop
 * forgetting what you told him, and turn a repeat request into something that happens by itself.
 *
 * The reading half ([wantsCatalogue], [offersToLearn], [summaryLine], [normalise]) is pure and
 * tested; only the storage and the drafting touch Android.
 */
object Growth {

    private const val PREFS = "jarvis_growth"
    private const val KEY_GAPS = "gaps"
    private const val KEY_PENDING = "pending_gap"

    /** Enough to see a pattern, few enough to stay readable. */
    const val MAX_GAPS = 30
    private const val MAX_HEARD_CHARS = 160

    /** One thing he could not do, and how often it has come up. */
    data class Gap(
        val heard: String,
        val reason: String,
        val count: Int = 1,
        val lastAt: Long = System.currentTimeMillis(),
        /** Set once a routine was drafted for it, so he does not draft the same thing twice. */
        val draftedWorkflowId: String? = null,
    ) {
        val key: String get() = normalise(heard)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------------

    fun gaps(context: Context): List<Gap> {
        val raw = prefs(context).getString(KEY_GAPS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (t: Throwable) {
            return emptyList()
        }
        val out = mutableListOf<Gap>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val heard = item.optString("heard")
            if (heard.isBlank()) continue
            out.add(
                Gap(
                    heard = heard,
                    reason = item.optString("reason"),
                    count = item.optInt("count", 1).coerceAtLeast(1),
                    lastAt = item.optLong("at", 0L),
                    draftedWorkflowId = item.optString("drafted").ifBlank { null },
                ),
            )
        }
        return out
    }

    private fun save(context: Context, gaps: List<Gap>) {
        val array = JSONArray()
        for (gap in gaps) {
            array.put(
                JSONObject()
                    .put("heard", gap.heard.take(MAX_HEARD_CHARS))
                    .put("reason", gap.reason.take(120))
                    .put("count", gap.count)
                    .put("at", gap.lastAt)
                    .put("drafted", gap.draftedWorkflowId.orEmpty()),
            )
        }
        prefs(context).edit().putString(KEY_GAPS, array.toString()).apply()
    }

    /**
     * Notes a failure.
     *
     * Repeats bump a counter instead of adding a row: the list should read like a shortlist of
     * things worth fixing, not like a log.
     */
    fun recordGap(context: Context, heard: String, reason: String = "") {
        val text = heard.trim()
        if (text.isBlank()) return
        val key = normalise(text)
        if (key.isBlank()) return

        val existing = gaps(context)
        val match = existing.firstOrNull { it.key == key }
        val updated = if (match != null) {
            existing.map {
                if (it.key == key) it.copy(count = it.count + 1, lastAt = System.currentTimeMillis()) else it
            }
        } else {
            (existing + Gap(heard = text.take(MAX_HEARD_CHARS), reason = reason)).takeLast(MAX_GAPS)
        }
        save(context, updated)
    }

    fun forget(context: Context, gap: Gap) {
        save(context, gaps(context).filterNot { it.key == gap.key })
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_GAPS).remove(KEY_PENDING).apply()
    }

    /** The three worth mentioning: the ones that keep happening. */
    fun worst(context: Context, limit: Int = 3): List<Gap> =
        gaps(context)
            .filter { it.draftedWorkflowId == null }
            .sortedWith(compareByDescending<Gap> { it.count }.thenByDescending { it.lastAt })
            .take(limit)

    // ---------------------------------------------------------------------------------------------
    // The conversation around it
    // ---------------------------------------------------------------------------------------------

    fun setPending(context: Context, gap: Gap?) {
        if (gap == null) {
            prefs(context).edit().remove(KEY_PENDING).apply()
            return
        }
        prefs(context).edit().putString(KEY_PENDING, gap.key).apply()
    }

    fun pending(context: Context): Gap? {
        val key = prefs(context).getString(KEY_PENDING, null) ?: return null
        return gaps(context).firstOrNull { it.key == key }
    }

    /** "what can't you do" / "kya nahi kar sakte" — asked plainly, answered plainly. */
    fun wantsCatalogue(clean: String): Boolean {
        val text = clean.lowercase(Locale.US)
        if (text.isBlank()) return false
        val asks = text.contains("what can't you") || text.contains("what cant you") ||
            text.contains("what can you not") || text.contains("what do you not") ||
            text.contains("kya nahi kar") || text.contains("kya nahi kar sakte") ||
            text.contains("kis cheez mein") || text.contains("kahan fail") ||
            text.contains("what did you fail") || text.contains("where do you fail")
        val aboutAbility = text.contains("do") || text.contains("kar") || text.contains("fail") ||
            text.contains("nahi")
        return asks && aboutAbility
    }

    /** "teach you" / "seekho" / "batao kaise" — the permission he asks for. */
    fun offersToLearn(clean: String): Boolean {
        val text = clean.lowercase(Locale.US)
        if (text.isBlank()) return false
        return text.contains("sikho") || text.contains("seekho") || text.contains("seekh lo") ||
            text.contains("sikha do") || text.contains("teach you") || text.contains("learn this") ||
            text.contains("batao kaise") || text.contains("bata do kaise") ||
            text.contains("how should you") || text.contains("kar ke dikhao")
    }

    /**
     * True when the sentence sounds like instructions rather than a request.
     *
     * This is the gate on turning a sentence into a saved routine, so it is deliberately narrow:
     * after he has offered to be taught, only an actual description ("jab main office jaun to DND on
     * kar do", "every night at 11 turn the torch off") is treated as teaching material. A plain
     * sentence still goes to the normal router.
     */
    fun looksLikeInstruction(clean: String): Boolean {
        val text = clean.lowercase(Locale.US)
        if (text.isBlank()) return false
        val markers = listOf(
            "jab ", "jabse", " jab", "when ", "whenever", "har ", "every ", "roz ", "daily",
            "baje", "o clock", "o'clock", "routine", "karo", "kar do", "kar dena", "banao",
            "banana", "set karo", "on kar", "off kar",
        )
        return markers.any { text.contains(it) } && text.length >= 12
    }

    /** "forget those" / "clear the list". */
    fun wantsForget(clean: String): Boolean {
        val text = clean.lowercase(Locale.US)
        return text.contains("bhool jao kya nahi") || text.contains("clear the failures") ||
            text.contains("forget what you cannot") || text.contains("failures bhool")
    }

    fun summaryLine(lang: Lang, gaps: List<Gap>, total: Int): String {
        if (gaps.isEmpty()) {
            return when (lang.replyLang) {
                Lang.HI -> "अभी कुछ ऐसा नहीं है जो मैं नहीं कर पाया।"
                Lang.HINGLISH -> "Abhi kuch aisa nahi hai jo main nahi kar paya."
                else -> "Nothing I have failed at so far."
            }
        }
        val lines = gaps.mapIndexed { index, gap ->
            "${index + 1}. \"${gap.heard.take(60)}\"" + if (gap.count > 1) " \u00D7${gap.count}" else ""
        }
        val head = when (lang.replyLang) {
            Lang.HI -> "ये चीज़ें मुझसे नहीं हुईं" + if (total > gaps.size) " (कुल $total)" else ""
            Lang.HINGLISH -> "Ye cheezein mujhse nahi hui" + if (total > gaps.size) " (total $total)" else ""
            else -> "These are the things I could not do" + if (total > gaps.size) " ($total total)" else ""
        }
        val tail = when (lang.replyLang) {
            Lang.HI -> "बताइए कैसे करूँ, मैं एक रूटीन बना दूँगा — चालू करना आपका काम।"
            Lang.HINGLISH -> "Batao kaise karun, main ek routine bana dunga — on karna aapka kaam."
            else -> "Tell me how and I will draft a routine — switching it on stays your call."
        }
        return (listOf(head) + lines + tail).joinToString("\n")
    }

    // ---------------------------------------------------------------------------------------------
    // Learning
    // ---------------------------------------------------------------------------------------------

    sealed interface Learned {
        /** A routine was drafted and saved disabled. The operator still has to enable it. */
        data class Drafted(val workflowId: String, val name: String, val steps: Int, val notes: List<String>) : Learned

        data class Refused(val message: String) : Learned
    }

    /**
     * Turns the operator's own description into a routine — saved **off**, never running.
     *
     * The drafting is [AiPlanner]'s job, which already refuses to invent capabilities: it may only
     * pick action ids that exist, it clamps values, and it cannot produce a step that skips a
     * confirmation gate. What this adds is the final rule: whatever comes back is stored disabled,
     * so being taught is always a proposal and never an action.
     */
    suspend fun learn(context: Context, description: String, gap: Gap? = null): Learned {
        val text = description.trim()
        if (text.isBlank()) {
            return Learned.Refused("I did not catch what to do.")
        }
        return when (val result = AiPlanner.plan(context, text)) {
            is PlanResult.Error -> Learned.Refused(result.message)
            is PlanResult.Ok -> {
                val workflow = result.workflow.copy(enabled = false)
                AutomationStore.upsert(context, workflow)
                if (gap != null) {
                    save(
                        context,
                        gaps(context).map {
                            if (it.key == gap.key) it.copy(draftedWorkflowId = workflow.id) else it
                        },
                    )
                }
                setPending(context, null)
                Learned.Drafted(
                    workflowId = workflow.id,
                    name = workflow.name,
                    steps = workflow.steps.size,
                    notes = result.notes,
                )
            }
        }
    }

    /** Said after a draft, so the next step is obvious and the choice stays theirs. */
    fun draftedLine(lang: Lang, name: String, steps: Int): String = when (lang.replyLang) {
        Lang.HI ->
            "\"$name\" नाम से $steps स्टेप का रूटीन बना दिया, बंद हालत में। AUTOMATION में चालू कीजिए, तभी चलेगा।"
        Lang.HINGLISH ->
            "\"$name\" naam se $steps step ka routine bana diya, band haalat mein. AUTOMATION mein on kijiye, tabhi chalega."
        else ->
            "I drafted \"$name\" with $steps steps, switched off. Turn it on in AUTOMATION and only then it runs."
    }

    /** Lowercased words only — the identity of a failure, so repeats bump a counter. */
    fun normalise(text: String): String = text.lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
