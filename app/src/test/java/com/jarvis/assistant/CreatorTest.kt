package com.jarvis.assistant

import com.jarvis.assistant.data.Creator
import com.jarvis.assistant.voice.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caption engine.
 *
 * Two things are being protected here. First, the platform rules are real: a hook that fits the
 * fold, hashtags that index rather than decorate, no phrase that gets a post down-ranked. Second,
 * and more important, the honesty edges — he drafts and hands over, he never claims to have posted,
 * and a draft he writes can never ship with a blocker.
 */
class CreatorTest {

    private fun draft(topic: String, lang: Lang = Lang.HINGLISH) =
        Creator.draft(Creator.Brief(topic = topic, lang = lang))

    // ---------------------------------------------------------------------------------------------
    // Structure
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aDraftHasAFoldSafeHookAnActionAndHashtags() {
        val caption = draft("reel editing on a phone")
        assertTrue("hook must exist", caption.hook.isNotBlank())
        assertTrue("hook must survive the fold", caption.hookFits)
        assertTrue("a call to action is required", caption.cta.isNotBlank())
        assertTrue(caption.hashtags.size in Creator.RECOMMENDED_HASHTAGS_LOW..Creator.RECOMMENDED_HASHTAGS_HIGH)
        assertTrue(caption.rendered.contains("#"))
    }

    @Test
    fun aDraftIsAlwaysShippable() {
        // Enforce is the promise that what he hands over can actually be posted.
        for (topic in listOf("x", "a very long topic about editing reels on a phone in the rain", "")) {
            val caption = draft(topic)
            assertTrue("no blockers for '$topic'", Creator.blockers(caption).isEmpty())
            assertTrue(caption.length <= Creator.MAX_CAPTION_CHARS)
            assertTrue(caption.hashtags.size <= Creator.MAX_HASHTAGS)
        }
    }

    @Test
    fun theLanguageChangesTheWordsButNotTheRules() {
        val english = draft("phone editing", Lang.EN)
        val hinglish = draft("phone editing", Lang.HINGLISH)
        val hindi = draft("phone editing", Lang.HI)
        assertFalse(english.hook == hinglish.hook)
        assertTrue("Hinglish stays Roman", hinglish.hook.none { it.code in 0x0900..0x097F })
        assertTrue("Hindi is Devanagari", hindi.hook.any { it.code in 0x0900..0x097F })
    }

    @Test
    fun theNotesBecomeTheBody() {
        val caption = Creator.draft(
            Creator.Brief(topic = "cutting on phone", notes = listOf("Beat pe cut", "Text chhota rakho")),
        )
        assertTrue(caption.rendered.contains("Beat pe cut"))
        assertTrue(caption.rendered.contains("Text chhota rakho"))
    }

    // ---------------------------------------------------------------------------------------------
    // Hashtags
    // ---------------------------------------------------------------------------------------------

    @Test
    fun hashtagsComeFromTheirOwnWordsAndAreValid() {
        val tags = Creator.hashtagsFor("reel editing on a phone")
        assertTrue(tags.isNotEmpty())
        for (tag in tags) {
            assertTrue("no spaces in #$tag", !tag.contains(" "))
            assertTrue("no hash inside #$tag", !tag.contains("#"))
            assertEquals("tags are lowercase", tag, tag.lowercase())
        }
        assertTrue("the topic is indexed", tags.any { it.contains("reel") || it.contains("editing") })
    }

    @Test
    fun aTagIsCleanWhateverItIsGiven() {
        assertEquals("reeledit", Creator.tag("#Reel Edit!!"))
        assertEquals("", Creator.tag("###"))
        assertEquals("34", Creator.tag("34"))
    }

    @Test
    fun genericWallsArePenalised() {
        val wall = Creator.Caption(
            hook = "A hook that fits easily",
            body = emptyList(),
            cta = "Save this.",
            hashtags = listOf("love", "instagood", "follow", "like", "happy", "fun"),
        )
        val issues = Creator.lint(wall)
        assertTrue(issues.any { it.message.contains("generic") })
    }

    // ---------------------------------------------------------------------------------------------
    // Linting
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aLongHookIsAFoldWarning() {
        val caption = Creator.Caption(
            hook = "x".repeat(Creator.PREVIEW_CHARS + 20),
            body = emptyList(),
            cta = "Save this.",
            hashtags = Creator.hashtagsFor("reel"),
        )
        assertTrue(Creator.lint(caption).any { it.message.contains("more") })
    }

    @Test
    fun overTheCapsIsABlocker() {
        val tooLong = Creator.Caption("hook", emptyList(), "cta", Creator.hashtagsFor("reel"))
            .copy(body = listOf("word ".repeat(600)))
        assertTrue(Creator.blockers(tooLong).any { it.message.contains("character limit") })

        val tooManyTags = Creator.Caption("hook", emptyList(), "cta", (1..40).map { "tag$it" })
        assertTrue(Creator.blockers(tooManyTags).any { it.message.contains("30") })
    }

    @Test
    fun thePhrasesThatCostReachAreBlockers() {
        val bad = Creator.Caption(
            hook = "do follow for follow to grow",
            body = emptyList(),
            cta = "comment",
            hashtags = Creator.hashtagsFor("reel"),
        )
        assertTrue(Creator.blockers(bad).any { it.message.contains("follow for follow") })
    }

    @Test
    fun aMissingActionIsAWarningAndShoutingIsToo() {
        val quiet = Creator.Caption("There is no action here", emptyList(), "", Creator.hashtagsFor("reel"))
        assertTrue(Creator.lint(quiet).any { it.message.contains("call to action") })

        val loud = Creator.Caption(
            "THIS IS ALL SHOUTING AND IT IS VERY ANNOYING TO READ",
            emptyList(),
            "Save it",
            Creator.hashtagsFor("reel"),
        )
        assertTrue(Creator.lint(loud).any { it.message.contains("shouting") })
    }

    @Test
    fun aHealthyCaptionRaisesNothingSerious() {
        val good = draft("reel editing")
        assertTrue(Creator.lint(good).none { it.severity == Creator.Severity.BLOCKER })
    }

    // ---------------------------------------------------------------------------------------------
    // Reading someone else's draft
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aBlobIsSplitIntoHookBodyAndTags() {
        val parsed = Creator.parse(
            """
            Three things about phone editing
            Beat pe cut karo
            Text chhota rakho
            Save this for later
            #reeledit #reelsindia #phonestorytelling
            """.trimIndent(),
        )
        assertEquals("Three things about phone editing", parsed.hook)
        assertEquals(2, parsed.body.size)
        assertEquals("Save this for later", parsed.cta)
        assertEquals(3, parsed.hashtags.size)
        assertEquals("reelsindia", parsed.hashtags[1])
    }

    @Test
    fun anEmptyBlobIsAnEmptyCaption() {
        assertEquals("", Creator.parse("   ").hook)
    }

    // ---------------------------------------------------------------------------------------------
    // Honesty
    // ---------------------------------------------------------------------------------------------

    @Test
    fun heCannotPostAndSaysWhy() {
        assertFalse("no publishing path exists, by design", Creator.canPost())
        val line = Creator.publishLine(Lang.HINGLISH)
        assertTrue(line.contains("copy"))
        assertTrue(line.contains("Instagram"))
    }

    @Test
    fun whatHeNeedsBeforeADraftIsUseful() {
        val missing = Creator.missingInfo(Creator.Brief(topic = "", notes = emptyList()))
        assertTrue(missing.any { it.contains("about") })
        assertTrue(missing.any { it.contains("points") })
        assertTrue(Creator.missingInfo(Creator.Brief(topic = "editing", notes = listOf("a"))).isEmpty())
    }

    @Test
    fun theModelIsToldTheSameRules() {
        val block = Creator.promptBlock()
        assertTrue(block.contains("125"))
        assertTrue(block.contains("2200"))
        assertTrue(block.contains("follow for follow"))
        assertTrue(block.contains("Never write"))
        assertTrue(block.contains("Hinglish"))
    }

    @Test
    fun theBestTimeOnlyComesFromTheirOwnNumbers() {
        assertEquals(null, Creator.bestWindowLine(null, null, Lang.EN))
        val line = Creator.bestWindowLine("Thursday", 21, Lang.EN)
        assertNotNull(line)
        assertTrue(line!!.contains("Thursday"))
        assertTrue(line.contains("9 PM"))
    }

    // ---------------------------------------------------------------------------------------------
    // Intent routing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun captionRequestsAreRecognised() {
        assertTrue(Creator.wantsCaption("reel ka caption banao"))
        assertTrue(Creator.wantsCaption("caption likho for my new reel"))
        assertTrue(Creator.wantsCaption("hashtag suggest karo"))
        assertFalse(Creator.wantsCaption("open whatsapp"))
    }

    @Test
    fun aPublishingRequestIsRecognisedSoItCanBeRefused() {
        assertTrue(Creator.wantsPublish("instagram pe post kar do"))
        assertTrue(Creator.wantsPublish("reel upload kar do insta pe"))
        assertFalse(Creator.wantsPublish("caption banao"))
    }

    @Test
    fun theTopicIsPulledOutOfTheSentence() {
        assertEquals("phone editing", Creator.topicOf("caption banao about phone editing"))
        assertTrue(Creator.topicOf("meri new reel ka caption likho").contains("new reel"))
    }

    @Test
    fun workInsideAnotherAppsScreensIsRecognised() {
        assertTrue(Creator.wantsForeignApp("arena website open karo"))
        assertTrue(Creator.wantsForeignApp("influesh app kholo"))
        assertFalse(Creator.wantsForeignApp("open whatsapp"))
    }
}
