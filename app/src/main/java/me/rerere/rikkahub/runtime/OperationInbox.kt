package me.rerere.rikkahub.runtime

import me.rerere.rikkahub.data.model.AgentMailboxEntry
import me.rerere.rikkahub.data.model.MailboxAckResult
import me.rerere.rikkahub.data.model.MailboxClaimResult
import me.rerere.rikkahub.data.model.MailboxDeliveryState
import me.rerere.rikkahub.data.model.MailboxKind
import me.rerere.rikkahub.data.repository.AgentMailboxStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 消费侧收件箱：claim 事件 → 交给模型真实调用 → T1 落库后由 ChatService 调 [ackPending] 做 T2。
 * claim 与 ack 之间若进程退出，行保持 CLAIMED，租约过期后可重领（宁可重投，不假读）。
 */
class OperationInbox(private val mailbox: AgentMailboxStore) {

    private data class PendingAck(val id: Uuid, val claimToken: String, val revision: Long)

    private val pendingAcks = ConcurrentHashMap<Uuid, MutableList<PendingAck>>()

    /** 领取最多 [limit] 条可消费事件；ACK 留到 T1（转录 flush）之后。 */
    suspend fun read(conversationId: Uuid, limit: Int): List<AgentMailboxEntry> {
        val out = mutableListOf<AgentMailboxEntry>()
        repeat(limit.coerceIn(1, MAX_EVENTS_PER_CALL)) {
            val claimed = mailbox.claimNextOfKinds(conversationId, CONSUMABLE_KINDS)
            if (claimed !is MailboxClaimResult.Claimed) return@repeat
            val entry = claimed.entry
            val token = entry.claimToken ?: return@repeat
            out += entry
            pendingAcks.computeIfAbsent(conversationId) { mutableListOf() }
                .add(PendingAck(entry.id, token, entry.revision))
        }
        return out
    }

    /** 未消费事件数（只读）：给有界提示用，不 claim、不 ACK。 */
    suspend fun unreadCount(conversationId: Uuid): Int =
        runCatching {
            mailbox.listForTarget(conversationId)
                .count { it.deliverState != MailboxDeliveryState.ACKED && it.kind in CONSUMABLE_KINDS }
        }.getOrDefault(0)

    /** T2：T1 落库之后调用；只确认本次真正投递过的行，返回成功确认的条目。 */
    suspend fun ackPending(conversationId: Uuid): List<AgentMailboxEntry> {
        val list = pendingAcks.remove(conversationId) ?: return emptyList()
        val acked = mutableListOf<AgentMailboxEntry>()
        for (pending in list) {
            when (val result = mailbox.ack(pending.id, pending.claimToken, pending.revision)) {
                is MailboxAckResult.Applied -> acked += result.entry
                else -> Unit
            }
        }
        return acked
    }

    companion object {
        const val MAX_EVENTS_PER_CALL = 8

        /** 只领可消费的结果/通知；STEER/ANSWER/ASSIGN 有各自的会话路径，不能被这里抢走。 */
        val CONSUMABLE_KINDS: Set<MailboxKind> = setOf(
            MailboxKind.RESULT,
            MailboxKind.FAILURE,
            MailboxKind.NOTICE,
            // 提问也是「必须由领队看到的事件」：inbox 路径不 claim/ACK，若不领它提醒永不触发
            MailboxKind.ASK,
        )
    }
}
