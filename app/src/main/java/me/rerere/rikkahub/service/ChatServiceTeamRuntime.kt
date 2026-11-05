package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation

/** ChatService 的 TeamRuntimePort 实现：逐个方法一对一转发，保持既有队列/审批语义。 */
class ChatServiceTeamRuntime(
    private val chatService: ChatService,
) : TeamRuntimePort {
    override val roundFinished: SharedFlow<Uuid> = chatService.generationDoneFlow

    override val runFinished: Flow<RunLifecycleEvent> = chatService.runEvents

    override val failures: Flow<List<TeamRuntimeFailure>> =
        chatService.errors.map { errors ->
            errors.map { TeamRuntimeFailure(it.id, it.conversationId, it.title, it.error) }
        }

    override fun observeJobs(): Flow<Map<Uuid, Job?>> = chatService.getConversationJobs()

    override suspend fun lastMessage(conversationId: Uuid): UIMessage? =
        chatService.getLastMessage(conversationId)

    override suspend fun message(conversationId: Uuid, messageId: Uuid): UIMessage? =
        chatService.getMessage(conversationId, messageId)

    override suspend fun conversation(conversationId: Uuid): Conversation? =
        chatService.getConversation(conversationId)

    override fun dispatch(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        runId: Uuid?,
        mailboxId: Uuid?,
    ) = chatService.sendMessage(conversationId, content, answer = true, runId = runId, mailboxId = mailboxId)

    override suspend fun messageById(conversationId: Uuid, messageId: Uuid): UIMessage? =
        chatService.getMessage(conversationId, messageId)

    override suspend fun awaitReady() = chatService.awaitRunRecoveryReady()

    override suspend fun completeDeferredTool(
        conversationId: Uuid,
        toolName: String,
        handle: String,
        output: List<UIMessagePart>,
        deliveryKey: String?,
    ): Boolean = chatService.completeDeferredToolCall(conversationId, toolName, handle, output, deliveryKey)

    override suspend fun notifyInbox(conversationId: Uuid, deliveryKey: String) =
        chatService.resumeForInbox(conversationId)

    override suspend fun notice(conversationId: Uuid, content: List<UIMessagePart>, messageId: Uuid?) =
        chatService.sendTeamNotice(conversationId, content, messageId)

    override suspend fun steer(conversationId: Uuid, content: List<UIMessagePart>): Boolean =
        chatService.steerGeneration(conversationId, content)

    override fun resumeQueue(conversationId: Uuid) = chatService.resumeMessageQueue(conversationId)

    override fun isQueuePaused(conversationId: Uuid): Boolean =
        chatService.isMessageQueuePaused(conversationId)

    override fun peekAwaitingApproval(conversationId: Uuid): Boolean? =
        chatService.peekAwaitingApproval(conversationId)

    override suspend fun stop(conversationId: Uuid) = chatService.stopGeneration(conversationId)
}
