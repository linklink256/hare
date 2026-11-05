package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_teams",
    indices = [
        // 一个 Lead 会话只能有一支队：并发 getOrCreateTeam 由唯一索引兜住，不建出第二支
        Index(value = ["lead_conversation_id"], unique = true),
        Index(value = ["parent_team_id"]),
        Index(value = ["root_team_id"]),
    ]
)
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
    /** 父团队 id；NULL = 根团队（可空列，迁移不加默认值） */
    @ColumnInfo("parent_team_id")
    val parentTeamId: String? = null,
    @ColumnInfo("depth", defaultValue = "0")
    val depth: Int = 0,
    /** 根团队 id；NULL = 迁移未回填，读出时归一为自己 */
    @ColumnInfo("root_team_id")
    val rootTeamId: String? = null,
    /** 与 AgentTeam.DEFAULT_ROOT_STEP_BUDGET 一致 */
    @ColumnInfo("step_budget", defaultValue = "1024")
    val stepBudget: Int = 1024,
    @ColumnInfo("steps_used", defaultValue = "0")
    val stepsUsed: Int = 0,
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
    @ColumnInfo(name = "last_run_elapsed_millis", defaultValue = "NULL")
    val lastRunElapsedMillis: Long? = null,
    /** JSON 数组字符串；NULL/空表示不限制 */
    @ColumnInfo(name = "allowed_tools", defaultValue = "NULL")
    val allowedTools: String? = null,
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
