package com.jarvis.assistant

import com.jarvis.assistant.voice.VoiceParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken-language parsers.
 *
 * These are the rules that decide whether "whatsapp Divik say hi" writes a message or just opens
 * WhatsApp, so they are worth pinning: a regression here is silent and looks like JARVIS ignoring
 * the operator.
 */
class VoiceParsingTest {

    // ---------------------------------------------------------------------------------------------
    // normalise / flatten
    // ---------------------------------------------------------------------------------------------

    @Test
    fun normalise_stripsPunctuationAndCollapsesWhitespace() {
        assertEquals("call dad", VoiceParsing.normalise("  Call    DAD!!  "))
    }

    @Test
    fun normalise_keepsMeaningfulCharacters() {
        assertEquals("alarm 7:30", VoiceParsing.normalise("Alarm 7:30."))
        assertEquals("volume 50%", VoiceParsing.normalise("Volume 50%"))
    }

    @Test
    fun normalise_preservesDevanagariAndConvertsUnicodeDigits() {
        assertEquals("गैलरी खोलो", VoiceParsing.normalise("गैलरी खोलो"))
        assertEquals("वॉल्यूम 50", VoiceParsing.normalise("वॉल्यूम ५०"))
    }

    @Test
    fun flatten_rewritesChainedChatPhrasing() {
        val flat = VoiceParsing.flatten("open chat with divik and say hi")
        assertEquals("chat with divik say hi", flat)
    }

    // ---------------------------------------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------------------------------------

    @Test
    fun message_appContactAndText() {
        val parsed = VoiceParsing.parseMessage("whatsapp divik say hi")
        assertEquals("whatsapp", parsed?.first)
        assertEquals("divik", parsed?.second)
        assertEquals("hi", parsed?.third)
    }

    @Test
    fun message_flattenedChatPhrase() {
        val parsed = VoiceParsing.parseMessage(VoiceParsing.flatten("open chat with divik and say hi"))
        assertEquals("divik", parsed?.second)
        assertEquals("hi", parsed?.third)
    }

    @Test
    fun message_thatClauseWithSpaces() {
        val parsed = VoiceParsing.parseMessage("send message to mom that i am late")
        assertEquals("mom", parsed?.second)
        assertEquals("i am late", parsed?.third)
    }

    @Test
    fun message_telegramIsDetected() {
        val parsed = VoiceParsing.parseMessage("telegram dad: coming home")
        assertEquals("telegram", parsed?.first)
        assertEquals("dad", parsed?.second)
        assertEquals("coming home", parsed?.third)
    }

    @Test
    fun message_devanagariContactAndTextAreKept() {
        val clean = VoiceParsing.normalise("राहुल को व्हाट्सएप पर संदेश भेजो कि मैं देर से आऊँगा")
        val parsed = VoiceParsing.parseMessage(clean)
        assertEquals("whatsapp", parsed?.first)
        assertEquals("राहुल", parsed?.second)
        assertEquals("मैं देर से आऊँगा", parsed?.third)
    }

    @Test
    fun message_nameOnlyOpensTheChatWithoutText() {
        val parsed = VoiceParsing.parseMessage("whatsapp divik")
        assertEquals("divik", parsed?.second)
        assertEquals("", parsed?.third)
    }

    @Test
    fun message_ignoresNonMessageSentences() {
        assertNull(VoiceParsing.parseMessage("open instagram"))
        assertNull(VoiceParsing.parseMessage("torch on"))
        assertNull(VoiceParsing.parseMessage("volume 40"))
    }

    @Test
    fun callTarget_supportsDevanagariAndHinglish() {
        assertEquals("राहुल", VoiceParsing.parseCallTarget("राहुल को कॉल करो"))
        assertEquals("Amit", VoiceParsing.parseCallTarget("Amit ko call karo"))
        assertEquals("dad", VoiceParsing.parseCallTarget("call dad"))
    }

    // ---------------------------------------------------------------------------------------------
    // Device commands
    // ---------------------------------------------------------------------------------------------

    @Test
    fun volume_readsAndClamps() {
        assertEquals(40, VoiceParsing.parseVolume("volume 40"))
        assertEquals(100, VoiceParsing.parseVolume("set volume to 130"))
        assertEquals(55, VoiceParsing.parseVolume("वॉल्यूम 55"))
        assertEquals(50, VoiceParsing.parseVolume(VoiceParsing.normalise("वॉल्यूम ५०")))
        assertNull(VoiceParsing.parseVolume("volume up"))
    }

    @Test
    fun alarm_handlesTwelveHourAndInvalidTimes() {
        assertEquals("07:30", VoiceParsing.parseAlarm("alarm 7:30"))
        assertEquals("18:30", VoiceParsing.parseAlarm("alarm 6 30 pm"))
        assertEquals("00:15", VoiceParsing.parseAlarm("alarm 12 15 am"))
        assertEquals("19:00", VoiceParsing.parseAlarm("अलार्म रात 7 बजे"))
        assertNull(VoiceParsing.parseAlarm("alarm 25:00"))
    }

    @Test
    fun timer_convertsUnits() {
        assertEquals(600, VoiceParsing.parseTimerSeconds("timer 10 minutes"))
        assertEquals(45, VoiceParsing.parseTimerSeconds("timer 45 sec"))
        assertEquals(7200, VoiceParsing.parseTimerSeconds("timer 2 hours"))
        assertEquals(600, VoiceParsing.parseTimerSeconds("टाइमर 10 मिनट"))
        assertEquals(600, VoiceParsing.parseTimerSeconds(VoiceParsing.normalise("टाइमर १० मिनट")))
        assertEquals(7200, VoiceParsing.parseTimerSeconds("टाइमर 2 घंटे"))
        assertNull(VoiceParsing.parseTimerSeconds("timer please"))
    }

    // ---------------------------------------------------------------------------------------------
    // Camera
    // ---------------------------------------------------------------------------------------------

    @Test
    fun camera_captureAndShow() {
        val ask = VoiceParsing.cameraAsk("open camera captures the image and show me in gallery")
        assertEquals(true, ask?.capture)
        assertEquals(true, ask?.showNewest)
    }

    @Test
    fun camera_captureOnly() {
        val ask = VoiceParsing.cameraAsk("take a picture")
        assertEquals(true, ask?.capture)
        assertEquals(false, ask?.showNewest)
    }

    @Test
    fun camera_openingTheAppIsNotACapture() {
        assertNull(VoiceParsing.cameraAsk("open camera"))
    }

    @Test
    fun camera_lookingAtPhotosIsNotACapture() {
        assertNull(VoiceParsing.cameraAsk("show me my photos"))
    }

    // ---------------------------------------------------------------------------------------------
    // Reminders
    // ---------------------------------------------------------------------------------------------

    @Test
    fun reminder_relativeTime() {
        val ask = VoiceParsing.parseReminder("remind me in 10 minutes to call dad")
        assertEquals(600L, ask?.inSeconds)
        assertNull(ask?.atClock)
        assertEquals("call dad", ask?.text)
    }

    @Test
    fun reminder_hindiRelativeTimeKeepsTheReminderText() {
        val ask = VoiceParsing.parseReminder("मुझे 10 मिनट में दवा लेने की याद दिलाना")
        assertEquals(600L, ask?.inSeconds)
        assertNull(ask?.atClock)
        assertEquals("दवा लेने", ask?.text)
    }

    @Test
    fun reminder_absoluteTime() {
        val ask = VoiceParsing.parseReminder("remind me at 6:30 to leave for the station")
        assertNull(ask?.inSeconds)
        assertEquals("06:30", ask?.atClock)
        assertEquals("leave for the station", ask?.text)
    }

    @Test
    fun reminder_withoutATimeIsNotAReminder() {
        assertNull(VoiceParsing.parseReminder("remind me to call dad"))
    }

    @Test
    fun reminder_ignoresAlarms() {
        assertNull(VoiceParsing.parseReminder("alarm 7:30"))
    }

    // ---------------------------------------------------------------------------------------------
    // Contact names
    // ---------------------------------------------------------------------------------------------

    @Test
    fun normaliseName_matchesAddressBookSpelling() {
        assertEquals("divik sharma", VoiceParsing.normaliseName("Divik  Sharma!"))
        assertTrue(VoiceParsing.normaliseName("  DAD ").isNotEmpty())
        assertEquals("आमिर खान", VoiceParsing.normaliseName("आमिर खान"))
    }
}
