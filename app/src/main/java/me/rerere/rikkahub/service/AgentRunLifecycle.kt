package me.rerere.rikkahub.service

import me.rerere.ai.agent.AgentFinishReason
import me.rerere.rikkahub.data.model.AgentMailboxRules
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.model.AgentRunState
import me.rerere.rikkahub.data.model.MailboxKind
import kotlin.uuid.Uuid

/**
 * 一次执行段的步数基线：Run 已记录步数 + 本段开始时回复消息上的累计步数。
 * 续跑时消息里的旧 stepCount 属于同一 Run 的历史，只按差值累加，不重复计入。
 */
data class RunStepBaseline(
    val runStepsUsed: Int,
    val messageStepCount: Int,
)

/** 成员投影：只回答这次 Run 处于什么阶段，不加载消息。 */
data class RunProjection(
    val hasActiveJob: Boolean,
    val running: Boolean,
    val awaitingApproval: Boolean?,
    val paused: Boolean,
)

/** 续跑判定：只接受同一 Run 上精确绑定的回复消息，不用会话最新消息猜是哪一轮。 */
sealed interface RunResume {
    data class SameRun(val run: AgentRun) : RunResume

    /** 步数预算用尽：没有扩展协议前拒绝无条件续跑 */
    data class RefusedStepLimit(val run: AgentRun) : RunResume

    /** 需要新 attempt（旧 Run 已终态或未绑定）；不改写旧 Run */
    data class NewAttempt(val previousRunId: Uuid?) : RunResume
}

/** Run 生命周期纯规则：无 Android 依赖，可直接被 run_tests 覆盖。 */
object AgentRunLifecycle {
    const val STEP_LIMIT_REASON = "step_limit"
    const val CANCELLED_REASON = "cancelled"
    const val FAILED_REASON = "failed"

    /** 派活被 mailbox 容量拒绝时终结孤儿 Run 的原因 */
    const val MAILBOX_CAPACITY_REASON = "mailbox_capacity"
    const val RECOVERY_HINT_REASON = "interrupted: residual running run recovered on startup"

    fun baseline(run: AgentRun?, startMessageStepCount: Int): RunStepBaseline = RunStepBaseline(
        runStepsUsed = run?.stepsUsed ?: 0,
        messageStepCount = startMessageStepCount,
    )

    /** 累计绝对步数：只加本段新增，续写旧消息的旧 stepCount 不计入新 Run */
    fun stepsAfterStep(baseline: RunStepBaseline, currentMessageStepCount: Int): Int {
        val delta = (currentMessageStepCount - baseline.messageStepCount).coerceAtLeast(0)
        return baseline.runStepsUsed + delta
    }

    /** 只映射 finish reason；状态迁移仍由调用方在最终转录 flush 之后提交 */
    fun stateFor(reason: AgentFinishReason): AgentRunState = when (reason) {
        AgentFinishReason.Completed -> AgentRunState.COMPLETED
        is AgentFinishReason.AwaitingApproval -> AgentRunState.WAITING_APPROVAL
        AgentFinishReason.StepLimitReached -> AgentRunState.PAUSED
    }

    fun reasonFor(reason: AgentFinishReason): String? = when (reason) {
        AgentFinishReason.StepLimitReached -> STEP_LIMIT_REASON
        else -> null
    }

    /** 终态对应哪类领队通知；null 表示不需要结果投递（非终态或已由别处投递） */
    fun mailboxKindFor(state: AgentRunState): MailboxKind? = when (state) {
        AgentRunState.COMPLETED -> MailboxKind.RESULT
        AgentRunState.FAILED -> MailboxKind.FAILURE
        AgentRunState.CANCELLED, AgentRunState.INTERRUPTED -> MailboxKind.NOTICE
        else -> null
    }

    /** 同一 Run 只产生一行结果通知 */
    fun resultIdempotencyKey(runId: Uuid): String = "result:$runId"

    /** 同一次派活只产生一行 ASSIGN */
    fun assignIdempotencyKey(runId: Uuid): String = "assign:$runId"

    /** 正文能否内联；超限一律走 bodyRef，不截断 */
    fun bodyFitsInline(text: String): Boolean = text.length <= AgentMailboxRules.MAX_BODY_TEXT_CHARS

    /**
     * 判断能否在同一个 Run 上续跑。
     * [replyMessageId] 是即将续写的那条助手消息；Run 必须精确绑定它，且未终态。
     */
    fun planResume(run: AgentRun?, replyMessageId: Uuid?): RunResume {
        if (run == null) return RunResume.NewAttempt(previousRunId = null)
        val bound = run.replyMessageId
        if (bound == null || replyMessageId == null || bound != replyMessageId.toString()) {
            return RunResume.NewAttempt(previousRunId = run.runId)
        }
        if (run.isTerminal) return RunResume.NewAttempt(previousRunId = run.runId)
        if (run.state == AgentRunState.PAUSED && run.reason == STEP_LIMIT_REASON) {
            return RunResume.RefusedStepLimit(run)
        }
        return RunResume.SameRun(run)
    }

    /** 投影到成员列表现有字段；null 表示没有 Run 行，保持 legacy 显示 */
    fun projection(run: AgentRun?): RunProjection? = projection(run?.state)

    /** 轻量摘要只有 state，没有 reason/负载，投影同样只按 state */
    fun projection(state: AgentRunState?): RunProjection? = when (state) {
        null -> null
        AgentRunState.QUEUED -> RunProjection(true, false, false, false)
        AgentRunState.RUNNING -> RunProjection(true, true, false, false)
        AgentRunState.WAITING_APPROVAL -> RunProjection(true, false, true, false)
        AgentRunState.WAITING_INPUT, AgentRunState.WAITING_CHILDREN ->
            RunProjection(true, false, false, false)
        AgentRunState.PAUSED -> RunProjection(false, false, false, true)
        AgentRunState.COMPLETED, AgentRunState.FAILED,
        AgentRunState.CANCELLED, AgentRunState.INTERRUPTED,
        -> RunProjection(false, false, false, false)
    }
}
