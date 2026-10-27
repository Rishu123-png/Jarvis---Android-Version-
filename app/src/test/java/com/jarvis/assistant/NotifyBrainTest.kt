package com.jarvis.assistant

import com.jarvis.assistant.device.NotificationKind
import com.jarvis.assistant.device.NotifyBrain
import com.jarvis.assistant.voice.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judgement calls behind "read my messages, but do not read me adverts".
 *
 * These are the rules that decide whether the phone talks, so they are tested rather than tuned by
 * ear on a real device.
 */
class NotifyBrainTest {

    @Test
    fun chatsAreMessages() {
        val verdict = NotifyBrain.classify(
            "com.whatsapp", "WhatsApp", "Khushi", "are you coming?", hasReplyAction = true,
        )
        assertEquals(NotificationKind.MESSAGE, verdict.kind)
        assertTrue(verdict.worthTelling)
        assertFalse(verdict.isPromo)
    }

    @Test
    fun shoppingIsAnAdvertisement() {
        val verdict = NotifyBrain.classify(
            "com.flipkart.android", "Flipkart", "Mega Sale", "Flat 70% off. Buy now!", false,
        )
        assertEquals(NotificationKind.PROMO, verdict.kind)
        assertFalse(verdict.worthTelling)
        assertTrue(verdict.promoScore >= 65)
    }

    @Test
    fun offersFromUnknownAppsAreStillAdvertisements() {
        val verdict = NotifyBrain.classify(
            "com.example.shop", "Shoply", "Big deal", "Hurry, limited time offer, 50% off", false,
        )
        assertEquals(NotificationKind.PROMO, verdict.kind)
    }

    @Test
    fun mailIsMail() {
        val verdict = NotifyBrain.classify(
            "com.google.android.gm", "Gmail", "Boss", "Please send the report", false,
        )
        assertEquals(NotificationKind.EMAIL, verdict.kind)
    }

    @Test
    fun oneTimeCodesAreReadWithTheCode() {
        val text = "Your OTP is 482913. Do not share it."
        val verdict = NotifyBrain.classify("com.android.mms", "Messages", "Bank", text, false)
        assertEquals(NotificationKind.OTP, verdict.kind)
        assertEquals("482913", NotifyBrain.extractOtp(text))
        assertTrue(verdict.worthTelling)
    }

    @Test
    fun aNumberWithoutTheWordOtpIsNotACode() {
        assertNull(NotifyBrain.extractOtp("Your order 12345 has shipped"))
    }

    @Test
    fun quietHoursWrapPastMidnight() {
        assertTrue(NotifyBrain.isQuietHour(23, 23, 7))
        assertTrue(NotifyBrain.isQuietHour(3, 23, 7))
        assertTrue(NotifyBrain.isQuietHour(6, 23, 7))
        assertFalse(NotifyBrain.isQuietHour(7, 23, 7))
        assertFalse(NotifyBrain.isQuietHour(12, 23, 7))
        assertFalse(NotifyBrain.isQuietHour(1, 0, 0))
    }

    @Test
    fun suggestionsAreThreeDistinctAndFitTheMessage() {
        val question = NotifyBrain.suggestions("Are you coming tonight?", "Khushi", Lang.EN)
        assertEquals(3, question.size)
        assertEquals(3, question.distinct().size)

        val hindi = NotifyBrain.suggestions("aa rahe ho?", "Khushi", Lang.HI)
        assertEquals(3, hindi.size)
        assertTrue(hindi != question)
    }

    @Test
    fun spokenLinesSayWhatTheThingIs() {
        val promo = NotifyBrain.classify("com.flipkart.android", "Flipkart", "Sale", "70% off", false)
        val line = NotifyBrain.announceLine(promo, "Flipkart", "Flipkart", "70% off", Lang.EN)
        assertTrue(line.contains("Ignoring"))

        val message = NotifyBrain.classify("com.whatsapp", "WhatsApp", "Khushi", "hi", true)
        val spoken = NotifyBrain.announceLine(message, "WhatsApp", "Khushi", "hi", Lang.EN)
        assertTrue(spoken.contains("Khushi"))
        assertTrue(spoken.contains("hi"))
    }
}
