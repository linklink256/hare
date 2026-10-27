package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AgentTeam
import me.rerere.rikkahub.data.model.TeamTask
import me.rerere.rikkahub.data.model.TeamTaskStatus
import me.rerere.rikkahub.data.model.TeamMemberListItem
import kotlin.uuid.Uuid

/**
 * 团队面板要展示的全部内容。
 * [members] 来自 AgentTeamService 的轻量标题/状态投影，[tasks] 来自共享任务板。
 */
data class AgentTeamPanelState(
    val team: AgentTeam,
    val members: List<TeamMemberListItem>,
    val tasks: List<TeamTask>,
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
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
                SectionTitle(stringResource(R.string.agent_team_members))
                if (state.members.isEmpty()) {
                    Hint(stringResource(R.string.agent_team_empty))
                } else {
                    state.members.forEach { view ->
                        MemberRow(view = view, onOpenConversation = onOpenConversation)
                    }
                }

                SectionTitle(stringResource(R.string.agent_team_tasks))
                if (state.tasks.isEmpty()) {
                    Hint(stringResource(R.string.agent_team_no_tasks))
                } else {
                    state.tasks.forEach { task ->
                        TaskRow(task = task, allTasks = state.tasks)
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

@Composable
private fun MemberRow(
    view: TeamMemberListItem,
    onOpenConversation: (Uuid) -> Unit,
) {
    Surface(
        onClick = { onOpenConversation(view.member.conversationId) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(running = view.running)
            Text(
                text = view.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
    }
}

@Composable
private fun TaskRow(task: TeamTask, allTasks: List<TeamTask>) {
    val byId = allTasks.associateBy { it.id }
    val blocked = task.blockedBy.any { byId[it]?.status != TeamTaskStatus.DONE }
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
