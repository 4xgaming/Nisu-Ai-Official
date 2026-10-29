package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCommandParserTest {

    private fun wake(s: String) = assertEquals("should WAKE: '$s'", WakeCommandParser.Command.WAKE, WakeCommandParser.classify(s))
    private fun sleep(s: String) = assertEquals("should SLEEP: '$s'", WakeCommandParser.Command.SLEEP, WakeCommandParser.classify(s))
    private fun none(s: String) = assertEquals("should be NONE: '$s'", WakeCommandParser.Command.NONE, WakeCommandParser.classify(s))

    // ---- every allowed wake phrase
    @Test fun wakeBengali() { wake("হে নিশু"); none("ওকে নিশু") }
    @Test fun wakeEnglish() { wake("Hey Nishu"); none("Okay Nishu") }
    @Test fun wakeAcceptedTranscriptionVariants() {
        wake("হেই নিশু"); wake("হে নিসু"); none("ওকে নিসু"); none("ও কে নিশু")
        wake("hey nisu"); none("ok nishu"); none("ok nisu"); none("o.k. nishu")
        wake("hey নিশু"); none("ওকে nishu"); none("Okay Nisu")
    }
    @Test fun everyDefinedWakePhraseIsAccepted() {
        assertTrue(WakeCommandParser.wakePhrases.isNotEmpty())
        for (p in WakeCommandParser.wakePhrases) wake(p)
    }

    // ---- every allowed sleep command
    @Test fun sleepBengali() { sleep("নিশু স্লিপ"); sleep("নিশু চুপ করো") }
    @Test fun sleepEnglish() { sleep("Nishu Sleep"); sleep("Nishu Chup Koro") }
    @Test fun sleepVariants() { sleep("নিসু স্লিপ"); sleep("nisu sleep"); sleep("নিসু চুপ করো"); sleep("nisu chup koro") }
    @Test fun everyDefinedSleepPhraseIsAccepted() {
        assertTrue(WakeCommandParser.sleepPhrases.isNotEmpty())
        for (p in WakeCommandParser.sleepPhrases) sleep(p)
    }

    // ---- v2.23: name second
    @Test fun sleepNameSecond() {
        sleep("Sleep Nishu"); sleep("sleep nisu"); sleep("স্লিপ নিশু"); sleep("স্লিপ নিসু"); sleep("Sleep, Nisu!")
        none("sleep nisha"); none("sleep nishu now"); none("please sleep nishu"); none("sleep sleep nishu")
        assertFalse(WakeCommandParser.isWake("sleep nishu"))
    }
    @Test fun heyNisuWakes() { wake("Hey Nisu"); wake("hey nisu"); wake("হে নিসু") }

    // ---- wrong names
    @Test fun wrongNamesRejected() {
        none("হে নিশা"); none("ওকে নিশা"); none("হে নীশু"); none("hey nisha"); none("okay nisha"); none("hey nitu")
        none("নিশা স্লিপ"); none("nisha sleep"); none("নিশা চুপ করো")
        none("hey nishus"); none("hey anishu"); none("okay nishu2")
    }

    // ---- partial phrases
    @Test fun partialPhrasesRejected() {
        none("হে"); none("নিশু"); none("ওকে"); none("Hey"); none("Okay"); none("Nishu"); none("nisu"); none("ok")
        none("স্লিপ"); none("sleep"); none("চুপ করো"); none("নিশু চুপ"); none("nishu chup"); none("নিশু স্লি")
        none("")
        none("   ")
    }

    // ---- no loose / substring / fuzzy matching
    @Test fun extraWordsRejected() {
        none("hey nishu what is the weather")
        none("well hey nishu")
        none("hey hey nishu")
        none("হে নিশু আজকের আবহাওয়া কেমন")
        none("ওকে নিশু ওকে")
        none("nishu sleep now")
        none("please nishu sleep")
        none("hey nishu nishu")
        none("নিশু স্লিপ করো")
    }
    @Test fun wakePhraseWordsInWrongOrderRejected() {
        none("নিশু হে"); none("nishu hey"); none("nishu okay"); none("নিশু ওকে"); none("nishu hey"); none("sleep hey nishu")
    }
    @Test fun wakeAndSleepAreNotMixedUp() {
        assertFalse(WakeCommandParser.isSleep("হে নিশু"))
        assertFalse(WakeCommandParser.isWake("নিশু স্লিপ"))
        assertFalse(WakeCommandParser.isWake("Nishu Chup Koro"))
        assertFalse(WakeCommandParser.isSleep("Okay Nishu"))
    }

    // ---- unrelated speech / background noise
    @Test fun unrelatedSpeechRejected() {
        none("what time is it")
        none("আজ আবহাওয়া কেমন")
        none("the quick brown fox jumps over the lazy dog")
        none("hello hello")
        none("hey there")
        none("okay then")
        none("good night")
        none("নিশুর বাড়ি কোথায়")                 // contains the name only as a prefix of another word
        none("this is a hey nishu in the middle of a sentence")
        none("TV: tonight nishu sleep deprived")
    }

    // ---- normalisation
    @Test fun capitalisationAndWhitespace() {
        wake("HEY NISHU"); wake("hEy   NiShU"); wake("  hey nishu  "); wake("hey\tnishu"); wake("hey\nnishu")
        sleep("NISHU SLEEP"); sleep("  Nishu   Chup   Koro "); sleep("nishu\u00A0sleep")
    }
    @Test fun punctuationIsIgnored() {
        wake("Hey, Nishu!"); wake("Hey Nishu."); none("\"Okay, Nishu?\""); wake("হে, নিশু!"); none("ওকে নিশু।"); wake("হে-নিশু")
        sleep("Nishu, sleep!"); sleep("Nishu... sleep."); sleep("নিশু, স্লিপ।"); sleep("নিশু চুপ করো।")
    }
    @Test fun zeroWidthCharactersAreIgnored() {
        wake("হে\u200C নিশু"); wake("হে নিশু\u200B"); none("ওকে\u200D নিশু"); sleep("নিশু স্লি\u200Cপ")
        wake("\uFEFFhey nishu")
    }
    @Test fun normalizeKeepsBengaliCombiningMarks() {
        assertEquals("নিশু স্লিপ", WakeCommandParser.normalize("  নিশু,   স্লিপ। "))
        assertEquals("hey nishu", WakeCommandParser.normalize("Hey, NISHU!!"))
        assertEquals("", WakeCommandParser.normalize("!!! ... ???"))
    }
    @Test fun decomposedUnicodeIsNormalised() {
        // NFD form of a precomposed Bengali letter must still match after NFC normalisation
        val nfd = java.text.Normalizer.normalize("হে নিশু", java.text.Normalizer.Form.NFD)
        wake(nfd)
    }

    // ---- recogniser alternatives and confidence
    @Test fun alternativesAreOnlyAcceptedWhenOneIsExact() {
        assertTrue(WakeCommandParser.findWake(listOf("hey nisha", "hey nishu")))
        assertFalse(WakeCommandParser.findWake(listOf("hey nisha", "hey nitu", "hey")))
        assertFalse(WakeCommandParser.findWake(emptyList()))
    }
    @Test fun onlyTheFirstThreeAlternativesAreLooked() {
        assertFalse(WakeCommandParser.findWake(listOf("a", "b", "c", "hey nishu")))
    }
    @Test fun lowConfidenceAlternativeIgnored() {
        assertFalse(WakeCommandParser.findWake(listOf("hey nishu"), floatArrayOf(0.1f)))
        assertTrue(WakeCommandParser.findWake(listOf("hey nishu"), floatArrayOf(0.9f)))
        assertTrue(WakeCommandParser.findWake(listOf("hey nishu"), floatArrayOf(-1f)))   // unknown confidence
        assertTrue(WakeCommandParser.findWake(listOf("hey nishu"), null))
    }
    @Test fun sleepPhraseIsNeverAWakeAlternative() {
        assertFalse(WakeCommandParser.findWake(listOf("nishu sleep")))
    }
}

class WakeCommandParserLooseTest {
    @org.junit.Test fun looseSleep() {
        for (s in listOf("Nishu sleep", "go to sleep nishu", "hey nishu sleep", "nishu, sleep.", "sleep", "নিশু ঘুমাও", "nisoo sleep", "ok nishu chup koro"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun looseSleepRejectsLongOrUnrelated() {
        for (s in listOf("I cannot sleep tonight because of the noise nishu is loud", "what is the weather", "hey nishu", "good night", ""))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun looseWake() {
        for (s in listOf("Hey Nishu", "hey neeshu", "hay nisu"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isWakeLoose(s))
        for (s in listOf("hey nisha", "nishu", "hey there", "well hey nishu please", "sleep nishu", "ও কে নিশু", "হাই নিশু", "okay nishu", "hi nishu"))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isWakeLoose(s))
    }
}

class WakeCommandParserV240Test {
    @org.junit.Test fun moreSleepWordings() {
        for (s in listOf("নিশু স্লিপ", "Sleep Nishu", "নিশু স্লীপ।", "I said go to sleep nishu", "ঘুমাও নিশু", "nishu ghumao", "nisha sleep", "sleep now", "go to sleep", "hey nishu please sleep"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun sleepStillRejectsSentences() {
        for (s in listOf("I want to sleep", "my baby cannot sleep nishu please tell me why", "good night nishu", "what is the sleep mode of the phone"))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun moreWakeWordings() {
        for (s in listOf("hey neshu", "hey nishu"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isWakeLoose(s))
        for (s in listOf("hey nisha", "nishu", "hey nishu call my mother", "nishu hey", "hey nishu wake up", "hello nishu", "হ্যালো নিশু", "ও নিশু", "hey nishu are you there"))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isWakeLoose(s))
    }
}
