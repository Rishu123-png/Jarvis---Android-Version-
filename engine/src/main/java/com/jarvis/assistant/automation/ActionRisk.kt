package com.jarvis.assistant.automation

/**
 * How much damage an action can do, in one ordered scale.
 *
 * The app used to answer that question with a single boolean, [ActionType.sensitive]. A boolean is
 * enough to decide *whether* to ask, but it cannot say what to ask *about*, and it cannot tell apart
 * "open the camera" from "reply to a message as you". Those two need different sentences, and one
 * of them cannot be taken back.
 *
 * Nothing here decides what runs. It exists so that the rest of the app can ask three questions in
 * plain code:
 *
 *  * may a plan be silently moved between the core and the provider? ([allowsSilentFallback])
 *  * what does this routine's worst step look like, before it is armed? ([of])
 *  * what do we tell the operator they are about to do? ([consequence])
 */
enum class ActionRisk(val severity: Int) {
    /** Looks at things. Nothing on the phone changes. */
    READ_ONLY(1),

    /** Changes the phone, but not anything that outlives a re-tap. */
    REVERSIBLE(2),

    /** Sends, spends, shares or writes something in the operator's name. */
    SENSITIVE(3),

    /** Cannot be taken back once it has happened. */
    IRREVERSIBLE(4),
    ;

    val needsConfirmation: Boolean get() = severity >= SENSITIVE.severity

    companion object {

        /**
         * Things that leave the phone or the operator's name behind, where "undo" is not a button.
         *
         * Deliberately short and explicit. Adding an action to a category by hand is a decision
         * somebody has to make on purpose, which is the point.
         */
        private val ALWAYS_IRREVERSIBLE: Set<ActionType> = setOf(
            ActionType.MESSAGE,
            ActionType.MESSAGE_REPLY,
            ActionType.CALL_CONTACT,
            ActionType.SHELL,
            ActionType.WEBHOOK,
            ActionType.SHARE_TEXT,
            ActionType.CONTACT_ADD,
            ActionType.CALENDAR_ADD,
            ActionType.DOWNLOAD,
        )

        /** Reads only. Nothing is written, sent or toggled. */
        private val NEVER_CHANGES: Set<ActionType> = setOf(
            ActionType.SAY,
            ActionType.READ_SCREEN,
            ActionType.READ_NOTIFICATIONS,
            ActionType.VERIFY,
            ActionType.CALCULATE,
            ActionType.HEALTH,
            ActionType.LOCATION,
            ActionType.DISTANCE,
            ActionType.CALENDAR_READ,
            ActionType.SEARCH_FILES,
            ActionType.OPEN_LAST_PHOTO,
        )

        fun of(action: ActionType): ActionRisk = when {
            action in ALWAYS_IRREVERSIBLE -> IRREVERSIBLE
            action in NEVER_CHANGES -> READ_ONLY
            action.sensitive -> SENSITIVE
            // Touching the screen changes whatever is in front, and a mis-tap can do real damage
            // inside a chat app. It is never held to be harmless just because it is not "sensitive".
            action.needsAccessibility -> SENSITIVE
            else -> REVERSIBLE
        }

        /** The worst step in a plan, which is the only level that matters for a whole routine. */
        fun highest(actions: Iterable<ActionType>): ActionRisk =
            actions.maxOfOrNull { of(it).severity }
                ?.let { worst -> ActionRisk.entries.first { it.severity == worst } }
                ?: READ_ONLY

        /** A routine's risk, from every step it contains, including nested ones. */
        fun of(workflow: Workflow): ActionRisk = highest(workflow.flatSteps.map { it.action })

        /**
         * Whether a plan may be moved between the core on the PC and the operator's key without
         * saying so.
         *
         * A different model can compose a different plan. For looking things up that is invisible;
         * for anything that speaks in the operator's name it is exactly the kind of quiet change
         * that would make him untrustworthy. Sensitive plans are planned by the machine the operator
         * chose, or he is told that it was not.
         */
        fun allowsSilentFallback(risk: ActionRisk): Boolean =
            risk.severity <= REVERSIBLE.severity

        /** One clause about what will happen, for a confirmation the operator actually reads. */
        fun consequence(risk: ActionRisk): String = when (risk) {
            READ_ONLY -> "This only looks at things."
            REVERSIBLE -> "This can be undone."
            SENSITIVE -> "This acts in your name."
            IRREVERSIBLE -> "This cannot be undone."
        }
    }
}
