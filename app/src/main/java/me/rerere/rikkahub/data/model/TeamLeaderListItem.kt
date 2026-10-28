package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

/** Read-only leader conversation link; no messages or tool permissions are included. */
data class TeamLeaderListItem(
    val conversationId: Uuid,
    val title: String,
    val running: Boolean,
)
