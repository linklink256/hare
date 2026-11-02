package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.model.TeamLeaderListItem
import me.rerere.rikkahub.data.model.TeamMemberListItem
import me.rerere.rikkahub.ui.theme.extendColors
import kotlin.uuid.Uuid

/**
 * 团队视图：从 Lead 会话行右侧的箭头进入，只列出该团队的队友会话。
 *
 * 队友会话不再混在主对话列表里；这里点进去就是那个队友自己的聊天。
 */
@Composable
fun ColumnScope.AgentTeamConversationList(
    title: String,
    leader: TeamLeaderListItem,
    members: List<TeamMemberListItem>,
    /** 当前打开的会话：用于和会话列表一致的高亮 */
    selectedConversationId: Uuid?,
    listState: LazyListState,
    sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    onBack: () -> Unit,
    onClick: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val sections = groupTeamMembers(members, sortOrder)
    val activeMembers = sections.running + sections.queued + sections.pending
    // 以团队为 key：换队/成员增减/秒计时变化都不会把用户展开的状态重置
    val sectionKey = leader.conversationId.toString()
    var showFinished by rememberSaveable(sectionKey) { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) {
            item(key = "drawer_header", contentType = "drawer_header") { header() }
        }
        item(key = "agent_team_header", contentType = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = HugeIcons.ArrowLeft01,
                        contentDescription = stringResource(R.string.agent_team_back_to_chats),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.agent_team_members) + " · " + members.size,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "agent_team_leader_label", contentType = "section") {
            ConversationSectionHeader(label = stringResource(R.string.agent_team_leader))
        }
        item(key = "agent_team_leader", contentType = "leader") {
            AgentTeamConversationItem(
                title = leader.title.ifBlank { stringResource(R.string.agent_team_leader) },
                running = leader.running,
                selected = leader.conversationId == selectedConversationId,
                onClick = { onClick(leader.conversationId) },
            )
        }
        item(key = "agent_team_members_label", contentType = "section") {
            ConversationSectionHeader(label = stringResource(R.string.agent_team_members))
        }

        if (members.isEmpty()) {
            item(key = "agent_team_empty") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        text = stringResource(id = R.string.agent_team_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // 顺序由共享纯分组决定：运行中 → 等待并发 → 其他待处理/未知；同组保持原 created_at 顺序。
        // 已结束的只默认收起显示，不删除、不重复：展开后仍逐条渲染。
        items(activeMembers, key = { it.member.id.toString() }) { view ->
            AgentTeamConversationItem(
                title = view.displayTitle,
                running = view.running,
                selected = view.member.conversationId == selectedConversationId,
                onClick = { onClick(view.member.conversationId) },
                modifier = Modifier.animateItem(),
            )
        }

        if (sections.finished.isNotEmpty()) {
            item(key = "agent_team_finished_header", contentType = "section") {
                FinishedSectionHeader(
                    count = sections.finished.size,
                    expanded = showFinished,
                    onToggle = { showFinished = !showFinished },
                )
            }
            if (showFinished) {
                items(sections.finished, key = { it.member.id.toString() }) { view ->
                    AgentTeamConversationItem(
                        title = view.displayTitle,
                        running = view.running,
                        selected = view.member.conversationId == selectedConversationId,
                        onClick = { onClick(view.member.conversationId) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * 已结束分组的分隔头：显示数量，点一下展开/收起。
 * 只收起展示，不影响其他分组，也不改成员本身的状态。
 */
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
            .clickable(
                onClickLabel = toggleLabel,
                role = Role.Button,
                onClick = onToggle,
            )
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
private fun AgentTeamConversationItem(
    title: String,
    running: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 与会话列表的 ConversationItem 保持同一套样式：胶囊高亮 + 一行标题 + 生成中的小圆点
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50f))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
                else Color.Transparent
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationListRowMinHeight)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            AnimatedVisibility(running) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.extendColors.green6)
                        .size(4.dp)
                        .semantics { contentDescription = "Running" }
                )
            }
        }
    }
}
