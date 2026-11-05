package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolOperationRules 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的字面量真值表，只使用 SPEC-R4 §1 声明的 API
 * （ToolOperationState / ToolOperation / ToolOperationRules / RecoveryPlan，
 * 含 MAX_INPUT_CHARS / MAX_OUTPUT_CHARS 两个常量）。
 * 不从产品实现反推期望；RecoveryPlan 的分类按行 id 断言（用 toSet 比较，不假定顺序）。
 * 本轮不运行测试（executed: false），未覆盖项见 TEST-R4.md。
 */
class ToolOperationRulesTest {

    // ---------- 1. transition：INTENT 的三条合法出边 ----------

    @Test
    fun `intent to succeeded is allowed and yields succeeded`() {
        assertEquals(
            ToolOperationState.SUCCEEDED,
            ToolOperationRules.transition(ToolOperationState.INTENT, ToolOperationState.SUCCEEDED),
        )
    }

    @Test
    fun `intent to failed is allowed and yields failed`() {
        assertEquals(
            ToolOperationState.FAILED,
            ToolOperationRules.transition(ToolOperationState.INTENT, ToolOperationState.FAILED),
        )
    }

    @Test
    fun `intent to uncertain is allowed and yields uncertain`() {
        assertEquals(
            ToolOperationState.UNCERTAIN,
            ToolOperationRules.transition(ToolOperationState.INTENT, ToolOperationState.UNCERTAIN),
        )
    }

    // ---------- 2. transition：INTENT → INTENT 非法 ----------

    @Test
    fun `intent to intent is rejected`() {
        assertNull(
            ToolOperationRules.transition(ToolOperationState.INTENT, ToolOperationState.INTENT),
        )
    }

    // ---------- 3. transition：三个终态没有任何出边 ----------

    @Test
    fun `succeeded has no out edges`() {
        assertNull(ToolOperationRules.transition(ToolOperationState.SUCCEEDED, ToolOperationState.INTENT))
        assertNull(ToolOperationRules.transition(ToolOperationState.SUCCEEDED, ToolOperationState.SUCCEEDED))
        assertNull(ToolOperationRules.transition(ToolOperationState.SUCCEEDED, ToolOperationState.FAILED))
        assertNull(ToolOperationRules.transition(ToolOperationState.SUCCEEDED, ToolOperationState.UNCERTAIN))
    }

    @Test
    fun `failed has no out edges`() {
        assertNull(ToolOperationRules.transition(ToolOperationState.FAILED, ToolOperationState.INTENT))
        assertNull(ToolOperationRules.transition(ToolOperationState.FAILED, ToolOperationState.SUCCEEDED))
        assertNull(ToolOperationRules.transition(ToolOperationState.FAILED, ToolOperationState.FAILED))
        assertNull(ToolOperationRules.transition(ToolOperationState.FAILED, ToolOperationState.UNCERTAIN))
    }

    @Test
    fun `uncertain has no out edges`() {
        assertNull(ToolOperationRules.transition(ToolOperationState.UNCERTAIN, ToolOperationState.INTENT))
        assertNull(ToolOperationRules.transition(ToolOperationState.UNCERTAIN, ToolOperationState.SUCCEEDED))
        assertNull(ToolOperationRules.transition(ToolOperationState.UNCERTAIN, ToolOperationState.FAILED))
        assertNull(ToolOperationRules.transition(ToolOperationState.UNCERTAIN, ToolOperationState.UNCERTAIN))
    }

    // ---------- 4. isTerminal ----------

    @Test
    fun `the three finished states are terminal`() {
        assertTrue(ToolOperationRules.isTerminal(ToolOperationState.SUCCEEDED))
        assertTrue(ToolOperationRules.isTerminal(ToolOperationState.FAILED))
        assertTrue(ToolOperationRules.isTerminal(ToolOperationState.UNCERTAIN))
    }

    @Test
    fun `intent is not terminal`() {
        assertFalse(ToolOperationRules.isTerminal(ToolOperationState.INTENT))
    }

    // ---------- 5. operationKey ----------

    @Test
    fun `operation key uses the tool colon run colon call shape`() {
        val run = Uuid.parse("11111111-1111-1111-1111-111111111111")
        assertEquals(
            "tool:11111111-1111-1111-1111-111111111111:call-1",
            ToolOperationRules.operationKey(run, "call-1"),
        )
    }

    @Test
    fun `operation key is stable for the same input`() {
        val run = Uuid.parse("22222222-2222-2222-2222-222222222222")
        assertEquals(
            ToolOperationRules.operationKey(run, "call-7"),
            ToolOperationRules.operationKey(run, "call-7"),
        )
        assertEquals("tool:22222222-2222-2222-2222-222222222222:call-7", ToolOperationRules.operationKey(run, "call-7"))
    }

    @Test
    fun `operation key differs per tool call id and per run id`() {
        val runA = Uuid.parse("33333333-3333-3333-3333-333333333333")
        val runB = Uuid.parse("44444444-4444-4444-4444-444444444444")
        assertTrue(ToolOperationRules.operationKey(runA, "call-1") != ToolOperationRules.operationKey(runA, "call-2"))
        assertTrue(ToolOperationRules.operationKey(runA, "call-1") != ToolOperationRules.operationKey(runB, "call-1"))
    }

    // ---------- 6. clampText ----------

    @Test
    fun `clamp text keeps null as null`() {
        assertNull(ToolOperationRules.clampText(null, 8192))
        assertNull(ToolOperationRules.clampText(null, 3))
    }

    @Test
    fun `clamp text keeps text that is at or below the limit`() {
        assertEquals("hello", ToolOperationRules.clampText("hello", 5))
        assertEquals("hello", ToolOperationRules.clampText("hello", 5000))
        assertEquals("", ToolOperationRules.clampText("", 5000))
        assertEquals("", ToolOperationRules.clampText("", 0))
    }

    @Test
    fun `clamp text cuts text longer than the limit down to the limit`() {
        assertEquals("abcde", ToolOperationRules.clampText("abcdefgh", 5))
        assertEquals("ab", ToolOperationRules.clampText("abcdefgh", 2))
    }

    @Test
    fun `clamp text honours the declared input and output size constants`() {
        // 手写真值：规格声明输入上限 8192 字符、输出上限 16384 字符
        assertEquals(8192, ToolOperationRules.MAX_INPUT_CHARS)
        assertEquals(16384, ToolOperationRules.MAX_OUTPUT_CHARS)

        val longInput = "x".repeat(ToolOperationRules.MAX_INPUT_CHARS + 5)
        val clampedInput = ToolOperationRules.clampText(longInput, ToolOperationRules.MAX_INPUT_CHARS)
        assertEquals(8192, clampedInput!!.length)
        assertEquals("x".repeat(8192), clampedInput)

        val longOutput = "y".repeat(ToolOperationRules.MAX_OUTPUT_CHARS + 1)
        val clampedOutput = ToolOperationRules.clampText(longOutput, ToolOperationRules.MAX_OUTPUT_CHARS)
        assertEquals(16384, clampedOutput!!.length)
        assertEquals("y".repeat(16384), clampedOutput)
    }

    // ---------- 7. planRecovery ----------

    @Test
    fun `recovery plan for no rows is empty`() {
        val plan = ToolOperationRules.planRecovery(emptyList())
        assertEquals(emptyList<Uuid>(), plan.uncertain)
        assertEquals(emptyList<Uuid>(), plan.completed)
        assertEquals(emptyList<Uuid>(), plan.failed)
        assertFalse(plan.hasUncertain)
    }

    @Test
    fun `two leftover intents become exactly two uncertain rows`() {
        val a = op(OP_A, ToolOperationState.INTENT)
        val b = op(OP_B, ToolOperationState.INTENT)
        val plan = ToolOperationRules.planRecovery(listOf(a, b))
        assertEquals(2, plan.uncertain.size)
        assertEquals(setOf(OP_A, OP_B), plan.uncertain.toSet())
        assertEquals(emptyList<Uuid>(), plan.completed)
        assertEquals(emptyList<Uuid>(), plan.failed)
        assertTrue(plan.hasUncertain)
    }

    @Test
    fun `recovery plan sorts finished rows by state`() {
        val succeeded = op(OP_A, ToolOperationState.SUCCEEDED)
        val failed = op(OP_B, ToolOperationState.FAILED)
        val uncertain = op(OP_C, ToolOperationState.UNCERTAIN)
        val plan = ToolOperationRules.planRecovery(listOf(succeeded, failed, uncertain))
        assertEquals(setOf(OP_A), plan.completed.toSet())
        assertEquals(setOf(OP_B, OP_C), plan.failed.toSet())
        assertEquals(emptyList<Uuid>(), plan.uncertain)
        assertFalse(plan.hasUncertain)
    }

    @Test
    fun `recovery plan mixes intent with finished rows`() {
        val intent = op(OP_A, ToolOperationState.INTENT)
        val succeeded = op(OP_B, ToolOperationState.SUCCEEDED)
        val uncertain = op(OP_C, ToolOperationState.UNCERTAIN)
        val failed = op(OP_D, ToolOperationState.FAILED)
        val plan = ToolOperationRules.planRecovery(listOf(intent, succeeded, uncertain, failed))
        assertEquals(setOf(OP_A), plan.uncertain.toSet())
        assertEquals(setOf(OP_B), plan.completed.toSet())
        assertEquals(setOf(OP_C, OP_D), plan.failed.toSet())
        assertTrue(plan.hasUncertain)
    }

    // ---------- 8. blocksAutomaticResume ----------

    @Test
    fun `leftover intent blocks automatic resume`() {
        assertTrue(ToolOperationRules.blocksAutomaticResume(listOf(op(OP_A, ToolOperationState.INTENT))))
    }

    @Test
    fun `uncertain blocks automatic resume`() {
        assertTrue(ToolOperationRules.blocksAutomaticResume(listOf(op(OP_A, ToolOperationState.UNCERTAIN))))
        assertTrue(
            ToolOperationRules.blocksAutomaticResume(
                listOf(op(OP_A, ToolOperationState.SUCCEEDED), op(OP_B, ToolOperationState.UNCERTAIN)),
            ),
        )
    }

    @Test
    fun `only finished rows do not block automatic resume`() {
        assertFalse(ToolOperationRules.blocksAutomaticResume(listOf(op(OP_A, ToolOperationState.SUCCEEDED))))
        assertFalse(ToolOperationRules.blocksAutomaticResume(listOf(op(OP_A, ToolOperationState.FAILED))))
        assertFalse(
            ToolOperationRules.blocksAutomaticResume(
                listOf(op(OP_A, ToolOperationState.SUCCEEDED), op(OP_B, ToolOperationState.FAILED)),
            ),
        )
    }

    @Test
    fun `no rows do not block automatic resume`() {
        assertFalse(ToolOperationRules.blocksAutomaticResume(emptyList()))
    }

    // ---------- 夹具（手写固定 id，便于按行 id 断言）----------

    private fun op(id: Uuid, state: ToolOperationState): ToolOperation = ToolOperation(
        id = id,
        runId = RUN_A,
        conversationId = CONV_A,
        toolCallId = "call-" + id.toString().takeLast(1),
        toolName = "read_file",
        operationKey = "tool:$RUN_A:fixture",
        state = state,
    )

    private companion object {
        val RUN_A: Uuid = Uuid.parse("55555555-5555-5555-5555-555555555555")
        val CONV_A: Uuid = Uuid.parse("66666666-6666-6666-6666-666666666666")
        val OP_A: Uuid = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
        val OP_B: Uuid = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000002")
        val OP_C: Uuid = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000003")
        val OP_D: Uuid = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000004")
    }
}
