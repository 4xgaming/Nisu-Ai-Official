package ai.nisu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildChatTest {
    @Test fun detectsBuildRequests() {
        assertTrue(BuildChat.looksLikeBuild("amar jonno ekta portfolio website banao"))
        assertTrue(BuildChat.looksLikeBuild("আমার জন্য একটা ওয়েবসাইট বানাও"))
        assertTrue(BuildChat.looksLikeBuild("make me a calculator app"))
        assertTrue(BuildChat.looksLikeBuild("ekta notes apk bana do"))
        assertTrue(BuildChat.looksLikeBuild("मेरे लिए एक वेबसाइट बनाओ"))
    }

    @Test fun ignoresNormalChat() {
        assertFalse(BuildChat.looksLikeBuild("tumi ki korcho?"))
        assertFalse(BuildChat.looksLikeBuild("আজকে খুব ক্লান্ত লাগছে"))
        assertFalse(BuildChat.looksLikeBuild("which app is best for music?"))
    }
}
