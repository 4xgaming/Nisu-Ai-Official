package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyModeRulesTest {
    private val chat = MainBrainReplyWriter.CHAT_RULES
    private val owner = MainBrainReplyWriter.CORE_RULES

    @Test fun chatRulesKeepTheBrandAndTheDeveloper() {
        assertTrue(chat.contains("NISU AI"))
        assertTrue(chat.contains("developed by Raju"))
    }

    @Test fun chatRulesAreNotTheBusyOwnerRole() {
        assertFalse(chat.contains("unavailable"))
        assertFalse(chat.contains("on his behalf"))
        assertTrue(owner.contains("unavailable"))
    }

    @Test fun chatRulesKeepPrivacySafetyAndNoReply() {
        assertTrue(chat.contains(AutoReplyLimits.PRIVACY_TEXT))
        assertTrue(chat.contains(MainBrainReplyWriter.NO_REPLY))
        assertTrue(chat.contains("passwords"))
        assertTrue(chat.contains("<<<MESSAGE>>>"))
    }

    @Test fun chatRepliesMayBeLongerButStayInsideTheSendGuard() {
        assertTrue(MainBrainReplyWriter.CHAT_MAX_REPLY_CHARS > MainBrainReplyWriter.DEFAULT_MAX_REPLY_CHARS)
        assertTrue(MainBrainReplyWriter.CHAT_MAX_REPLY_CHARS <= AutoReplyLimits.MAX_REPLY_CHARS)
        assertEquals(2000, AutoReplyLimits.MAX_REPLY_CHARS)
    }
}
