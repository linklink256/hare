package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.AgentMailboxEntry
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.model.AgentRunRules
import me.rerere.rikkahub.data.model.MailboxClaimResult
import me.rerere.rikkahub.data.model.MailboxEnqueueResult
import me.rerere.rikkahub.data.model.MailboxKind
import me.rerere.rikkahub.data.model.MemberReportRules
import me.rerere.rikkahub.data.model.NewMailboxEntry
import me.rerere.rikkahub.data.model.AgentRunState
import me.rerere.rikkahub.data.model.AgentTeam
import me.rerere.rikkahub.data.model.AgentTeamRules
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TeamLeaderListItem
import me.rerere.rikkahub.data.model.TeamMember
import me.rerere.rikkahub.data.model.TeamMemberListItem
import me.rerere.rikkahub.data.model.TeamMemberStatus
import me.rerere.rikkahub.data.model.TeamReportStatus
import me.rerere.rikkahub.data.model.fullTeamReplyText
import me.rerere.rikkahub.data.model.parseTeamReport
import me.rerere.rikkahub.data.model.pinChatModel
import me.rerere.rikkahub.data.repository.AgentMailboxStore
import me.rerere.rikkahub.data.repository.AgentRunStore
import me.rerere.rikkahub.data.repository.AgentTeamRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CreateRunResult
import me.rerere.rikkahub.data.repository.NewAgentRun

/** 一轮超过这个时长就提醒领队一次（异常卡死没有回调，只能靠时间发现） */
private const val SLOW_ROUND_MILLIS = 10 * 60 * 1000L

/** 领队只消费这三类；STEER/ASK/ANSWER 留给后续轮次 */
private val DELIVERABLE_MAILBOX_KINDS =
    setOf(MailboxKind.RESULT, MailboxKind.FAILURE, MailboxKind.NOTICE)
private const val SLOW_ROUND_CHECK_MILLIS = 60 * 1000L

/** 团队操作被拒绝（参数非法、名册满、名字被占用……），消息直接回给模型看 */
class AgentTeamException(message: String) : IllegalStateException(message)

sealed interface TeamSendOutcome {
    data class Sent(val member: TeamMember, val running: Boolean) : TeamSendOutcome

    data object MemberNotFound : TeamSendOutcome
}

sealed interface TeamWaitOutcome {
    data class MemberFinished(val conversationId: Uuid, val memberName: String?) : TeamWaitOutcome

    /** 没有任何队友在跑 —— 调用方应先唤醒一个，而不是干等 */
    data object NoProgress : TeamWaitOutcome

    data object Timeout : TeamWaitOutcome
}

sealed interface TeamInterruptOutcome {
    data class Interrupted(val member: TeamMember, val wasRunning: Boolean) : TeamInterruptOutcome

    data object MemberNotFound : TeamInterruptOutcome
}

/** 给模型/UI 看的成员视图 */
data class TeamMemberView(
    val member: TeamMember,
    val running: Boolean,
    val lastMessage: String?,
)

/**
 * 团队编排：建队、加队友（= 建一个独立会话）、派活、等待、打断。
 *
 * 队友就是一个普通 [Conversation]，所以用户能直接点进队友的聊天看它在干什么；
 * 这里不引入第二套消息/存储体系，只借用 [TeamRuntimePort] 的既有链路。
 */
class AgentTeamService(
    private val teamRepository: AgentTeamRepository,
    private val conversationRepository: ConversationRepository,
    private val runtime: TeamRuntimePort,
    private val settingsStore: SettingsStore,
    private val teamExecutionGate: TeamExecutionGate,
    private val appScope: AppScope,
    private val context: Application,
    private val agentRunStore: AgentRunStore,
    private val agentMailboxStore: AgentMailboxStore,
) {
    // Serialize roster/start decisions across suspension points; runtime admission is separately enforced by ChatService.
    private val dispatchMutex = Mutex()

    /** 旧版本遗留的派活记录文件：只读一次做提示，之后不再写、不再按末条消息推断 */
    private val inflightFile: File by lazy { File(context.filesDir, "team-inflight-rounds.txt") }

    /**
     * 退役旧 inflight 文件：存在就只读一次，给每个现有 team 的领队发一条 legacy 提示，然后删除。
     * 不逐会话补投、不按末条消息推断，避免把未知结果说成成功。
     */
    private suspend fun retireLegacyInflightFile() {
        val count = withContext(Dispatchers.IO) {
            runCatching {
                inflightFile.takeIf { it.exists() }?.readLines()?.count { it.isNotBlank() } ?: 0
            }.getOrDefault(0)
        }
        if (count > 0) {
            runCatching {
                teamRepository.observeTeams().first().forEach { team ->
                    val notice = "【旧版本遗留的派活记录 $count 条】\n" +
                        "这些记录来自已退役的旧执行器，无法判断结果，不要当成成功；" +
                        "需要的话用 send_message 让对应队友重做。"
                    runtime.notice(team.leadConversationId, listOf(UIMessagePart.Text(notice)))
                }
            }.onFailure { Log.w("AgentTeamService", "legacy inflight notice failed", it) }
        }
        withContext(Dispatchers.IO) { runCatching { inflightFile.delete() } }
    }

    init {
        // 队友每轮结束都把结果投递到领队会话：领队空闲即开新一轮，忙则排队，不再依赖阻塞式的 wait_agent。
        appScope.launch {
            // 每条单独起一个协程，别让 DB 查询卡住 generationDoneFlow 的发射方（缓冲为 0）。
            runtime.roundFinished.collect { id -> appScope.launch { relayMemberRound(id) } }
        }
        // 新 Run 的完成事件带 runId：只按该 Run 的精确回复节点取输出
        appScope.launch {
            runtime.runFinished.collect { event ->
                if (event.state == AgentRunState.COMPLETED) {
                    appScope.launch { relayCompletedRun(event) }
                }
                // 终态通知已随迁移落进 mailbox，这里只负责消费
                appScope.launch {
                    runCatching { consumeLeadForMember(event.conversationId) }
                        .onFailure { Log.w("AgentTeamService", "mailbox drain failed", it) }
                }
            }
        }
        // 启动：等 Run 恢复屏障之后再补投各领队的待通知
        appScope.launch {
            runCatching {
                runtime.awaitReady()
                teamRepository.observeTeams().first()
                    .forEach { team -> consumeLeadMailbox(team.leadConversationId) }
            }.onFailure { Log.w("AgentTeamService", "startup mailbox drain failed", it) }
        }
        appScope.launch { retireLegacyInflightFile() }
        appScope.launch { observeMemberFailures() }
        appScope.launch { watchSlowRounds() }
    }

    /** 队友一轮失败（异常/工具错误）也告诉领队：失败轮不会走正常完成的那条投递。 */
    private suspend fun observeMemberFailures() {
        val seen = LinkedHashSet<Uuid>()
        var primed = false
        runtime.failures.collect { errors ->
            // 首次是历史错误回放，只记不报；之后只报新出现的错误
            val fresh = if (primed) errors.filter { it.id !in seen } else emptyList()
            primed = true
            // 有界地只留最近 256 个 id：超出就淘汰最旧的，不能停止记录，否则旧错误会被当成新错误重投
            errors.forEach { seen.add(it.id) }
            while (seen.size > 256) seen.remove(seen.first())
            fresh.forEach { error -> appScope.launch { relayMemberFailure(error) } }
        }
    }

    private suspend fun relayMemberFailure(error: TeamRuntimeFailure) {
        try {
            relayFailure(error)
        } finally {
        }
    }

    private suspend fun relayFailure(error: TeamRuntimeFailure) {
        val conversationId = error.conversationId ?: return
        val member = teamRepository.getMemberByConversation(conversationId) ?: return
        val team = teamRepository.getTeam(member.teamId) ?: return
        if (runtime.conversation(team.leadConversationId) == null) return
        val notice = buildString {
            append("【队友「").append(member.name).append("」异常中断，不是正常完成】\n")
            append(error.title?.takeIf { it.isNotBlank() } ?: "生成失败")
            append("：").append(error.error.message?.take(300)?.takeIf { it.isNotBlank() } ?: error.error.javaClass.simpleName.orEmpty())
            append("\n\n（它的会话和上下文都还在：用 send_message 找同一个队友让它继续或换方式，不要新建。）")
        }
        runtime.notice(team.leadConversationId, listOf(UIMessagePart.Text(notice)))
    }

    /** 一轮跑太久又没有任何回调时，主动提醒领队一次：异常卡死的场景只能靠这个发现。 */
    private suspend fun watchSlowRounds() {
        val warned = mutableMapOf<Uuid, Long>()
        while (true) {
            delay(SLOW_ROUND_CHECK_MILLIS)
            val now = System.nanoTime()
            val timings = teamExecutionGate.runTimings.value
            warned.keys.retainAll(timings.filterValues { it.running }.keys)
            timings.forEach { (conversationId, timing) ->
                if (!timing.running || warned[conversationId] == timing.startedAtNanos) return@forEach
                val elapsed = timing.elapsedMillisAt(now)
                if (elapsed < SLOW_ROUND_MILLIS) return@forEach
                warned[conversationId] = timing.startedAtNanos
                appScope.launch { relaySlowRound(conversationId, elapsed) }
            }
        }
    }

    private suspend fun relaySlowRound(conversationId: Uuid, elapsedMillis: Long) {
        val member = teamRepository.getMemberByConversation(conversationId) ?: return
        val team = teamRepository.getTeam(member.teamId) ?: return
        if (runtime.conversation(team.leadConversationId) == null) return
        val minutes = elapsedMillis / 60_000L
        val notice = "【队友「${member.name}」已经跑了 $minutes 分钟还没结束】\n" +
            "（可能卡住了，也可能只是长任务：list_agents 看它最后一句；不需要它继续就 interrupt_agent 停掉这一轮，" +
            "再用 send_message 让同一个队友继续或换方式；会话和上下文都保留。）"
        runtime.notice(team.leadConversationId, listOf(UIMessagePart.Text(notice)))
    }

    /**
     * 队友会话一轮正常结束后，把本轮正文作为一条消息送进领队会话。
     *
     * 只看「最后一条是助手消息且没有待审批」：等待审批与失败也会触发完成回调，
     * 上一轮的助手消息还没落地时也不能当成本轮结果。
     */
    private suspend fun relayMemberRound(conversationId: Uuid) {
        // 有 Run 记录时由 runFinished/mailbox 路径负责，不再用末条消息推断
        if (agentRunStore.latestForConversation(conversationId) != null) return
        relayRound(conversationId)
    }

    private suspend fun relayRound(conversationId: Uuid) {
        val member = teamRepository.getMemberByConversation(conversationId) ?: return
        val team = teamRepository.getTeam(member.teamId) ?: return
        val last = runtime.lastMessage(conversationId) ?: return
        if (last.role != MessageRole.ASSISTANT) return
        if (runtime.peekAwaitingApproval(conversationId) == true) return
        // 失败与用户停止都会暂停队列，这时助手消息可能只是半截，交给错误提示而不是当作结果转发。
        if (runtime.isQueuePaused(conversationId)) return
        relayCompletedRound(team, member, messageText(last) ?: return)
    }

    /**
     * 成功投递只认事件携带的该 Run 输出快照。
     * 后续 attempt 可能复用同一 UIMessage.id 追加内容，稍后重读消息不保证仍是这一版；
     * 缺快照只报 missing，不回退 lastMessage 或新版本。
     */
    private suspend fun relayCompletedRun(event: RunLifecycleEvent) {
        // 有 mailbox 结果行时由消费循环投递，这条旧路径只服务 mailbox 之前的 Run
        if (agentMailboxStore.findByIdempotencyKey(AgentRunLifecycle.resultIdempotencyKey(event.runId)) != null) {
            return
        }
        val conversationId = event.conversationId
        val member = teamRepository.getMemberByConversation(conversationId) ?: return
        val team = teamRepository.getTeam(member.teamId) ?: return
        val run = agentRunStore.get(event.runId)
        if (run != null && (run.conversationId != conversationId || run.state != AgentRunState.COMPLETED)) return
        if (run == null) Log.w("AgentTeamService", "run row missing for ${event.runId}; use event snapshot")
        if (runtime.conversation(team.leadConversationId) == null) return
        val body = event.message?.let(::messageText)
        if (body == null) {
            runtime.notice(
                team.leadConversationId,
                listOf(
                    UIMessagePart.Text(
                        "【队友「${member.name}」本轮已完成，但结果快照缺失（missing）】\n" +
                            "（没有可精确对应的输出，未转发任何旧结果；不要把上一条当成本轮结果。）"
                    )
                ),
            )
            return
        }
        relayCompletedRound(team, member, body)
    }

    private suspend fun relayCompletedRound(team: AgentTeam, member: TeamMember, body: String) {
        val report = parseTeamReport(body)
        if (runtime.conversation(team.leadConversationId) == null) return
        val title = when (report.status) {
            TeamReportStatus.DONE -> "【队友「${member.name}」完成了】"
            TeamReportStatus.BLOCKED ->
                "【队友「${member.name}」卡住了${report.reason?.let { "：$it" }.orEmpty()}】"
            TeamReportStatus.UNKNOWN -> "【队友「${member.name}」本轮结束，未标注状态】"
        }
        // 复用同一个队友会话，而不是再建一个：上下文和已做过的事都还在它那边
        val hint = if (report.status == TeamReportStatus.BLOCKED) {
            "（自动送达，不需要 wait_agent；卡住时需要你给条件或换方式，继续用 send_message 找同一个队友，不要新建。）"
        } else {
            "（自动送达，不需要 wait_agent；要跟进就继续用 send_message 找同一个队友，不要新建。）"
        }
        val notice = buildString {
            append(title).append('\n')
            append(report.body.ifBlank { "（本轮没有正文输出）" })
            append("\n\n").append(hint)
        }
        runtime.notice(team.leadConversationId, listOf(UIMessagePart.Text(notice)))
    }

    suspend fun requireLeader(conversationId: Uuid) {
        if (teamRepository.getMemberByConversation(conversationId) != null) {
            throw AgentTeamException("teammates cannot use leader tools or create nested teams")
        }
    }

    private suspend fun requireRootTeam(teamId: Uuid): AgentTeam {
        val team = teamRepository.getTeam(teamId) ?: throw AgentTeamException("team not found")
        requireLeader(team.leadConversationId)
        return team
    }

    private suspend fun checkCapacity(teamId: Uuid, target: Uuid? = null) {
        val limit = settingsStore.awaitLoaded().agentTeamMaxMembers.coerceAtLeast(1)
        val jobs = runtime.observeJobs().first()
        // A queued/lazy/cancelling job still reserves this member until completion.
        if (target != null && jobs[target] != null) return
        val occupied = AgentTeamRules.occupiedSlots(teamRepository.listMembers(teamId)) { jobs[it] != null }
        if (occupied >= limit) throw AgentTeamException(
            "already $occupied/$limit teammates running or queued; wait for one to finish, or raise the limit in settings"
        )
    }

    /** Lead 会话第一次用到团队工具时按需建队 */
    suspend fun getOrCreateTeam(leadConversationId: Uuid): AgentTeam = dispatchMutex.withLock {
        requireLeader(leadConversationId)
        teamRepository.getTeamByLeadConversation(leadConversationId)?.let { return@withLock it }
        val lead = runtime.conversation(leadConversationId)
        // 上限取自设置里的「子代理团队」页；改动只影响之后新建的团队
        val settings = settingsStore.awaitLoaded()
        val team = AgentTeam(
            leadConversationId = leadConversationId,
            title = lead?.title?.takeIf { it.isNotBlank() } ?: "Agent Team",
            maxMembers = settings.agentTeamMaxMembers,
            maxTasks = settings.agentTeamMaxTasks,
        )
        teamRepository.upsertTeam(team)
        team
    }

    /**
     * 创建队友：建一个独立会话并登记进名册。
     *
     * 名册扁平且不可变 —— 名字一旦占用永不复用，也不能重命名或删除；
     * 只有 Lead 能创建直属队友（没有嵌套团队）。
     */
    suspend fun spawnTeammate(
        teamId: Uuid,
        name: String,
        role: String = "",
        initialTask: String? = null,
        assistantId: Uuid? = null,
    ): TeamMember = dispatchMutex.withLock {
        val team = requireRootTeam(teamId)
        val normalized = AgentTeamRules.normalizeMemberName(name)
        if (!AgentTeamRules.isValidMemberName(normalized)) {
            throw AgentTeamException(
                "invalid teammate name '$name': use lowercase letters/digits/_/-, " +
                    "start with a letter, max 32 chars, and avoid reserved names"
            )
        }
        if (teamRepository.getMemberByName(teamId, normalized) != null) {
            throw AgentTeamException(
                "teammate name '$normalized' is already taken; names are permanent and never reused"
            )
        }
        checkCapacity(teamId)

        val settings = settingsStore.awaitLoaded()
        val lead = runtime.conversation(team.leadConversationId)
        val resolvedAssistant = assistantId ?: lead?.assistantId ?: DEFAULT_ASSISTANT_ID

        val conversationId = Uuid.random()
        val conversation = Conversation.ofId(
            id = conversationId,
            assistantId = resolvedAssistant,
            newConversation = true,
        ) // Leave the title blank so the configured title model can name this conversation.

        val member = TeamMember(
            teamId = teamId,
            name = normalized,
            conversationId = conversationId,
            role = role,
            status = TeamMemberStatus.ACTIVE,
        )

        // 设置里选了「队友默认模型」就把它钉在队友会话上（其余配置沿用领队助手）
        val pinned = conversation.pinChatModel(settings, settings.agentTeamModelId)
        conversationRepository.insertConversation(pinned)
        teamRepository.upsertMember(member)
        teamRepository.upsertTeam(team)

        if (!initialTask.isNullOrBlank()) {
            dispatchTracked(member, initialTask)
        }
        member
    }

    /** 非阻塞派活/传话：只保证消息进了队友的收件箱，不保证它已经处理完 */
    suspend fun sendToMember(teamId: Uuid, target: String, text: String): TeamSendOutcome = dispatchMutex.withLock {
        val team = requireRootTeam(teamId)
        val member = teamRepository.getMemberByName(teamId, target)
            ?: return@withLock TeamSendOutcome.MemberNotFound
        if (member.status == TeamMemberStatus.FAILED) {
            throw AgentTeamException("teammate '${member.name}' failed to start")
        }
        checkCapacity(teamId, member.conversationId)
        // 打断/失败会把队友队列暂停；显式派活时先恢复，别让消息压在暂停的队列里不动
        runtime.resumeQueue(member.conversationId)
        val runId = createDispatchRun(member, text)
        // 派活先进持久收件箱：同一次派活重复投递只留一行、不二次开轮
        val enqueued = agentMailboxStore.enqueue(
            NewMailboxEntry(
                targetConversationId = member.conversationId,
                kind = MailboxKind.ASSIGN,
                idempotencyKey = AgentRunLifecycle.assignIdempotencyKey(runId),
                teamId = member.teamId,
                sourceConversationId = team.leadConversationId,
                runId = runId,
                bodyText = text.takeIf { AgentRunLifecycle.bodyFitsInline(it) },
                bodyRef = "dispatch:$runId",
            )
        )
        when (enqueued) {
            is MailboxEnqueueResult.Created -> runtime.dispatch(
                conversationId = member.conversationId,
                content = listOf(UIMessagePart.Text(text)),
                runId = runId,
                mailboxId = enqueued.entry.id,
            )

            is MailboxEnqueueResult.Duplicate -> Log.i(
                "AgentTeamService",
                "assign already enqueued for run=$runId (entry=${enqueued.existingId}); skip dispatch",
            )

            // 容量满 fail-closed：先把刚建的 Run 终结，别留永久 QUEUED 的孤儿
            is MailboxEnqueueResult.CapacityExceeded -> {
                runCatching {
                    val run = agentRunStore.get(runId)
                    if (run != null && !run.isTerminal) {
                        agentRunStore.transition(
                            runId = runId,
                            expectedRevision = run.stateRevision,
                            to = AgentRunState.CANCELLED,
                            reason = AgentRunLifecycle.MAILBOX_CAPACITY_REASON,
                        )
                    }
                }.onFailure { Log.w("AgentTeamService", "cancel orphan run $runId failed", it) }
                throw AgentTeamException(
                    "teammate mailbox is full (${enqueued.pending} unacked); run=$runId cancelled"
                )
            }
        }
        val running = isRunning(member.conversationId) || hasLiveRun(member.conversationId)
        TeamSendOutcome.Sent(member, running)
    }

    fun observeLeaderConversation(team: AgentTeam): Flow<TeamLeaderListItem> = combine(
        conversationRepository.observeTitleById(team.leadConversationId),
        runtime.observeJobs(),
    ) { title, jobs ->
        TeamLeaderListItem(
            conversationId = team.leadConversationId,
            title = title.orEmpty(),
            running = jobs[team.leadConversationId]?.isActive == true,
        )
    }.distinctUntilChanged()

    /** Drawer list observes only roster/title/status, without subscribing to the elapsed-time ticker. */
    fun observeMemberList(teamId: Uuid): Flow<List<TeamMemberListItem>> = combine(
        combine(
            teamRepository.observeMembers(teamId),
            conversationRepository.observeTitlesOfTeam(teamId),
        ) { members, titles -> members to titles },
        runtime.observeJobs(),
        teamExecutionGate.admittedConversations,
        // 两个轻量集合合成一路：错误只看 conversationId，审批只取已完成轮次的标量快照；
        // 都不含每秒变化的 elapsed，抽屉不会因此重启 ticker。
        combine(
            runtime.failures.map { failures -> failures.mapNotNull { it.conversationId }.toSet() }.distinctUntilChanged(),
            teamExecutionGate.finishedApprovalSnapshots,
        ) { errorConversationIds, finishedApproval -> errorConversationIds to finishedApproval },
        // 每个会话最近 1 个 Run：热路径不拉全历史，也不为分组加载消息
        agentRunStore.observeLatestForTeam(teamId),
    ) { (members, titles), jobs, admitted, (errorConversationIds, finishedApproval), latestRuns ->
        val runs = latestRuns.associateBy { it.conversationId }
        members.map { member ->
            val hasActiveJob = jobs[member.conversationId]?.isActive == true
            val projection = AgentRunLifecycle.projection(runs[member.conversationId]?.state)
            TeamMemberListItem(
                member = member,
                // 有 Run 行以 Run 判态；无 Run 时保持 legacy（job + 准入 + 审批快照）
                running = projection?.running ?: (hasActiveJob && member.conversationId in admitted),
                title = titles[member.conversationId]?.title.orEmpty(),
                conversationCreateAt = titles[member.conversationId]?.createAt,
                conversationUpdateAt = titles[member.conversationId]?.updateAt,
                hasActiveJob = projection?.hasActiveJob ?: hasActiveJob,
                // 优先读 Run 行；无 Run 时才退回会话实时审批与完成快照。两者都没有就是未知。
                awaitingApproval = if (projection != null) {
                    projection.awaitingApproval
                } else {
                    runtime.peekAwaitingApproval(member.conversationId)
                        ?: finishedApproval[member.conversationId]
                },
                hasError = member.conversationId in errorConversationIds,
            )
        }
    }.distinctUntilChanged()

    /** Only the agent-team panel opts in to persisted/live task durations. */
    fun observeTimedMemberList(teamId: Uuid): Flow<List<TeamMemberListItem>> = combine(
        observeMemberList(teamId),
        teamExecutionGate.runTimings,
    ) { rows, timings ->
        rows to timings.filterKeys { id -> rows.any { it.member.conversationId == id } }
    }.transformLatest { (rows, timings) ->
        fun currentRows(): List<TeamMemberListItem> {
            val now = System.nanoTime()
            return rows.map { row ->
                row.copy(
                    elapsedMillis = timings[row.member.conversationId]?.elapsedMillisAt(now)
                        ?: row.member.lastRunElapsedMillis,
                )
            }
        }
        emit(currentRows())
        // Collected only while the panel is visible; the conversation drawer never starts this ticker.
        while (timings.values.any { it.running }) {
            delay(1000)
            emit(currentRows())
        }
    }.distinctUntilChanged()

    suspend fun listAgents(teamId: Uuid): List<TeamMemberView> {
        val members = teamRepository.listMembers(teamId)
        val jobs = runtime.observeJobs().first()
        // 每个会话最近 1 个 Run；有 Run 时以 Run 行判态
        val runs = agentRunStore.observeLatestForTeam(teamId).first().associateBy { it.conversationId }
        return members.map { member ->
            val lastMessage = runtime.lastMessage(member.conversationId)
            val projection = AgentRunLifecycle.projection(runs[member.conversationId]?.state)
            TeamMemberView(
                member = member,
                running = projection?.running
                    ?: (jobs[member.conversationId]?.isActive == true &&
                        teamExecutionGate.isAdmitted(member.conversationId)),
                lastMessage = lastMessage?.let(::messageText),
            )
        }
    }

    /**
     * 等任意队友的一轮生成结束。没有队友在跑时立刻返回 [TeamWaitOutcome.NoProgress]，
     * 让调用方先去唤醒一个，而不是干等超时。
     */
    suspend fun waitForChange(teamId: Uuid, timeoutMs: Long): TeamWaitOutcome {
        val members = teamRepository.listMembers(teamId)
        if (members.isEmpty()) return TeamWaitOutcome.NoProgress
        val byConversation = members.associateBy { it.conversationId }
        val jobs = runtime.observeJobs().first()
        val anyRunning = jobs.any { (conversationId, job) ->
            conversationId in byConversation && job?.isActive == true
        }
        if (!anyRunning) return TeamWaitOutcome.NoProgress

        val finished = withTimeoutOrNull(timeoutMs) {
            runtime.roundFinished.first { it in byConversation }
        } ?: return TeamWaitOutcome.Timeout
        return TeamWaitOutcome.MemberFinished(finished, byConversation[finished]?.name)
    }

    /** 打断某个队友当前这一轮（仅 Lead 可用） */
    suspend fun interrupt(teamId: Uuid, target: String): TeamInterruptOutcome {
        val member = teamRepository.getMemberByName(teamId, target)
            ?: return TeamInterruptOutcome.MemberNotFound
        val wasRunning = isRunning(member.conversationId)
        if (wasRunning) runtime.stop(member.conversationId)
        return TeamInterruptOutcome.Interrupted(member, wasRunning)
    }

    suspend fun isRunning(conversationId: Uuid): Boolean =
        runtime.observeJobs().first()[conversationId]?.isActive == true

    private fun sendToConversation(conversationId: Uuid, text: String, runId: Uuid?) {
        runtime.dispatch(
            conversationId = conversationId,
            content = listOf(UIMessagePart.Text(text)),
            runId = runId,
        )
    }

    /**
     * 显式派活：先持久 create 再入队，队列只承载 runId。
     * 超长输入改走 inputRef（不截断、不拒绝）；R1 不保证 queued 输入可恢复。
     */
    private suspend fun dispatchTracked(member: TeamMember, text: String) {
        sendToConversation(member.conversationId, text, createDispatchRun(member, text))
    }

    private suspend fun createDispatchRun(member: TeamMember, text: String): Uuid {
        val inline = buildJsonObject { put("text", text) }.toString()
            .takeIf { it.length <= AgentRunRules.MAX_INPUT_JSON_CHARS }
        val dispatchId = Uuid.random()
        return when (
            val result = agentRunStore.create(
                NewAgentRun(
                    conversationId = member.conversationId,
                    teamId = member.teamId,
                    agentId = member.id,
                    sourceKey = "team:${member.teamId}:dispatch:$dispatchId",
                    attemptNo = 1,
                    inputRef = if (inline == null) "dispatch:$dispatchId" else null,
                    inputJson = inline,
                )
            )
        ) {
            is CreateRunResult.Created -> result.run.runId
            is CreateRunResult.Duplicate -> result.existing.runId
            // 已知成员建不了 Run 就 fail-closed：不能退回 legacy 让同一派活在旧执行器上重跑
            is CreateRunResult.Rejected ->
                throw IllegalStateException("dispatch run rejected for ${member.conversationId}: ${result.reason}")
        }
    }

    /**
     * 消费领队的持久收件箱：只投递 RESULT/FAILURE/NOTICE，用条目的稳定 id 当消息 id，
     * 已存在就只 ack 不重复追加；全程不调用 resumeQueue。
     */
    private suspend fun consumeLeadMailbox(leadConversationId: Uuid) {
        while (true) {
            val claimed = when (val result = agentMailboxStore.claimNext(leadConversationId)) {
                is MailboxClaimResult.Claimed -> result.entry
                MailboxClaimResult.None -> return
            }
            if (claimed.kind !in DELIVERABLE_MAILBOX_KINDS) {
                // STEER/ASK/ANSWER 留给后续轮次，不 ack 也不投递
                continue
            }
            try {
                if (runtime.messageById(leadConversationId, claimed.id) == null) {
                    val text = deliverableText(claimed)
                    runtime.notice(leadConversationId, listOf(UIMessagePart.Text(text)), claimed.id)
                }
            } catch (e: Exception) {
                // 投递失败不 ack：租约过期后会重试
                Log.w("AgentTeamService", "deliver mailbox ${claimed.id} failed", e)
                return
            }
            ackMailboxQuietly(claimed)
        }
    }

    /** RESULT/FAILURE 优先按 v1 结构化汇报渲染；解析不出才回退原文本（legacy 的 STATUS 尾行仍由文本路径处理） */
    private suspend fun deliverableText(entry: AgentMailboxEntry): String {
        val text = entry.bodyText?.takeIf { it.isNotBlank() }
        if (text != null && entry.kind == MailboxKind.RESULT) {
            val report = MemberReportRules.parse(text)
            if (report != null) {
                val name = entry.sourceConversationId
                    ?.let { teamRepository.getMemberByConversation(it)?.name }
                    ?: "队友"
                val hint = if (report.status == me.rerere.rikkahub.data.model.MemberReportStatus.BLOCKED) {
                    "（自动送达，不需要 wait_agent；卡住时需要你给条件或换方式，继续用 send_message 找同一个队友。）"
                } else {
                    "（自动送达，不需要 wait_agent；要跟进就继续用 send_message 找同一个队友。）"
                }
                return MemberReportRules.renderForLead(name, report) + "\n\n" + hint
            }
        }
        return text ?: "【团队通知缺少正文（ref=${entry.bodyRef ?: "-"}）】"
    }

    private suspend fun consumeLeadForMember(memberConversationId: Uuid) {
        val team = teamRepository.getTeamForConversation(memberConversationId) ?: return
        consumeLeadMailbox(team.leadConversationId)
    }

    private suspend fun ackMailboxQuietly(entry: AgentMailboxEntry) {
        runCatching {
            agentMailboxStore.ack(
                id = entry.id,
                claimToken = entry.claimToken.orEmpty(),
                expectedRevision = entry.revision,
            )
        }.onFailure { Log.w("AgentTeamService", "ack mailbox ${entry.id} failed", it) }
    }

    /** 该会话是否有一个占用的 Run（排队/执行/等待） */
    private suspend fun hasLiveRun(conversationId: Uuid): Boolean {
        val run = agentRunStore.latestForConversation(conversationId) ?: return false
        return when (run.state) {
            AgentRunState.QUEUED, AgentRunState.RUNNING, AgentRunState.WAITING_APPROVAL,
            AgentRunState.WAITING_INPUT, AgentRunState.WAITING_CHILDREN,
            -> true

            else -> false
        }
    }

    private fun messageText(message: UIMessage): String? = fullTeamReplyText(
        message.parts.asSequence().filterIsInstance<UIMessagePart.Text>().map { it.text }
    )
}
