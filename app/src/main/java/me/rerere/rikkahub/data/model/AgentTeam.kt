package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 多 Agent 团队（Agent Team）的领域模型。
 *
 * 设计对齐 DeepSeek Harness 官方 Agent Teams 的几条已验证取舍：
 * - 名册**扁平且不可变**：只有 Lead 能创建直属队友，名字一经创建永久占用，不重命名、不复用；
 * - 队友是**独立上下文**的持久身份（这里落成一个独立会话），不是同进程 subagent；
 * - 任务依赖图**强制无环**，且每次变更写整份快照 + revision，用 compare-and-set 提交，
 *   过期编辑被拒绝而不是覆盖。
 */
@Serializable
data class AgentTeam(
    val id: Uuid = Uuid.random(),
    /** 团队根会话（Lead）的 id */
    val leadConversationId: Uuid,
    val title: String = "",
    val maxMembers: Int = DEFAULT_MAX_MEMBERS,
    val maxTasks: Int = DEFAULT_MAX_TASKS,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.now(),
) {
    companion object {
        /** 同时运行的队友上限（不含创建失败与已结束的），对齐官方 bundle 的默认值 8 */
        const val DEFAULT_MAX_MEMBERS = 8

        /** 任务板上活跃任务上限 */
        const val DEFAULT_MAX_TASKS = 256
    }
}

@Serializable
enum class TeamMemberStatus {
    /** 已建会话、尚未开始干活 */
    PROVISIONING,

    /** 正常可派活 */
    ACTIVE,

    /** 创建失败（名字仍被永久占用） */
    FAILED,
}

@Serializable
data class TeamMember(
    val id: Uuid = Uuid.random(),
    val teamId: Uuid,
    /** 唯一、小写、不可变；只用于模型与 UI 称呼，不等于会话标题 */
    val name: String,
    /** 队友自己的会话（用户可直接点进去看它在干什么） */
    val conversationId: Uuid,
    /** 角色/职责提示词 */
    val role: String = "",
    val status: TeamMemberStatus = TeamMemberStatus.PROVISIONING,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
)

@Serializable
enum class TeamTaskStatus {
    PENDING,
    CLAIMED,
    DONE,
    FAILED,
}

@Serializable
data class TeamTask(
    val id: Uuid = Uuid.random(),
    val teamId: Uuid,
    val title: String,
    val description: String = "",
    val status: TeamTaskStatus = TeamTaskStatus.PENDING,
    /** 负责人名字（对应 [TeamMember.name]），null 表示无人认领 */
    val ownerName: String? = null,
    /** 阻塞边：这些任务未 DONE 之前本任务不可派发 */
    val blockedBy: List<Uuid> = emptyList(),
    /** compare-and-set 用的版本号，每次变更 +1 */
    val revision: Long = 1,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.now(),
)

/** 一次任务变更的结果：过期的 revision 会被拒绝，而不是覆盖更新的成果。 */
sealed interface TeamTaskUpdateResult {
    data class Applied(val task: TeamTask) : TeamTaskUpdateResult

    /** [current] 是拒绝时的最新快照，调用方应重新读取再改 */
    data class Conflict(val current: TeamTask) : TeamTaskUpdateResult

    data object NotFound : TeamTaskUpdateResult
}

/**
 * 纯逻辑规则。放在这里是为了能脱离 Android 单测（见 AgentTeamRulesTest）。
 */
object AgentTeamRules {
    /** 队友名：小写字母开头，只含小写字母/数字/下划线/连字符，最长 32 */
    private val MEMBER_NAME_REGEX = Regex("^[a-z][a-z0-9_-]{0,31}$")

    /** 保留名：Lead 是团队根，不能被队友占用 */
    private val RESERVED_MEMBER_NAMES = setOf("lead", "user", "system", "assistant")

    fun normalizeMemberName(name: String): String = name.trim().lowercase()

    fun isValidMemberName(name: String): Boolean =
        MEMBER_NAME_REGEX.matches(name) && name !in RESERVED_MEMBER_NAMES

    /**
     * 把 [taskId] 的依赖改成 [blockedBy] 之后，任务依赖图是否会出现环（含自环）。
     *
     * [blockedByOf] 给出每个任务**当前**依赖的上游任务 id。
     */
    fun wouldCreateCycle(
        taskId: Uuid,
        blockedBy: List<Uuid>,
        blockedByOf: (Uuid) -> List<Uuid>,
    ): Boolean {
        if (taskId in blockedBy) return true
        val visited = mutableSetOf<Uuid>()
        val pending = ArrayDeque<Uuid>()
        pending.addAll(blockedBy)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current == taskId) return true
            if (!visited.add(current)) continue
            pending.addAll(blockedByOf(current))
        }
        return false
    }

    /** 任务是否已具备派发条件：自身未完成，且所有上游依赖都已 DONE */
    fun isDispatchable(task: TeamTask, allTasks: List<TeamTask>): Boolean {
        if (task.status == TeamTaskStatus.DONE || task.status == TeamTaskStatus.FAILED) return false
        if (task.blockedBy.isEmpty()) return true
        val byId = allTasks.associateBy { it.id }
        return task.blockedBy.all { byId[it]?.status == TeamTaskStatus.DONE }
    }

    /**
     * 当前占用的"同时运行"名额。
     *
     * 上限是**并发上限**而不是累计创建数：创建失败的（[TeamMemberStatus.FAILED]）
     * 和已经跑完、当前空闲的队友都不占名额。
     */
    fun occupiedSlots(
        members: List<TeamMember>,
        isRunning: (Uuid) -> Boolean,
    ): Int = members.count { member ->
        member.status != TeamMemberStatus.FAILED && isRunning(member.conversationId)
    }

    /**
     * 状态机：只允许单向推进，`done`/`failed` 是终态。
     * 返回 null 表示该迁移非法。
     */
    fun transition(from: TeamTaskStatus, to: TeamTaskStatus): TeamTaskStatus? = when {
        from == to -> from
        from == TeamTaskStatus.PENDING && to == TeamTaskStatus.CLAIMED -> to
        from == TeamTaskStatus.PENDING && to == TeamTaskStatus.FAILED -> to
        from == TeamTaskStatus.CLAIMED && to == TeamTaskStatus.DONE -> to
        from == TeamTaskStatus.CLAIMED && to == TeamTaskStatus.FAILED -> to
        // 终态可以被重新打开（reopen），但必须显式经过 PENDING
        (from == TeamTaskStatus.DONE || from == TeamTaskStatus.FAILED) &&
            to == TeamTaskStatus.PENDING -> to
        else -> null
    }
}
