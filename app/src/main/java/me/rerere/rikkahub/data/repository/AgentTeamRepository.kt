package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.AgentTeamDAO
import me.rerere.rikkahub.data.db.entity.AgentTeamEntity
import me.rerere.rikkahub.data.db.entity.AgentTeamMemberEntity
import me.rerere.rikkahub.data.db.entity.AgentTeamTaskEntity
import me.rerere.rikkahub.data.model.AgentTeam
import me.rerere.rikkahub.data.model.AgentTeamRules
import me.rerere.rikkahub.data.model.TeamMember
import me.rerere.rikkahub.data.model.TeamMemberStatus
import me.rerere.rikkahub.data.model.TeamTask
import me.rerere.rikkahub.data.model.TeamTaskStatus
import me.rerere.rikkahub.data.model.TeamTaskUpdateResult
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/** 团队会话索引，供会话列表区分「Lead 会话」与「队友会话」 */
data class AgentTeamConversationIndex(
    val leadConversationIds: Set<Uuid> = emptySet(),
    val memberConversationIds: Set<Uuid> = emptySet(),
) {
    companion object {
        val EMPTY = AgentTeamConversationIndex()
    }
}

private fun List<String>.toUuidSet(): Set<Uuid> =
    mapNotNull { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }.toSet()

/**
 * 团队状态（团队 / 名册 / 任务板）的持久化。
 *
 * 只负责状态本身；创建队友会话、派活、等待、打断这些编排在 AgentTeamService。
 */
class AgentTeamRepository(
    private val agentTeamDAO: AgentTeamDAO,
) {
    // ---------- 团队 ----------

    suspend fun getTeam(id: Uuid): AgentTeam? =
        agentTeamDAO.getTeam(id.toString())?.toModel()

    suspend fun getTeamByLeadConversation(conversationId: Uuid): AgentTeam? =
        agentTeamDAO.getTeamByLeadConversation(conversationId.toString())?.toModel()

    /** UI navigation only: membership lookup never grants access to leader tools. */
    suspend fun getTeamForConversation(conversationId: Uuid): AgentTeam? {
        val member = getMemberByConversation(conversationId)
        // Membership wins over legacy nested teams; an orphan must not resolve to another team.
        return if (member != null) getTeam(member.teamId) else getTeamByLeadConversation(conversationId)
    }

    fun observeTeams(): Flow<List<AgentTeam>> =
        agentTeamDAO.listTeams().map { list -> list.map { it.toModel() } }

    suspend fun upsertTeam(team: AgentTeam) {
        agentTeamDAO.upsertTeam(team.copy(updatedAt = Instant.now()).toEntity())
    }

    suspend fun deleteTeam(id: Uuid) {
        agentTeamDAO.deleteMembersOfTeam(id.toString())
        agentTeamDAO.deleteTasksOfTeam(id.toString())
        agentTeamDAO.deleteTeam(id.toString())
    }

    /**
     * 团队与普通会话的对应关系：Lead 会话（要显示团队入口）与队友会话（要从主列表隐藏）。
     * 抽屉列表用它把子代理会话收进团队视图，而不是混在主对话列表里。
     */
    fun observeConversationIndex(): Flow<AgentTeamConversationIndex> =
        combine(
            agentTeamDAO.observeLeadConversationIds(),
            agentTeamDAO.observeMemberConversationIds(),
        ) { leads, members ->
            AgentTeamConversationIndex(
                leadConversationIds = leads.toUuidSet(),
                memberConversationIds = members.toUuidSet(),
            )
        }

    // ---------- 名册 ----------

    suspend fun listMembers(teamId: Uuid): List<TeamMember> =
        agentTeamDAO.listMembers(teamId.toString()).map { it.toModel() }

    fun observeMembers(teamId: Uuid): Flow<List<TeamMember>> =
        agentTeamDAO.observeMembers(teamId.toString()).map { list -> list.map { it.toModel() } }

    suspend fun getMemberByName(teamId: Uuid, name: String): TeamMember? =
        agentTeamDAO.getMemberByName(teamId.toString(), AgentTeamRules.normalizeMemberName(name))
            ?.toModel()

    suspend fun getMemberByConversation(conversationId: Uuid): TeamMember? =
        agentTeamDAO.getMemberByConversation(conversationId.toString())?.toModel()

    suspend fun countMembers(teamId: Uuid): Int = agentTeamDAO.countMembers(teamId.toString())

    suspend fun upsertMember(member: TeamMember) {
        agentTeamDAO.upsertMember(member.toEntity())
    }

    // ---------- 任务板 ----------

    suspend fun listTasks(teamId: Uuid): List<TeamTask> =
        agentTeamDAO.listTasks(teamId.toString()).map { it.toModel() }

    fun observeTasks(teamId: Uuid): Flow<List<TeamTask>> =
        agentTeamDAO.observeTasks(teamId.toString()).map { list -> list.map { it.toModel() } }

    suspend fun getTask(id: Uuid): TeamTask? = agentTeamDAO.getTask(id.toString())?.toModel()

    /**
     * 新建任务。依赖边必须指向同一团队内**已存在**的任务，且不能让图出现环；
     * 违反时抛 [IllegalArgumentException]，而不是写坏状态。
     */
    suspend fun createTask(
        teamId: Uuid,
        title: String,
        description: String,
        blockedBy: List<Uuid>,
        ownerName: String? = null,
        now: Instant = Instant.now(),
    ): TeamTask {
        require(title.isNotBlank()) { "task title must not be blank" }
        val existing = listTasks(teamId)
        val existingIds = existing.map { it.id }.toSet()
        val unknown = blockedBy.filter { it !in existingIds }
        require(unknown.isEmpty()) { "blockedBy references unknown tasks: $unknown" }
        val team = getTeam(teamId) ?: throw IllegalArgumentException("team not found: $teamId")
        require(existing.size < team.maxTasks) { "task board is full (maxTasks=${team.maxTasks})" }

        val id = Uuid.random()
        val blockedByOf = existing.associate { it.id to it.blockedBy }
        require(!AgentTeamRules.wouldCreateCycle(id, blockedBy, { blockedByOf[it] ?: emptyList() })) {
            "task dependencies would create a cycle"
        }

        val task = TeamTask(
            id = id,
            teamId = teamId,
            title = title.trim(),
            description = description,
            ownerName = ownerName,
            blockedBy = blockedBy.distinct(),
            revision = 1,
            createdAt = now,
            updatedAt = now,
        )
        agentTeamDAO.insertTask(task.toEntity())
        return task
    }

    /**
     * compare-and-set 更新任务。[expectedRevision] 与库里不一致时返回
     * [TeamTaskUpdateResult.Conflict]，**不覆盖**别人的更新。
     */
    suspend fun updateTask(
        task: TeamTask,
        expectedRevision: Long,
        title: String = task.title,
        description: String = task.description,
        status: TeamTaskStatus = task.status,
        ownerName: String? = task.ownerName,
        blockedBy: List<Uuid> = task.blockedBy,
        now: Instant = Instant.now(),
    ): TeamTaskUpdateResult {
        require(title.isNotBlank()) { "task title must not be blank" }

        // 依赖图校验要基于"改动后"的视图
        val siblings = listTasks(task.teamId).filter { it.id != task.id }
        val siblingIds = siblings.map { it.id }.toSet()
        val unknown = blockedBy.filter { it !in siblingIds }
        require(unknown.isEmpty()) { "blockedBy references unknown tasks: $unknown" }
        val blockedByOf = siblings.associate { it.id to it.blockedBy }
        require(!AgentTeamRules.wouldCreateCycle(task.id, blockedBy, { blockedByOf[it] ?: emptyList() })) {
            "task dependencies would create a cycle"
        }

        val updated = task.copy(
            title = title.trim(),
            description = description,
            status = status,
            ownerName = ownerName,
            blockedBy = blockedBy.distinct(),
            revision = expectedRevision + 1,
            updatedAt = now,
        )
        val affected = agentTeamDAO.updateTaskCas(
            id = updated.id.toString(),
            revision = expectedRevision,
            title = updated.title,
            description = updated.description,
            status = updated.status.name,
            ownerName = updated.ownerName,
            blockedBy = JsonInstant.encodeToString(updated.blockedBy),
            updatedAt = now.toEpochMilli(),
        )
        if (affected > 0) return TeamTaskUpdateResult.Applied(updated)
        val current = getTask(task.id) ?: return TeamTaskUpdateResult.NotFound
        return TeamTaskUpdateResult.Conflict(current)
    }

    suspend fun deleteTask(id: Uuid) {
        agentTeamDAO.deleteTask(id.toString())
    }

    // ---------- 转换 ----------

    private fun AgentTeamEntity.toModel() = AgentTeam(
        id = Uuid.parse(id),
        leadConversationId = Uuid.parse(leadConversationId),
        title = title,
        maxMembers = maxMembers,
        maxTasks = maxTasks,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )

    private fun AgentTeam.toEntity() = AgentTeamEntity(
        id = id.toString(),
        leadConversationId = leadConversationId.toString(),
        title = title,
        maxMembers = maxMembers,
        maxTasks = maxTasks,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )

    private fun AgentTeamMemberEntity.toModel() = TeamMember(
        id = Uuid.parse(id),
        teamId = Uuid.parse(teamId),
        name = name,
        conversationId = Uuid.parse(conversationId),
        role = role,
        status = runCatching { TeamMemberStatus.valueOf(status) }
            .getOrDefault(TeamMemberStatus.PROVISIONING),
        createdAt = Instant.ofEpochMilli(createdAt),
    )

    private fun TeamMember.toEntity() = AgentTeamMemberEntity(
        id = id.toString(),
        teamId = teamId.toString(),
        name = name,
        conversationId = conversationId.toString(),
        role = role,
        status = status.name,
        createdAt = createdAt.toEpochMilli(),
    )

    private fun AgentTeamTaskEntity.toModel() = TeamTask(
        id = Uuid.parse(id),
        teamId = Uuid.parse(teamId),
        title = title,
        description = description,
        status = runCatching { TeamTaskStatus.valueOf(status) }
            .getOrDefault(TeamTaskStatus.PENDING),
        ownerName = ownerName,
        blockedBy = decodeUuidList(blockedBy),
        revision = revision,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )

    private fun TeamTask.toEntity() = AgentTeamTaskEntity(
        id = id.toString(),
        teamId = teamId.toString(),
        title = title,
        description = description,
        status = status.name,
        ownerName = ownerName,
        blockedBy = JsonInstant.encodeToString(blockedBy),
        revision = revision,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )

    private fun decodeUuidList(raw: String): List<Uuid> {
        if (raw.isBlank()) return emptyList()
        return runCatching { JsonInstant.decodeFromString<List<Uuid>>(raw) }.getOrDefault(emptyList())
    }
}
