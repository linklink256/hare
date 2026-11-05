package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentRunRules 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的真值表，不从 ALLOWED_TRANSITIONS / allowedTargets 反推，
 * 也不经过 repository：Room 的 CAS SQL、索引、事务与 AutoMigration 本轮未执行，见 TEST-PLAN.md。
 */
class AgentRunRulesTest {
    private val conversation = Uuid.random()

    /**
     * 手写真值表：每行是该状态下唯一合法的目标集合。
     * 不含自身——同态在规则里一律非法（重复提交靠 stateRevision 幂等，不靠自迁移）。
     */
    private val expectedTargets: Map<AgentRunState, Set<AgentRunState>> = mapOf(
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
        AgentRunState.WAITING_OPERATION to setOf(
            AgentRunState.RUNNING,
            AgentRunState.COMPLETED,
            AgentRunState.PAUSED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        ),
        AgentRunState.PAUSED to setOf(
            AgentRunState.RUNNING,
            AgentRunState.QUEUED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.INTERRUPTED,
        ),
        AgentRunState.COMPLETED to emptySet(),
        AgentRunState.FAILED to emptySet(),
        AgentRunState.CANCELLED to emptySet(),
        AgentRunState.INTERRUPTED to emptySet(),
    )

    private val terminalStates = listOf(
        AgentRunState.COMPLETED,
        AgentRunState.FAILED,
        AgentRunState.CANCELLED,
        AgentRunState.INTERRUPTED,
    )

    private val waitingStates = listOf(
        AgentRunState.WAITING_APPROVAL,
        AgentRunState.WAITING_INPUT,
        AgentRunState.WAITING_CHILDREN,
        AgentRunState.WAITING_OPERATION,
    )

    private val nonTerminalStates = listOf(
        AgentRunState.QUEUED,
        AgentRunState.RUNNING,
        AgentRunState.WAITING_APPROVAL,
        AgentRunState.WAITING_INPUT,
        AgentRunState.WAITING_CHILDREN,
        AgentRunState.WAITING_OPERATION,
        AgentRunState.PAUSED,
    )

    private fun run(
        state: AgentRunState = AgentRunState.QUEUED,
        revision: Long = 1L,
        stepsUsed: Int = 0,
        replyMessageId: String? = null,
        startedAt: Instant? = null,
    ) = AgentRun(
        conversationId = conversation,
        state = state,
        stateRevision = revision,
        stepsUsed = stepsUsed,
        replyMessageId = replyMessageId,
        startedAt = startedAt,
    )

    // ---------- 迁移真值表 ----------

    @Test
    fun `transition truth table matches the hand-written table for every pair`() {
        val states = AgentRunState.entries
        assertEquals("truth table must cover every state", states.toSet(), expectedTargets.keys)

        val mismatches = mutableListOf<String>()
        for (from in states) {
            for (to in states) {
                val expected = to in expectedTargets.getValue(from)
                val flag = AgentRunRules.validTransition(from, to)
                val target = AgentRunRules.transition(from, to)
                val want = if (expected) to else null
                if (flag != expected) mismatches += "validTransition($from,$to)=$flag expected=$expected"
                if (target != want) mismatches += "transition($from,$to)=$target expected=$want"
            }
        }
        assertTrue("transition mismatches: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun `allowedTargets matches the hand-written table for every state`() {
        for (state in AgentRunState.entries) {
            assertEquals("allowedTargets($state)", expectedTargets.getValue(state), AgentRunRules.allowedTargets(state))
        }
    }

    @Test
    fun `ALLOWED_TRANSITIONS declares exactly one row per state`() {
        assertEquals(AgentRunState.entries.toSet(), AgentRunRules.ALLOWED_TRANSITIONS.keys)
    }

    @Test
    fun `no state is allowed to transition to itself`() {
        for (state in AgentRunState.entries) {
            assertFalse("$state -> $state must be illegal", AgentRunRules.validTransition(state, state))
            assertNull(AgentRunRules.transition(state, state))
        }
    }

    @Test
    fun `queued and running are not waiting and not terminal`() {
        assertFalse(AgentRunRules.isWaiting(AgentRunState.QUEUED))
        assertFalse(AgentRunRules.isWaiting(AgentRunState.RUNNING))
        assertFalse(AgentRunRules.isTerminal(AgentRunState.QUEUED))
        assertFalse(AgentRunRules.isTerminal(AgentRunState.RUNNING))
    }

    @Test
    fun `waiting states are non-terminal and can resume through RUNNING`() {
        for (state in waitingStates) {
            assertTrue("$state must be waiting", AgentRunRules.isWaiting(state))
            assertFalse("$state must not be terminal", AgentRunRules.isTerminal(state))
            assertTrue("$state must be able to resume", AgentRunRules.validTransition(state, AgentRunState.RUNNING))
            assertTrue("$state must be able to cancel", AgentRunRules.validTransition(state, AgentRunState.CANCELLED))
        }
    }

    @Test
    fun `isWaiting is true only for the three waiting states`() {
        for (state in AgentRunState.entries) {
            assertEquals("isWaiting($state)", state in waitingStates, AgentRunRules.isWaiting(state))
        }
    }

    @Test
    fun `terminal states reject every out-edge`() {
        for (state in terminalStates) {
            assertTrue("$state must be terminal", AgentRunRules.isTerminal(state))
            assertTrue("$state must have no allowed targets", AgentRunRules.allowedTargets(state).isEmpty())
            for (to in AgentRunState.entries) {
                assertFalse("terminal $state -> $to must be illegal", AgentRunRules.validTransition(state, to))
                assertNull("terminal $state -> $to must yield no state", AgentRunRules.transition(state, to))
            }
        }
    }

    @Test
    fun `queued cannot jump straight into a waiting state`() {
        for (state in waitingStates) {
            assertFalse("QUEUED -> $state must be illegal", AgentRunRules.validTransition(AgentRunState.QUEUED, state))
        }
    }

    @Test
    fun `paused cannot become completed without resuming`() {
        assertFalse(AgentRunRules.validTransition(AgentRunState.PAUSED, AgentRunState.COMPLETED))
        assertTrue(AgentRunRules.validTransition(AgentRunState.PAUSED, AgentRunState.RUNNING))
    }

    // ---------- planTransition：CAS/NotFound 优先级 ----------

    @Test
    fun `planTransition reports NOT_FOUND with no state when the run is missing`() {
        val plan = AgentRunRules.planTransition(current = null, expectedRevision = 1L, to = AgentRunState.RUNNING)
        assertEquals(TransitionPlan.Reject(TransitionRejection.NOT_FOUND, null, AgentRunState.RUNNING), plan)
    }

    @Test
    fun `planTransition checks stale revision before the transition rules`() {
        val current = run(state = AgentRunState.COMPLETED, revision = 5L)
        // COMPLETED -> RUNNING 同时是非法边：版本优先，必须报 STALE_REVISION
        val plan = AgentRunRules.planTransition(current, expectedRevision = 4L, to = AgentRunState.RUNNING)
        assertEquals(
            TransitionPlan.Reject(TransitionRejection.STALE_REVISION, AgentRunState.COMPLETED, AgentRunState.RUNNING),
            plan,
        )
    }

    @Test
    fun `planTransition rejects an illegal edge at the matching revision`() {
        val current = run(state = AgentRunState.COMPLETED, revision = 5L)
        val plan = AgentRunRules.planTransition(current, expectedRevision = 5L, to = AgentRunState.RUNNING)
        assertEquals(
            TransitionPlan.Reject(
                TransitionRejection.ILLEGAL_TRANSITION,
                AgentRunState.COMPLETED,
                AgentRunState.RUNNING,
            ),
            plan,
        )
    }

    @Test
    fun `planTransition bumps revision and stamps startedAt on first running`() {
        val current = run(state = AgentRunState.QUEUED, revision = 1L, startedAt = null)
        val plan = AgentRunRules.planTransition(current, expectedRevision = 1L, to = AgentRunState.RUNNING)
        assertEquals(
            TransitionPlan.Apply(
                from = AgentRunState.QUEUED,
                to = AgentRunState.RUNNING,
                nextRevision = 2L,
                stampStartedAt = true,
                stampFinishedAt = false,
            ),
            plan,
        )
    }

    @Test
    fun `planTransition does not re-stamp startedAt when resuming from a waiting state`() {
        val current = run(
            state = AgentRunState.WAITING_APPROVAL,
            revision = 3L,
            startedAt = Instant.ofEpochSecond(1_000L),
        )
        val plan = AgentRunRules.planTransition(current, expectedRevision = 3L, to = AgentRunState.RUNNING)
        assertEquals(
            TransitionPlan.Apply(
                from = AgentRunState.WAITING_APPROVAL,
                to = AgentRunState.RUNNING,
                nextRevision = 4L,
                stampStartedAt = false,
                stampFinishedAt = false,
            ),
            plan,
        )
    }

    @Test
    fun `planTransition stamps finishedAt only when entering a terminal state`() {
        val current = run(state = AgentRunState.RUNNING, revision = 7L)
        val completed = AgentRunRules.planTransition(current, expectedRevision = 7L, to = AgentRunState.COMPLETED)
        assertEquals(
            TransitionPlan.Apply(
                from = AgentRunState.RUNNING,
                to = AgentRunState.COMPLETED,
                nextRevision = 8L,
                stampStartedAt = false,
                stampFinishedAt = true,
            ),
            completed,
        )
        val waiting = AgentRunRules.planTransition(current, expectedRevision = 7L, to = AgentRunState.WAITING_APPROVAL)
        assertEquals(
            TransitionPlan.Apply(
                from = AgentRunState.RUNNING,
                to = AgentRunState.WAITING_APPROVAL,
                nextRevision = 8L,
                stampStartedAt = false,
                stampFinishedAt = false,
            ),
            waiting,
        )
    }

    // ---------- planBindReply：终态禁绑、同 ID 幂等 ----------

    @Test
    fun `planBindReply reports NOT_FOUND for a missing run`() {
        assertEquals(
            BindReplyPlan.Reject(BindReplyRejection.NOT_FOUND),
            AgentRunRules.planBindReply(null, expectedRevision = 1L, replyMessageId = "msg-1"),
        )
    }

    @Test
    fun `planBindReply treats the same reply id as idempotent even on a terminal run`() {
        val current = run(state = AgentRunState.COMPLETED, revision = 9L, replyMessageId = "msg-1")
        // 版本故意不匹配：同一 id 是读回幂等，不产生写入，先于版本检查返回
        val plan = AgentRunRules.planBindReply(current, expectedRevision = 1L, replyMessageId = "msg-1")
        assertEquals(BindReplyPlan.AlreadyBound(current), plan)
    }

    @Test
    fun `planBindReply refuses a new binding on a terminal run before checking revision`() {
        val current = run(state = AgentRunState.COMPLETED, revision = 9L, replyMessageId = null)
        val plan = AgentRunRules.planBindReply(current, expectedRevision = 1L, replyMessageId = "msg-2")
        assertEquals(BindReplyPlan.Reject(BindReplyRejection.TERMINAL_STATE), plan)
    }

    @Test
    fun `planBindReply refuses rebinding to another reply id`() {
        val current = run(state = AgentRunState.RUNNING, revision = 2L, replyMessageId = "msg-1")
        val plan = AgentRunRules.planBindReply(current, expectedRevision = 2L, replyMessageId = "msg-2")
        assertEquals(BindReplyPlan.Reject(BindReplyRejection.ALREADY_BOUND_ELSEWHERE), plan)
    }

    @Test
    fun `planBindReply checks stale revision before ALREADY_BOUND_ELSEWHERE`() {
        val current = run(state = AgentRunState.RUNNING, revision = 2L, replyMessageId = "msg-1")
        val plan = AgentRunRules.planBindReply(current, expectedRevision = 1L, replyMessageId = "msg-2")
        assertEquals(BindReplyPlan.Reject(BindReplyRejection.STALE_REVISION), plan)
    }

    @Test
    fun `planBindReply bumps revision for the first binding`() {
        val current = run(state = AgentRunState.RUNNING, revision = 2L, replyMessageId = null)
        assertEquals(
            BindReplyPlan.Apply(nextRevision = 3L),
            AgentRunRules.planBindReply(current, expectedRevision = 2L, replyMessageId = "msg-1"),
        )
    }

    // ---------- planRecordSteps：不减/终态/非法 ----------

    @Test
    fun `planRecordSteps reports NOT_FOUND for a missing run`() {
        assertEquals(
            StepPlan.Reject(StepProgressRejection.NOT_FOUND, null),
            AgentRunRules.planRecordSteps(null, expectedRevision = 1L, stepsUsed = 3),
        )
    }

    @Test
    fun `planRecordSteps refuses terminal runs before checking revision`() {
        val current = run(state = AgentRunState.COMPLETED, revision = 5L, stepsUsed = 3)
        val plan = AgentRunRules.planRecordSteps(current, expectedRevision = 1L, stepsUsed = 4)
        assertEquals(StepPlan.Reject(StepProgressRejection.TERMINAL_STATE, current), plan)
    }

    @Test
    fun `planRecordSteps rejects a decreasing counter`() {
        val current = run(state = AgentRunState.RUNNING, revision = 2L, stepsUsed = 5)
        assertEquals(
            StepPlan.Reject(StepProgressRejection.DECREASING, current),
            AgentRunRules.planRecordSteps(current, expectedRevision = 2L, stepsUsed = 4),
        )
    }

    @Test
    fun `planRecordSteps accepts equal steps as an idempotent retry and bumps revision`() {
        val current = run(state = AgentRunState.RUNNING, revision = 2L, stepsUsed = 5)
        assertEquals(
            StepPlan.Apply(nextRevision = 3L, stepsUsed = 5),
            AgentRunRules.planRecordSteps(current, expectedRevision = 2L, stepsUsed = 5),
        )
    }

    @Test
    fun `planRecordSteps applies an increased absolute value`() {
        val current = run(state = AgentRunState.RUNNING, revision = 2L, stepsUsed = 5)
        assertEquals(
            StepPlan.Apply(nextRevision = 3L, stepsUsed = 7),
            AgentRunRules.planRecordSteps(current, expectedRevision = 2L, stepsUsed = 7),
        )
    }

    @Test
    fun `planRecordSteps checks stale revision before DECREASING`() {
        val current = run(state = AgentRunState.RUNNING, revision = 2L, stepsUsed = 5)
        assertEquals(
            StepPlan.Reject(StepProgressRejection.STALE_REVISION, current),
            AgentRunRules.planRecordSteps(current, expectedRevision = 3L, stepsUsed = 1),
        )
    }

    @Test
    fun `canRecordSteps is false exactly for terminal states`() {
        for (state in AgentRunState.entries) {
            assertEquals("canRecordSteps($state)", state !in terminalStates, AgentRunRules.canRecordSteps(state))
        }
    }

    // ---------- 重启恢复 ----------

    @Test
    fun `recovery interrupts only RUNNING and keeps every other state`() {
        for (state in AgentRunState.entries) {
            val expected = if (state == AgentRunState.RUNNING) RecoveryAction.INTERRUPT else RecoveryAction.KEEP
            assertEquals("recoveryAction($state)", expected, AgentRunRules.recoveryAction(state))
            assertEquals(
                "shouldInterruptOnRecovery($state)",
                state == AgentRunState.RUNNING,
                AgentRunRules.shouldInterruptOnRecovery(state),
            )
        }
        // 中断后的 Run 本身是终态，不能被再次恢复成 RUNNING
        assertTrue(AgentRunRules.isTerminal(AgentRunState.INTERRUPTED))
        assertEquals(RecoveryAction.KEEP, AgentRunRules.recoveryAction(AgentRunState.INTERRUPTED))
    }

    // ---------- 新建入参边界 ----------

    @Test
    fun `new run limits are the frozen constants`() {
        assertEquals(8192, AgentRunRules.MAX_INPUT_JSON_CHARS)
        assertEquals(256, AgentRunRules.DEFAULT_STEP_LIMIT)
    }

    @Test
    fun `validateNewRun accepts boundary values`() {
        assertNull(AgentRunRules.validateNewRun(attemptNo = 1, inputJsonLength = null, stepLimit = null))
        assertNull(AgentRunRules.validateNewRun(attemptNo = 1, inputJsonLength = 0, stepLimit = 1))
        assertNull(AgentRunRules.validateNewRun(attemptNo = 99, inputJsonLength = 8192, stepLimit = 1))
    }

    @Test
    fun `validateNewRun rejects attemptNo below one first`() {
        val reason = AgentRunRules.validateNewRun(attemptNo = 0, inputJsonLength = 8193, stepLimit = 0)
        assertTrue("reason should mention attemptNo: $reason", reason != null && reason.startsWith("attemptNo"))
    }

    @Test
    fun `validateNewRun rejects inputJson one character over the limit without truncating`() {
        val reason = AgentRunRules.validateNewRun(attemptNo = 1, inputJsonLength = 8193, stepLimit = 256)
        assertTrue("reason should mention inputJson: $reason", reason != null && reason.contains("inputJson"))
        assertTrue("reason should point at inputRef: $reason", reason != null && reason.contains("inputRef"))
        assertTrue("reason should carry the actual length: $reason", reason != null && reason.contains("8193"))
        assertNull(AgentRunRules.validateNewRun(attemptNo = 1, inputJsonLength = 8192, stepLimit = 256))
    }

    @Test
    fun `validateNewRun rejects stepLimit below one when set`() {
        val reason = AgentRunRules.validateNewRun(attemptNo = 1, inputJsonLength = null, stepLimit = 0)
        assertTrue("reason should mention stepLimit: $reason", reason != null && reason.contains("stepLimit"))
        assertNull(AgentRunRules.validateNewRun(attemptNo = 1, inputJsonLength = null, stepLimit = null))
    }

    // ---------- 派活去重键 ----------

    @Test
    fun `normalizeSourceKey trims and maps blank to null`() {
        assertNull(AgentRunRules.normalizeSourceKey(null))
        assertNull(AgentRunRules.normalizeSourceKey(""))
        assertNull(AgentRunRules.normalizeSourceKey("   "))
        assertNull(AgentRunRules.normalizeSourceKey("\t\n "))
        assertEquals("dialog:1", AgentRunRules.normalizeSourceKey("  dialog:1  "))
    }

    @Test
    fun `normalizeSourceKey keeps inner whitespace and case`() {
        assertEquals("a b", AgentRunRules.normalizeSourceKey(" a b "))
        assertEquals("ABC-1", AgentRunRules.normalizeSourceKey("ABC-1"))
    }

    // ---------- 模型便捷属性 ----------

    @Test
    fun `AgentRun isTerminal property follows the rule layer`() {
        for (state in AgentRunState.entries) {
            assertEquals("isTerminal($state)", state in terminalStates, run(state = state).isTerminal)
        }
    }
}
