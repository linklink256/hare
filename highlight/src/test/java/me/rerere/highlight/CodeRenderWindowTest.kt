package me.rerere.highlight

import org.junit.Assert.*
import org.junit.Test

class CodeRenderWindowTest {
    @Test fun `small source is unchanged`() {
        for (source in listOf("", "hello", "a\nb", "甲乙😀\r\nnext")) {
            val window = CodeRenderWindow.slice(source)
            assertEquals(source, window.text)
            assertFalse(window.hasNext)
            assertFalse(window.hasPrevious)
        }
    }

    @Test fun `single enormous line has bounded shaping width`() {
        val source = "x".repeat(256 * 1024)
        val window = CodeRenderWindow.slice(source)
        assertEquals(CodeRenderWindow.MAX_LINE_CHARS, window.text.length)
        assertTrue(window.hasNext)
        assertTrue(CodeRenderWindow.preview(source).contains("原始内容保留"))
        assertEquals(256 * 1024, source.length)
    }

    @Test fun `many lines are bounded before layout`() {
        val source = "0123456789\n".repeat(20000)
        val window = CodeRenderWindow.slice(source)
        assertTrue(window.text.lines().size <= CodeRenderWindow.MAX_LINES)
        assertTrue(window.text.length <= CodeRenderWindow.MAX_CHARS)
    }

    @Test fun `total character cap applies even with wide rows`() {
        val source = ("x".repeat(511) + "\n").repeat(100)
        assertEquals(CodeRenderWindow.MAX_CHARS, CodeRenderWindow.slice(source).text.length)
    }

    @Test fun `all pages reconstruct source exactly including unicode and CRLF`() {
        val sources = listOf(
            "long".repeat(30000),
            "甲乙😀\r\n".repeat(4000),
            ("x".repeat(511) + "😀\n").repeat(40),
            "\r\n".repeat(300),
            "\r".repeat(300),
        )
        for (source in sources) {
            val restored = StringBuilder()
            var offset = 0
            while (offset < source.length) {
                val window = CodeRenderWindow.slice(source, offset)
                assertTrue(window.end > offset)
                assertTrue(window.text.length <= CodeRenderWindow.MAX_CHARS)
                assertTrue(window.text.lines().size <= CodeRenderWindow.MAX_LINES)
                if (window.text.isNotEmpty()) {
                    assertFalse(Character.isHighSurrogate(window.text.last()))
                    assertFalse(Character.isLowSurrogate(window.text.first()))
                }
                assertEquals(source.substring(window.start, window.end), window.text)
                restored.append(window.text)
                offset = window.end
            }
            assertEquals(source, restored.toString())
        }
    }

    @Test fun `invalid offsets are rejected`() {
        for (offset in listOf(-1, 4)) {
            assertThrows(IllegalArgumentException::class.java) { CodeRenderWindow.slice("abc", offset) }
        }
    }
}
