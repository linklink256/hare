package me.rerere.rikkahub.data.model

/** UI-only projection: renders title/status, never loads a message preview. */
data class TeamMemberListItem(
    val member: TeamMember,
    val running: Boolean,
    val title: String,
    val elapsedMillis: Long? = null,
    /** 该队友会话当前有生成任务（含排队等并发名额、尚未获准的那种） */
    val hasActiveJob: Boolean = running,
    /** 是否有工具调用在等用户审批；null 表示会话未缓存/未加载，即未知 */
    val awaitingApproval: Boolean? = null,
    /** ChatService 里该会话是否还有没清掉的错误 */
    val hasError: Boolean = false,
    /** 队友会话自身的创建/更新时间（排序用；会话行不存在时为空） */
    val conversationCreateAt: Long? = null,
    val conversationUpdateAt: Long? = null,
) {
    val displayTitle: String get() = title.takeIf { it.isNotBlank() } ?: member.name
}

/** Old versions seeded teammate titles with the immutable tool name. Treat that as a placeholder. */
internal fun needsAutomaticConversationTitle(title: String, teammateName: String?): Boolean =
    title.isBlank() || (teammateName != null && title == teammateName)
