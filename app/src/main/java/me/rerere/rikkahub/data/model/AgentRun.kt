package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 一次执行尝试（Run）的状态。
 *
 * 与 TeamTaskStatus 的区别：Task 是「要做什么、验收了没有」，Run 是「谁、这一次跑到哪」。
 * 一个 Task 可以有多条 Run（失败重试、换人再试），Run 的终态不等于 Task 被接受。
 */
@Serializable
enum class AgentRunState {
    /** 已持久化、等调度准入 */
    QUEUED,

    /** 已准入，正在执行模型步/工具步 */
    RUNNING,

    /** 等审批：让出执行名额，审批回来仍在同一 Run 续跑（非终态） */
    WAITING_APPROVAL,

    /** 等外部输入（求助/提问）：非终态 */
    WAITING_INPUT,

    /** 等子 Run 结束：非终态，父不占模型名额 */
    WAITING_CHILDREN,

    /** 本回合已结束、仍有已受理的后台 operation 等消费：非终态，释放执行名额，可被事件唤醒 */
    WAITING_OPERATION,

    /** 暂停（步数上限/预算收紧等），需显式续跑 */
    PAUSED,

    COMPLETED,
    FAILED,
    CANCELLED,

    /** 进程重启等导致的保守中断：保留输出与最后一个已提交检查点，不自动续跑 */
    INTERRUPTED,
}

@Serializable
enum class AgentRunEventKind {
    CREATED,
    STATE_CHANGED,
    REPLY_BOUND,

    /** 模型步数累计推进（`stepsUsed` 是绝对值，不是增量） */
    STEP_PROGRESS,
}

/**
 * Run 的不可变事实日志里的一条。
 *
 * `revision` 等于该次提交之后的 `AgentRun.stateRevision`，`(runId, revision)` 唯一，
 * 因此重复提交同一版本会被数据库拒绝，而不是追加两条自相矛盾的事件。
 */
@Serializable
data class AgentRunEvent(
    val runId: Uuid,
    val revision: Long,
    val kind: AgentRunEventKind,
    val fromState: AgentRunState? = null,
    val toState: AgentRunState? = null,
    val reason: String? = null,
    /** 仅 `STEP_PROGRESS` 事件填写：该次提交后的累计步数 */
    val stepsUsed: Int? = null,
    /** 事件关联的 operation；R2 零写入，R3 写 OPERATION_* 事件时才填。 */
    val operationId: Uuid? = null,
    /** 一次模型请求快照的 id；R2 拿不到真实 step 身份，恒空。 */
    val stepId: Uuid? = null,
    /** 本次 Run 内第 n 次模型请求（1-based）；R2 恒空。 */
    val stepNo: Int? = null,
    @Serializable(with = InstantSerializer::class)
    val at: Instant = Instant.now(),
)

/**
 * 一次执行尝试的持久状态。
 *
 * 这里只存「运行事实」：身份、状态、状态版本、输入引用、回复锚点与时间。
 * `stepsUsed` 只由 `recordSteps` 按累计绝对值推进（非终态、不可减少）；本层不做预算扣减或拦截。
 * `stepLimit` / `deadlineAt` 仍是软提醒占位，本层不拦截，也不是跨 Run 的根任务总账。
 */
@Serializable
data class AgentRun(
    val runId: Uuid = Uuid.random(),
    /** 所属团队；单 agent 的普通聊天 Run 可以为空 */
    val teamId: Uuid? = null,
    /** 执行者身份：当前复用 TeamMember.id；null 表示团队 Lead / 单 agent */
    val agentId: Uuid? = null,
    /** 该 agent 的会话，Run 的转录归属；同一会话可以先后有多条 Run */
    val conversationId: Uuid,
    val taskId: Uuid? = null,
    /** 派生父 Run（嵌套/子任务归属），**只**表示图上的父子关系 */
    val parentRunId: Uuid? = null,
    /** 上一次尝试的 Run（重试/续跑链），与 [parentRunId] 分开，避免嵌套图与尝试链混账 */
    val previousRunId: Uuid? = null,
    /** 派活去重键：同一次派活只允许一条 Run；调用方负责加命名空间 */
    val sourceKey: String? = null,
    /** 第几次尝试，从 1 开始 */
    val attemptNo: Int = 1,
    /** 输入引用（文件/消息/附件等外部引用） */
    val inputRef: String? = null,
    /** 内联的有界输入 JSON（过长应改走 inputRef） */
    val inputJson: String? = null,
    /** 本 Run 的回复落在该会话的哪个消息节点上 */
    val replyMessageId: String? = null,
    val state: AgentRunState = AgentRunState.QUEUED,
    /** 最近一次迁移的原因/说明；null 表示没有 */
    val reason: String? = null,
    /** 状态 compare-and-set 版本，从 1 开始，每次提交 +1 */
    val stateRevision: Long = 1L,
    /** 软提醒用的步数上限；本层不拦截，默认 256 */
    val stepLimit: Int? = AgentRunRules.DEFAULT_STEP_LIMIT,
    /** 累计模型步数（绝对值），跨审批/暂停只增不减 */
    val stepsUsed: Int = 0,
    @Serializable(with = InstantSerializer::class)
    val deadlineAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.now(),
    /** 真正被准入执行的时刻，只在第一次进入 RUNNING 时写 */
    @Serializable(with = InstantSerializer::class)
    val startedAt: Instant? = null,
    /** 进入终态的时刻，只在终态迁移时写 */
    @Serializable(with = InstantSerializer::class)
    val finishedAt: Instant? = null,
    /** 逻辑代次：R2 恒 0（只存不递增），取消/替代时才变（R4）。 */
    val executionGeneration: Long = 0L,
    /** 预算预留步数；R5 才由 ledger 写。 */
    val reservedSteps: Int = 0,
    /** 取消请求落库时刻；R4 才写。 */
    @Serializable(with = InstantSerializer::class)
    val cancelRequestedAt: Instant? = null,
) {
    val isTerminal: Boolean get() = AgentRunRules.isTerminal(state)
}

/** 迁移被拒的原因：与 `AgentRunStore.transition` 的四类输出一一对应，便于纯 JVM 断言。 */
enum class TransitionRejection {
    /** Run 不存在 → `RunTransitionResult.NotFound` */
    NOT_FOUND,

    /** 版本已过期或别人先改了 → `RunTransitionResult.Conflict(current)` */
    STALE_REVISION,

    /** 纯规则不允许（终态覆写、同态、等待态互跳）→ `RunTransitionResult.IllegalTransition(from,to)` */
    ILLEGAL_TRANSITION,
}

/** 一次迁移的纯决策：要么写明该写什么，要么写明为什么被拒。 */
sealed interface TransitionPlan {
    data class Apply(
        val from: AgentRunState,
        val to: AgentRunState,
        val nextRevision: Long,
        /** 本次是否该写 `started_at`（只在首次真正进入 RUNNING 时） */
        val stampStartedAt: Boolean,
        /** 本次是否该写 `finished_at`（只在进入终态时） */
        val stampFinishedAt: Boolean,
    ) : TransitionPlan

    data class Reject(
        val reason: TransitionRejection,
        /** 被拒时的当前状态；`NOT_FOUND` 时为 null */
        val from: AgentRunState?,
        val to: AgentRunState,
    ) : TransitionPlan
}

enum class BindReplyRejection {
    NOT_FOUND,

    /** 终态行禁止新绑/改绑：迟到事件不能改写已结束历史 */
    TERMINAL_STATE,

    /** 版本过期，别人先改了 → `BindReplyResult.Conflict` */
    STALE_REVISION,

    /** 已绑定到另一个回复节点（不可改绑） */
    ALREADY_BOUND_ELSEWHERE,
}

/** `bindReply` 的纯决策：不改状态，只在未绑定时绑定并 +1 版本。 */
sealed interface BindReplyPlan {
    data class Apply(val nextRevision: Long) : BindReplyPlan

    /** 已经绑定同一个节点：幂等重复调用 */
    data class AlreadyBound(val run: AgentRun) : BindReplyPlan

    data class Reject(val reason: BindReplyRejection) : BindReplyPlan
}

enum class StepProgressRejection {
    NOT_FOUND,

    /** 终态行不接受步数追加：迟到事件不能改变已结束历史 */
    TERMINAL_STATE,

    /** 版本过期，别人先改了 → `Conflict` */
    STALE_REVISION,

    /** 步数是累计绝对值，不允许减少（相等视为幂等重试） */
    DECREASING,
}

/** `recordSteps` 的纯决策。 */
sealed interface StepPlan {
    data class Apply(val nextRevision: Long, val stepsUsed: Int) : StepPlan

    data class Reject(val reason: StepProgressRejection, val current: AgentRun?) : StepPlan
}

/** 重启恢复策略：只有残留 RUNNING 保守中断，其余状态保持原状。 */
enum class RecoveryAction { INTERRUPT, KEEP }

/**
 * Run 状态机的纯规则。没有 Android / Room 依赖，app JVM 单测（仅 JUnit4）可直接覆盖全部真值表。
 *
 * 这里只判「该不该、该写什么」；并发正确性仍由 repository 的 `state_revision` CAS 负责。
 */
object AgentRunRules {
    /** 内联输入 JSON 的上限；超限一律显式拒绝，**不截断**，调用方应改用 inputRef */
    const val MAX_INPUT_JSON_CHARS = 8192

    /** 步数上限默认值：只作软提醒，本层不拦截、不扣减 */
    const val DEFAULT_STEP_LIMIT = 256

    /** 恢复时统一使用的原因文案，便于事后区分「进程重启」与业务失败 */
    const val RECOVERY_REASON = "interrupted: residual running run recovered on startup"

    /** 全部合法迁移的唯一事实来源；[transition] / [validTransition] / [planTransition] 都读它 */
    val ALLOWED_TRANSITIONS: Map<AgentRunState, Set<AgentRunState>> = mapOf(
        AgentRunState.QUEUED to setOf(
            AgentRunState.RUNNING,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
        ),
        AgentRunState.RUNNING to setOf(
            AgentRunState.WAITING_APPROVAL,
            AgentRunState.WAITING_INPUT,
            AgentRunState.WAITING_CHILDREN,
            AgentRunState.WAITING_OPERATION,
            AgentRunState.PAUSED,
            AgentRunState.COMPLETED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        ),
        AgentRunState.WAITING_APPROVAL to setOf(
            AgentRunState.RUNNING,
            AgentRunState.QUEUED,
            AgentRunState.PAUSED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        ),
        AgentRunState.WAITING_INPUT to setOf(
            AgentRunState.RUNNING,
            AgentRunState.QUEUED,
            AgentRunState.PAUSED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        ),
        AgentRunState.WAITING_CHILDREN to setOf(
            AgentRunState.RUNNING,
            AgentRunState.QUEUED,
            AgentRunState.PAUSED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        ),
        // 等消费：可被下一回合（adopt）或事件唤醒续跑；超时退 PAUSED，不直接完成
        AgentRunState.WAITING_OPERATION to setOf(
            AgentRunState.RUNNING,
            AgentRunState.COMPLETED,
            AgentRunState.PAUSED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        ),
        // 终止：只能显式续跑/取消；不从暂停直接完成或失败重入
        AgentRunState.PAUSED to setOf(
            AgentRunState.RUNNING,
            AgentRunState.QUEUED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        ),
        // 终态无出边：不可覆写
        AgentRunState.COMPLETED to emptySet(),
        AgentRunState.FAILED to emptySet(),
        AgentRunState.CANCELLED to emptySet(),
        AgentRunState.INTERRUPTED to emptySet(),
    )

    /** 终态不可覆写 */
    fun isTerminal(state: AgentRunState): Boolean = when (state) {
        AgentRunState.COMPLETED,
        AgentRunState.FAILED,
        AgentRunState.CANCELLED,
        AgentRunState.INTERRUPTED,
        -> true

        else -> false
    }

    /** 等待态：让出模型执行名额，但保留上下文归属，且都不是终态 */
    fun isWaiting(state: AgentRunState): Boolean = when (state) {
        AgentRunState.WAITING_APPROVAL,
        AgentRunState.WAITING_INPUT,
        AgentRunState.WAITING_CHILDREN,
        AgentRunState.WAITING_OPERATION,
        -> true

        else -> false
    }

    /** 某状态下允许迁移到的目标集合（终态为空集）；测试可直接断言整张表 */
    fun allowedTargets(from: AgentRunState): Set<AgentRunState> =
        ALLOWED_TRANSITIONS[from].orEmpty()

    /** 返回迁移后的状态；null 表示非法迁移（含同态、终态出边、等待态互跳） */
    fun transition(from: AgentRunState, to: AgentRunState): AgentRunState? =
        if (from != to && to in allowedTargets(from)) to else null

    /** 纯布尔版本，方便接线层与测试直接问「这条迁移合不合法」 */
    fun validTransition(from: AgentRunState, to: AgentRunState): Boolean =
        transition(from, to) != null

    /** 恢复策略：残留 RUNNING → INTERRUPT，排队/等待/暂停/终态 → KEEP */
    fun recoveryAction(state: AgentRunState): RecoveryAction =
        if (state == AgentRunState.RUNNING) RecoveryAction.INTERRUPT else RecoveryAction.KEEP

    /** @see RecoveryAction */
    fun shouldInterruptOnRecovery(state: AgentRunState): Boolean =
        recoveryAction(state) == RecoveryAction.INTERRUPT

    /**
     * 迁移的纯决策，判定顺序固定：不存在 → NotFound；版本不符 → Conflict；规则不允许 → IllegalTransition；
     * 否则给出可写入的版本、起止时间打点与事件 revision。repository 直接消费本结果，保证测试与产品同一条路径。
     */
    fun planTransition(
        current: AgentRun?,
        expectedRevision: Long,
        to: AgentRunState,
    ): TransitionPlan {
        if (current == null) {
            return TransitionPlan.Reject(TransitionRejection.NOT_FOUND, null, to)
        }
        if (current.stateRevision != expectedRevision) {
            return TransitionPlan.Reject(TransitionRejection.STALE_REVISION, current.state, to)
        }
        val next = transition(current.state, to)
            ?: return TransitionPlan.Reject(TransitionRejection.ILLEGAL_TRANSITION, current.state, to)
        return TransitionPlan.Apply(
            from = current.state,
            to = next,
            nextRevision = current.stateRevision + 1,
            stampStartedAt = next == AgentRunState.RUNNING && current.startedAt == null,
            stampFinishedAt = isTerminal(next),
        )
    }

    /**
     * 绑定回复节点的纯决策：已绑同一节点 → AlreadyBound（幂等）；版本不符或已绑别的节点 → Reject；
     * 否则 Apply，版本 +1。
     */
    fun planBindReply(
        current: AgentRun?,
        expectedRevision: Long,
        replyMessageId: String,
    ): BindReplyPlan {
        if (current == null) return BindReplyPlan.Reject(BindReplyRejection.NOT_FOUND)
        // 同一个 id 是幂等读回，终态也允许（不产生任何写入）
        if (current.replyMessageId == replyMessageId) return BindReplyPlan.AlreadyBound(current)
        // 终态行一律拒绝新绑/改绑：已结束的历史不可被迟到事件改写
        if (isTerminal(current.state)) return BindReplyPlan.Reject(BindReplyRejection.TERMINAL_STATE)
        if (current.stateRevision != expectedRevision) {
            return BindReplyPlan.Reject(BindReplyRejection.STALE_REVISION)
        }
        if (current.replyMessageId != null) {
            return BindReplyPlan.Reject(BindReplyRejection.ALREADY_BOUND_ELSEWHERE)
        }
        return BindReplyPlan.Apply(nextRevision = current.stateRevision + 1)
    }

    /** 只有非终态 Run 能追加步数：终态历史不可改 */
    fun canRecordSteps(state: AgentRunState): Boolean = !isTerminal(state)

    /**
     * 步数记录的纯决策，顺序固定：不存在 → NotFound；终态 → Rejected(TERMINAL_STATE)；
     * 版本不符 → Conflict；比已记录的小 → Rejected(DECREASING)；否则 Apply（版本 +1，写绝对值）。
     */
    fun planRecordSteps(
        current: AgentRun?,
        expectedRevision: Long,
        stepsUsed: Int,
    ): StepPlan {
        if (current == null) return StepPlan.Reject(StepProgressRejection.NOT_FOUND, null)
        if (isTerminal(current.state)) return StepPlan.Reject(StepProgressRejection.TERMINAL_STATE, current)
        if (current.stateRevision != expectedRevision) {
            return StepPlan.Reject(StepProgressRejection.STALE_REVISION, current)
        }
        if (stepsUsed < current.stepsUsed) {
            return StepPlan.Reject(StepProgressRejection.DECREASING, current)
        }
        return StepPlan.Apply(nextRevision = current.stateRevision + 1, stepsUsed = stepsUsed)
    }

    /** 派活去重键规范化：去空白，空串视为「不去重」 */
    fun normalizeSourceKey(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** 新建 Run 的入参校验；返回 null 表示通过，否则是给调用方看的错误说明（repository 用 require 抛出） */
    fun validateNewRun(
        attemptNo: Int,
        inputJsonLength: Int?,
        stepLimit: Int?,
    ): String? = when {
        attemptNo < 1 -> "attemptNo must be >= 1"
        inputJsonLength != null && inputJsonLength > MAX_INPUT_JSON_CHARS ->
            "inputJson too large ($inputJsonLength > $MAX_INPUT_JSON_CHARS); use inputRef"

        stepLimit != null && stepLimit < 1 -> "stepLimit must be >= 1 when set"
        else -> null
    }
}
