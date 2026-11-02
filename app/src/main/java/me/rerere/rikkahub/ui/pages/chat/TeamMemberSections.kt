package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.model.TeamMemberListItem

/**
 * 队友列表的分组，两个团队入口（抽屉团队视图、团队面板）共用同一套规则。
 *
 * 顺序固定为 运行中 → 等待并发 → 其他待处理/未知 → 已结束。
 * 组内按会话的创建/更新时间排序（跟主对话列表同一个设置）。
 */
enum class TeamMemberSection {
    /** 有生成任务且已拿到并发名额 */
    RUNNING,

    /** 有生成任务但在排队等并发名额 */
    QUEUED,

    /** 其余一律留在这里：待开始、审批中、有错误、状态未知 */
    PENDING,

    /** 明确收尾的一轮：没有任务、有上一轮耗时、审批明确不需要、没有未清错误 */
    FINISHED,
}

data class TeamMemberSections(
    val running: List<TeamMemberListItem>,
    val queued: List<TeamMemberListItem>,
    val pending: List<TeamMemberListItem>,
    val finished: List<TeamMemberListItem>,
) {
    val totalSize: Int get() = running.size + queued.size + pending.size + finished.size

    fun of(section: TeamMemberSection): List<TeamMemberListItem> = when (section) {
        TeamMemberSection.RUNNING -> running
        TeamMemberSection.QUEUED -> queued
        TeamMemberSection.PENDING -> pending
        TeamMemberSection.FINISHED -> finished
    }
}

/**
 * 是否「已结束」。只认明确信号：排队/审批/错误/未知一律不算结束。
 *
 * [TeamMemberListItem.elapsedMillis] 只说明跑过一轮（取消也算），不代表成功，所以命名只敢说「已结束」。
 */
val TeamMemberListItem.isFinished: Boolean
    get() = !hasActiveJob &&
        // 抽屉列表不跑 ticker，只有面板填 elapsedMillis；两者都表示跑完过一轮
        (elapsedMillis ?: member.lastRunElapsedMillis) != null &&
        awaitingApproval == false &&
        !hasError

/** 单个队友该进哪一组。运行中优先于旧的 elapsed 记录，避免把刚重启的一轮误判成已结束。 */
fun TeamMemberListItem.teamMemberSection(): TeamMemberSection = when {
    hasActiveJob -> if (running) TeamMemberSection.RUNNING else TeamMemberSection.QUEUED
    isFinished -> TeamMemberSection.FINISHED
    else -> TeamMemberSection.PENDING
}

/** 纯分组：单遍遍历，不排序、不订阅、不改输入。 */
fun groupTeamMembers(
    members: List<TeamMemberListItem>,
    sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
): TeamMemberSections {
    val running = ArrayList<TeamMemberListItem>(members.size)
    val queued = ArrayList<TeamMemberListItem>()
    val pending = ArrayList<TeamMemberListItem>()
    val finished = ArrayList<TeamMemberListItem>()
    members.forEach { item ->
        when (item.teamMemberSection()) {
            TeamMemberSection.RUNNING -> running += item
            TeamMemberSection.QUEUED -> queued += item
            TeamMemberSection.PENDING -> pending += item
            TeamMemberSection.FINISHED -> finished += item
        }
    }
    fun List<TeamMemberListItem>.orderedBySortOrder(): List<TeamMemberListItem> =
        sortedWith(
            compareByDescending<TeamMemberListItem> { item ->
                when (sortOrder) {
                    ConversationSortOrder.CREATE_TIME -> item.conversationCreateAt
                    ConversationSortOrder.UPDATE_TIME -> item.conversationUpdateAt
                } ?: Long.MIN_VALUE
            }.thenByDescending { it.member.createdAt.toEpochMilli() }
        )

    return TeamMemberSections(
        running = running.orderedBySortOrder(),
        queued = queued.orderedBySortOrder(),
        pending = pending.orderedBySortOrder(),
        finished = finished.orderedBySortOrder(),
    )
}
