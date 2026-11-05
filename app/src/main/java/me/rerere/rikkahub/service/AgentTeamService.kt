package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import java.io.File
import java.time.Duration
import java.time.Instant
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.tools.AgentTeamToolNames
import me.rerere.rikkahub.data.model.AdmissionDecision
import me.rerere.rikkahub.data.model.AgentMailboxEntry
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.model.AgentRunRules
import me.rerere.rikkahub.data.model.MailboxAckResult
import me.rerere.rikkahub.data.model.MailboxClaimResult
import me.rerere.rikkahub.data.model.MailboxDeliveryState
import me.rerere.rikkahub.data.model.MailboxEnqueueResult
import me.rerere.rikkahub.data.model.MailboxKind
import me.rerere.rikkahub.data.model.MemberReportRules
import me.rerere.rikkahub.data.model.NewMailboxEntry
import me.rerere.rikkahub.data.model.TeamAdmissionRules
import me.rerere.rikkahub.data.model.TeamAnswer
import me.rerere.rikkahub.data.model.TeamAskAnswerRules
import me.rerere.rikkahub.data.model.AgentRunState
import me.rerere.rikkahub.data.model.AgentTeam
import me.rerere.rikkahub.data.model.AgentTeamRules
import me.rerere.rikkahub.data.model.AttemptBudgetPlan
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TeamLeaderListItem
import me.rerere.rikkahub.data.model.TeamMember
import me.rerere.rikkahub.data.model.TeamMemberToolScope
import me.rerere.rikkahub.data.model.RootBudgetPlan
import me.rerere.rikkahub.data.model.SpawnPlan
import me.rerere.rikkahub.data.model.TeamBudgetRules
import me.rerere.rikkahub.data.model.TeamNestingRules
import me.rerere.rikkahub.data.model.TeamMemberListItem
import me.rerere.rikkahub.data.model.TeamMemberStatus
import me.rerere.rikkahub.data.model.TaskAcceptance
import me.rerere.rikkahub.data.model.TaskDispatchPlan
import me.rerere.rikkahub.data.model.TeamTask
import me.rerere.rikkahub.data.model.TeamTaskSchedulingRules
import me.rerere.rikkahub.data.model.TeamTaskUpdateResult
import me.rerere.rikkahub.data.model.describeDispatchRejection
import me.rerere.rikkahub.data.model.TeamReportStatus
import me.rerere.rikkahub.data.model.fullTeamReplyText
import me.rerere.rikkahub.data.model.parseTeamReport
import me.rerere.rikkahub.data.model.pinChatModel
import me.rerere.rikkahub.data.repository.AgentMailboxStore
import me.rerere.rikkahub.data.repository.AgentRunStore
import me.rerere.rikkahub.data.repository.AgentTeamRepository
import me.rerere.rikkahub.runtime.OperationRuntime
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CreateRunResult
import me.rerere.rikkahub.data.repository.NewAgentRun
import me.rerere.rikkahub.data.repository.RunTransitionResult
import me.rerere.rikkahub.data.repository.TaskAcceptanceResult

/** 一轮超过这个时长就提醒领队一次（异常卡死没有回调，只能靠时间发现） */
private const val SLOW_ROUND_MILLIS = 10 * 60 * 1000L

/** 领队消费 RESULT/FAILURE/NOTICE 与队友提问 ASK；STEER/ANSWER 不投给领队 */
/**
 * 彻底 tool 化：不再有任何"推"进对话的团队消息。
 * 所有通知与结果都留在 mailbox，由领队的 expect_report / 回合结束补投领取。
 */
private val DELIVERABLE_MAILBOX_KINDS: Set<MailboxKind> = emptySet()
private const val SLOW_ROUND_CHECK_MILLIS = 60 * 1000L

/** 这些状态都算"队友有东西要交给领队"：完成、失败、取消、中断、暂停、等输入（提问） */
private val WAKE_RUN_STATES = setOf(
    AgentRunState.COMPLETED,
    AgentRunState.FAILED,
    AgentRunState.CANCELLED,
    AgentRunState.INTERRUPTED,
    AgentRunState.PAUSED,
    AgentRunState.WAITING_INPUT,
)

/** 超过这个时间还没被消费的团队通知视为幽灵数据（重启/升级后不再重投） */
private const val STALE_MAILBOX_MILLIS = 24L * 60 * 60 * 1000

/** 自动唤醒的重试窗口：领队正忙时等它这一轮结束再回填 */
private const val WAKE_DELIVERY_ATTEMPTS = 40
private const val WAKE_RETRY_DELAY_MILLIS = 3_000L
private const val WAITING_INPUT_TIMEOUT_MILLIS = 10 * 60_000L
private const val ASK_REMINDER_INTERVAL_MILLIS = 10 * 60_000L

/** 未 ACK 结果的周期补偿间隔（回合结束那一次之外的兜底） */
private const val UNDELIVERED_SWEEP_MILLIS = 45_000L
private val SWEEP_KINDS = setOf(
    MailboxKind.RESULT, MailboxKind.FAILURE, MailboxKind.ASK, MailboxKind.NOTICE,
)

/** 队友结果只走工具通道（不做 user 消息投递） */
private const val TOOL_ONLY_TEAM_RESULTS = true

/** 父 Run 被取消/失败时后代 Run 的取消原因；与 ChatService 的 PARENT_CANCELLED_REASON 同值 */
private const val PARENT_CANCELLED_REASON = "parent_cancelled"

/** 团队操作被拒绝（参数非法、名册满、名字被占用……），消息直接回给模型看 */
class AgentTeamException(message: String) : IllegalStateException(message)

sealed interface TeamSendOutcome {
    /**
     * [task] 非空表示本次派活绑定了任务板上的任务；[queued] 是投递时快照（真在忙就只能排队）；
     * [deduplicated] 表示幂等键命中：本次既没新建也没新启动。
     */
    data class Sent(
        val member: TeamMember,
        val running: Boolean,
        /** 本次派活对应的成员 Run；后台结果按它配对到 operation 的投递键。 */
        val runId: Uuid,
        val task: TeamTask? = null,
        val queued: Boolean = false,
        val deduplicated: Boolean = false,
    ) : TeamSendOutcome

    /** 中途插话：已投进那一轮，不新建 Run、不动任务板 */
    data class Steered(val member: TeamMember) : TeamSendOutcome

    /** 没有投递：原因回给模型，让它决定改传 steer=false 还是等审批 */
    data class Rejected(val member: TeamMember, val reason: TeamSendRejectReason) : TeamSendOutcome

    data object MemberNotFound : TeamSendOutcome
}

/** 拒绝投递的原因；[wire] 直接回给模型，[hint] 说明下一步怎么走 */
enum class TeamSendRejectReason(val wire: String, val hint: String) {
    QUEUE_PAUSED(
        "queue_paused",
        "the queue is paused by a user stop or a failed round; pass steer=false to dispatch a new round (that resumes it)",
    ),
    AWAITING_APPROVAL(
        "awaiting_approval",
        "a tool call is waiting for user approval; resend after it is approved, or pass steer=false to queue a new round",
    ),
    NOT_GENERATING(
        "not_generating",
        "that teammate is not generating right now; pass steer=false to dispatch a new round",
    ),
    STEER_UNAVAILABLE(
        "steer_unavailable",
        "the running round has no safe insertion point right now; pass steer=false to queue a new round",
    ),
    STOPPED_BY_USER(
        "stopped_by_user",
        "the last round was stopped by the user (or its parent team was cancelled); pass steer=false to dispatch a new round",
    ),
}

/** 接受结果验收的去向：Accepted/AlreadyAccepted 都带回接受凭据，便于直接回给模型 */
sealed interface TeamAcceptOutcome {
    data class Accepted(val task: TeamTask, val acceptance: TaskAcceptance) : TeamAcceptOutcome

    data class AlreadyAccepted(val task: TeamTask, val acceptance: TaskAcceptance) : TeamAcceptOutcome

    data class Conflict(val current: TeamTask) : TeamAcceptOutcome

    data object NotFound : TeamAcceptOutcome
}

sealed interface TeamInterruptOutcome {
    data class Interrupted(val member: TeamMember, val wasRunning: Boolean) : TeamInterruptOutcome

    data object MemberNotFound : TeamInterruptOutcome
}

/** send_message(reply_to=...) 的结果：答复是否入队、成员是否在等待、是否续跑 */
sealed interface TeamAnswerOutcome {
    data class Queued(
        val correlationId: String,
        val memberName: String,
        val resumed: Boolean,
        val memberWaiting: Boolean,
    ) : TeamAnswerOutcome

    data object AskNotFound : TeamAnswerOutcome

    data object NotThisLead : TeamAnswerOutcome

    /** 调用者给了 target，但这条提问其实是另一个队友问的（防止答复张冠李戴） */
    data class WrongMember(val actualMember: String) : TeamAnswerOutcome

    data class AlreadyAnswered(val correlationId: String) : TeamAnswerOutcome
}

/** 给模型/UI 看的成员视图；[state] 取该会话最近一条 Run 的状态，没有 Run 时为 null */
data class TeamMemberView(
    val member: TeamMember,
    val running: Boolean,
    val lastMessage: String?,
    val state: AgentRunState? = null,
)

/**
 * 工具通道取结果时的视图：handle 目前就是队友名。
 * `terminal` 为 true 时 `resultText` 可能为空（报告还没落进信箱或已被投递过）。
 */
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
    private val operationRuntime: OperationRuntime,
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
                    enqueueNotice(team.leadConversationId, "legacy-inflight:${team.id}:$count", notice)
                }
            }.onFailure { Log.w("AgentTeamService", "legacy inflight notice failed", it) }
        }
        withContext(Dispatchers.IO) { runCatching { inflightFile.delete() } }
    }

    init {
        // 队友每轮结束都把结果投递到领队会话：领队空闲即开新一轮，忙则排队，领队从不阻塞等待。
        appScope.launch {
            // 每条单独起一个协程，别让 DB 查询卡住 generationDoneFlow 的发射方（缓冲为 0）。
            runtime.roundFinished.collect { id ->
                appScope.launch { relayMemberRound(id) }
                // 任意一轮结束都尝试消费领队收件箱：等待答复的轮次不会有 runFinished 事件，提问靠这里送达
                appScope.launch {
                    runCatching { consumeLeadForMember(id) }
                        .onFailure { Log.w("AgentTeamService", "round mailbox drain failed", it) }
                }
                // 领队自己的回合刚结束：补投所有未 ACK 事件，并提醒未答提问
                appScope.launch {
                    // 等 generationDoneFlow 的发射方退出 job，否则 completeDeferredTool 会看到“正忙”
                    delay(1_500)
                    runCatching { sweepLeadIfAny(id) }
                        .onFailure { Log.w("AgentTeamService", "lead sweep failed", it) }
                }
            }
        }
        // 新 Run 的完成事件带 runId：只按该 Run 的精确回复节点取输出
        appScope.launch {
            runtime.runFinished.collect { event ->
                if (event.state == AgentRunState.COMPLETED) {
                    appScope.launch { relayCompletedRun(event) }
                }
                // 有 await_teammate 挂起时：完成/失败/中断/等待输入都回填并续跑（无 user 消息）
                if (event.state in WAKE_RUN_STATES) {
                    appScope.launch { runCatching { wakeDeferredTool(event) } }
                }
                // 成员终态：该 Run 若已无未读事件，受理过的 ACCEPTED 必须结算，不能永久挂着
                if (me.rerere.rikkahub.data.model.AgentRunRules.isTerminal(event.state)) {
                    appScope.launch {
                        runCatching { settleOrphanAcceptedOperations(event.runId, event.conversationId) }
                            .onFailure { Log.w("AgentTeamService", "settle accepted operations failed", it) }
                    }
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
                    .forEach { team -> resumeUndelivered(team.leadConversationId) }
            }.onFailure { Log.w("AgentTeamService", "startup mailbox drain failed", it) }
        }
        // 周期补偿：会话忙、事件丢失或进程刚恢复时，未 ACK 的结果不靠“回合结束那一次”
        appScope.launch {
            // 补偿与 Run 恢复是两条独立路径：屏障失败只记一条日志，并推迟一个周期再开始扫
            val recovered = runCatching { runtime.awaitReady() }.onFailure {
                Log.w("AgentTeamService", "run recovery barrier failed; draining anyway", it)
            }.isSuccess
            if (!recovered) delay(UNDELIVERED_SWEEP_MILLIS)
            while (true) {
                delay(UNDELIVERED_SWEEP_MILLIS)
                runCatching {
                    teamRepository.observeTeams().first().forEach { team ->
                        resumeUndelivered(team.leadConversationId)
                    }
                }.onFailure { Log.w("AgentTeamService", "periodic mailbox drain failed", it) }
            }
        }
        // 升级/重启后清掉旧版本遗留的未消费行，避免被 wait/fetch 当成新结果翻出来
        appScope.launch {
            runCatching {
                runtime.awaitReady()
                teamRepository.observeTeams().first()
                    .forEach { team -> purgeStaleMailbox(team.leadConversationId) }
            }.onFailure { Log.w("AgentTeamService", "purge stale mailbox failed", it) }
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
        enqueueNotice(
            team.leadConversationId,
            "notice:fail:${member.conversationId}:${error.title}:${error.error.message?.take(80)}",
            notice,
            member.conversationId,
        )
    }

    /** 一轮跑太久又没有任何回调时，主动提醒领队一次：异常卡死的场景只能靠这个发现。 */
    private suspend fun watchSlowRounds() {
        val warned = mutableMapOf<Uuid, Long>()
        while (true) {
            delay(SLOW_ROUND_CHECK_MILLIS)
            val now = System.nanoTime()
            runCatching { expireStuckRuns() }
                .onFailure { Log.w("AgentTeamService", "expire waiting inputs failed", it) }
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
            "（可能卡住了，也可能只是长任务：team_status 看它最后一句；不需要它继续就 interrupt_agent 停掉这一轮，" +
            "再用 send_message 让同一个队友继续或换方式；会话和上下文都保留。）"
        enqueueNotice(
            team.leadConversationId,
            "notice:slow:${member.conversationId}:$elapsedMillis",
            notice,
            member.conversationId,
        )
    }

    /**
     * 队友会话一轮正常结束后，把本轮正文作为一条消息送进领队会话。
     *
     * 只看「最后一条是助手消息且没有待审批」：等待审批与失败也会触发完成回调，
     * 上一轮的助手消息还没落地时也不能当成本轮结果。
     */
    private suspend fun relayMemberRound(conversationId: Uuid) {
        // 队友结果只走工具通道：不再作为 user 消息投递，由 expect_report 取
        if (TOOL_ONLY_TEAM_RESULTS) return
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
        // 结果留在 mailbox 等 expect_report 取；这里不再写 user 消息
        if (TOOL_ONLY_TEAM_RESULTS) return
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
        if (TOOL_ONLY_TEAM_RESULTS) return
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
            "（自动送达；卡住时需要你给条件或换方式，继续用 send_message 找同一个队友，不要新建。）"
        } else {
            "（自动送达；要跟进就继续用 send_message 找同一个队友，不要新建。）"
        }
        val notice = buildString {
            append(title).append('\n')
            append(report.body.ifBlank { "（本轮没有正文输出）" })
            append("\n\n").append(hint)
        }
        runtime.notice(team.leadConversationId, listOf(UIMessagePart.Text(notice)))
    }

    /**
     * 领队工具的唯一授权点：调用者必须就是这支队（[teamId]）的 lead 会话。
     * 不是「调用者不是任何队的成员」——嵌套后成员也是自己子队的 lead。
     */
    suspend fun requireLeaderTool(caller: Uuid, teamId: Uuid): AgentTeam {
        val team = teamRepository.getTeam(teamId) ?: throw AgentTeamException("team not found")
        if (team.leadConversationId != caller) throw AgentTeamException("only this team's lead can do that")
        return team
    }

    /** 只读工具的授权：该队 lead 或本队成员（跨队一律拒绝） */
    private suspend fun requireTeamAccess(caller: Uuid, teamId: Uuid): AgentTeam {
        val team = teamRepository.getTeam(teamId) ?: throw AgentTeamException("team not found")
        if (team.leadConversationId == caller) return team
        if (teamRepository.getMemberByConversation(caller)?.teamId != teamId) {
            throw AgentTeamException("only this team's lead or one of its teammates can do that")
        }
        return team
    }

    private suspend fun checkCapacity(teamId: Uuid, target: Uuid? = null) {
        val limit = settingsStore.awaitLoaded().agentTeamMaxMembers
        val jobs = runtime.observeJobs().first()
        // A queued/lazy/cancelling job still reserves this member until completion.
        val occupied = AgentTeamRules.occupiedSlots(teamRepository.listMembers(teamId)) { jobs[it] != null }
        when (val decision = TeamAdmissionRules.planDispatch(occupied, limit, target != null && jobs[target] != null)) {
            AdmissionDecision.AlreadyQueued -> return
            AdmissionDecision.Admit -> Unit
            is AdmissionDecision.Reject -> throw AgentTeamException(
                "already ${decision.occupied}/${decision.limit} teammates running or queued; " +
                    "wait for one to finish, or raise the limit in settings"
            )
        }
    }

    /** Lead 会话第一次用到团队工具时按需建队；成员自己建队则算下一层并先过深度门禁 */
    suspend fun getOrCreateTeam(leadConversationId: Uuid): AgentTeam = dispatchMutex.withLock {
        teamRepository.getTeamByLeadConversation(leadConversationId)?.let { return@withLock it }
        val lead = runtime.conversation(leadConversationId)
        // 上限取自设置里的「子代理团队」页；改动只影响之后新建的团队
        val settings = settingsStore.awaitLoaded()
        // 调用者是某队成员时它建的是子队：先按父队定 depth/root，再过深度门禁
        val parent = teamRepository.getMemberByConversation(leadConversationId)
            ?.let { member -> teamRepository.getTeam(member.teamId) }
        val depth = if (parent == null) {
            0
        } else {
            when (val plan = TeamNestingRules.planSpawn(
                callerIsMember = true,
                currentDepth = parent.depth,
                maxDepth = TeamNestingRules.normalizeMaxDepth(settings.agentTeamMaxDepth),
            )) {
                is SpawnPlan.Rejected -> throw AgentTeamException(plan.message)
                SpawnPlan.Allowed -> TeamNestingRules.depthOf(callerIsMember = true, parentDepth = parent.depth)
            }
        }
        // 根 lead 恒 depth=0、root=自己；子队共享父队的根账本（rootTeamId）
        val created = AgentTeam(
            leadConversationId = leadConversationId,
            title = lead?.title?.takeIf { it.isNotBlank() } ?: "Agent Team",
            maxMembers = settings.agentTeamMaxMembers,
            maxTasks = settings.agentTeamMaxTasks,
            // 建队时把当前设置快照进根账本；之后调高设置仍会通过 max() 生效（不用重建团队）
            stepBudget = TeamBudgetRules.normalizeLimit(settings.agentTeamRootStepBudget),
            parentTeamId = parent?.id,
            depth = depth,
        )
        val team = if (parent == null) created else created.copy(rootTeamId = parent.rootTeamId)
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
        caller: Uuid,
        name: String,
        role: String = "",
        allowedTools: List<String> = emptyList(),
        initialTask: String? = null,
        assistantId: Uuid? = null,
    ): TeamMember = dispatchMutex.withLock {
        // 白名单先规范化再校验，文案直接回给模型；空列表表示不限制
        val scope = TeamMemberToolScope.normalize(allowedTools)
        TeamMemberToolScope.validate(scope)?.let { throw AgentTeamException(it) }
        val team = requireLeaderTool(caller, teamId)
        val settings = settingsStore.awaitLoaded()
        // 深度门禁看**调用者**所属父队：根 lead 恒可建，成员要 parent.depth+1 仍小于 maxDepth
        val callerParent = teamRepository.getMemberByConversation(caller)
            ?.let { member -> teamRepository.getTeam(member.teamId) }
        if (callerParent != null) {
            when (val plan = TeamNestingRules.planSpawn(
                callerIsMember = true,
                currentDepth = callerParent.depth,
                maxDepth = TeamNestingRules.normalizeMaxDepth(settings.agentTeamMaxDepth),
            )) {
                is SpawnPlan.Rejected -> throw AgentTeamException(plan.message)
                SpawnPlan.Allowed -> Unit
            }
        }
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
            allowedTools = scope,
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
    suspend fun sendToMember(
        teamId: Uuid,
        caller: Uuid,
        target: String,
        text: String,
        taskId: Uuid? = null,
        requestKey: String? = null,
        steer: Boolean? = null,
    ): TeamSendOutcome = dispatchMutex.withLock {
        val team = requireLeaderTool(caller, teamId)
        val member = teamRepository.getMemberByName(teamId, target)
            ?: return@withLock TeamSendOutcome.MemberNotFound
        if (member.status == TeamMemberStatus.FAILED) {
            throw AgentTeamException("teammate '${member.name}' failed to start")
        }
        // 最新 Run 只读一次：下面做持久停止门禁，“继续”分支接着复用
        val previousRun = agentRunStore.latestForConversation(member.conversationId)
        // isQueuePaused 只看已缓存 session：用户停过后 session 被回收就查不到，用最新 Run 兜住
        val stoppedByUser = previousRun != null &&
            previousRun.state == AgentRunState.CANCELLED &&
            (previousRun.reason == AgentRunLifecycle.CANCELLED_REASON ||
                previousRun.reason == PARENT_CANCELLED_REASON)
        // steer=null 是未传：自动路由；true 只许插话；false 才是“明确派新轮并恢复队列”
        if (steer != false && stoppedByUser) {
            return@withLock TeamSendOutcome.Rejected(member, TeamSendRejectReason.STOPPED_BY_USER)
        }
        val paused = runtime.isQueuePaused(member.conversationId)
        // peek 只看已缓存 session；会话被回收后要用持久 Run 的 WAITING_APPROVAL 兜住
        val awaitingApproval = runtime.peekAwaitingApproval(member.conversationId) == true ||
            previousRun?.state == AgentRunState.WAITING_APPROVAL
        // 未传/true 都不自动唤醒：暂停或待审批先拒绝，只有显式 false 才恢复队列
        if (steer != false && paused) {
            return@withLock TeamSendOutcome.Rejected(member, TeamSendRejectReason.QUEUE_PAUSED)
        }
        if (steer != false && awaitingApproval) {
            return@withLock TeamSendOutcome.Rejected(member, TeamSendRejectReason.AWAITING_APPROVAL)
        }
        // 忙/闲只看实际 Job；历史 Run 可能残留，不能拿它冒充活跃
        val generating = isRunning(member.conversationId)
        // 成员正在跑：一律把消息作为 user 文本注入当前轮（steer），绝不排队
        if (generating) {
            if (runtime.steer(member.conversationId, listOf(UIMessagePart.Text(text)))) {
                return@withLock TeamSendOutcome.Steered(member)
            }
            return@withLock TeamSendOutcome.Rejected(member, TeamSendRejectReason.STEER_UNAVAILABLE)
        }
        if (steer == true) {
            return@withLock TeamSendOutcome.Rejected(member, TeamSendRejectReason.NOT_GENERATING)
        }
        checkCapacity(teamId, member.conversationId)
        // 上一轮被中断时，这条消息就是“继续”：新 attempt 指回旧 Run，并明确不要从头重做
        // 只有进程级中断才当“继续”；用户主动停止不自动改写新指令
        val resuming = taskId == null && previousRun?.state == AgentRunState.INTERRUPTED
        val dispatchText = if (resuming) {
            "【继续】上一轮被中断；已完成的工具结果都在上下文里。继续原任务，不要从头重做。\n\n$text"
        } else {
            text
        }
        // 绑定任务时先过依赖门禁：拒绝就 fail-closed，不建 Run、不写 ASSIGN、不动任务
        val claimedTask = taskId?.let { id -> claimTaskForDispatch(teamId, id, member.name) }
        // 打断/失败会把队友队列暂停；只有显式 false 才算明确派活，才替它恢复
        if (steer == false) runtime.resumeQueue(member.conversationId)
        // 建 Run / 入队抛任何非容量异常都要把刚认领的任务放回去，否则任务卡在 CLAIMED 且没有 Run
        val (runId, enqueued) = try {
            val newRunId = createDispatchRun(
                member = member,
                text = dispatchText,
                taskId = taskId,
                requestKey = requestKey,
                previous = previousRun.takeIf { resuming },
            )
            // 派活先进持久收件箱：同一次派活重复投递只留一行、不二次开轮
            newRunId to agentMailboxStore.enqueue(
                NewMailboxEntry(
                    targetConversationId = member.conversationId,
                    kind = MailboxKind.ASSIGN,
                    idempotencyKey = AgentRunLifecycle.assignIdempotencyKey(newRunId),
                    teamId = member.teamId,
                    sourceConversationId = team.leadConversationId,
                    runId = newRunId,
                    taskId = taskId,
                    bodyText = dispatchText.takeIf { AgentRunLifecycle.bodyFitsInline(it) },
                    bodyRef = "dispatch:$newRunId",
                )
            )
        } catch (e: Exception) {
            // best-effort 回滚；容量分支有自己的回滚，不走这里（它在 try 之外抛）
            if (claimedTask != null) {
                runCatching { teamRepository.releaseTask(claimedTask.id, claimedTask.revision) }
                    .onFailure { Log.w("AgentTeamService", "release task ${claimedTask.id} failed", it) }
            }
            throw e
        }
        var effectiveTask = claimedTask
        when (enqueued) {
            is MailboxEnqueueResult.Created -> runtime.dispatch(
                conversationId = member.conversationId,
                content = listOf(UIMessagePart.Text(dispatchText)),
                runId = runId,
                mailboxId = enqueued.entry.id,
            )

            is MailboxEnqueueResult.Duplicate -> {
                Log.i(
                    "AgentTeamService",
                    "assign already enqueued for run=$runId (entry=${enqueued.existingId}); skip dispatch",
                )
                // 没有真正派出去：把刚认领的任务放回 PENDING，别留无主的 CLAIMED
                if (claimedTask != null) {
                    runCatching { teamRepository.releaseTask(claimedTask.id, claimedTask.revision) }
                        .onFailure { Log.w("AgentTeamService", "release task ${claimedTask.id} failed", it) }
                    effectiveTask = null
                }
            }

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
                // 派活没成，把本次认领的任务放回 PENDING；best-effort，失败只记日志
                if (claimedTask != null) {
                    runCatching { teamRepository.releaseTask(claimedTask.id, claimedTask.revision) }
                        .onFailure { Log.w("AgentTeamService", "release task ${claimedTask.id} failed", it) }
                }
                throw AgentTeamException(
                    "teammate mailbox is full (${enqueued.pending} unacked); run=$runId cancelled"
                )
            }
        }
        TeamSendOutcome.Sent(
            member = member,
            running = isRunning(member.conversationId),
            runId = runId,
            task = effectiveTask,
            queued = generating || awaitingApproval,
            deduplicated = enqueued is MailboxEnqueueResult.Duplicate,
        )
    }

    /**
     * 领队验收某任务某一版结果：只有这里能把任务置 DONE。
     * 验收者默认 lead；执行者自报完成（verifier == owner）由规则层拒绝。
     */
    suspend fun acceptResult(
        teamId: Uuid,
        caller: Uuid,
        taskId: Uuid,
        expectedRevision: Long,
        resultRef: String,
        resultSha256: String?,
        evidenceRef: String,
        verifier: String = "lead",
    ): TeamAcceptOutcome {
        requireLeaderTool(caller, teamId)
        val task = teamRepository.getTask(taskId) ?: return TeamAcceptOutcome.NotFound
        if (task.teamId != teamId) throw AgentTeamException("task belongs to another team")
        return when (
            val result = teamRepository.acceptResult(
                taskId = taskId,
                expectedRevision = expectedRevision,
                resultRef = resultRef,
                resultSha256 = resultSha256,
                evidenceRef = evidenceRef,
                verifier = verifier,
            )
        ) {
            is TaskAcceptanceResult.Applied -> TeamAcceptOutcome.Accepted(result.task, result.acceptance)
            is TaskAcceptanceResult.AlreadyAccepted ->
                TeamAcceptOutcome.AlreadyAccepted(result.task, result.acceptance)

            is TaskAcceptanceResult.Conflict -> TeamAcceptOutcome.Conflict(result.current)
            TaskAcceptanceResult.NotFound -> TeamAcceptOutcome.NotFound
            is TaskAcceptanceResult.Rejected -> throw AgentTeamException(result.message)
        }
    }

    /** 派发前任务门禁：通过后按 revision 认领，返回 CLAIMED 的任务快照 */
    private suspend fun claimTaskForDispatch(teamId: Uuid, taskId: Uuid, memberName: String): TeamTask {
        val task = teamRepository.getTask(taskId)
            ?: throw AgentTeamException("no task with id $taskId")
        if (task.teamId != teamId) throw AgentTeamException("task belongs to another team")
        // 依赖门禁用 accepted 版本：DONE 但没有接受凭据的上游仍算未满足
        val plan = TeamTaskSchedulingRules.planDispatchWithAcceptance(
            task = task,
            allTasks = teamRepository.listTasks(teamId),
            acceptedTaskIds = teamRepository.acceptedTaskIds(teamId),
            memberName = memberName,
        )
        if (plan is TaskDispatchPlan.Rejected) throw AgentTeamException(describeDispatchRejection(task, plan))
        // 仓库层在事务里会再判一次；这里把不可认领包成可读的团队错误
        val result = try {
            teamRepository.claimTask(taskId, task.revision, memberName)
        } catch (e: IllegalArgumentException) {
            throw AgentTeamException(e.message ?: "task cannot be claimed")
        }
        return when (result) {
            is TeamTaskUpdateResult.Applied -> result.task
            is TeamTaskUpdateResult.Conflict ->
                throw AgentTeamException("task changed while dispatching; re-read and retry")

            TeamTaskUpdateResult.NotFound -> throw AgentTeamException("no task with id $taskId")
        }
    }

    /**
     * 领队回答队友提问：答复只入对方收件箱，默认不唤醒。
     *
     * 幂等靠 `answer:<corr>` 唯一键：重复回答不写第二行、不改已 ACK 的行；
     * 只有显式 resume=true 且该 Run 仍在 WAITING_INPUT 时才重新排一轮。
     */
    suspend fun answerAsk(
        teamId: Uuid,
        leadConversationId: Uuid,
        correlationId: String,
        answer: String,
        resume: Boolean,
        expectedMember: String? = null,
    ): TeamAnswerOutcome {
        val corr = correlationId.trim()
        val text = answer.trim()
        // 工具层已校验，这里兜底：非法输入直接拒绝，不落任何行
        TeamAskAnswerRules.validateAnswer(corr, text)?.let { throw AgentTeamException(it) }
        val ask = agentMailboxStore.findByIdempotencyKey(TeamAskAnswerRules.askIdempotencyKey(corr))
            ?: return TeamAnswerOutcome.AskNotFound
        if (ask.kind != MailboxKind.ASK) return TeamAnswerOutcome.AskNotFound
        if (ask.targetConversationId != leadConversationId) return TeamAnswerOutcome.NotThisLead
        val memberConversationId = ask.sourceConversationId ?: return TeamAnswerOutcome.AskNotFound
        val member = teamRepository.getMemberByConversation(memberConversationId)
            ?: return TeamAnswerOutcome.AskNotFound
        if (member.teamId != teamId) return TeamAnswerOutcome.NotThisLead
        // 合并进 send_message 后 target 也要参与校验：不匹配就拒绝，避免答复错人
        if (expectedMember != null && !member.name.equals(expectedMember.trim(), ignoreCase = true)) {
            return TeamAnswerOutcome.WrongMember(member.name)
        }
        val answerKey = TeamAskAnswerRules.answerIdempotencyKey(corr)
        // 已回答过就直接返回既有事实，不写第二行
        if (agentMailboxStore.findByIdempotencyKey(answerKey) != null) {
            return TeamAnswerOutcome.AlreadyAnswered(corr)
        }
        val encoded = TeamAskAnswerRules.encodeAnswer(TeamAnswer(corr, text))
        when (
            val queued = agentMailboxStore.enqueue(
                NewMailboxEntry(
                    targetConversationId = memberConversationId,
                    kind = MailboxKind.ANSWER,
                    idempotencyKey = answerKey,
                    teamId = member.teamId,
                    sourceConversationId = leadConversationId,
                    runId = ask.runId,
                    bodyText = encoded,
                    bodyRef = null,
                )
            )
        ) {
            is MailboxEnqueueResult.Created -> Unit
            is MailboxEnqueueResult.Duplicate -> return TeamAnswerOutcome.AlreadyAnswered(corr)
            is MailboxEnqueueResult.CapacityExceeded ->
                throw AgentTeamException("teammate mailbox is full (${queued.pending} unacked); answer not queued")
        }
        val memberWaiting =
            ask.runId?.let { agentRunStore.get(it) }?.state == AgentRunState.WAITING_INPUT
        var resumed = false
        // 默认不唤醒：只有显式 resume 且成员确实在等待时才重新排一轮
        if (resume && memberWaiting) {
            runtime.dispatch(
                conversationId = memberConversationId,
                content = listOf(
                    UIMessagePart.Text(
                        "【领队已答复 · correlationId=$corr】答复在你的收件箱，本轮开始时会注入，请继续。"
                    )
                ),
                runId = ask.runId,
            )
            resumed = true
        }
        return TeamAnswerOutcome.Queued(corr, member.name, resumed, memberWaiting)
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

    suspend fun listAgents(teamId: Uuid, caller: Uuid): List<TeamMemberView> {
        requireTeamAccess(caller, teamId)
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
                state = runs[member.conversationId]?.state,
            )
        }
    }

    /** 打断某个队友当前这一轮（仅 Lead 可用）；保留旧入口，面板与旧调用继续用 */
    suspend fun interrupt(teamId: Uuid, caller: Uuid, target: String): TeamInterruptOutcome =
        interruptScoped(teamId, caller, target, "one").firstOrNull()
            ?: TeamInterruptOutcome.MemberNotFound

    /**
     * 按范围打断：one 只停目标；subtree 连它自己建的子队（递归）一起停；
     * all 停本队与所有后代队的成员。按 conversationId 去重，且永不停调用者自己。
     */
    suspend fun interruptScoped(
        teamId: Uuid,
        caller: Uuid,
        target: String?,
        scope: String,
    ): List<TeamInterruptOutcome> {
        val team = requireLeaderTool(caller, teamId)
        val normalized = scope.trim().lowercase().ifBlank { "one" }
        val members = when (normalized) {
            "one", "subtree" -> {
                val name = target?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw AgentTeamException("target is required for scope '$normalized'")
                val member = teamRepository.getMemberByName(teamId, name)
                    ?: return listOf(TeamInterruptOutcome.MemberNotFound)
                if (normalized == "one") {
                    listOf(member)
                } else {
                    listOf(member) + membersOfSubTeams(member.conversationId)
                }
            }

            "all" -> membersUnder(team)
            else -> throw AgentTeamException("unknown scope '$scope'; expected one|subtree|all")
        }
        val seen = mutableSetOf<Uuid>()
        val outcomes = mutableListOf<TeamInterruptOutcome>()
        for (member in members) {
            if (member.conversationId == caller) continue
            if (!seen.add(member.conversationId)) continue
            val wasRunning = isRunning(member.conversationId)
            if (wasRunning) runtime.stop(member.conversationId)
            outcomes += TeamInterruptOutcome.Interrupted(member, wasRunning)
        }
        return outcomes
    }

    /** 某成员自己当 lead 建出来的子队（递归）里的全部成员 */
    private suspend fun membersOfSubTeams(leadConversationId: Uuid): List<TeamMember> {
        val own = teamRepository.getTeamByLeadConversation(leadConversationId) ?: return emptyList()
        return membersUnder(own)
    }

    /** 该队加上所有后代队的成员 */
    private suspend fun membersUnder(team: AgentTeam): List<TeamMember> {
        val members = teamRepository.listMembers(team.id).toMutableList()
        teamRepository.listSubTeams(team.id).forEach { child -> members += membersUnder(child) }
        return members
    }

    /** 未 ack 的邮箱条目计数（按 kind）：team_status 的概览用，只读不领取 */
    suspend fun unackedEventSummary(conversationId: Uuid): Map<String, Int> =
        agentMailboxStore.listForTarget(conversationId, 1000)
            .filter { it.deliverState != MailboxDeliveryState.ACKED }
            .groupingBy { it.kind.name }
            .eachCount()

    suspend fun isRunning(conversationId: Uuid): Boolean =
        runtime.observeJobs().first()[conversationId]?.isActive == true

    /**
     * 只解析、不建队：owned → 成员父队 → null。
     * team_status / team_task(list|get) 用它，避免只读调用把可嵌套成员变成空子队 lead。
     */
    suspend fun resolveTeamForCaller(caller: Uuid): AgentTeam? {
        teamRepository.getTeamByLeadConversation(caller)?.let { return it }
        val member = teamRepository.getMemberByConversation(caller) ?: return null
        return teamRepository.getTeam(member.teamId)
    }

    /** 执行期授权：不建队；根会话放行（真正建队发生在 execute），成员必须仍可嵌套 */
    suspend fun authorizeTeamTool(caller: Uuid) {
        if (teamRepository.getTeamByLeadConversation(caller) != null) return
        val member = teamRepository.getMemberByConversation(caller) ?: return
        val parent = teamRepository.getTeam(member.teamId)
            ?: throw AgentTeamException("team not found for this teammate")
        val maxDepth = TeamNestingRules.normalizeMaxDepth(settingsStore.awaitLoaded().agentTeamMaxDepth)
        if (!TeamNestingRules.canNest(parent.depth, maxDepth)) {
            throw AgentTeamException(
                "nested teams are disabled (max depth $maxDepth); ask the lead to create the teammate"
            )
        }
    }

    /** 已送达但尚未答复的提问数：team_status(events) 用 */
    suspend fun pendingQuestionCount(conversationId: Uuid): Int =
        agentMailboxStore.listForTarget(conversationId, 200).count { entry ->
            if (entry.kind != MailboxKind.ASK) return@count false
            val ask = entry.bodyText?.let { TeamAskAnswerRules.parseAsk(it) } ?: return@count false
            agentMailboxStore.findByIdempotencyKey(TeamAskAnswerRules.answerIdempotencyKey(ask.correlationId)) == null
        }

    /**
     * 补偿投递：把该领队所有未 ACK 的 RESULT/FAILURE/ASK/NOTICE 按 FIFO 补投。
     * 会话忙（completeDeferredTool 返回 false）就停，留到下一次回合结束/启动再试。
     */
    private suspend fun resumeUndelivered(leadConversationId: Uuid) = deliveryMutex.withLock {
        val entries = agentMailboxStore.listForTarget(leadConversationId, 64)
            .filter { it.deliverState != MailboxDeliveryState.ACKED && it.kind in SWEEP_KINDS }
            .sortedBy { it.createdAt }
        // 一次把未 ACK 的行全部补投：逐条投会把领队拖成好几个回合，后一条还要再等一次空闲
        val ready = entries.mapNotNull { entry ->
            val current = agentMailboxStore.get(entry.id) ?: return@mapNotNull null
            current.takeIf {
                it.deliverState != MailboxDeliveryState.ACKED &&
                    // 超过 24h 的旧行交给启动清理，不在升级时把陈年结果翻出来
                    Duration.between(it.createdAt, Instant.now()).toHours() < 24
            }
        }
        if (ready.isEmpty()) return@withLock
        // 新路径：只唤醒，不 claim/不 ACK；事件由模型真调 receive_events 领取
        if (useInbox(leadConversationId)) {
            ready.forEach { runtime.notifyInbox(leadConversationId, it.id.toString()) }
            return@withLock
        }
        // 按来源成员分组：填回去的锚点按 handle 匹配，混成一个 “system” 永远落不到 expect_report 上
        val groups = ready.groupBy { entry ->
            entry.sourceConversationId?.let { teamRepository.getMemberByConversation(it)?.name } ?: "system"
        }
        for ((handle, rows) in groups) {
            // deliverableText 是 suspend，joinToString 的 transform 不是内联 lambda，先取好再拼
            val text = rows.map { deliverableText(it) }.joinToString("\n\n")
            // 组内行 id 拼成的稳定键：同一批行重复补投时不会二次改写历史
            val deliveryKey = rows.map { it.id.toString() }.sorted().joinToString(",")
            val delivered = runtime.completeDeferredTool(
                conversationId = leadConversationId,
                toolName = AgentTeamToolNames.EXPECT_REPORT,
                handle = handle,
                output = listOf(UIMessagePart.Text(text)),
                deliveryKey = deliveryKey,
            )
            if (!delivered) continue
            rows.forEach { entry ->
                val claimed = when (val result = agentMailboxStore.claimOne(entry.id)) {
                    is MailboxClaimResult.Claimed -> result.entry
                    MailboxClaimResult.None -> null
                }
                if (claimed != null) ackMailboxQuietly(claimed)
            }
        }
    }

    /** 领队回合结束：补投 + 未答提问提醒（10 分钟节流） */
    private suspend fun sweepLeadIfAny(conversationId: Uuid) {
        val team = teamRepository.getTeamByLeadConversation(conversationId) ?: return
        resumeUndelivered(conversationId)
        maybeRemindPendingAsks(conversationId, team)
    }

    private val askReminderAt = java.util.concurrent.ConcurrentHashMap<Uuid, Long>()
    // 投递串行化：runFinished 与回合结束补投可能同时看到同一条未 ACK 行，必须只投一次
    private val deliveryMutex = Mutex()

    private suspend fun maybeRemindPendingAsks(leadConversationId: Uuid, team: AgentTeam) {
        val now = System.currentTimeMillis()
        if (now - (askReminderAt[leadConversationId] ?: 0L) < ASK_REMINDER_INTERVAL_MILLIS) return
        val inboxMode = useInbox(leadConversationId)
        val pending = agentMailboxStore.listForTarget(leadConversationId, 200).filter { entry ->
            if (entry.kind != MailboxKind.ASK) return@filter false
            // legacy 提醒针对「已回填进转录的提问」；inbox 路径从不 claim/ACK，待答就是未 ACK
            val stateMatches =
                if (inboxMode) entry.deliverState != MailboxDeliveryState.ACKED
                else entry.deliverState == MailboxDeliveryState.ACKED
            if (!stateMatches) return@filter false
            val ask = entry.bodyText?.let { TeamAskAnswerRules.parseAsk(it) } ?: return@filter false
            agentMailboxStore.findByIdempotencyKey(TeamAskAnswerRules.answerIdempotencyKey(ask.correlationId)) == null
        }
        if (pending.isEmpty()) return
        val first = pending.minByOrNull { it.createdAt } ?: return
        val ask = first.bodyText?.let { TeamAskAnswerRules.parseAsk(it) } ?: return
        val member = first.sourceConversationId?.let { teamRepository.getMemberByConversation(it) } ?: return
        val text = "【仍有 ${pending.size} 个提问未回答】最近一条来自「${member.name}」，correlationId=${ask.correlationId}。\n" +
            "用 send_message 回复：target=${member.name}，text=<你的答复>，reply_to=${ask.correlationId}。"
        val reminderKey = "ask-remind:${first.id}:${now / ASK_REMINDER_INTERVAL_MILLIS}"
        if (useInbox(leadConversationId)) {
            // 新路径：提醒投成 NOTICE 事件，由模型真调 receive_events 消费；不合成 team_event
            enqueueNotice(leadConversationId, reminderKey, text, first.sourceConversationId)
            runtime.notifyInbox(leadConversationId, reminderKey)
            askReminderAt[leadConversationId] = now
            return
        }
        val delivered = runtime.completeDeferredTool(
            conversationId = leadConversationId,
            // 提醒不是回报：用 team_event 身份追加，避免顶替该成员还没回填的占位
            toolName = AgentTeamToolNames.TEAM_EVENT,
            handle = member.name,
            output = listOf(UIMessagePart.Text(text)),
            deliveryKey = reminderKey,
        )
        if (delivered) askReminderAt[leadConversationId] = now
    }

    /**
     * 成员 Run 已终态且该 Run 在领队邮箱里没有未读行时，把 still-ACCEPTED 的 operation 结算 FAILED。
     * 读不到邮箱时按「仍有未读」处理：宁可留着可见的 ACCEPTED，也不误判结果不可交付。
     */
    private suspend fun settleOrphanAcceptedOperations(runId: Uuid, memberConversationId: Uuid) {
        val key = OperationRuntime.deliveryKeyFor(runId)
        val accepted = runCatching { operationRuntime.listAcceptedByDelivery(key) }.getOrDefault(emptyList())
        if (accepted.isEmpty()) return
        val member = teamRepository.getMemberByConversation(memberConversationId) ?: return
        val team = teamRepository.getTeam(member.teamId) ?: return
        val stillUnread = runCatching {
            agentMailboxStore.listForTarget(team.leadConversationId)
                .any { it.runId == runId && it.deliverState != MailboxDeliveryState.ACKED }
        }.getOrDefault(true)
        if (stillUnread) return
        accepted.forEach { operation ->
            val reason = if (operation.deliveryState != null) "already_delivered" else "result_unavailable"
            runCatching { operationRuntime.settleFailed(operation, reason) }
                .onFailure { Log.w("AgentTeamService", "settle accepted ${operation.operationId} failed", it) }
        }
    }

    /** 等待/排队超时：等人答复、或重启后没人再派的 Run 都标失败并通知领队 */
    private suspend fun expireStuckRuns() {
        val now = Instant.now()
        val jobs = runtime.observeJobs().first()
        agentRunStore.listByState(AgentRunState.QUEUED).forEach { run ->
            if (Duration.between(run.updatedAt, now).toMillis() < WAITING_INPUT_TIMEOUT_MILLIS) return@forEach
            if (jobs[run.conversationId]?.isActive == true) return@forEach
            if (runtime.isQueuePaused(run.conversationId)) return@forEach
            val applied = agentRunStore.transition(
                runId = run.runId,
                expectedRevision = run.stateRevision,
                to = AgentRunState.FAILED,
                reason = "queued_timeout",
            )
            if (applied !is RunTransitionResult.Applied) return@forEach
            val member = teamRepository.getMemberByConversation(run.conversationId) ?: return@forEach
            val team = teamRepository.getTeam(member.teamId) ?: return@forEach
            enqueueNotice(
                team.leadConversationId,
                "notice:queued-timeout:${run.runId}",
                "【队友「${member.name}」的排队轮次已超时】\nRun 从未开始执行（可能是重启后队列丢失），已标记失败。用 send_message 重新派活即可。",
                member.conversationId,
            )
        }
        agentRunStore.listByState(AgentRunState.WAITING_OPERATION).forEach { run ->
            if (jobs[run.conversationId]?.isActive == true) return@forEach
            if (Duration.between(run.updatedAt, now).toMillis() < WAITING_INPUT_TIMEOUT_MILLIS) return@forEach
            // 只有「全部开放 operation 都能解析出投递键、且对应成员 Run 已终态」才算超时
            val allResolved = runCatching {
                val open = operationRuntime.listForRun(run.runId)
                    .filter { me.rerere.rikkahub.data.model.RuntimeOperationRules.isOpen(it.state) }
                open.isNotEmpty() && open.all { operation ->
                    val key = operation.deliveryState ?: return@all false
                    if (!key.startsWith(OperationRuntime.WAITING_PREFIX)) return@all false
                    val memberRunId = runCatching {
                        Uuid.parse(key.removePrefix(OperationRuntime.WAITING_PREFIX))
                    }.getOrNull() ?: return@all false
                    agentRunStore.get(memberRunId)?.isTerminal == true
                }
            }.getOrDefault(false)
            if (!allResolved) return@forEach
            val applied = agentRunStore.transition(
                runId = run.runId,
                expectedRevision = run.stateRevision,
                to = AgentRunState.PAUSED,
                reason = "waiting_operation_timeout",
            )
            if (applied is RunTransitionResult.Applied) {
                Log.w("AgentTeamService", "waiting operation timeout: lead run ${run.runId} -> PAUSED")
                enqueueNotice(
                    run.conversationId,
                    "notice:waiting-op-timeout:${run.runId}",
                    "【领队回合等待后台结果超时】\nRun 已标记暂停；未读事件仍在收件箱，可用 receive_events 消费后继续。",
                )
            }
        }
        agentRunStore.listByState(AgentRunState.WAITING_INPUT).forEach { run ->
            if (jobs[run.conversationId]?.isActive == true) return@forEach
            if (Duration.between(run.updatedAt, now).toMillis() < WAITING_INPUT_TIMEOUT_MILLIS) return@forEach
            val applied = agentRunStore.transition(
                runId = run.runId,
                expectedRevision = run.stateRevision,
                to = AgentRunState.FAILED,
                reason = "input_timeout",
            )
            if (applied !is RunTransitionResult.Applied) return@forEach
            val member = teamRepository.getMemberByConversation(run.conversationId) ?: return@forEach
            val team = teamRepository.getTeam(member.teamId) ?: return@forEach
            enqueueNotice(
                team.leadConversationId,
                "notice:input-timeout:${run.runId}",
                "【队友「${member.name}」等待答复超时】\n超过 10 分钟没有收到答复，本轮已标记失败。" +
                    "要继续就用 send_message 找同一个队友，重新说明条件。",
                member.conversationId,
            )
        }
    }

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

    /** 父 Run 归属：该会话当前仍在跑的 Run（终态不接，别再挂到已结束的轮次上） */
    private suspend fun liveRunIdOf(conversationId: Uuid): Uuid? =
        agentRunStore.latestForConversation(conversationId)?.takeIf { !it.isTerminal }?.runId

    private suspend fun createDispatchRun(
        member: TeamMember,
        text: String,
        taskId: Uuid? = null,
        requestKey: String? = null,
        previous: AgentRun? = null,
    ): Uuid {
        // 绑定任务时步数额度按 task 跨 attempt 共享，换 attempt 不能重置
        val taskBudget = taskId?.let { id ->
            val used = agentRunStore.listForTask(id).sumOf { it.stepsUsed }
            when (val plan = TeamAdmissionRules.planAttempt(used)) {
                is AttemptBudgetPlan.Exhausted -> throw AgentTeamException(
                    "task step budget exhausted (used ${plan.used} / ${plan.budget}); " +
                        "accept the current result or create a new task"
                )
                is AttemptBudgetPlan.Granted -> plan.stepLimit
            }
        }
        // 根账本：同一 rootTeamId 下所有层共用一份额度，耗尽就 fail-closed
        val team = teamRepository.getTeam(member.teamId) ?: throw AgentTeamException("team not found")
        val root = teamRepository.getTeam(team.rootTeamId) ?: team
        // 有效额度取「建队快照」与「设置当前值」的较大者：用满后调高设置即可恢复，不必重建团队
        val effectiveLimit = TeamBudgetRules.normalizeLimit(
            maxOf(root.stepBudget, settingsStore.awaitLoaded().agentTeamRootStepBudget)
        )
        val stepLimit = when (
            val plan = TeamBudgetRules.planRootBudget(
                limit = effectiveLimit,
                used = root.stepsUsed,
                taskRemaining = taskBudget,
            )
        ) {
            is RootBudgetPlan.Granted -> plan.stepLimit
            is RootBudgetPlan.Rejected -> throw AgentTeamException(
                "root team step budget exhausted (used ${plan.used} / ${plan.limit}); " +
                    "raise the root step budget in Settings > Agent Team"
            )
        }
        val inline = buildJsonObject { put("text", text) }.toString()
            .takeIf { it.length <= AgentRunRules.MAX_INPUT_JSON_CHARS }
        // 有 request_key 才做幂等（同一调用重试命中原 Run）；没有就用新键，避免同文误吞
        val dispatchKey = requestKey?.trim()?.takeIf { it.isNotEmpty() } ?: Uuid.random().toString()
        return when (
            val result = agentRunStore.create(
                NewAgentRun(
                    conversationId = member.conversationId,
                    teamId = member.teamId,
                    agentId = member.id,
                    taskId = taskId,
                    sourceKey = "team:${member.teamId}:dispatch:${member.conversationId}:$dispatchKey",
                    attemptNo = (previous?.attemptNo ?: 0) + 1,
                    previousRunId = previous?.runId,
                    inputRef = if (inline == null) "dispatch:$dispatchKey" else null,
                    inputJson = inline,
                    stepLimit = stepLimit,
                    // 派活方=该队 lead：它当前那一轮就是这次派活的父 Run（级联取消靠它）
                    parentRunId = liveRunIdOf(team.leadConversationId),
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
     * 结果到达时优先回填到领队那条 `expect_report` 调用：
     * 写回 output → 会话空闲就自动续跑；忙就留行，交给回合结束/启动补投。
     */
    private suspend fun wakeDeferredTool(event: RunLifecycleEvent) {
        val member = teamRepository.getMemberByConversation(event.conversationId) ?: return
        val team = teamRepository.getTeam(member.teamId) ?: return
        if (runtime.conversation(team.leadConversationId) == null) return
        deliveryMutex.withLock {
            // 先读不领取：只有真正写回成功才 ack，否则结果必须留在 mailbox
            val entry = agentMailboxStore.findUndeliveredWake(team.leadConversationId, member.conversationId)
                ?: return@withLock
            if (entry.deliverState == MailboxDeliveryState.ACKED) return@withLock
            // 新路径：有 Run 行的会话一律走 inbox，不写转录、不 claim/ACK，由模型真调 receive_events 消费
            if (useInbox(team.leadConversationId)) {
                runtime.notifyInbox(team.leadConversationId, entry.id.toString())
                return@withLock
            }
            // RESULT 用队友这一轮的原始输出；ASK/FAILURE/NOTICE 必须走渲染
            val text = when (entry.kind) {
                MailboxKind.ASK, MailboxKind.FAILURE, MailboxKind.NOTICE -> deliverableText(entry)
                else -> lastAssistantText(member.conversationId)?.takeIf { it.isNotBlank() }
                    ?: entry.bodyText?.takeIf { it.isNotBlank() }
                    ?: deliverableText(entry)
            }
            // 单次尝试：忙就不 ack，由 resumeUndelivered 在回合结束/启动/周期补偿时补投
            val delivered = runtime.completeDeferredTool(
                conversationId = team.leadConversationId,
                toolName = AgentTeamToolNames.EXPECT_REPORT,
                handle = member.name,
                output = listOf(UIMessagePart.Text(text)),
                deliveryKey = entry.id.toString(),
            )
            if (!delivered) {
                wakeLog("deliver deferred member=${member.name} run=${event.runId}")
                return@withLock
            }
            val claimed = when (val result = agentMailboxStore.claimOne(entry.id)) {
                is MailboxClaimResult.Claimed -> result.entry
                MailboxClaimResult.None -> {
                    wakeLog("claim skipped ${entry.id} state=${entry.deliverState}")
                    return@withLock
                }
            }
            ackMailboxQuietly(claimed)
        }
    }

    /** 事件类通知只落 mailbox，由回合结束的补投统一带给领队 */
    private suspend fun enqueueNotice(
        leadConversationId: Uuid,
        idempotencyKey: String,
        text: String,
        sourceConversationId: Uuid? = null,
    ) {
        runCatching {
            agentMailboxStore.enqueue(
                NewMailboxEntry(
                    targetConversationId = leadConversationId,
                    kind = MailboxKind.NOTICE,
                    idempotencyKey = idempotencyKey.take(180),
                    sourceConversationId = sourceConversationId,
                    bodyText = text,
                )
            )
        }.onFailure { Log.w("AgentTeamService", "enqueue notice failed: $idempotencyKey", it) }
    }

    private suspend fun lastAssistantText(conversationId: Uuid): String? =
        runtime.lastMessage(conversationId)
            ?.takeIf { it.role == MessageRole.ASSISTANT }
            ?.let { messageText(it) }

    private suspend fun consumeLeadMailbox(leadConversationId: Uuid) {
        // 彻底 tool 化：没有任何团队消息再推进对话；保留空实现给既有调用点
        if (DELIVERABLE_MAILBOX_KINDS.isEmpty()) return
        while (true) {
            val claimed = when (val result = agentMailboxStore.claimNextOfKinds(leadConversationId, DELIVERABLE_MAILBOX_KINDS)) {
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
                    "（这条结果是通过工具返回的；卡住时需要你给条件或换方式，继续用 send_message 找同一个队友。）"
                } else {
                    "（这条结果是通过工具返回的；要跟进就继续用 send_message 找同一个队友。）"
                }
                return MemberReportRules.renderForLead(name, report) + "\n\n" + hint
            }
        }
        if (entry.kind == MailboxKind.ASK) {
            val ask = text?.let { TeamAskAnswerRules.parseAsk(it) }
            if (ask == null) {
                // 解析不出就按原文投递，并明确标注载荷不可解析
                val fallback = text ?: "【提问正文缺失：ref=${entry.bodyRef ?: "-"}】"
                return fallback + "\n\n（提问载荷无法解析，按原文处理。）"
            }
            val name = entry.sourceConversationId
                ?.let { teamRepository.getMemberByConversation(it)?.name }
            return TeamAskAnswerRules.renderAskForLead(name, ask)
        }
        return text ?: "【团队通知缺少正文（ref=${entry.bodyRef ?: "-"}）】"
    }

    private suspend fun consumeLeadForMember(memberConversationId: Uuid) {
        // 成员自己带一队时要消费**它自己**的信箱，不能靠成员优先解析回父队（跨层抢投递）
        val team = teamRepository.ownedTeam(memberConversationId)
            ?: teamRepository.getTeamForConversation(memberConversationId)
            ?: return
        consumeLeadMailbox(team.leadConversationId)
    }

    /** 诊断：唤醒/确认路径写文件，便于无 logcat 时取证（只读排查用，不影响投递语义） */
    private fun wakeLog(line: String) {
        runCatching {
            File(context.filesDir, "team-wake.log").appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    private suspend fun ackMailboxQuietly(entry: AgentMailboxEntry) {
        // ack 冲突（版本被别处推进）会留下永久 CLAIMED 行：按最新快照重取版本再确认
        var snapshot = entry
        repeat(3) { attempt ->
            val outcome = runCatching {
                agentMailboxStore.ack(
                    id = snapshot.id,
                    claimToken = snapshot.claimToken.orEmpty(),
                    expectedRevision = snapshot.revision,
                )
            }
            val result = outcome.getOrNull()
            if (outcome.isSuccess && result is MailboxAckResult.Applied) return
            val latest = (result as? MailboxAckResult.Conflict)?.current
            wakeLog(
                "ack id=${snapshot.id} attempt=$attempt want rev=${snapshot.revision} " +
                    "state=${snapshot.deliverState} token=${snapshot.claimToken?.take(8)} -> " +
                    (result?.let { it::class.simpleName } ?: "ex:${outcome.exceptionOrNull()?.message}") +
                    " latest=" + (latest?.let { "${it.deliverState}/rev${it.revision}/tok${it.claimToken?.take(8)}" } ?: "-")
            )
            Log.w(
                "AgentTeamService",
                "ack mailbox ${snapshot.id} not applied (attempt=$attempt rev=${snapshot.revision} " +
                    "state=${snapshot.deliverState}): " +
                    (result?.let { it::class.simpleName } ?: outcome.exceptionOrNull()?.message.orEmpty()) +
                    " latest=" + (latest?.let { "${it.deliverState}/rev${it.revision}" } ?: "-"),
            )
            // 同一个 token 仍在 CLAIMED：只是版本被推进，用最新版本重试
            if (latest != null &&
                latest.deliverState == MailboxDeliveryState.CLAIMED &&
                latest.claimToken == snapshot.claimToken
            ) {
                snapshot = latest
            } else if (latest != null && latest.deliverState == MailboxDeliveryState.ACKED) {
                return
            }
            delay(200)
        }
    }

    /** 幽灵数据清理：超过 24h 仍未 ACK 的行（含卡在 CLAIMED 的）直接 ack 掉，避免升级后被翻出来重投 */
    /** 路由判据：该会话有过 Run 行即走新路径；没有 Run 的旧会话保持 legacy。 */
    private suspend fun useInbox(leadConversationId: Uuid): Boolean =
        agentRunStore.latestForConversation(leadConversationId) != null

    private suspend fun purgeStaleMailbox(leadConversationId: Uuid) {
        val cutOff = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - STALE_MAILBOX_MILLIS)
        val stale = runCatching { agentMailboxStore.listUndeliveredBefore(leadConversationId, cutOff) }
            .getOrDefault(emptyList())
        stale.forEach { entry ->
            // 有 operation 记账的事件属于消费协议，任何状态都不得被 24h 清扫静默 ACK
            val ownedByOperation = entry.runId?.let { memberRunId ->
                runCatching { operationRuntime.countByDelivery(OperationRuntime.deliveryKeyFor(memberRunId)) }
                    .getOrDefault(0) > 0
            } ?: false
            if (ownedByOperation) return@forEach
            val claimed = when (val result = agentMailboxStore.claimOne(entry.id)) {
                is MailboxClaimResult.Claimed -> result.entry
                MailboxClaimResult.None -> null
            }
            if (claimed != null) ackMailboxQuietly(claimed)
        }
        if (stale.isNotEmpty()) Log.i("AgentTeamService", "purged ${stale.size} stale mailbox rows for $leadConversationId")
    }

    private fun messageText(message: UIMessage): String? = fullTeamReplyText(
        message.parts.asSequence().filterIsInstance<UIMessagePart.Text>().map { it.text }
    )
}
