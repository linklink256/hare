package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.StreamChunkEntity

@Dao
interface StreamChunkDAO {
    @Insert
    suspend fun insertAll(chunks: List<StreamChunkEntity>)

    @Query("SELECT * FROM stream_chunk WHERE conversation_id = :conversationId ORDER BY id ASC")
    suspend fun getOfConversation(conversationId: String): List<StreamChunkEntity>

    @Query("SELECT DISTINCT conversation_id FROM stream_chunk")
    suspend fun getConversationIds(): List<String>

    @Query("DELETE FROM stream_chunk WHERE conversation_id = :conversationId")
    suspend fun deleteOfConversation(conversationId: String)
}
