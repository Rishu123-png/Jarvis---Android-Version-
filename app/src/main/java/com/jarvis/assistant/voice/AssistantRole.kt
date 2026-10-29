package com.jarvis.assistant.voice

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

/**
 * The assistant seat.
 *
 * Android keeps one app registered as "the assistant". Holding it means the phone's own assistant
 * gesture — press-and-hold Home, the side key, whatever the OEM calls it — opens JARVIS already
 * listening. Nothing listens in the background: the system launches us when the gesture happens,
 * which is why this costs nothing at idle and why it is the honest answer to "wake word".
 *
 * There is no permission to request here. The operator grants the role through the system's own
 * dialog, and can take it back the same way.
 */
object AssistantRole {

    /** `android.app.role.ASSISTANT`. The constant exists from API 29, which is our minSdk. */
    private val ROLE: String = RoleManager.ROLE_ASSISTANT

    enum class State {
        /** JARVIS is the phone's assistant: the gesture opens him. */
        HELD,

        /** This phone can hand out the role, and has not given it to us yet. */
        AVAILABLE,

        /** This phone does not offer the role to third-party apps at all. */
        UNSUPPORTED,
    }

    fun state(context: Context): State {
        val manager = try {
            context.getSystemService(RoleManager::class.java)
        } catch (t: Throwable) {
            null
        } ?: return State.UNSUPPORTED

        return try {
            when {
                !manager.isRoleAvailable(ROLE) -> State.UNSUPPORTED
                manager.isRoleHeld(ROLE) -> State.HELD
                else -> State.AVAILABLE
            }
        } catch (t: Throwable) {
            // Some vendor ROMs throw instead of returning false for an unknown role.
            State.UNSUPPORTED
        }
    }

    /**
     * The system dialog, or null when this phone has nothing to show. Creating the intent never
     * grants anything — the user decides in Android's own window.
     */
    fun request(context: Context): Intent? {
        val manager = try {
            context.getSystemService(RoleManager::class.java)
        } catch (t: Throwable) {
            null
        } ?: return null

        return try {
            if (!manager.isRoleAvailable(ROLE)) null else manager.createRequestRoleIntent(ROLE)
        } catch (t: Throwable) {
            null
        }
    }

    /** One line for the settings panel. */
    fun label(state: State): String = when (state) {
        State.HELD -> "HELD BY JARVIS"
        State.AVAILABLE -> "NOT SET"
        State.UNSUPPORTED -> "NOT OFFERED BY THIS PHONE"
    }

    fun isGreen(state: State): Boolean = state == State.HELD

    /**
     * Where the gesture actually lives on this manufacturer's software, because "press and hold
     * Home" is either wrong or hidden on most of them.
     *
     * Pure on purpose: the caller passes `Build.MANUFACTURER`, so this can be tested without a
     * phone.
     */
    fun gestureNote(manufacturer: String?): String {
        val maker = manufacturer.orEmpty().trim().lowercase()
        return when {
            maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") ->
                "HyperOS / MIUI hides this one: Settings -> Home screen -> turn on \"press and hold " +
                    "Home to open the assistant\", then long-press Home."

            maker.contains("oppo") || maker.contains("realme") || maker.contains("oneplus") ->
                "ColorOS / OxygenOS: Settings -> System settings -> Gesture & motion -> " +
                    "\"Press and hold Home\", or the side key, then pick JARVIS."

            maker.contains("samsung") ->
                "One UI: Settings -> Advanced features -> Side button -> Press and hold -> " +
                    "Assistant app -> JARVIS."

            maker.contains("vivo") || maker.contains("iqoo") ->
                "Funtouch / OriginOS: Settings -> Jovi -> Assistant -> set the default assistant " +
                    "to JARVIS, then long-press Home."

            maker.contains("motorola") || maker.contains("moto") ->
                "Moto: Settings -> Gestures -> press and hold Home, or the power button if you have " +
                    "mapped it to the assistant."

            else ->
                "Long-press Home — or whatever key your phone uses for the assistant. If another " +
                    "assistant opens instead, choose JARVIS under Settings -> Apps -> Default apps -> " +
                    "Digital assistant."
        }
    }
}
