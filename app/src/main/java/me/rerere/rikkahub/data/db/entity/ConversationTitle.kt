package me.rerere.rikkahub.data.db.entity

/** Lightweight Room projection; never loads message nodes or conversation configuration. */
data class ConversationTitle(
    val conversationId: String,
    val title: String,
    /** 会话自身的创建/更新时间；会话行已不存在时为空 */
    val createAt: Long? = null,
    val updateAt: Long? = null,
)
