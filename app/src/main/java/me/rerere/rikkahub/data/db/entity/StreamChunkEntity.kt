package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 生成中的增量片段：每写出一小段就往表里追加一行（几百字节），
 * 不重写会话行、不重跑 FTS；进程被杀后按 message_id + part_index 顺序拼回来。
 */
@Entity(
    tableName = "stream_chunk",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["conversation_id", "message_id"])],
)
data class StreamChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("message_id")
    val messageId: String,
    @ColumnInfo("part_index")
    val partIndex: Int,
    /** text / reasoning */
    @ColumnInfo("part_kind")
    val partKind: String,
    /** 本次新增的文本；[isReset] 为 true 时是这一段从头开始的完整文本（重试把内容改短/换掉） */
    @ColumnInfo("chunk_text")
    val chunkText: String,
    @ColumnInfo("is_reset", defaultValue = "0")
    val isReset: Boolean = false,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
