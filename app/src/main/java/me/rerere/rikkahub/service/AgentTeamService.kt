package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.AgentTeam
import me.rerere.rikkahub.data.model.AgentTeamRules
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TeamMember
import me.rerere.rikkahub.data.model.TeamMemberStatus
import me.rerere.rikkahub.data.repository.AgentTeamRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/** 团队操作被拒绝（参数非法、名册满、名字被占用……），消息直接回给模型看 */
class AgentTeamException(message: String) : IllegalStateException(message)

sealed interface TeamSendOutcome {
    data class Sent(val member: TeamMember, val running: Boolean) : TeamSendOutcome

    data object MemberNotFound : TeamSendOutcome
}

sealed interface TeamWaitOutcome {
    data class MemberFinished(val conversationId: Uuid, val memberName: String?) : TeamWaitOutcome

    /** 没有任何队友在跑 —— 调用方应先唤醒一个，而不是干等 */
    data object NoProgress : TeamWaitOutcome

    data object Timeout : TeamWaitOutcome
}

sealed interface TeamInterruptOutcome {
    data class Interrupted(val member: TeamMember, val wasRunning: Boolean) : TeamInterruptOutcome

    data object MemberNotFound : TeamInterruptOutcome
}

/** 给模型/UI 看的成员视图 */
data class TeamMemberView(
    val member: TeamMember,
    val running: Boolean,
    val lastMessage: String?,
)

/**
 * 团队编排：建队、加队友（= 建一个独立会话）、派活、等待、打断。
 *
 * 队友就是一个普通 [Conversation]，所以用户能直接点进队友的聊天看它在干什么；
 * 这里不引入第二套消息/存储体系，只借用 [ChatService] 的既有链路。
 */
class AgentTeamService(
    private val teamRepository: AgentTeamRepository,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
) {
    /** Lead 会话第一次用到团队工具时按需建队 */
    suspend fun getOrCreateTeam(leadConversationId: Uuid): AgentTeam {
        teamRepository.getTeamByLeadConversation(leadConversationId)?.let { return it }
        val lead = chatService.getConversation(leadConversationId)
        val team = AgentTeam(
            leadConversationId = leadConversationId,
            title = lead?.title?.takeIf { it.isNotBlank() } ?: "Agent Team",
        )
        teamRepository.upsertTeam(team)
        return team
    }

    /**
     * 创建队友：建一个独立会话并登记进名册。
     *
     * 名册扁平且不可变 —— 名字一旦占用永不复用，也不能重命名或删除；
     * 只有 Lead 能创建直属队友（没有嵌套团队）。
     */
    suspend fun spawnTeammate(
        teamId: Uuid,
        name: String,
        role: String = "",
        initialTask: String? = null,
        assistantId: Uuid? = null,
    ): TeamMember {
        val team = teamRepository.getTeam(teamId) ?: throw AgentTeamException("team not found")
        val normalized = AgentTeamRules.normalizeMemberName(name)
        if (!AgentTeamRules.isValidMemberName(normalized)) {
            throw AgentTeamException(
                "invalid teammate name '$name': use lowercase letters/digits/_/-, " +
                    "start with a letter, max 32 chars, and avoid reserved names"
            )
        }
        if (teamRepository.getMemberByName(teamId, normalized) != null) {
            throw AgentTeamException(
                "teammate name '$normalized' is already taken; names are permanent and never reused"
            )
        }
        val count = teamRepository.countMembers(teamId)
        if (count >= team.maxMembers) {
            throw AgentTeamException("team is full: $count/${team.maxMembers} teammates")
        }

        val lead = chatService.getConversation(team.leadConversationId)
        val resolvedAssistant = assistantId ?: lead?.assistantId ?: DEFAULT_ASSISTANT_ID

        val conversationId = Uuid.random()
        val conversation = Conversation.ofId(
            id = conversationId,
            assistantId = resolvedAssistant,
            newConversation = true,
        ).copy(title = normalized)

        val member = TeamMember(
            teamId = teamId,
            name = normalized,
            conversationId = conversationId,
            role = role,
            status = TeamMemberStatus.ACTIVE,
        )

        conversationRepository.insertConversation(conversation)
        teamRepository.upsertMember(member)
        teamRepository.upsertTeam(team)

        if (!initialTask.isNullOrBlank()) {
            sendToConversation(conversationId, initialTask)
        }
        return member
    }

    /** 非阻塞派活/传话：只保证消息进了队友的收件箱，不保证它已经处理完 */
    suspend fun sendToMember(teamId: Uuid, target: String, text: String): TeamSendOutcome {
        val member = teamRepository.getMemberByName(teamId, target)
            ?: return TeamSendOutcome.MemberNotFound
        if (member.status == TeamMemberStatus.FAILED) {
            throw AgentTeamException("teammate '${member.name}' failed to start")
        }
        sendToConversation(member.conversationId, text)
        return TeamSendOutcome.Sent(member, isRunning(member.conversationId))
    }

    suspend fun listAgents(teamId: Uuid): List<TeamMemberView> {
        val members = teamRepository.listMembers(teamId)
        val jobs = chatService.getConversationJobs().first()
        return members.map { member ->
            val conversation = chatService.getConversation(member.conversationId)
            TeamMemberView(
                member = member,
                running = jobs[member.conversationId]?.isActive == true,
                lastMessage = conversation?.currentMessages?.lastOrNull()?.let(::previewText),
            )
        }
    }

    /**
     * 等任意队友的一轮生成结束。没有队友在跑时立刻返回 [TeamWaitOutcome.NoProgress]，
     * 让调用方先去唤醒一个，而不是干等超时。
     */
    suspend fun waitForChange(teamId: Uuid, timeoutMs: Long): TeamWaitOutcome {
        val members = teamRepository.listMembers(teamId)
        if (members.isEmpty()) return TeamWaitOutcome.NoProgress
        val byConversation = members.associateBy { it.conversationId }
        val jobs = chatService.getConversationJobs().first()
        val anyRunning = jobs.any { (conversationId, job) ->
            conversationId in byConversation && job?.isActive == true
        }
        if (!anyRunning) return TeamWaitOutcome.NoProgress

        val finished = withTimeoutOrNull(timeoutMs) {
            chatService.generationDoneFlow.first { it in byConversation }
        } ?: return TeamWaitOutcome.Timeout
        return TeamWaitOutcome.MemberFinished(finished, byConversation[finished]?.name)
    }

    /** 打断某个队友当前这一轮（仅 Lead 可用） */
    suspend fun interrupt(teamId: Uuid, target: String): TeamInterruptOutcome {
        val member = teamRepository.getMemberByName(teamId, target)
            ?: return TeamInterruptOutcome.MemberNotFound
        val wasRunning = isRunning(member.conversationId)
        if (wasRunning) chatService.stopGeneration(member.conversationId)
        return TeamInterruptOutcome.Interrupted(member, wasRunning)
    }

    suspend fun isRunning(conversationId: Uuid): Boolean =
        chatService.getConversationJobs().first()[conversationId]?.isActive == true

    private fun sendToConversation(conversationId: Uuid, text: String) {
        chatService.sendMessage(
            conversationId = conversationId,
            content = listOf(UIMessagePart.Text(text)),
            answer = true,
        )
    }

    private fun previewText(message: UIMessage): String? {
        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        return text.takeIf { it.isNotEmpty() }?.take(LAST_MESSAGE_PREVIEW_CHARS)
    }

    private companion object {
        const val LAST_MESSAGE_PREVIEW_CHARS = 400
    }
}
