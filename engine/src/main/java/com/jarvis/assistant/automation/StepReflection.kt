package com.jarvis.assistant.automation

/**
 * Asking the screen whether the step actually did anything.
 *
 * The screen agent used to be open-loop: it read the page, decided an action, performed it, and moved
 * on. If the tap had missed — a row that scrolled, a button that was off-screen, a label shared by
 * three rows — the loop had no way of knowing, so it repeated the same action until it ran out of
 * steps, then gave up with "I could not find that".
 *
 * The fix is cheaper than a second model call. Compare a *fingerprint* of the screen before and after
 * the action. The screen is a string of visible labels ([ScreenMap.render]); if the string is
 * identical afterwards, then either the input never landed or the app ignored it, and both mean the
 * same thing: try the same control a different way, by resource id, before spending another thought.
 *
 * Pure, so the rule has a test that does not need a phone.
 */
object StepReflection {

    /** What an action did, as far as the screen admits. */
    enum class Verdict {
        /** The screen or the app in front changed. Carry on. */
        PROGRESSED,

        /** The screen did not move after an action that should have moved it. */
        NO_EFFECT,

        /** The action was taken, but the screen could not be read afterwards. */
        BLIND,

        /** Nothing to compare — the caller has no "before". */
        NOT_CHECKED,
        ;

        val moved: Boolean get() = this == PROGRESSED
    }

    /**
     * Actions that must change something. A `wait` is not here: waiting for a spinner and getting the
     * same screen back is a success, not a miss, and retrying it would just add seconds.
     */
    private val MUTATING = setOf(
        "press", "tap", "type", "scroll", "swipe", "open_app",
    )

    fun mutating(action: String): Boolean = action.trim().lowercase() in MUTATING

    /**
     * The verdict for one step.
     *
     * `before` and `after` are fingerprints of the whole screen; `appBefore`/`appAfter` are the
     * package in front. An app change counts as progress even when the text happens to match —
     * "Settings" → "Settings" across two different apps is a real move.
     */
    fun of(
        action: String,
        before: String?,
        after: String?,
        appBefore: String = "",
        appAfter: String = "",
    ): Verdict {
        if (before.isNullOrBlank()) return Verdict.NOT_CHECKED
        if (after.isNullOrBlank()) return Verdict.BLIND
        if (before == after) {
            if (appBefore.isNotBlank() && appAfter.isNotBlank() && appBefore != appAfter) {
                return Verdict.PROGRESSED
            }
            return if (mutating(action)) Verdict.NO_EFFECT else Verdict.PROGRESSED
        }
        return Verdict.PROGRESSED
    }

    /**
     * A better second attempt at the same control, or null when there is nothing better.
     *
     * The first attempt is made with the label, because that is what a model can read. When the
     * label matched more than one row, or the row it matched was not the clickable one, the
     * resource id is exact where the label is ambiguous — so the retry is `#send` rather than
     * "Send", and `JarvisAccessibilityService.pressLoose` routes the `#` form by id.
     */
    fun retryTarget(target: String, items: List<ScreenItem>): String? {
        val wanted = target.trim().removePrefix("#").trim()
        if (wanted.isEmpty()) return null
        if (target.trim().startsWith("#")) return null // already the exact form

        val matches = items.filter { item ->
            item.label.equals(wanted, ignoreCase = true) ||
                item.label.contains(wanted, ignoreCase = true)
        }
        if (matches.isEmpty()) return null

        val usable = matches.filter { it.viewId.isNotBlank() }
        if (usable.isEmpty()) return null

        // Prefer a clickable row; then prefer the one whose label is the exact match.
        val best = usable.firstOrNull { it.clickable && it.label.equals(wanted, ignoreCase = true) }
            ?: usable.firstOrNull { it.clickable }
            ?: usable.first()

        val shortId = best.viewId.substringAfterLast('/')
        if (shortId.isBlank()) return null
        return "#$shortId"
    }
}
