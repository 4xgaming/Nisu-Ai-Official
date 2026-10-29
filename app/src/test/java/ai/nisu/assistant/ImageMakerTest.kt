package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMakerTest {
    @Test fun promptGetsMakeAnImagePrefix() {
        assertEquals("Create an image: a girl with black hair, anime style", ImageRules.buildPrompt("a girl with black hair, anime style"))
    }

    @Test fun promptThatAlreadyAsksIsKept() {
        assertEquals("Generate a photo of a red car at sunset", ImageRules.buildPrompt("Generate a photo of a red car at sunset"))
        assertEquals("please draw a logo for a coffee shop", ImageRules.buildPrompt("  please draw a logo for a coffee shop "))
    }

    @Test fun emptyPromptStaysEmptyAndWhitespaceIsTidied() {
        assertEquals("", ImageRules.buildPrompt("   \n "))
        assertEquals("Create an image: a cat on a sofa\n\nrealistic", ImageRules.buildPrompt("a cat   on  a  sofa\n\n\n\nrealistic"))
    }

    @Test fun longPromptIsCut() {
        assertTrue(ImageRules.buildPrompt("x".repeat(5000)).length <= ImageRules.MAX_PROMPT + "Create an image: ".length)
    }

    @Test fun loginPageIsRecognised() {
        assertTrue(ImageRules.looksLoggedOut("Welcome back\nLog in\nSign up\nContinue with Google"))
        assertFalse(ImageRules.looksLoggedOut("Ask anything\nNew chat\nCreate image"))
    }

    @Test fun busyLimitAndRefusal() {
        assertTrue(ImageRules.looksBusy("Creating image..."))
        assertFalse(ImageRules.looksBusy("Here is your picture"))
        assertTrue(ImageRules.hitLimit("You've reached the limit for image generation. Try again later"))
        assertTrue(ImageRules.looksRefused("I'm sorry, but I can't create that"))
        assertFalse(ImageRules.looksRefused("Here is your image"))
    }

    @Test fun waitIsClamped() {
        assertEquals(75, ImageRules.waitSeconds(null))
        assertEquals(20, ImageRules.waitSeconds(1))
        assertEquals(150, ImageRules.waitSeconds(999))
        assertEquals(90, ImageRules.waitSeconds(90))
    }

    @Test fun sendArrowIsFoundByPositionWhenItHasNoName() {
        // message row: box 100..900 wide, y 2000..2100; plus button on the left, dictate and voice mode and an unnamed arrow on the right
        val cands = listOf(
            ImageRules.Cand(20, 2000, 90, 2100, "Add"),
            ImageRules.Cand(700, 2000, 780, 2100, "Dictate"),
            ImageRules.Cand(790, 2000, 840, 2100, "Voice mode"),
            ImageRules.Cand(820, 2010, 890, 2090, ""),
            ImageRules.Cand(820, 300, 890, 380, "")          // far above the row: not the send arrow
        )
        assertEquals(3, ImageRules.pickSendByPosition(100, 2000, 900, 2100, cands))
    }

    @Test fun noSendCandidateGivesNullAndHugeNodesAreIgnored() {
        assertNull(ImageRules.pickSendByPosition(100, 2000, 900, 2100, listOf(ImageRules.Cand(790, 2000, 840, 2100, "Voice mode"))))
        assertNull(ImageRules.pickSendByPosition(100, 2000, 900, 2100, listOf(ImageRules.Cand(0, 1990, 1080, 2110, ""))))
        assertNull(ImageRules.pickSendByPosition(100, 2000, 900, 2100, emptyList()))
    }

    @Test fun badSendLabels() {
        assertTrue(ImageRules.isBadSendLabel("Voice mode"))
        assertTrue(ImageRules.isBadSendLabel("Attach files"))
        assertFalse(ImageRules.isBadSendLabel(""))
        assertFalse(ImageRules.isBadSendLabel("Send message"))
    }

    @Test fun popUpButtonsAreWholeWordsOnly() {
        assertTrue("Not now" in ImageRules.DISMISS_LABELS)
        assertFalse("Continue with Google" in ImageRules.DISMISS_LABELS)
        assertFalse("Log in" in ImageRules.DISMISS_LABELS)
    }
}
