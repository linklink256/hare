package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AgentTeam
import me.rerere.rikkahub.data.model.TeamLeaderListItem
import me.rerere.rikkahub.data.model.TeamMemberListItem
import me.rerere.rikkahub.data.model.TeamTask
import me.rerere.rikkahub.data.model.TeamTaskStatus

/**
 * 团队面板要展示的全部内容。
 * [members] 来自 AgentTeamService 的轻量标题/状态投影，[tasks] 来自共享任务板。
 */
data class AgentTeamPanelState(
    val team: AgentTeam,
    val leader: TeamLeaderListItem,
    val members: List<TeamMemberListItem>,
    val tasks: List<TeamTask>,
    val acceptedTaskIds: Set<Uuid> = emptySet(),
)

/**
 * 团队面板：成员名册 + 共享任务板。
 *
 * 队友各自是普通会话，所以每一项都能直接跳进它的聊天里看进展。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTeamSheet(
    state: AgentTeamPanelState?,
    onOpenConversation: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    // 以团队为 key：换团队/成员增减/耗时秒计时变化都不会重置展开状态；state 为空时 key 稳定
    val teamKey = state?.team?.id?.toString().orEmpty()
    var showFinished by rememberSaveable(teamKey) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 固定视口：空态与名册按同一高度测量，异步 Room 数据到达不会移动 sheet anchor
                .height(560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.agent_team_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (state == null) {
                Hint(stringResource(R.string.agent_team_empty))
            } else {
                SectionTitle(stringResource(R.string.agent_team_leader))
                ConversationRow(
                    title = state.leader.title.ifBlank { stringResource(R.string.agent_team_leader) },
                    running = state.leader.running,
                    onClick = { onOpenConversation(state.leader.conversationId) },
                )

                SectionTitle(stringResource(R.string.agent_team_members))
                if (state.members.isEmpty()) {
                    Hint(stringResource(R.string.agent_team_empty))
                } else {
                    // 与抽屉共用同一套纯分组：运行中 → 等待并发 → 其他待处理/未知 → 已结束
                    val sections = groupTeamMembers(state.members)
                    (sections.running + sections.queued + sections.pending).forEach { view ->
                        MemberRow(view = view, onOpenConversation = onOpenConversation)
                    }
                    if (sections.finished.isNotEmpty()) {
                        FinishedSectionHeader(
                            count = sections.finished.size,
                            expanded = showFinished,
                            onToggle = { showFinished = !showFinished },
                        )
                        // 收起时不组装这些行；展开才逐条渲染
                        if (showFinished) {
                            sections.finished.forEach { view ->
                                MemberRow(view = view, onOpenConversation = onOpenConversation)
                            }
                        }
                    }
                }

                SectionTitle(stringResource(R.string.agent_team_tasks))
                if (state.tasks.isEmpty()) {
                    Hint(stringResource(R.string.agent_team_no_tasks))
                } else {
                    state.tasks.forEach { task ->
                        TaskRow(
                            task = task,
                            allTasks = state.tasks,
                            acceptedTaskIds = state.acceptedTaskIds,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 已结束分组的分隔头：数量 + 展开/收起箭头，与抽屉同口径。 */
@Composable
private fun FinishedSectionHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val toggleLabel = stringResource(
        if (expanded) R.string.code_block_collapse else R.string.code_block_expand
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.agent_team_finished) + " · " + count,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
            contentDescription = toggleLabel,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MemberRow(
    view: TeamMemberListItem,
    onOpenConversation: (Uuid) -> Unit,
) {
    ConversationRow(
        title = view.displayTitle,
        running = view.running,
        showElapsed = true,
        elapsedMillis = view.elapsedMillis,
        onClick = { onOpenConversation(view.member.conversationId) },
    )
}

@Composable
private fun ConversationRow(
    title: String,
    running: Boolean,
    onClick: () -> Unit,
    showElapsed: Boolean = false,
    elapsedMillis: Long? = null,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(running = running)
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            if (showElapsed) {
                TeamTaskElapsed(elapsedMillis, Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TeamTask,
    allTasks: List<TeamTask>,
    acceptedTaskIds: Set<Uuid>,
) {
    val byId = allTasks.associateBy { it.id }
    // 与派发门禁一致：依赖是「DONE 但未验收」也算未完成
    val blocked = task.blockedBy.any { dep ->
        byId[dep]?.let { it.status != TeamTaskStatus.DONE || it.id !in acceptedTaskIds } ?: true
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(running = task.status == TeamTaskStatus.CLAIMED)
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            val meta = buildString {
                append(task.status.name.lowercase())
                task.ownerName?.takeIf { it.isNotBlank() }?.let { append(" · @").append(it) }
                if (blocked) append(" · ").append(stringResource(R.string.agent_team_blocked))
                if (task.blockedBy.isNotEmpty()) append(" · deps ").append(task.blockedBy.size)
                if (task.status == TeamTaskStatus.DONE) {
                    append(" · ").append(
                        stringResource(
                            if (task.id in acceptedTaskIds) R.string.agent_team_accepted
                            else R.string.agent_team_pending_acceptance
                        )
                    )
                }
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun StatusDot(running: Boolean) {
    val color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(shape = MaterialTheme.shapes.small, color = color, modifier = Modifier.size(8.dp)) {}
}
