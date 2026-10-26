package com.jarvis.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service used **only** for two narrow automation primitives:
 *
 *  1. **System gestures** — back, home, recents, notification shade, quick settings, lock screen.
 *  2. **Press a labelled control** — a single tap-equivalent on a node whose text or description
 *     exactly matches what the operator typed, in the currently active window.
 *
 * Both are gated: the service does nothing at all until the user enables it in system settings, and
 * [pressByText] additionally requires the separate "press on screen" tier to be switched on inside
 * JARVIS, plus per-step confirmation before every use.
 *
 * Deliberately out of scope: reading screen content in the background, scraping text, monitoring
 * typing, gesture dispatch, or navigating arbitrary UI trees. If a routine needs those, it needs a
 * real app integration instead.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: JARVIS never reads content in the background. Everything happens
        // synchronously when a routine step asks for it.
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    override fun onDestroy() {
        connected = false
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: JarvisAccessibilityService? = null

        @Volatile
        var connected: Boolean = false
            private set

        /** Performs a global navigation action. Returns false when the service is not connected. */
        fun perform(action: Int): Boolean {
            val service = instance ?: return false
            return try {
                service.performGlobalAction(action)
            } catch (t: Throwable) {
                false
            }
        }

        /**
         * Finds a clickable node whose text, content description or hint exactly equals [label]
         * (case-insensitive) and presses it.
         *
         * Bounded on purpose: single window, depth- and count-limited traversal, exact match only.
         * No fuzzy matching, no partial text, no "press whatever is first".
         */
        fun pressByText(label: String): Boolean {
            val service = instance ?: return false
            if (!AccessibilityStatus.isScreenPressAllowed(service)) return false
            if (label.isBlank()) return false

            val root: AccessibilityNodeInfo = try {
                service.rootInActiveWindow ?: return false
            } catch (t: Throwable) {
                return false
            }

            return try {
                val target = findExact(root, label, depth = 0, counter = intArrayOf(0))
                val clickable = target?.let { nearestClickable(it) }
                val performed = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                performed
            } catch (t: Throwable) {
                false
            } finally {
                @Suppress("DEPRECATION")
                try {
                    root.recycle()
                } catch (t: Throwable) {
                    // Ignore: recycling is best-effort on modern API levels.
                }
            }
        }

        private fun findExact(
            node: AccessibilityNodeInfo,
            label: String,
            depth: Int,
            counter: IntArray,
        ): AccessibilityNodeInfo? {
            if (depth > 24 || counter[0] > 800) return null
            counter[0]++

            if (node.isVisibleToUser && node.matchesLabel(label)) return node

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                val found = findExact(child, label, depth + 1, counter)
                if (found != null) return found
            }
            return null
        }

        private fun AccessibilityNodeInfo.matchesLabel(label: String): Boolean {
            val candidates = listOfNotNull(text, contentDescription, hintText, tooltipText)
            return candidates.any { it.toString().trim().equals(label.trim(), ignoreCase = true) }
        }

        private fun nearestClickable(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var node: AccessibilityNodeInfo? = start
            var hops = 0
            while (node != null && hops < 6) {
                if (node.isClickable) return node
                node = try {
                    node.parent
                } catch (t: Throwable) {
                    null
                }
                hops++
            }
            return start.takeIf { it.isClickable }
        }
    }
}

/**
 * Whether automation access is granted, and the two-tier consent that guards screen pressing.
 */
object AccessibilityStatus {

    private const val PREFS = "jarvis_automation"
    private const val KEY_SCREEN_PRESS = "screen_press_allowed"

    /** True when the user has enabled JARVIS in Settings → Accessibility. */
    fun isServiceEnabled(context: Context): Boolean {
        if (JarvisAccessibilityService.connected) return true
        val expected = ComponentName(context, JarvisAccessibilityService::class.java)
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        } catch (t: Throwable) {
            null
        } ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val parsed = ComponentName.unflattenFromString(entry) ?: continue
            if (parsed == expected) return true
        }
        return false
    }

    /**
     * Second consent tier: pressing buttons in other apps. Off by default and independent of the
     * service switch, so enabling the service alone only unlocks navigation gestures.
     */
    fun isScreenPressAllowed(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_PRESS, false)

    fun setScreenPressAllowed(context: Context, allowed: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREEN_PRESS, allowed)
            .apply()
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
