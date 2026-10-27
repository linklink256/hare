package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TeamMemberListItemTest {
    private val member = TeamMember(
        teamId = Uuid.random(),
        name = "graphics_review",
        conversationId = Uuid.random(),
        role = "PRIVATE_ROLE_TEXT",
    )

    @Test fun generatedTitleIsShownInsteadOfToolNameOrRole() {
        val item = TeamMemberListItem(member, false, "图形内存审查")
        assertEquals("图形内存审查", item.displayTitle)
        assertEquals("graphics_review", item.member.name)
    }

    @Test fun untitledMemberFallsBackToImmutableName() {
        assertEquals(member.name, TeamMemberListItem(member, false, "").displayTitle)
    }

    @Test fun blankTitleFallsBackToImmutableName() {
        assertEquals(member.name, TeamMemberListItem(member, false, " \n").displayTitle)
    }

    @Test fun updatedTitleIsReflectedWithoutChangingIdentity() {
        val original = TeamMemberListItem(member, true, "")
        val updated = original.copy(title = "新标题")
        assertEquals("新标题", updated.displayTitle)
        assertEquals(original.member, updated.member)
        assertTrue(updated.running)
    }

    @Test fun newConversationCanUseAutomaticTitle() {
        assertTrue(needsAutomaticConversationTitle("", null))
        assertTrue(needsAutomaticConversationTitle("", member.name))
    }

    @Test fun legacyTeammateNameIsAPlaceholder() {
        assertTrue(needsAutomaticConversationTitle(member.name, member.name))
    }

    @Test fun actualTeammateTitleIsNotRegenerated() {
        assertFalse(needsAutomaticConversationTitle("图形内存审查", member.name))
    }

    @Test fun ordinaryConversationTitleIsNotTreatedAsTeammatePlaceholder() {
        assertFalse(needsAutomaticConversationTitle(member.name, null))
    }
}
