package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentTeamEntity
import me.rerere.rikkahub.data.db.entity.AgentTeamMemberEntity
import me.rerere.rikkahub.data.db.entity.AgentTeamTaskEntity
import me.rerere.rikkahub.data.db.entity.TaskAcceptanceEntity

@Dao
interface AgentTeamDAO {
    // ---------- 团队 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTeam(team: AgentTeamEntity)

    @Query("SELECT * FROM agent_teams WHERE id = :id LIMIT 1")
    suspend fun getTeam(id: String): AgentTeamEntity?

    @Query("SELECT * FROM agent_teams WHERE lead_conversation_id = :conversationId LIMIT 1")
    suspend fun getTeamByLeadConversation(conversationId: String): AgentTeamEntity?

    @Query("SELECT * FROM agent_teams ORDER BY updated_at DESC")
    fun listTeams(): Flow<List<AgentTeamEntity>>

    /** 直接子队（创建时间升序）；跨层展开由 Repository 逐层调用，不在 SQL 里递归 */
    @Query("SELECT * FROM agent_teams WHERE parent_team_id = :parentId ORDER BY created_at ASC")
    suspend fun listSubTeams(parentId: String): List<AgentTeamEntity>

    /** 同一根账本下的团队 id（含根自己）；迁移未回填的旧行只命中 `id = :rootId` 这一支 */
    @Query("SELECT id FROM agent_teams WHERE root_team_id = :rootId OR id = :rootId")
    suspend fun listTeamIdsUnderRoot(rootId: String): List<String>

    /** 根账本累加：调用方保证 delta >= 0，账本只加不减 */
    @Query("UPDATE agent_teams SET steps_used = steps_used + :delta WHERE id = :teamId")
    suspend fun addSteps(teamId: String, delta: Int)

    @Query("DELETE FROM agent_teams WHERE id = :id")
    suspend fun deleteTeam(id: String)

    /** 所有 Lead 会话 id：抽屉据此决定哪些会话行显示「进入团队」箭头 */
    @Query("SELECT lead_conversation_id FROM agent_teams")
    fun observeLeadConversationIds(): Flow<List<String>>

    /** 会话被删除时同步清掉名册行，避免留下指向已删会话的孤儿成员 */
    @Query("DELETE FROM agent_team_members WHERE conversation_id = :conversationId")
    suspend fun deleteMemberByConversation(conversationId: String)

    /** 所有队友会话 id：抽屉据此把这些会话从主列表里隐藏 */
    @Query("SELECT conversation_id FROM agent_team_members")
    fun observeMemberConversationIds(): Flow<List<String>>

    // ---------- 名册 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: AgentTeamMemberEntity)

    @Query("SELECT * FROM agent_team_members WHERE team_id = :teamId ORDER BY created_at ASC")
    suspend fun listMembers(teamId: String): List<AgentTeamMemberEntity>

    @Query("SELECT * FROM agent_team_members WHERE team_id = :teamId ORDER BY created_at ASC")
    fun observeMembers(teamId: String): Flow<List<AgentTeamMemberEntity>>

    @Query("SELECT * FROM agent_team_members WHERE team_id = :teamId AND name = :name LIMIT 1")
    suspend fun getMemberByName(teamId: String, name: String): AgentTeamMemberEntity?

    @Query("SELECT * FROM agent_team_members WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun getMemberByConversation(conversationId: String): AgentTeamMemberEntity?

    @Query("UPDATE agent_team_members SET last_run_elapsed_millis = :elapsedMillis WHERE conversation_id = :conversationId")
    suspend fun updateMemberElapsed(conversationId: String, elapsedMillis: Long)

    @Query("SELECT COUNT(*) FROM agent_team_members WHERE team_id = :teamId")
    suspend fun countMembers(teamId: String): Int

    @Query("DELETE FROM agent_team_members WHERE team_id = :teamId")
    suspend fun deleteMembersOfTeam(teamId: String)

    // ---------- 任务板 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: AgentTeamTaskEntity)

    @Query("SELECT * FROM agent_team_tasks WHERE id = :id LIMIT 1")
    suspend fun getTask(id: String): AgentTeamTaskEntity?

    @Query("SELECT * FROM agent_team_tasks WHERE team_id = :teamId ORDER BY created_at ASC")
    suspend fun listTasks(teamId: String): List<AgentTeamTaskEntity>

    @Query("SELECT * FROM agent_team_tasks WHERE team_id = :teamId ORDER BY created_at ASC")
    fun observeTasks(teamId: String): Flow<List<AgentTeamTaskEntity>>

    @Query("SELECT COUNT(*) FROM agent_team_tasks WHERE team_id = :teamId")
    suspend fun countTasks(teamId: String): Int

    /**
     * compare-and-set 更新：只有库里 revision 与调用方看到的一致时才写入，
     * 并把 revision 加一。返回受影响行数，0 表示版本已过期（拒绝覆盖）。
     */
    @Query(
        """
        UPDATE agent_team_tasks
        SET title = :title, description = :description, status = :status,
            owner_name = :ownerName, blocked_by = :blockedBy,
            revision = revision + 1, updated_at = :updatedAt
        WHERE id = :id AND revision = :revision
        """
    )
    suspend fun updateTaskCas(
        id: String,
        revision: Long,
        title: String,
        description: String,
        status: String,
        ownerName: String?,
        blockedBy: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM agent_team_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM agent_team_tasks WHERE team_id = :teamId")
    suspend fun deleteTasksOfTeam(teamId: String)

    // ---------- 接受凭据（append-only） ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAcceptance(row: TaskAcceptanceEntity)

    @Query("SELECT * FROM task_acceptance WHERE task_id = :taskId AND task_revision = :taskRevision LIMIT 1")
    suspend fun getAcceptance(taskId: String, taskRevision: Long): TaskAcceptanceEntity?

    @Query("SELECT * FROM task_acceptance WHERE team_id = :teamId")
    suspend fun listAcceptance(teamId: String): List<TaskAcceptanceEntity>

    @Query("SELECT * FROM task_acceptance WHERE team_id = :teamId ORDER BY created_at DESC")
    fun observeAcceptance(teamId: String): Flow<List<TaskAcceptanceEntity>>
}
