package me.rerere.rikkahub.data.db.entity

/** Lightweight Room projection; never loads message nodes or conversation configuration. */
data class ConversationTitle(
    val conversationId: String,
    val title: String,
)
