package me.rerere.rikkahub.ui.richtext

import me.rerere.ui.markdown.MarkdownRenderWindow

import org.junit.Assert.*
import org.junit.Test

class MarkdownRenderWindowTest {
    @Test fun shortMarkdownUnchanged() {
        val s = "# Test\n\n**bold** 🙂"
        assertEquals(s, MarkdownRenderWindow.slice(s).text)
        assertFalse(MarkdownRenderWindow.slice(s).hasNext)
    }
    @Test fun longSingleLineBounded() {
        assertEquals(MarkdownRenderWindow.MAX_LINE_CHARS, MarkdownRenderWindow.slice("x".repeat(1000000)).text.length)
    }
    @Test fun manyLinesBounded() {
        val w = MarkdownRenderWindow.slice("line\n".repeat(20000))
        assertTrue(w.text.length <= MarkdownRenderWindow.MAX_CHARS)
        assertTrue(w.text.count { it == '\n' } < MarkdownRenderWindow.MAX_LINES)
    }
    @Test fun totalCharacterBudget() {
        val w = MarkdownRenderWindow.slice(("x".repeat(600) + "\n").repeat(100))
        assertTrue(w.text.length <= MarkdownRenderWindow.MAX_CHARS)
        assertTrue(w.hasNext)
    }
    @Test fun unicodeCrLfReconstructsWithoutLoss() {
        val s = ("🙂".repeat(1030) + "\r\n" + "中文\n").repeat(60)
        val parts = mutableListOf<String>()
        var offset = 0
        while (offset < s.length) {
            val w = MarkdownRenderWindow.slice(s, offset)
            assertFalse(w.text.isNotEmpty() && Character.isLowSurrogate(w.text.first()))
            assertFalse(w.text.isNotEmpty() && Character.isHighSurrogate(w.text.last()))
            parts += w.text
            offset = w.end
        }
        assertEquals(s, parts.joinToString(""))
    }
    @Test fun emptyAndUnicodeOffset() {
        assertEquals("", MarkdownRenderWindow.slice("").text)
        assertEquals(0, MarkdownRenderWindow.slice("🙂x", 1).start)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidOffsetRejected() {
        MarkdownRenderWindow.slice("short", 6)
    }
}
