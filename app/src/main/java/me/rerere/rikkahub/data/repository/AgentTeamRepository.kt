package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.AgentTeamDAO
import me.rerere.rikkahub.data.db.entity.AgentTeamEntity
import me.rerere.rikkahub.data.db.entity.AgentTeamMemberEntity
import me.rerere.rikkahub.data.db.entity.AgentTeamTaskEntity
import me.rerere.rikkahub.data.db.entity.TaskAcceptanceEntity
import me.rerere.rikkahub.data.model.AcceptancePlan
import me.rerere.rikkahub.data.model.AgentTeam
import me.rerere.rikkahub.data.model.AgentTeamRules
import me.rerere.rikkahub.data.model.TaskAcceptance
import me.rerere.rikkahub.data.model.TaskAcceptanceRules
import me.rerere.rikkahub.data.model.TaskDispatchPlan
import me.rerere.rikkahub.data.model.TeamMember
import me.rerere.rikkahub.data.model.TeamMemberStatus
import me.rerere.rikkahub.data.model.TeamTask
import me.rerere.rikkahub.data.model.TeamTaskSchedulingRules
import me.rerere.rikkahub.data.model.TeamTaskStatus
import me.rerere.rikkahub.data.model.TeamTaskUpdateResult
import me.rerere.rikkahub.data.model.describeDispatchRejection
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/** 一次结果验收的结果：接受与置 DONE 在同一事务里完成，重复接受幂等 */
sealed interface TaskAcceptanceResult {
    data class Applied(val task: TeamTask, val acceptance: TaskAcceptance) : TaskAcceptanceResult

    /** 同一 task+revision 已有接受行：不写第二行、不改任务 */
    data class AlreadyAccepted(val task: TeamTask, val acceptance: TaskAcceptance) : TaskAcceptanceResult

    data class Conflict(val current: TeamTask) : TaskAcceptanceResult

    data object NotFound : TaskAcceptanceResult

    data class Rejected(val message: String) : TaskAcceptanceResult
}

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
    /** 生产由 DI 注入；测试/纯查询路径可为 null（此时退化为非事务读改写） */
    private val database: AppDatabase? = null,
) {
    /** 任务图校验与 CAS 在同一事务里：并发改图时不会写坏状态 */
    private suspend fun <T> inTeamTransaction(block: suspend () -> T): T =
        database?.withTransaction { block() } ?: block()

    // ---------- 团队 ----------

    suspend fun getTeam(id: Uuid): AgentTeam? =
        agentTeamDAO.getTeam(id.toString())?.toModel()

    suspend fun getTeamByLeadConversation(conversationId: Uuid): AgentTeam? =
        agentTeamDAO.getTeamByLeadConversation(conversationId.toString())?.toModel()

    /**
     * UI navigation only: membership lookup never grants access to leader tools.
     * 它不是权限/投递来源（成员自己带一队时会解析回父队），那些路径用 [ownedTeam]。
     */
    suspend fun getTeamForConversation(conversationId: Uuid): AgentTeam? {
        val member = getMemberByConversation(conversationId)
        // Membership wins over legacy nested teams; an orphan must not resolve to another team.
        return if (member != null) getTeam(member.teamId) else getTeamByLeadConversation(conversationId)
    }

    /**
     * 该会话**作为 lead** 拥有的团队（= getTeamByLeadConversation）。
     * 权限、跨层投递、预算都用它；成员身份不会被解析成父队。
     */
    suspend fun ownedTeam(conversationId: Uuid): AgentTeam? =
        agentTeamDAO.getTeamByLeadConversation(conversationId.toString())?.toModel()

    /** 直接子队（成员自己建出来的下一层） */
    suspend fun listSubTeams(teamId: Uuid): List<AgentTeam> =
        agentTeamDAO.listSubTeams(teamId.toString()).map { it.toModel() }

    /** 同一根账本下的团队 id（含根自己）；级联取消/跨层公告按根团队收口时用 */
    suspend fun listTeamIdsUnderRoot(rootTeamId: Uuid): List<Uuid> =
        agentTeamDAO.listTeamIdsUnderRoot(rootTeamId.toString()).mapNotNull { raw ->
            runCatching { Uuid.parse(raw) }.getOrNull()
        }

    fun observeTeams(): Flow<List<AgentTeam>> =
        agentTeamDAO.listTeams().map { list -> list.map { it.toModel() } }

    suspend fun upsertTeam(team: AgentTeam) {
        agentTeamDAO.upsertTeam(team.copy(updatedAt = Instant.now()).toEntity())
    }

    /** 只删这一支队（不级联）；删整棵子树请用 [deleteTeamCascade]，否则子队的 parent 会悬空 */
    suspend fun deleteTeam(id: Uuid) {
        agentTeamDAO.deleteMembersOfTeam(id.toString())
        agentTeamDAO.deleteTasksOfTeam(id.toString())
        agentTeamDAO.deleteTeam(id.toString())
    }

    /**
     * 根账本累加：`delta` 是本次成功写入的步数增量，负数按 0（账本只加不减）。
     * 调用方负责吞掉失败并记日志，不阻断执行。
     */
    suspend fun addRootSteps(rootTeamId: Uuid, delta: Int) {
        agentTeamDAO.addSteps(rootTeamId.toString(), delta.coerceAtLeast(0))
    }

    /**
     * 删除团队及其全部子队：按层展开后**由深到浅**删，最后删自己，同一事务内完成，
     * 不留 `parent_team_id` 悬空的行。
     */
    suspend fun deleteTeamCascade(id: Uuid) = inTeamTransaction {
        val levels = mutableListOf<List<Uuid>>()
        val visited = mutableSetOf<Uuid>()
        var frontier = listOf(id)
        while (frontier.isNotEmpty()) {
            // 已访问过的 id 不再展开，异常数据成环时也能停
            val level = frontier.filter { visited.add(it) }
            if (level.isEmpty()) break
            levels += level
            frontier = level.flatMap { parent ->
                agentTeamDAO.listSubTeams(parent.toString()).map { Uuid.parse(it.id) }
            }
        }
        levels.asReversed().forEach { level -> level.forEach { deleteTeam(it) } }
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

    /** 删除会话时同步移除名册行（会话行本身可能已被删） */
    suspend fun deleteMemberByConversation(conversationId: Uuid) =
        agentTeamDAO.deleteMemberByConversation(conversationId.toString())

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

    suspend fun updateMemberElapsed(conversationId: Uuid, elapsedMillis: Long) {
        agentTeamDAO.updateMemberElapsed(conversationId.toString(), elapsedMillis.coerceAtLeast(0L))
    }

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
    ): TeamTask = inTeamTransaction {
        // 上限检查、依赖校验与插入在同一事务里：并发建任务不会越过 maxTasks 或写出环
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
        task
    }

    /**
     * compare-and-set 更新任务。[expectedRevision] 与库里不一致时返回
     * [TeamTaskUpdateResult.Conflict]，**不覆盖**别人的更新。
     *
     * 重读、状态机校验、依赖图校验与 CAS 全在同一个事务里。
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
    ): TeamTaskUpdateResult = inTeamTransaction {
        require(title.isNotBlank()) { "task title must not be blank" }

        val current = getTask(task.id) ?: return@inTeamTransaction TeamTaskUpdateResult.NotFound
        if (current.revision != expectedRevision) {
            return@inTeamTransaction TeamTaskUpdateResult.Conflict(current)
        }
        // 状态机沿用 AgentTeamRules.transition，非法迁移直接拒绝
        require(AgentTeamRules.transition(current.status, status) != null) {
            "illegal task transition ${current.status} -> $status"
        }

        // 依赖图校验要基于"改动后"的视图
        val siblings = listTasks(current.teamId).filter { it.id != current.id }
        val siblingIds = siblings.map { it.id }.toSet()
        val unknown = blockedBy.filter { it !in siblingIds }
        require(unknown.isEmpty()) { "blockedBy references unknown tasks: $unknown" }
        val blockedByOf = siblings.associate { it.id to it.blockedBy }
        require(!AgentTeamRules.wouldCreateCycle(current.id, blockedBy, { blockedByOf[it] ?: emptyList() })) {
            "task dependencies would create a cycle"
        }

        val updated = current.copy(
            title = title.trim(),
            description = description,
            status = status,
            ownerName = ownerName,
            blockedBy = blockedBy.distinct(),
            revision = expectedRevision + 1,
            updatedAt = now,
        )
        if (commitTaskCas(updated, expectedRevision, now)) return@inTeamTransaction TeamTaskUpdateResult.Applied(updated)
        val latest = getTask(current.id) ?: return@inTeamTransaction TeamTaskUpdateResult.NotFound
        TeamTaskUpdateResult.Conflict(latest)
    }

    /** 按 revision CAS 把任务置 CLAIMED 并记 owner；依赖不满足/终态/被别人认领都拒绝 */
    suspend fun claimTask(
        taskId: Uuid,
        expectedRevision: Long,
        ownerName: String,
        now: Instant = Instant.now(),
    ): TeamTaskUpdateResult = inTeamTransaction {
        val current = getTask(taskId) ?: return@inTeamTransaction TeamTaskUpdateResult.NotFound
        if (current.revision != expectedRevision) {
            return@inTeamTransaction TeamTaskUpdateResult.Conflict(current)
        }
        // 依赖门禁用 accepted 版本：上游 DONE 但没有接受凭据仍算未满足
        val plan = TeamTaskSchedulingRules.planDispatchWithAcceptance(
            current,
            listTasks(current.teamId),
            acceptedTaskIds(current.teamId),
            ownerName,
        )
        if (plan is TaskDispatchPlan.Rejected) {
            throw IllegalArgumentException(describeDispatchRejection(current, plan))
        }

        val claimed = current.copy(
            status = TeamTaskStatus.CLAIMED,
            ownerName = ownerName,
            revision = expectedRevision + 1,
            updatedAt = now,
        )
        if (commitTaskCas(claimed, expectedRevision, now)) return@inTeamTransaction TeamTaskUpdateResult.Applied(claimed)
        val latest = getTask(taskId) ?: return@inTeamTransaction TeamTaskUpdateResult.NotFound
        TeamTaskUpdateResult.Conflict(latest)
    }

    /** 派活失败时把刚认领的任务放回 PENDING（仅当当前仍是 CLAIMED 且 revision 匹配） */
    suspend fun releaseTask(
        taskId: Uuid,
        expectedRevision: Long,
        now: Instant = Instant.now(),
    ): TeamTaskUpdateResult = inTeamTransaction {
        val current = getTask(taskId) ?: return@inTeamTransaction TeamTaskUpdateResult.NotFound
        if (current.status != TeamTaskStatus.CLAIMED || current.revision != expectedRevision) {
            return@inTeamTransaction TeamTaskUpdateResult.Conflict(current)
        }

        val released = current.copy(
            status = TeamTaskStatus.PENDING,
            ownerName = null,
            revision = expectedRevision + 1,
            updatedAt = now,
        )
        if (commitTaskCas(released, expectedRevision, now)) return@inTeamTransaction TeamTaskUpdateResult.Applied(released)
        val latest = getTask(taskId) ?: return@inTeamTransaction TeamTaskUpdateResult.NotFound
        TeamTaskUpdateResult.Conflict(latest)
    }

    /**
     * 接受某任务某一版结果：CAS 置 DONE 与写接受行在同一事务里。
     * 重复接受同一 revision 幂等；验收者 == 执行者一律拒绝。
     */
    suspend fun acceptResult(
        taskId: Uuid,
        expectedRevision: Long,
        resultRef: String,
        resultSha256: String?,
        evidenceRef: String,
        verifier: String,
        runId: Uuid? = null,
        now: Instant = Instant.now(),
    ): TaskAcceptanceResult = inTeamTransaction {
        val current = getTask(taskId) ?: return@inTeamTransaction TaskAcceptanceResult.NotFound
        if (current.revision != expectedRevision) {
            // 上一次接受已把 revision +1：库里这一版 DONE 且有凭据时按幂等返回，而不是误报过期
            val doneAcceptance = if (current.status == TeamTaskStatus.DONE) {
                getAcceptance(taskId, current.revision)
            } else {
                null
            }
            if (doneAcceptance != null) {
                return@inTeamTransaction TaskAcceptanceResult.AlreadyAccepted(current, doneAcceptance)
            }
            return@inTeamTransaction TaskAcceptanceResult.Conflict(current)
        }
        val acceptedRevision = expectedRevision + 1
        // 幂等：同一 task+revision 已有凭据就直接返回既有事实
        val existing = getAcceptance(taskId, acceptedRevision)
        if (existing != null) {
            return@inTeamTransaction TaskAcceptanceResult.AlreadyAccepted(current, existing)
        }

        val invalid = TaskAcceptanceRules.validate(resultRef, resultSha256, evidenceRef, verifier)
        if (invalid != null) return@inTeamTransaction TaskAcceptanceResult.Rejected(invalid)
        val plan = TaskAcceptanceRules.planAccept(current, verifier)
        if (plan is AcceptancePlan.Rejected) {
            return@inTeamTransaction TaskAcceptanceResult.Rejected(plan.message)
        }
        val verifiedBy = (plan as AcceptancePlan.Apply).verifier

        val accepted = current.copy(
            status = TeamTaskStatus.DONE,
            revision = acceptedRevision,
            updatedAt = now,
        )
        if (!commitTaskCas(accepted, expectedRevision, now)) {
            // CAS 失败时还没有接受行，直接回冲突让调用方重读
            val latest = getTask(taskId) ?: return@inTeamTransaction TaskAcceptanceResult.NotFound
            return@inTeamTransaction TaskAcceptanceResult.Conflict(latest)
        }

        val acceptance = TaskAcceptance(
            teamId = current.teamId,
            taskId = taskId,
            taskRevision = acceptedRevision,
            runId = runId,
            resultRef = TaskAcceptanceRules.normalizeRef(resultRef) ?: resultRef.trim(),
            resultSha256 = TaskAcceptanceRules.normalizeSha256(resultSha256),
            evidenceRef = TaskAcceptanceRules.normalizeRef(evidenceRef) ?: evidenceRef.trim(),
            acceptedBy = verifiedBy,
            createdAt = now,
        )
        // 唯一键冲突直接抛出，让整个事务回滚（不吞异常、不写半条状态）
        agentTeamDAO.insertAcceptance(acceptance.toEntity())
        TaskAcceptanceResult.Applied(accepted, acceptance)
    }

    /** 当前 revision 已拿到接受凭据的任务 id（reopen 后旧凭据自动失效） */
    suspend fun acceptedTaskIds(teamId: Uuid): Set<Uuid> {
        val rows = listAcceptance(teamId).groupBy { it.taskId }
        return listTasks(teamId)
            .filter { task -> rows[task.id]?.any { it.taskRevision == task.revision } == true }
            .map { it.id }
            .toSet()
    }

    /** 面板用：该团队的全部接受凭据（只读、按创建时间倒序） */
    fun observeAcceptance(teamId: Uuid): Flow<List<TaskAcceptance>> =
        agentTeamDAO.observeAcceptance(teamId.toString()).map { rows -> rows.map { it.toModel() } }

    private suspend fun getAcceptance(taskId: Uuid, taskRevision: Long): TaskAcceptance? =
        agentTeamDAO.getAcceptance(taskId.toString(), taskRevision)?.toModel()

    private suspend fun listAcceptance(teamId: Uuid): List<TaskAcceptance> =
        agentTeamDAO.listAcceptance(teamId.toString()).map { it.toModel() }

    /** CAS 写一行任务；返回是否写成功（false 表示 revision 已被别人推进） */
    private suspend fun commitTaskCas(task: TeamTask, expectedRevision: Long, now: Instant): Boolean =
        agentTeamDAO.updateTaskCas(
            id = task.id.toString(),
            revision = expectedRevision,
            title = task.title,
            description = task.description,
            status = task.status.name,
            ownerName = task.ownerName,
            blockedBy = JsonInstant.encodeToString(task.blockedBy),
            updatedAt = now.toEpochMilli(),
        ) > 0

    suspend fun deleteTask(id: Uuid) {
        agentTeamDAO.deleteTask(id.toString())
    }

    // ---------- 转换 ----------

    private fun AgentTeamEntity.toModel(): AgentTeam {
        val teamId = Uuid.parse(id)
        return AgentTeam(
            id = teamId,
            leadConversationId = Uuid.parse(leadConversationId),
            title = title,
            maxMembers = maxMembers,
            maxTasks = maxTasks,
            parentTeamId = parentTeamId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
            depth = depth,
            // 偏差：迁移不做物理回填，root_team_id 为 NULL 的旧行按「自己就是根」归一
            rootTeamId = rootTeamId?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: teamId,
            stepBudget = stepBudget,
            stepsUsed = stepsUsed,
            createdAt = Instant.ofEpochMilli(createdAt),
            updatedAt = Instant.ofEpochMilli(updatedAt),
        )
    }

    private fun AgentTeam.toEntity() = AgentTeamEntity(
        id = id.toString(),
        leadConversationId = leadConversationId.toString(),
        title = title,
        maxMembers = maxMembers,
        maxTasks = maxTasks,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        // 模型层已把 rootTeamId 归一成非空，新写入的行不带 NULL
        parentTeamId = parentTeamId?.toString(),
        depth = depth,
        rootTeamId = rootTeamId.toString(),
        stepBudget = stepBudget,
        stepsUsed = stepsUsed,
    )

    private fun AgentTeamMemberEntity.toModel() = TeamMember(
        id = Uuid.parse(id),
        teamId = Uuid.parse(teamId),
        name = name,
        conversationId = Uuid.parse(conversationId),
        role = role,
        allowedTools = decodeStringList(allowedTools),
        status = runCatching { TeamMemberStatus.valueOf(status) }
            .getOrDefault(TeamMemberStatus.PROVISIONING),
        createdAt = Instant.ofEpochMilli(createdAt),
        lastRunElapsedMillis = lastRunElapsedMillis,
    )

    private fun TeamMember.toEntity() = AgentTeamMemberEntity(
        id = id.toString(),
        teamId = teamId.toString(),
        name = name,
        conversationId = conversationId.toString(),
        role = role,
        allowedTools = if (allowedTools.isEmpty()) null else JsonInstant.encodeToString(allowedTools),
        status = status.name,
        createdAt = createdAt.toEpochMilli(),
        lastRunElapsedMillis = lastRunElapsedMillis,
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

    private fun TaskAcceptanceEntity.toModel() = TaskAcceptance(
        id = Uuid.parse(id),
        teamId = Uuid.parse(teamId),
        taskId = Uuid.parse(taskId),
        taskRevision = taskRevision,
        runId = runId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        resultRef = resultRef,
        resultSha256 = resultSha256,
        evidenceRef = evidenceRef,
        acceptedBy = acceptedBy,
        createdAt = Instant.ofEpochMilli(createdAt),
    )

    private fun TaskAcceptance.toEntity() = TaskAcceptanceEntity(
        id = id.toString(),
        teamId = teamId.toString(),
        taskId = taskId.toString(),
        taskRevision = taskRevision,
        runId = runId?.toString(),
        resultRef = resultRef,
        resultSha256 = resultSha256,
        evidenceRef = evidenceRef,
        acceptedBy = acceptedBy,
        createdAt = createdAt.toEpochMilli(),
    )

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { JsonInstant.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeUuidList(raw: String): List<Uuid> {
        if (raw.isBlank()) return emptyList()
        return runCatching { JsonInstant.decodeFromString<List<Uuid>>(raw) }.getOrDefault(emptyList())
    }
}
