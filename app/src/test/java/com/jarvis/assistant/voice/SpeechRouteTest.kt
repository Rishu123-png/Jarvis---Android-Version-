package com.jarvis.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRouteTest {

    @Test
    fun localRoutesNeverUseJARVISCloudPlanningOrProviderTranscription() {
        listOf(SpeechRoute.DEVICE, SpeechRoute.OFFLINE).forEach { route ->
            assertFalse(route.allowsCloudPlanner)
            assertFalse(route.allowsProviderTranscription)
        }
    }

    @Test
    fun cloudRoutesAreExplicitlyProviderEnabled() {
        listOf(SpeechRoute.AUTO, SpeechRoute.WHISPER).forEach { route ->
            assertTrue(route.allowsCloudPlanner)
            assertTrue(route.allowsProviderTranscription)
        }
    }
}
