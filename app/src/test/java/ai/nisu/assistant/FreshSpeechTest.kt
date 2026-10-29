package ai.nisu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshSpeechTest {
    private val oldPoem = "আকাশ জুড়ে মেঘ করেছে আজ, বৃষ্টি নামে ঝমঝম করে, তোমার কথা মনে পড়ে আজ, মনটা আমার কেমন করে"

    @Test fun songRequestsAreRecognised() {
        assertTrue(NaturalReply.songRequest("ekta gaan shonao"))
        assertTrue(NaturalReply.songRequest("একটা কবিতা বলো"))
        assertTrue(NaturalReply.songRequest("ek shayari sunao"))
        assertTrue(NaturalReply.songRequest("एक शायरी सुनाओ"))
        assertTrue(NaturalReply.songRequest("sing me a song"))
        assertFalse(NaturalReply.songRequest("ajke ki khabo"))
        assertFalse(NaturalReply.songRequest("Singapore trip"))
    }

    @Test fun anOldPoemSungAgainIsCaught() {
        assertTrue(NaturalReply.reusesLines(oldPoem, listOf(oldPoem), "arekta kobita bolo"))
        assertTrue(NaturalReply.needsFresh(oldPoem, listOf(oldPoem), "arekta kobita bolo"))
        // only for a song / poem request
        assertFalse(NaturalReply.reusesLines(oldPoem, listOf("আকাশ জুড়ে মেঘ করেছে আজ, বৃষ্টি নামে ঝমঝম করে, তোমার কথা"), "ki khobor"))
    }

    @Test fun aNewPoemPasses() {
        val fresh = "নদীর ধারে কাশের বন, দুলছে হাওয়ায় সারাক্ষণ, শরৎ এলো নীল আকাশে, সাদা মেঘের ভেলা ভাসে"
        assertFalse(NaturalReply.reusesLines(fresh, listOf(oldPoem), "arekta kobita bolo"))
        assertFalse(NaturalReply.needsFresh(fresh, listOf(oldPoem), "arekta kobita bolo"))
    }

    @Test fun loopingIsCaught() {
        assertTrue(NaturalReply.looping("ভালোবাসি ভালোবাসি ভালোবাসি তোমাকে"))
        assertTrue(NaturalReply.looping("tumi amar\ntumi amar\ntumi amar\nshesh"))
        assertFalse(NaturalReply.looping("না না না, আমি যাব না"))   // short words are normal speech
        assertFalse(NaturalReply.looping("tumi amar\ntumi amar\nshesh"))   // a chorus twice is allowed
    }

    @Test fun theBlockIsInEveryModePrompt() {
        for (m in PersonaMode.ALL) {
            val t = Persona.modeTruth(m)
            assertTrue(t.contains("FRESH WORDS"))
            assertTrue(t.contains("NATIVE SPEECH"))
        }
        assertFalse(FreshSpeech.NOTE.contains("$"))   // plain text, no template
    }

    @Test fun banglaStaysEverydayAndBangladeshi() {
        assertTrue(FreshSpeech.NATIVE_NOTE.contains("Never সাধু"))
        assertTrue(FreshSpeech.NATIVE_NOTE.contains("পানি"))
        assertTrue(FreshSpeech.NATIVE_NOTE.contains("CLEAR AND PERFECT"))
    }

    @Test fun theSongNoteAppearsInTheTypedChatNote() {
        assertTrue(NaturalReply.note("ekta gaan shonao", emptyList(), PersonaMode.CARE).contains("brand-new"))
        assertTrue(NaturalReply.note("ok", emptyList(), PersonaMode.CARE).isEmpty())
    }
}
