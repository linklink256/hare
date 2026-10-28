package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeamReplyTextTest {
    @Test fun `empty text parts do not create a report`() {
        assertNull(fullTeamReplyText(emptySequence()))
    }

    @Test fun `whitespace only remains absent`() {
        assertNull(fullTeamReplyText(sequenceOf("  ", "\n\t")))
    }

    @Test fun `report beyond both previous preview sizes keeps its tail`() {
        val report = "长报告".repeat(4000) + "\nFINAL-CONCLUSION"
        assertEquals(report, fullTeamReplyText(sequenceOf(report)))
    }

    @Test fun `unicode surrogate pairs remain intact across former boundary`() {
        val report = "a".repeat(399) + "😀" + "中".repeat(1001) + "🚀结论"
        assertEquals(report, fullTeamReplyText(sequenceOf(report)))
    }

    @Test fun `multiple text parts preserve their order and complete content`() {
        val first = "甲".repeat(1200)
        val second = "乙".repeat(1500)
        assertEquals("$first\n$second", fullTeamReplyText(sequenceOf(first, second)))
    }

    @Test fun `code indentation and trailing newlines are not trimmed`() {
        val report = "  indented code\n\n"
        assertEquals(report, fullTeamReplyText(sequenceOf(report)))
    }

    @Test fun `empty parts preserve existing text part separators`() {
        assertEquals("\nbody\n", fullTeamReplyText(sequenceOf("", "body", "")))
    }
}
