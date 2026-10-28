package me.rerere.highlight

import org.junit.Assert.*
import org.junit.Test

class CodeDisplayContentTest {
    @Test fun expandedSingleLineIsNotCutAtOldWindowBoundary() {
        val code = "x".repeat(30_000) + "END_MARKER"
        val display = CodeDisplayContent.visibleCode(code, collapsed = false)
        assertSame(code, display)
        assertTrue(display.endsWith("END_MARKER"))
    }

    @Test fun expandedMultilineKeepsLastLinePastOldPageLimits() {
        val code = (1..300).joinToString("\n") { "line $it" } + "\nTAIL"
        assertEquals(code, CodeDisplayContent.visibleCode(code, collapsed = false))
    }

    @Test fun expandedKeepsUnicodeAndCrLfWithoutRewriting() {
        val code = "🙂中文\r\n".repeat(5000)
        assertSame(code, CodeDisplayContent.visibleCode(code, collapsed = false))
    }

    @Test fun existingCollapseSettingStillProducesOnlyItsPreview() {
        val code = (1..20).joinToString("\n") { "line $it" }
        assertEquals((1..10).joinToString("\n") { "line $it" }, CodeDisplayContent.visibleCode(code, collapsed = true))
        assertEquals(code, CodeDisplayContent.visibleCode(code, collapsed = false))
    }

    @Test fun collapsedHugeSingleLineIsNotSilentlyTruncated() {
        val code = "x".repeat(20_000)
        assertEquals(code, CodeDisplayContent.visibleCode(code, collapsed = true))
    }

    @Test fun costlyCodeLosesOnlyHighlightingNeverContent() {
        val code = "x".repeat(20_000) + "VISIBLE_TAIL"
        assertFalse(CodeDisplayContent.shouldHighlight(code))
        assertTrue(CodeDisplayContent.visibleCode(code, collapsed = false).endsWith("VISIBLE_TAIL"))
    }

    @Test fun shortCodeStillEligibleForHighlighting() {
        assertTrue(CodeDisplayContent.shouldHighlight("val a = 1"))
        assertTrue(CodeDisplayContent.shouldHighlight("x".repeat(CodeDisplayContent.MAX_HIGHLIGHT_CHARS)))
    }

    @Test fun emptyContentRemainsEmpty() {
        assertEquals("", CodeDisplayContent.visibleCode("", collapsed = false))
    }
}
