package me.rerere.rikkahub.data.model

/** UI-only projection: renders title/status, never loads a message preview. */
data class TeamMemberListItem(
    val member: TeamMember,
    val running: Boolean,
    val title: String,
) {
    val displayTitle: String get() = title.takeIf { it.isNotBlank() } ?: member.name
}

/** Old versions seeded teammate titles with the immutable tool name. Treat that as a placeholder. */
internal fun needsAutomaticConversationTitle(title: String, teammateName: String?): Boolean =
    title.isBlank() || (teammateName != null && title == teammateName)
