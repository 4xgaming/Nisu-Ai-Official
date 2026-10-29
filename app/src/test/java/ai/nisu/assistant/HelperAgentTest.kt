package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperAgentTest {
    @Test fun givenKindWins() {
        assertEquals("image", HelperRules.kindOf("IMAGE", "anything"))
        assertEquals("files", HelperRules.kindOf(" files ", "anything"))
        assertEquals("search", HelperRules.kindOf("search", "make an image"))
    }

    @Test fun missingKindIsGuessedFromTheTask() {
        assertEquals("image", HelperRules.kindOf("", "please make an image of a red car"))
        assertEquals("files", HelperRules.kindOf(null, "find the pdf in my download folder"))
        assertEquals("search", HelperRules.kindOf("weird", "bitcoin price today"))
    }

    @Test fun onlyOneModelIsUsedAndItIsThinkingByDefault() {
        assertEquals("gemini-3.8-live", HelperRules.modelOf(""))
        assertEquals("gemini-3.8-live", HelperRules.modelOf(null))
        assertEquals(HelperRules.THINKING_MODEL, HelperRules.modelOf(" models/gemini-3.8-live-extended-thinking "))
        assertEquals("my-model", HelperRules.modelOf(" models/my-model "))
    }

    @Test fun everyErrorHasAPlainHintForTheMainBrain() {
        for (c in listOf("live_rejected", "timeout", "empty", "live_closed", "")) assertTrue(c, HelperRules.errorHint(c).startsWith("The helper") || HelperRules.errorHint(c).startsWith("Gemini"))
        assertTrue(HelperRules.errorHint("live_rejected").contains("key"))
    }

    @Test fun helperFilesAreReadOnly() {
        assertEquals(setOf("file_search", "file_list", "file_read"), HelperRules.FILE_TOOLS)
        assertFalse("file_op" in HelperRules.FILE_TOOLS)
        assertFalse("file_share" in HelperRules.FILE_TOOLS)
    }

    @Test fun clipCutsLongTextAndKeepsShortText() {
        assertEquals("abc", HelperRules.clip("abc", 10))
        assertTrue(HelperRules.clip("x".repeat(50), 10).endsWith("[cut]"))
    }

    @Test fun plainSearchToolsBecomeHelperSearchTasks() {
        assertEquals("bitcoin price today", HelperRules.taskFor("web_search", " bitcoin price today ", ""))
        assertEquals(null, HelperRules.taskFor("web_search", "  ", ""))
        assertTrue(HelperRules.taskFor("visit_page", "", "https://example.com/a")!!.contains("https://example.com/a"))
        assertEquals(null, HelperRules.taskFor("visit_page", "", ""))
        assertEquals(null, HelperRules.taskFor("tap", "x", "y"))
    }

    @Test fun searchAnswersAreAskedShortAndDirectFirst() {
        assertTrue(HelperRules.searchSystem().contains("120 words"))
        assertTrue(HelperRules.searchSystem().contains("START with the direct answer"))
    }

    // v3.119: 3.8 Thinking is the silent agent only: not in the voice model menu, but the agent keeps its own rules (v1alpha, async tools).
    @Test fun thinkingModelIsTheAgentOnlyAndKeepsItsOwnRules() {
        assertTrue(LiveModels.ALL.none { it.id == LiveModels.AGENT_ID })
        assertEquals(null, LiveModels.find(LiveModels.AGENT_ID))
        val r = LiveModels.agentRules(LiveModels.AGENT_ID)
        assertEquals("v1alpha", r.apiVersion); assertEquals(LiveModels.ToolMode.NON_BLOCKING, r.tools); assertEquals("LOW", r.thinkingLevel)
        assertEquals(LiveModels.rules("gemini-3.8-live").apiVersion, LiveModels.agentRules("gemini-3.8-live").apiVersion)
    }

    // v3.128: a quota / rate / session-limit end is its own error, found by its words (Google often closes with 1011, not 1008), and the helper rests.
    @Test fun quotaEndsAreRecognisedByTheirWords() {
        assertTrue(HelperRules.isQuota("closed 1011 You exceeded your current quota, please check your plan and billing details"))
        assertTrue(HelperRules.isQuota("error Expected HTTP 101 (HTTP 429) rate limit"))
        assertTrue(HelperRules.isQuota("closed 1008 RESOURCE_EXHAUSTED"))
        assertTrue(HelperRules.isQuota("closed 1008 Too many concurrent sessions"))
        assertFalse(HelperRules.isQuota("closed 1007 API key not valid"))
        assertFalse(HelperRules.isQuota("closed 1008 models/x is not found"))
        assertFalse(HelperRules.isQuota("closed 1006"))
    }

    @Test fun restTimeFollowsTheKindOfLimit() {
        assertEquals(30 * 60_000L, HelperRules.restMs("quota exceeded: GenerateContentRequestsPerDay limit: 0"))
        assertEquals(20_000L, HelperRules.restMs("Too many concurrent sessions"))
        assertEquals(90_000L, HelperRules.restMs("You exceeded your current quota"))
    }

    @Test fun quotaAndBusyHintsTellTheMainBrainNotToRetry() {
        for (c in listOf("quota", "busy")) assertTrue(c, HelperRules.errorHint(c).startsWith("The helper"))
        assertTrue(HelperRules.errorHint("quota", 90).contains("Do NOT call ask_helper"))
        assertTrue(HelperRules.errorHint("quota", 90).contains("1 min"))
        assertTrue(HelperRules.errorHint("quota", 20).contains("20 s"))
    }

    @Test fun helperSessionsAreLimited() {
        assertTrue(HelperRules.MAX_PARALLEL in 1..3)
        assertTrue(HelperRules.GATE_WAIT_MS < HelperRules.LIVE_TIMEOUT_MS)
    }

    // v3.129: the helper's Live session stays open between jobs and is closed only after a long idle time.
    @Test fun helperSessionStaysOpenBetweenJobsAndClosesOnlyWhenIdleForLong() {
        assertTrue(HelperRules.IDLE_MS >= 10 * 60_000L)
    }
}
