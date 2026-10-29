package ai.nisu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstGuardTest {
    private fun msg(id: Long, at: Long, exact: Boolean = true, written: Long = at) = ProcessedMessage(
        messageId = id, conversationId = "c1", source = MessageSource.WHATSAPP, packageName = "com.whatsapp", appName = "WhatsApp",
        kind = ConversationKind.PRIVATE, chatTitle = "A", groupName = null, senderName = "A", senderLabel = "A", text = "hi",
        timestamp = written, timestampExact = exact, receivedAt = at, isBacklog = false, notificationId = 1, notificationKey = "k", attempt = 0
    )

    @Test fun nothingAnsweredYetCoversNothing() {
        assertFalse(BurstGuard.answered(msg(5, 1_000), 0L, 0L))
    }

    @Test fun messagesUpToTheAnsweredOneAreCovered() {
        assertTrue(BurstGuard.answered(msg(4, 1_000), 5L, 9_000L))
        assertTrue(BurstGuard.answered(msg(5, 2_000), 5L, 9_000L))
    }

    @Test fun aMessageThatArrivedAfterTheReplyWasDraftedIsNew() {
        // received BEFORE the reply finished (time 8 000 < barrier 9 000) but stored after the answered one: it must get its own reply
        assertFalse(BurstGuard.answered(msg(6, 8_000), 5L, 9_000L))
    }

    @Test fun anOldMessageShownAgainIsStillRecognised() {
        // written at 1 000, first seen at 500 000 (re-posted), the reply barrier is later than its time
        assertTrue(BurstGuard.answered(msg(9, 500_000, exact = true, written = 1_000), 0L, 9_000L))
    }
}
