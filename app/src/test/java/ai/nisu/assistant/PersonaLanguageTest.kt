package ai.nisu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaLanguageTest {
    @Test fun mixedSoundListsAreRewrittenForOneLanguage() {
        val src = "you gasp (\"আরে!\", \"अरे!\"). sounds: \"উফ্\", \"আহা\", \"ইশ\", \"hmm\", \"ओहो\", \"अरे यार\", \"उफ़्\", with pauses. "
        val out = Persona.scrubForOneLanguage(src)
        assertFalse(out.contains("ओहो"))
        assertTrue(out.contains("the small sounds of the language you are speaking"))
    }

    @Test fun everyFixTargetStillExistsInThePersonaSource() {
        // guards against the persona text being edited so that a fix silently stops matching
        var src = java.io.File("src/main/java/ai/nisu/assistant/Tools.kt").readText()
        val listStart = src.indexOf("internal val ONE_LANGUAGE_FIXES")
        val listEnd = src.indexOf("\n    )\n", listStart) + 7
        src = src.substring(0, listStart) + src.substring(listEnd)   // the fix list itself must not count as a match
        for ((from, _) in Persona.ONE_LANGUAGE_FIXES) {
            assertTrue("fix target not found: ${from.take(40)}", src.contains(from))
        }
    }
}
