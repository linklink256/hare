package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentMailboxRules 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的字面量表，不从产品里的规则函数反推。
 * Room 事务/CAS SQL/唯一索引/AutoMigration 与 repository、服务接线未执行，见 TEST-PLAN.md。
 */
class AgentMailboxRulesTest {
    private val target = Uuid.random()

    private val now = Instant.ofEpochMilli(1_000_000L)
    private val ttl = 600_000L
    /** claimedAt + ttl == now：恰好压在租约边界上 */
    private val exactlyStale = Instant.ofEpochMilli(now.toEpochMilli() - ttl)
    private val notYetStale = Instant.ofEpochMilli(now.toEpochMilli() - ttl + 1)

    private fun entry(
        state: MailboxDeliveryState = MailboxDeliveryState.PENDING,
        revision: Long = 1L,
        attemptCount: Int = 0,
        claimToken: String? = null,
        claimedAt: Instant? = null,
    ) = AgentMailboxEntry(
        targetConversationId = target,
        kind = MailboxKind.RESULT,
        idempotencyKey = "result:run-1",
        deliverState = state,
        revision = revision,
        attemptCount = attemptCount,
        claimToken = claimToken,
        claimedAt = claimedAt,
    )

    // ---------- 状态迁移真值表 ----------

    @Test
    fun `mailbox transition truth table covers all nine pairs with only two legal edges`() {
        val expected = mapOf(
            (MailboxDeliveryState.PENDING to MailboxDeliveryState.PENDING) to false,
            (MailboxDeliveryState.PENDING to MailboxDeliveryState.CLAIMED) to true,
            (MailboxDeliveryState.PENDING to MailboxDeliveryState.ACKED) to false,
            (MailboxDeliveryState.CLAIMED to MailboxDeliveryState.PENDING) to false,
            (MailboxDeliveryState.CLAIMED to MailboxDeliveryState.CLAIMED) to false,
            (MailboxDeliveryState.CLAIMED to MailboxDeliveryState.ACKED) to true,
            (MailboxDeliveryState.ACKED to MailboxDeliveryState.PENDING) to false,
            (MailboxDeliveryState.ACKED to MailboxDeliveryState.CLAIMED) to false,
            (MailboxDeliveryState.ACKED to MailboxDeliveryState.ACKED) to false,
        )
        val allPairs = MailboxDeliveryState.entries.flatMap { from ->
            MailboxDeliveryState.entries.map { to -> from to to }
        }
        assertEquals("truth table must cover 3x3", allPairs.toSet(), expected.keys)

        val mismatches = mutableListOf<String>()
        for ((pair, want) in expected) {
            val got = AgentMailboxRules.validMailboxTransition(pair.first, pair.second)
            if (got != want) mismatches += "${pair.first}->${pair.second}: got=$got want=$want"
        }
        assertTrue("transition mismatches: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun `ACKED is terminal and has no legal out-edge`() {
        for (to in MailboxDeliveryState.entries) {
            assertFalse(
                "ACKED -> $to must be illegal",
                AgentMailboxRules.validMailboxTransition(MailboxDeliveryState.ACKED, to),
            )
        }
    }

    // ---------- 终态 / 未确认 ----------

    @Test
    fun `isTerminal and isUnacked split exactly on ACKED`() {
        for (state in MailboxDeliveryState.entries) {
            assertEquals("isTerminal($state)", state == MailboxDeliveryState.ACKED, AgentMailboxRules.isTerminal(state))
            assertEquals("isUnacked($state)", state != MailboxDeliveryState.ACKED, AgentMailboxRules.isUnacked(state))
        }
    }

    // ---------- enqueue 决策 ----------

    @Test
    fun `frozen mailbox limits`() {
        assertEquals(16384, AgentMailboxRules.MAX_BODY_TEXT_CHARS)
        assertEquals(64, AgentMailboxRules.MAX_UNACKED_PER_TARGET)
        assertEquals(600_000L, AgentMailboxRules.CLAIM_TTL_MILLIS)
    }

    @Test
    fun `planEnqueue allows a fresh key below capacity`() {
        assertNull(AgentMailboxRules.planEnqueue(existingId = null, unackedCount = 0))
        assertNull(AgentMailboxRules.planEnqueue(existingId = null, unackedCount = 63))
        assertNull(AgentMailboxRules.planEnqueue(existingId = null, unackedCount = 4, limit = 5))
    }

    @Test
    fun `planEnqueue rejects at the default capacity boundary`() {
        assertEquals(
            MailboxEnqueueRejection.CAPACITY_EXCEEDED,
            AgentMailboxRules.planEnqueue(existingId = null, unackedCount = 64),
        )
        assertEquals(
            MailboxEnqueueRejection.CAPACITY_EXCEEDED,
            AgentMailboxRules.planEnqueue(existingId = null, unackedCount = 65),
        )
    }

    @Test
    fun `planEnqueue rejects at a custom capacity boundary`() {
        assertEquals(
            MailboxEnqueueRejection.CAPACITY_EXCEEDED,
            AgentMailboxRules.planEnqueue(existingId = null, unackedCount = 5, limit = 5),
        )
        assertEquals(
            MailboxEnqueueRejection.CAPACITY_EXCEEDED,
            AgentMailboxRules.planEnqueue(existingId = null, unackedCount = 1, limit = 1),
        )
    }

    @Test
    fun `planEnqueue reports DUPLICATE before capacity`() {
        assertEquals(
            MailboxEnqueueRejection.DUPLICATE,
            AgentMailboxRules.planEnqueue(existingId = Uuid.random(), unackedCount = 0),
        )
        // 既有 id 与满容量同时成立：幂等优先，不能报容量
        assertEquals(
            MailboxEnqueueRejection.DUPLICATE,
            AgentMailboxRules.planEnqueue(existingId = Uuid.random(), unackedCount = 64),
        )
    }

    // ---------- claim 决策 ----------

    @Test
    fun `planClaim applies a new revision and attempt count for PENDING`() {
        val current = entry(state = MailboxDeliveryState.PENDING, revision = 3L, attemptCount = 1)

        assertEquals(
            MailboxClaimPlan.Apply(nextRevision = 4L, attemptCount = 2),
            AgentMailboxRules.planClaim(current, now, ttl),
        )
    }

    @Test
    fun `planClaim rejects a fresh CLAIMED lease`() {
        val current = entry(
            state = MailboxDeliveryState.CLAIMED,
            revision = 2L,
            claimToken = "token-1",
            claimedAt = notYetStale,
        )

        assertEquals(MailboxClaimPlan.NotClaimable(current), AgentMailboxRules.planClaim(current, now, ttl))
    }

    @Test
    fun `planClaim allows a CLAIMED lease exactly at the TTL boundary`() {
        val current = entry(
            state = MailboxDeliveryState.CLAIMED,
            revision = 5L,
            attemptCount = 2,
            claimToken = "token-old",
            claimedAt = exactlyStale,
        )

        // claimedAt + ttl == now 即视为过期，可被重新领取（边界包含等号）
        assertEquals(
            MailboxClaimPlan.Apply(nextRevision = 6L, attemptCount = 3),
            AgentMailboxRules.planClaim(current, now, ttl),
        )
    }

    @Test
    fun `planClaim rejects ACKED, missing claimedAt, and missing rows`() {
        val acked = entry(state = MailboxDeliveryState.ACKED, claimedAt = exactlyStale)
        assertEquals(MailboxClaimPlan.NotClaimable(acked), AgentMailboxRules.planClaim(acked, now, ttl))

        val claimedWithoutTimestamp = entry(state = MailboxDeliveryState.CLAIMED, claimedAt = null)
        assertEquals(
            MailboxClaimPlan.NotClaimable(claimedWithoutTimestamp),
            AgentMailboxRules.planClaim(claimedWithoutTimestamp, now, ttl),
        )

        assertEquals(MailboxClaimPlan.NotClaimable(null), AgentMailboxRules.planClaim(null, now, ttl))
    }

    @Test
    fun `canClaim follows state plus lease boundary`() {
        assertTrue(AgentMailboxRules.canClaim(MailboxDeliveryState.PENDING, null, now, ttl))
        assertTrue(AgentMailboxRules.canClaim(MailboxDeliveryState.CLAIMED, exactlyStale, now, ttl))
        assertFalse(AgentMailboxRules.canClaim(MailboxDeliveryState.CLAIMED, notYetStale, now, ttl))
        assertFalse(AgentMailboxRules.canClaim(MailboxDeliveryState.CLAIMED, null, now, ttl))
        assertFalse(AgentMailboxRules.canClaim(MailboxDeliveryState.ACKED, exactlyStale, now, ttl))
        // 自定义 ttl 也要按同一等号边界判断
        assertTrue(AgentMailboxRules.canClaim(MailboxDeliveryState.CLAIMED, now, now, ttlMillis = 0L))
    }

    // ---------- ack 决策 ----------

    @Test
    fun `planAck reports NOT_FOUND for a missing row`() {
        assertEquals(
            MailboxAckPlan.Reject(MailboxAckRejection.NOT_FOUND, null),
            AgentMailboxRules.planAck(null, claimToken = "token-1", expectedRevision = 1L),
        )
    }

    @Test
    fun `planAck reports ALREADY_ACKED before token and revision checks`() {
        val current = entry(
            state = MailboxDeliveryState.ACKED,
            revision = 9L,
            claimToken = "token-1",
            claimedAt = exactlyStale,
        )

        // token 故意错误、版本也不匹配：ACKED 优先，重复确认不算成功
        assertEquals(
            MailboxAckPlan.Reject(MailboxAckRejection.ALREADY_ACKED, current),
            AgentMailboxRules.planAck(current, claimToken = "other", expectedRevision = 1L),
        )
    }

    @Test
    fun `planAck reports NOT_CLAIMED for a PENDING row even with a matching token`() {
        val current = entry(state = MailboxDeliveryState.PENDING, revision = 1L, claimToken = "token-1")

        assertEquals(
            MailboxAckPlan.Reject(MailboxAckRejection.NOT_CLAIMED, current),
            AgentMailboxRules.planAck(current, claimToken = "token-1", expectedRevision = 1L),
        )
    }

    @Test
    fun `planAck rejects a mismatched or missing claim token`() {
        val current = entry(
            state = MailboxDeliveryState.CLAIMED,
            revision = 4L,
            claimToken = "token-1",
            claimedAt = notYetStale,
        )
        assertEquals(
            MailboxAckPlan.Reject(MailboxAckRejection.TOKEN_MISMATCH, current),
            AgentMailboxRules.planAck(current, claimToken = "token-2", expectedRevision = 4L),
        )

        val noToken = entry(state = MailboxDeliveryState.CLAIMED, revision = 4L, claimToken = null)
        assertEquals(
            MailboxAckPlan.Reject(MailboxAckRejection.TOKEN_MISMATCH, noToken),
            AgentMailboxRules.planAck(noToken, claimToken = "", expectedRevision = 4L),
        )
    }

    @Test
    fun `planAck rejects a stale revision after the token matches`() {
        val current = entry(
            state = MailboxDeliveryState.CLAIMED,
            revision = 4L,
            claimToken = "token-1",
            claimedAt = notYetStale,
        )

        assertEquals(
            MailboxAckPlan.Reject(MailboxAckRejection.STALE_REVISION, current),
            AgentMailboxRules.planAck(current, claimToken = "token-1", expectedRevision = 3L),
        )
    }

    @Test
    fun `planAck applies the next revision when token and revision match`() {
        val current = entry(
            state = MailboxDeliveryState.CLAIMED,
            revision = 4L,
            claimToken = "token-1",
            claimedAt = notYetStale,
        )

        assertEquals(
            MailboxAckPlan.Apply(nextRevision = 5L),
            AgentMailboxRules.planAck(current, claimToken = "token-1", expectedRevision = 4L),
        )
    }

    // ---------- 新建入参校验 ----------

    @Test
    fun `validateNewEntry rejects blank idempotency keys`() {
        for (blank in listOf("", "   ", "\t\n")) {
            val reason = AgentMailboxRules.validateNewEntry(bodyText = "x", idempotencyKey = blank).orEmpty()
            assertTrue("blank key '$blank' should be rejected: $reason", reason.contains("idempotencyKey"))
        }
        assertTrue(
            "null body with a blank key must still be rejected",
            AgentMailboxRules.validateNewEntry(bodyText = null, idempotencyKey = "").orEmpty()
                .contains("idempotencyKey"),
        )
        assertNull(AgentMailboxRules.validateNewEntry(bodyText = "x", idempotencyKey = "result:1"))
    }

    @Test
    fun `validateNewEntry accepts 16384 body chars and rejects 16385 without truncating`() {
        assertNull(AgentMailboxRules.validateNewEntry(bodyText = "a".repeat(16384), idempotencyKey = "result:1"))

        val reason = AgentMailboxRules
            .validateNewEntry(bodyText = "a".repeat(16385), idempotencyKey = "result:1")
            .orEmpty()
        assertTrue("expected a rejection, got empty", reason.isNotEmpty())
        assertTrue("reason should mention bodyRef: $reason", reason.contains("bodyRef"))
        assertTrue("reason should carry the actual length: $reason", reason.contains("16385"))
    }

    @Test
    fun `validateNewEntry passes when the payload goes through bodyRef`() {
        // bodyText 为空、正文放 bodyRef 是允许的形态
        assertNull(AgentMailboxRules.validateNewEntry(bodyText = null, idempotencyKey = "result:1"))
    }

    @Test
    fun `validateNewEntry checks the blank key before the body size`() {
        val reason = AgentMailboxRules.validateNewEntry(bodyText = "a".repeat(16385), idempotencyKey = " ").orEmpty()

        assertTrue("expected a rejection, got empty", reason.isNotEmpty())
        assertTrue("key error should win: $reason", reason.contains("idempotencyKey"))
        assertFalse("size error must not win: $reason", reason.contains("bodyRef"))
    }

    // ---------- 幂等键规范化 ----------

    @Test
    fun `normalizeIdempotencyKey trims and maps blank to null`() {
        assertNull(AgentMailboxRules.normalizeIdempotencyKey(null))
        assertNull(AgentMailboxRules.normalizeIdempotencyKey(""))
        assertNull(AgentMailboxRules.normalizeIdempotencyKey("   "))
        assertNull(AgentMailboxRules.normalizeIdempotencyKey("\t\n "))
        assertEquals("result:run-1", AgentMailboxRules.normalizeIdempotencyKey("  result:run-1  "))
    }

    @Test
    fun `normalizeIdempotencyKey keeps inner whitespace and case`() {
        assertEquals("a b", AgentMailboxRules.normalizeIdempotencyKey(" a b "))
        assertEquals("RESULT:Run-1", AgentMailboxRules.normalizeIdempotencyKey("RESULT:Run-1"))
    }

    // ---------- 容量判定单测 ----------

    @Test
    fun `shouldRejectForCapacity follows the inclusive limit`() {
        assertFalse(AgentMailboxRules.shouldRejectForCapacity(0))
        assertFalse(AgentMailboxRules.shouldRejectForCapacity(63))
        assertTrue(AgentMailboxRules.shouldRejectForCapacity(64))
        assertTrue(AgentMailboxRules.shouldRejectForCapacity(65))
        assertFalse(AgentMailboxRules.shouldRejectForCapacity(0, limit = 1))
        assertTrue(AgentMailboxRules.shouldRejectForCapacity(1, limit = 1))
    }
}
