package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCodeTest {
    @Test fun fencesReadsLanguageAndCode() {
        val b = LiveCodeRules.fences("Here:\n```kotlin\nval a = 1\n```\ntext\n```html\n<p>x</p>\n```")
        assertEquals(2, b.size)
        assertEquals("kotlin", b[0].lang); assertEquals("val a = 1", b[0].code)
        assertEquals("html", b[1].lang)
    }

    @Test fun unclosedLastBlockIsKept() {
        val b = LiveCodeRules.fences("```js\nlet x = 1;\nlet y = 2;")
        assertEquals(1, b.size)
        assertTrue(b[0].code.contains("let y"))
    }

    @Test fun noFenceMeansNoBlocks() = assertTrue(LiveCodeRules.fences("just text").isEmpty())

    @Test fun mergeKeepsPlaceAndCountsRevisions() {
        var l = LiveCodeRules.merge(emptyList(), "a.html", "1", "new", "x", 0)
        l = LiveCodeRules.merge(l, "b.css", "2", "new", "x", 0)
        l = LiveCodeRules.merge(l, "a.html", "3", "new", "x", 0)
        assertEquals(2, l.size)
        assertEquals("a.html", l.last().path); assertEquals(2, l.last().rev); assertEquals("3", l.last().content)
    }

    @Test fun mergeDropsOldestWhenTooMany() {
        var l = emptyList<CodeFile>()
        for (i in 1..LiveCodeRules.MAX_FILES + 5) l = LiveCodeRules.merge(l, "f$i.txt", "x", "new", "x", 0)
        assertEquals(LiveCodeRules.MAX_FILES, l.size)
        assertEquals("f6.txt", l.first().path)
    }

    @Test fun extFor() { assertEquals("kt", LiveCodeRules.extFor("kotlin")); assertEquals("txt", LiveCodeRules.extFor("")) }
}

class LiveCodePartialTest {
    @Test fun completeFilesAreRead() {
        val t = """{"files":[{"path":"index.html","content":"<h1>Hi</h1>\n<p>x</p>"},{"path":"a.css","content":"p{}"}],"notes":"ok"}"""
        val f = LiveCodeRules.partialFiles(t)
        assertEquals(2, f.size)
        assertEquals("index.html", f[0].first); assertEquals("<h1>Hi</h1>\n<p>x</p>", f[0].second)
        assertEquals("p{}", f[1].second)
    }

    @Test fun cutOffFileGrows() {
        val t = """{"files":[{"path":"index.html","content":"<h1>Hi</h1>\n<p>par"""
        val f = LiveCodeRules.partialFiles(t)
        assertEquals(1, f.size)
        assertEquals("<h1>Hi</h1>\n<p>par", f[0].second)
    }

    @Test fun cutInsideEscapeAndKeyIsSafe() {
        assertEquals("x", LiveCodeRules.partialFiles("{\"files\":[{\"path\":\"a.txt\",\"content\":\"x\\u00").first().second)
        assertTrue(LiveCodeRules.partialFiles("{\"files\":[{\"pa").isEmpty())
        assertTrue(LiveCodeRules.partialFiles("{\"files\":[{\"path\":\"a.t").isEmpty())   // the path itself is not finished
        assertTrue(LiveCodeRules.partialFiles("no json").isEmpty())
    }

    @Test fun contentBeforePathAndQuotesInsideCode() {
        val t = """{"files":[{"content":"say \"hi\" \\ done","path":"b.js"}]}"""
        val f = LiveCodeRules.partialFiles(t)
        assertEquals("b.js", f.single().first)
        assertEquals("say \"hi\" \\ done", f.single().second)
    }

    @Test fun streamedFileKeepsItsVersion() {
        var l = LiveCodeRules.merge(emptyList(), "a.html", "<h", "live", "x", 0)
        l = LiveCodeRules.merge(l, "a.html", "<h1>", "live", "x", 0)
        l = LiveCodeRules.merge(l, "a.html", "<h1>done</h1>", "new", "x", 0)
        assertEquals(1, l.single().rev)
    }
}
