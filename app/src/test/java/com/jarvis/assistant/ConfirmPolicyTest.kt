package com.jarvis.assistant

import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.voice.ConfirmPolicy
import com.jarvis.assistant.voice.Lang
import com.jarvis.assistant.voice.PlannedStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What waits for a yes, and what does not.
 *
 * This is the one place where being wrong is expensive in both directions: asking about everything
 * makes him slow and annoying, and asking about nothing lets a misheard word call somebody.
 */
class ConfirmPolicyTest {

    @Test
    fun callsAndMessagesAndWritesAsk() {
        assertTrue(ConfirmPolicy.needsAsking(ActionType.CALL_CONTACT, "Amit"))
        assertTrue(ConfirmPolicy.needsAsking(ActionType.DIAL_NUMBER, "9999999999"))
        assertTrue(ConfirmPolicy.needsAsking(ActionType.MESSAGE, "whatsapp|Divik|hi"))
        assertTrue(ConfirmPolicy.needsAsking(ActionType.MESSAGE_REPLY, "Divik|on my way"))
        assertTrue(ConfirmPolicy.needsAsking(ActionType.CONTACT_ADD, "Amit|999"))
        assertTrue(ConfirmPolicy.needsAsking(ActionType.CALENDAR_ADD, "tomorrow 5pm|dentist"))
    }

    @Test
    fun ordinaryControlDoesNotAsk() {
        assertFalse(ConfirmPolicy.needsAsking(ActionType.TORCH_ON, ""))
        assertFalse(ConfirmPolicy.needsAsking(ActionType.OPEN_APP, "com.whatsapp"))
        assertFalse(ConfirmPolicy.needsAsking(ActionType.WIFI, "off"))
        assertFalse(ConfirmPolicy.needsAsking(ActionType.BRIGHTNESS, "40"))
        assertFalse(ConfirmPolicy.needsAsking(ActionType.SHELL, "settings put global airplane_mode_on 0"))
    }

    @Test
    fun shellAsksOnlyWhenItCannotBeUndone() {
        assertTrue(ConfirmPolicy.shellIsFinal("reboot"))
        assertTrue(ConfirmPolicy.shellIsFinal("pm clear com.whatsapp"))
        assertTrue(ConfirmPolicy.shellIsFinal("cmd package uninstall com.example"))
        assertTrue(ConfirmPolicy.shellIsFinal("rm -rf /sdcard/Download"))
        assertFalse(ConfirmPolicy.needsAsking(ActionType.SHELL, "svc wifi disable"))
        // svc is harmless; it must not be caught by a substring of another rule.
        assertFalse(ConfirmPolicy.shellIsFinal("svc wifi disable"))
        assertFalse(ConfirmPolicy.shellIsFinal("settings put system screen_brightness 120"))
    }

    @Test
    fun theQuestionNamesThePersonAndTheMessage() {
        val question = ConfirmPolicy.question(
            PlannedStep(ActionType.MESSAGE, "whatsapp|Divik|on my way", "SEND"),
            Lang.EN,
        )
        assertTrue(question.contains("Divik"))
        assertTrue(question.contains("on my way"))
        assertTrue(question.contains("yes", ignoreCase = true))

        val call = ConfirmPolicy.question(PlannedStep(ActionType.CALL_CONTACT, "Amit", "CALL"), Lang.EN)
        assertTrue(call.startsWith("Call Amit?"))
    }

    @Test
    fun theQuestionIsInTheOperatorsLanguage() {
        val step = PlannedStep(ActionType.CALL_CONTACT, "Amit", "CALL")
        val hindi = ConfirmPolicy.question(step, Lang.HI)
        val hinglish = ConfirmPolicy.question(step, Lang.HINGLISH)
        val english = ConfirmPolicy.question(step, Lang.EN)

        assertTrue(hindi.any { it.code in 0x0900..0x097F })
        assertTrue(hinglish.contains("Haan"))
        assertTrue(english.contains("Say yes"))
        assertTrue(hindi != english && hinglish != english)
    }

    @Test
    fun theFirstStepThatNeedsAskingIsFound() {
        val steps = listOf(
            PlannedStep(ActionType.TORCH_ON, "", "TORCH"),
            PlannedStep(ActionType.CALL_CONTACT, "Amit", "CALL"),
            PlannedStep(ActionType.MESSAGE, "whatsapp|Divik|hi", "SEND"),
        )
        val first = ConfirmPolicy.firstAskable(steps)
        assertTrue(first != null && first.action == ActionType.CALL_CONTACT)
        assertNull(ConfirmPolicy.firstAskable(listOf(PlannedStep(ActionType.TORCH_ON, "", "TORCH"))))
    }
}
