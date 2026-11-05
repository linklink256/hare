package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.model.AgentRunRules
import me.rerere.rikkahub.data.model.AgentRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentRunLifecycle 纯 helper 的单测（JUnit4，无 Android/Room/服务依赖）。
 *
 * 覆盖范围只到 helper：finish reason → Run 状态映射、续跑判定、步数基线与投影。
 * 真实 runTurn / RunStore 写入 / ChatService 接线不在此文件，也不得据此声称整链路已测。
 */
class AgentRunLifecycleTest {

    private val conversation = Uuid.random()
    private val reply = Uuid.random()

    private fun run(
        state: AgentRunState = AgentRunState.RUNNING,
        revision: Long = 1L,
        stepsUsed: Int = 0,
        replyMessageId: String? = reply.toString(),
        reason: String? = null,
    ) = AgentRun(
        conversationId = conversation,
        state = state,
        stateRevision = revision,
        stepsUsed = stepsUsed,
        replyMessageId = replyMessageId,
        reason = reason,
    )

    // ---------- finish reason 映射 ----------

    @Test
    fun `finish reasons map to the frozen run states and reasons`() {
        assertEquals(AgentRunState.COMPLETED, AgentRunLifecycle.stateFor(AgentFinishReason.Completed))
        // 回合结束时仍有已受理的后台 operation：进等待态而不是假装完成
        assertEquals(
            AgentRunState.WAITING_OPERATION,
            AgentRunLifecycle.stateFor(AgentFinishReason.Completed, hasOpenOperations = true),
        )
        assertEquals(
            AgentRunState.WAITING_APPROVAL,
            AgentRunLifecycle.stateFor(AgentFinishReason.AwaitingApproval(setOf("call-1", "call-2"))),
        )
        assertEquals(AgentRunState.PAUSED, AgentRunLifecycle.stateFor(AgentFinishReason.StepLimitReached))

        assertNull(AgentRunLifecycle.reasonFor(AgentFinishReason.Completed))
        assertNull(AgentRunLifecycle.reasonFor(AgentFinishReason.AwaitingApproval(setOf("call-1"))))
        assertEquals("step_limit", AgentRunLifecycle.STEP_LIMIT_REASON)
        assertEquals("step_limit", AgentRunLifecycle.reasonFor(AgentFinishReason.StepLimitReached))
    }

    @Test
    fun `awaiting approval maps to a waiting state that is not terminal`() {
        val state = AgentRunLifecycle.stateFor(AgentFinishReason.AwaitingApproval(setOf("call-1")))

        assertTrue(AgentRunRules.isWaiting(state))
        assertFalse(AgentRunRules.isTerminal(state))
        // 审批是让出执行权，不是结束：同一个 Run 之后还要能被续跑
        assertTrue(AgentRunRules.validTransition(state, AgentRunState.RUNNING))
    }

    // ---------- 续跑判定 ----------

    @Test
    fun `planResume keeps the same run only when the run is bound to the exact reply id`() {
        val current = run(state = AgentRunState.WAITING_APPROVAL, replyMessageId = reply.toString())

        assertEquals(RunResume.SameRun(current), AgentRunLifecycle.planResume(current, reply))
    }

    @Test
    fun `planResume starts a new attempt when the reply id does not match`() {
        val current = run(state = AgentRunState.WAITING_APPROVAL, replyMessageId = reply.toString())

        val plan = AgentRunLifecycle.planResume(current, Uuid.random())

        assertEquals(RunResume.NewAttempt(previousRunId = current.runId), plan)
    }

    @Test
    fun `planResume starts a new attempt when the caller has no reply id`() {
        val current = run(state = AgentRunState.RUNNING, replyMessageId = reply.toString())

        assertEquals(RunResume.NewAttempt(previousRunId = current.runId), AgentRunLifecycle.planResume(current, null))
    }

    @Test
    fun `planResume starts a new attempt when the run has no bound reply`() {
        val current = run(state = AgentRunState.RUNNING, replyMessageId = null)

        assertEquals(RunResume.NewAttempt(previousRunId = current.runId), AgentRunLifecycle.planResume(current, reply))
    }

    @Test
    fun `planResume starts a new attempt from a terminal run even with the same reply id`() {
        for (state in listOf(
            AgentRunState.COMPLETED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        )) {
            val current = run(state = state, replyMessageId = reply.toString())

            assertEquals(
                "terminal $state must start a new attempt",
                RunResume.NewAttempt(previousRunId = current.runId),
                AgentRunLifecycle.planResume(current, reply),
            )
        }
    }

    @Test
    fun `planResume starts the first attempt when there is no run`() {
        assertEquals(RunResume.NewAttempt(previousRunId = null), AgentRunLifecycle.planResume(null, reply))
        assertEquals(RunResume.NewAttempt(previousRunId = null), AgentRunLifecycle.planResume(null, null))
    }

    @Test
    fun `planResume refuses a run paused by the step limit`() {
        val current = run(
            state = AgentRunState.PAUSED,
            replyMessageId = reply.toString(),
            reason = AgentRunLifecycle.STEP_LIMIT_REASON,
        )

        assertEquals(RunResume.RefusedStepLimit(current), AgentRunLifecycle.planResume(current, reply))
    }

    @Test
    fun `planResume allows a paused run paused for another reason`() {
        val current = run(state = AgentRunState.PAUSED, replyMessageId = reply.toString(), reason = null)

        assertEquals(RunResume.SameRun(current), AgentRunLifecycle.planResume(current, reply))
    }

    // ---------- 步数基线 ----------

    @Test
    fun `baseline takes used steps from the run and keeps the message count`() {
        assertEquals(
            RunStepBaseline(runStepsUsed = 5, messageStepCount = 100),
            AgentRunLifecycle.baseline(run(stepsUsed = 5), 100),
        )
        assertEquals(RunStepBaseline(runStepsUsed = 0, messageStepCount = 7), AgentRunLifecycle.baseline(null, 7))
    }

    @Test
    fun `steps after a step add only the new message steps`() {
        val baseline = RunStepBaseline(runStepsUsed = 5, messageStepCount = 100)

        // (runUsed=5, start=100, current=101) -> 6
        assertEquals(6, AgentRunLifecycle.stepsAfterStep(baseline, 101))
        // (0, 100, 101) -> 1
        assertEquals(1, AgentRunLifecycle.stepsAfterStep(RunStepBaseline(0, 100), 101))
    }

    @Test
    fun `steps after a step never go backwards when the message count regresses`() {
        val baseline = RunStepBaseline(runStepsUsed = 5, messageStepCount = 100)

        assertEquals(5, AgentRunLifecycle.stepsAfterStep(baseline, 99))
        assertEquals(5, AgentRunLifecycle.stepsAfterStep(baseline, 100))
        assertEquals(5, AgentRunLifecycle.stepsAfterStep(baseline, 0))
    }

    // ---------- 成员投影 ----------

    @Test
    fun `projection maps every state to the member row flags`() {
        val expected = mapOf(
            AgentRunState.QUEUED to RunProjection(true, false, false, false),
            AgentRunState.RUNNING to RunProjection(true, true, false, false),
            AgentRunState.WAITING_APPROVAL to RunProjection(true, false, true, false),
            AgentRunState.WAITING_INPUT to RunProjection(true, false, false, false),
            AgentRunState.WAITING_CHILDREN to RunProjection(true, false, false, false),
            AgentRunState.WAITING_OPERATION to RunProjection(true, false, false, false),
            AgentRunState.PAUSED to RunProjection(false, false, false, true),
            AgentRunState.COMPLETED to RunProjection(false, false, false, false),
            AgentRunState.FAILED to RunProjection(false, false, false, false),
            AgentRunState.CANCELLED to RunProjection(false, false, false, false),
            AgentRunState.INTERRUPTED to RunProjection(false, false, false, false),
        )
        assertEquals(AgentRunState.entries.toSet(), expected.keys)

        for (state in AgentRunState.entries) {
            assertEquals("projection($state)", expected.getValue(state), AgentRunLifecycle.projection(state))
        }
    }

    @Test
    fun `projection is null without a run so legacy rows stay unchanged`() {
        assertNull(AgentRunLifecycle.projection(null as AgentRun?))
        assertNull(AgentRunLifecycle.projection(null as AgentRunState?))
    }

    @Test
    fun `projection of a run delegates to its state`() {
        for (state in AgentRunState.entries) {
            assertEquals(
                "projection(run in $state)",
                AgentRunLifecycle.projection(state),
                AgentRunLifecycle.projection(run(state = state)),
            )
        }
    }
}
