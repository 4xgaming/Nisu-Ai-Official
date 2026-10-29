package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendModeRulesTest {
    private val friend = MainBrainReplyWriter.FRIEND_RULES

    @Test fun friendRulesKeepTheBrandAndTheDeveloper() {
        assertTrue(friend.contains("NISU AI"))
        assertTrue(friend.contains("Raju developed NISU AI"))
    }

    @Test fun friendRulesSpeakOnRajusBehalfButNeverPretendToBeHimOrHuman() {
        assertTrue(friend.contains("on his behalf"))
        assertTrue(friend.contains("not Raju"))
        assertTrue(friend.contains("not a human being"))
    }

    @Test fun friendRulesFollowTheLanguageOfEveryMessage() {
        assertTrue(friend.contains("Hindi"))
        assertTrue(friend.contains("Bengali"))
        assertTrue(friend.contains("English"))
        assertTrue(friend.contains("switch"))
    }

    @Test fun friendRulesKeepPrivacySafetyAndNoReply() {
        assertTrue(friend.contains(AutoReplyLimits.PRIVACY_TEXT))
        assertTrue(friend.contains(MainBrainReplyWriter.NO_REPLY))
        assertTrue(friend.contains("passwords"))
        assertTrue(friend.contains("<<<MESSAGE>>>"))
    }

    @Test fun friendRulesDoNotCopySongLyrics() {
        assertTrue(friend.contains("never copy the lyrics"))
    }

    @Test fun friendIntroTextNamesRajuInEveryLanguage() {
        for (l in listOf(ReplyLanguage.BENGALI, ReplyLanguage.ENGLISH, ReplyLanguage.HINDI)) {
            val t = BrainLanguage.friendIntroText(l)
            assertTrue(t.contains("NISU AI"))
            assertFalse(t.contains("\n"))
        }
        assertEquals(BrainLanguage.FRIEND_INTRO_HI, BrainLanguage.friendIntroText(ReplyLanguage.HINDI))
        assertEquals(BrainLanguage.FRIEND_INTRO_EN, BrainLanguage.friendIntroText(ReplyLanguage.ENGLISH))
    }

    @Test fun friendIsAnOpenTalkModeButNotTheDefaultOne() {
        assertTrue(ReplySettings.Mode.values().any { it == ReplySettings.Mode.FRIEND })
        assertEquals(ReplySettings.Mode.OWNER, ReplySettings.DEFAULT_MODE)
        assertTrue(MainBrainReplyWriter.CHAT_MAX_REPLY_CHARS <= AutoReplyLimits.MAX_REPLY_CHARS)
    }

    @Test fun everyReplyModeKeepsTheEmotionAndToneRuleAndStaysShort() {
        for (r in listOf(MainBrainReplyWriter.CORE_RULES, MainBrainReplyWriter.CHAT_RULES, MainBrainReplyWriter.FRIEND_RULES)) {
            assertTrue(r.contains("Emotion and tone"))
            assertTrue(r.contains("Mirror"))
            assertTrue(r.length < 7_000)
        }
    }
}
