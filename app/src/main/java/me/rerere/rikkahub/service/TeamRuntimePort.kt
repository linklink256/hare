package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation

/**
 * AgentTeamService 所需的运行时能力（派活/查询/暂停/审批/结束通知）。
 * 只做转发形状，不引入新语义；后续可换成 Run 内核的实现。
 */
interface TeamRuntimePort {
    /** 每轮生成结束的会话 id 流，语义等同 ChatService.generationDoneFlow。 */
    val roundFinished: SharedFlow<Uuid>

    /** Run 阶段事件（含 runId）：relay 只按 runId 处理，不靠会话最新一轮猜 */
    val runFinished: Flow<RunLifecycleEvent>

    /** 会话级异常流（含首次历史回放），已脱去 ChatService 内部类型。 */
    val failures: Flow<List<TeamRuntimeFailure>>

    /** 会话 → 生成 Job；非 null 含排队/惰性/取消中，仍算占位。 */
    fun observeJobs(): Flow<Map<Uuid, Job?>>

    suspend fun lastMessage(conversationId: Uuid): UIMessage?

    /** 按精确消息 id 取当前输出；缺失返回 null，不回退到最新消息 */
    suspend fun message(conversationId: Uuid, messageId: Uuid): UIMessage?

    suspend fun conversation(conversationId: Uuid): Conversation?

    /** 入队一条用户消息并尝试启动（answer=true）；[runId]/[mailboxId] 由派活方先持久化创建 */
    fun dispatch(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        runId: Uuid? = null,
        mailboxId: Uuid? = null,
    )

    /**
     * 团队通知：忙则插节点/插话，闲则入队，不解除暂停。
     * [messageId] 非空表示 mailbox 重投：只按稳定 id 追加转录，不开轮、不碰队列暂停。
     */
    suspend fun notice(conversationId: Uuid, content: List<UIMessagePart>, messageId: Uuid? = null)

    /** 按消息 id 查转录里是否已有这条通知（用于重投去重） */
    suspend fun messageById(conversationId: Uuid, messageId: Uuid): UIMessage?

    /** 启动恢复屏障：Run 恢复完成（或明确失败）后才返回 */
    suspend fun awaitReady()

    fun resumeQueue(conversationId: Uuid)

    fun isQueuePaused(conversationId: Uuid): Boolean

    fun peekAwaitingApproval(conversationId: Uuid): Boolean?

    suspend fun stop(conversationId: Uuid)
}

/** 适配器侧的轻量失败事件：字段直接取自 ChatError，不新增判断。 */
data class TeamRuntimeFailure(
    val id: Uuid,
    val conversationId: Uuid?,
    val title: String?,
    val error: Throwable,
)
