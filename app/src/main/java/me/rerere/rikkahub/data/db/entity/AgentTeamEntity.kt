package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agent_teams")
data class AgentTeamEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("lead_conversation_id")
    val leadConversationId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("max_members")
    val maxMembers: Int,
    @ColumnInfo("max_tasks")
    val maxTasks: Int,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

/**
 * 团队成员名册。`(team_id, name)` 唯一 —— 名字一旦占用永不复用，
 * 即使成员创建失败也保留（对齐官方 Agent Teams 的扁平不可变名册）。
 */
@Entity(
    tableName = "agent_team_members",
    indices = [
        Index(value = ["team_id", "name"], unique = true),
        Index(value = ["conversation_id"]),
    ]
)
data class AgentTeamMemberEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("team_id")
    val teamId: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("role")
    val role: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

/** 共享任务板。`blocked_by` 是 JSON 数组字符串，`revision` 用于 compare-and-set。 */
@Entity(
    tableName = "agent_team_tasks",
    indices = [
        Index(value = ["team_id"]),
        Index(value = ["team_id", "status"]),
    ]
)
data class AgentTeamTaskEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("team_id")
    val teamId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("description")
    val description: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("owner_name")
    val ownerName: String?,
    @ColumnInfo("blocked_by")
    val blockedBy: String,
    @ColumnInfo("revision")
    val revision: Long,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
