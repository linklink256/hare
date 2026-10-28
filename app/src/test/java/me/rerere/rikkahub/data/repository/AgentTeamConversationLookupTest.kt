package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.dao.AgentTeamDAO
import me.rerere.rikkahub.data.db.entity.AgentTeamEntity
import me.rerere.rikkahub.data.db.entity.AgentTeamMemberEntity
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.uuid.Uuid

/** Exercise the actual repository lookup through a strictly read-only fake DAO. */
class AgentTeamConversationLookupTest {
    private val lead = Uuid.random()
    private val team = team(lead)
    private val teammate = member(team)
    private val teams = mutableMapOf(team.id to team)
    private val members = mutableMapOf(teammate.conversationId to teammate)
    private val calls = mutableListOf<String>()
    private val dao = Proxy.newProxyInstance(
        AgentTeamDAO::class.java.classLoader,
        arrayOf(AgentTeamDAO::class.java),
    ) { _, method, args ->
        calls += method.name
        when (method.name) {
            "getMemberByConversation" -> members[args!![0]]
            "getTeam" -> teams[args!![0]]
            "getTeamByLeadConversation" -> teams.values.firstOrNull { it.leadConversationId == args!![0] }
            else -> error("UI lookup must not write or read messages: ${method.name}")
        }
    } as AgentTeamDAO
    private val repository = AgentTeamRepository(dao)

    @Test fun leaderResolvesOwnTeam() = runTest {
        assertEquals(team.id, repository.getTeamForConversation(lead)?.id.toString())
        assertEquals(listOf("getMemberByConversation", "getTeamByLeadConversation"), calls)
    }

    @Test fun teammateResolvesParentTeamAndLeaderLink() = runTest {
        val actual = repository.getTeamForConversation(Uuid.parse(teammate.conversationId))
        assertEquals(team.id, actual?.id.toString())
        assertEquals(lead, actual?.leadConversationId)
        assertEquals(listOf("getMemberByConversation", "getTeam"), calls)
    }

    @Test fun siblingMembersResolveSameTeam() = runTest {
        val sibling = member(team)
        members[sibling.conversationId] = sibling
        assertEquals(
            repository.getTeamForConversation(Uuid.parse(teammate.conversationId)),
            repository.getTeamForConversation(Uuid.parse(sibling.conversationId)),
        )
    }

    @Test fun differentTeamCannotLeakIntoMemberLookup() = runTest {
        val other = team(Uuid.random())
        teams[other.id] = other
        val otherMember = member(other)
        members[otherMember.conversationId] = otherMember
        assertEquals(other.id, repository.getTeamForConversation(Uuid.parse(otherMember.conversationId))?.id.toString())
        assertEquals(team.id, repository.getTeamForConversation(Uuid.parse(teammate.conversationId))?.id.toString())
    }

    @Test fun unrelatedConversationRemainsTeamlessWithoutCreatingAnything() = runTest {
        assertNull(repository.getTeamForConversation(Uuid.random()))
        assertEquals(1, teams.size)
        assertEquals(1, members.size)
    }

    @Test fun orphanedMemberDoesNotFallBackToLegacyNestedTeam() = runTest {
        teams.remove(team.id)
        val nested = team(Uuid.parse(teammate.conversationId))
        teams[nested.id] = nested
        assertNull(repository.getTeamForConversation(Uuid.parse(teammate.conversationId)))
        assertFalse("getTeamByLeadConversation" in calls)
    }

    @Test fun legacyNestedTeamCannotReplaceActualMembership() = runTest {
        val nested = team(Uuid.parse(teammate.conversationId))
        teams[nested.id] = nested
        assertEquals(team.id, repository.getTeamForConversation(Uuid.parse(teammate.conversationId))?.id.toString())
        assertFalse("getTeamByLeadConversation" in calls)
    }

    @Test fun failedMemberCanStillViewItsParentTeam() = runTest {
        members[teammate.conversationId] = teammate.copy(status = "FAILED")
        assertEquals(team.id, repository.getTeamForConversation(Uuid.parse(teammate.conversationId))?.id.toString())
    }

    private fun team(lead: Uuid) = AgentTeamEntity(
        id = Uuid.random().toString(), leadConversationId = lead.toString(), title = "Team",
        maxMembers = 8, maxTasks = 256, createdAt = 0, updatedAt = 0,
    )

    private fun member(team: AgentTeamEntity) = AgentTeamMemberEntity(
        id = Uuid.random().toString(), teamId = team.id, name = "reviewer",
        conversationId = Uuid.random().toString(), role = "private role, never a title",
        status = "ACTIVE", createdAt = 0,
    )
}
