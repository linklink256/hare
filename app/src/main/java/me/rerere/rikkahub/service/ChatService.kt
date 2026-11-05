package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import me.rerere.ai.ui.limitContext
import androidx.annotation.StringRes
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.RetryAttempt
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.ChatTurnContext
import me.rerere.rikkahub.data.ai.ConversationMetaGenerator
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkahub.data.ai.sameContextExceptReasoningFinishTime
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.AskLeadRecord
import me.rerere.rikkahub.data.ai.tools.MemberReportSink
import me.rerere.rikkahub.data.ai.tools.TeamAskSink
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.tools.AgentTeamToolNames
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.model.AgentRunRules
import me.rerere.rikkahub.data.model.AgentRunState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.StepProgressRejection
import me.rerere.rikkahub.data.model.TeamAsk
import me.rerere.rikkahub.data.model.TeamAskAnswerRules
import me.rerere.rikkahub.data.model.TeamMember
import me.rerere.rikkahub.data.model.needsAutomaticConversationTitle
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.cancelInterruptedTools
import me.rerere.rikkahub.data.model.deleteMessage
import me.rerere.rikkahub.data.model.editMessage
import me.rerere.rikkahub.data.model.fillModelSnapshots
import me.rerere.rikkahub.data.model.fork
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.getStoredAssistantOf
import me.rerere.rikkahub.data.model.insertContextCheckpoint
import me.rerere.rikkahub.data.model.insertMessageBefore
import me.rerere.rikkahub.data.model.isAwaitingToolApproval
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceUserRegexes
import me.rerere.rikkahub.data.model.selectBranch
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withAssistantUpdate
import me.rerere.rikkahub.data.model.withNodeFavorite
import me.rerere.rikkahub.data.model.withToolApproval
import me.rerere.rikkahub.data.model.withTranslation
import me.rerere.rikkahub.data.model.withoutConversationFields
import me.rerere.rikkahub.data.model.withoutUnresumableToolCalls
import me.rerere.rikkahub.data.model.AgentMailboxEntry
import me.rerere.rikkahub.data.model.MailboxClaimResult
import me.rerere.rikkahub.data.model.MailboxEnqueueRejection
import me.rerere.rikkahub.data.model.MailboxKind
import me.rerere.rikkahub.data.model.NewMailboxEntry
import me.rerere.rikkahub.data.repository.AgentMailboxStore
import me.rerere.rikkahub.data.repository.AgentRunStore
import me.rerere.rikkahub.data.repository.BindReplyResult
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CreateRunResult
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.NewAgentRun
import me.rerere.rikkahub.data.repository.RecordStepsResult
import me.rerere.rikkahub.data.repository.RunTransitionAndEnqueueResult
import me.rerere.rikkahub.data.repository.RunTransitionResult
import me.rerere.rikkahub.data.repository.OperationStore
import me.rerere.rikkahub.data.repository.ToolOperationStore
import me.rerere.rikkahub.runtime.Admission
import me.rerere.rikkahub.runtime.AdmitKind
import me.rerere.rikkahub.runtime.AdmitRequest
import me.rerere.rikkahub.runtime.FenceToken
import me.rerere.rikkahub.runtime.OperationAccept
import me.rerere.rikkahub.runtime.OperationInbox
import me.rerere.rikkahub.runtime.OperationRecorder
import me.rerere.rikkahub.runtime.OperationRuntime
import me.rerere.rikkahub.runtime.acceptedReceiptOf
import me.rerere.rikkahub.runtime.RunCoordinator
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** 自动续跑的合并窗口：窗口内到达的多条队友结果只开一轮 */
private const val AUTO_RESUME_COALESCE_MILLIS = 2_000L

/** 连续自动续跑的硬上限，超过就停下等用户开口，避免两个 agent 互相喂消息空转 */
private const val MAX_AUTO_RESUME_STREAK = 6

/** 父 Run 被取消/失败时，后代的取消原因（契约固定字面量） */
private const val PARENT_CANCELLED_REASON = "parent_cancelled"

/**
 * 一个 Run 进入新阶段时的对外事件；relay 只认 runId，不从会话最新消息猜是哪一轮。
 *
 * [message] 只在 COMPLETED 时携带：最终 flush 之后、PostTurn 之前捕获的该 Run 输出快照。
 * 后续 attempt 可能复用同一 UIMessage.id 并追加内容，事件是非持久通知，重启不补投成功。
 */
data class RunLifecycleEvent(
    val runId: Uuid,
    val conversationId: Uuid,
    val state: AgentRunState,
    val message: UIMessage? = null,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val requestFactory: ChatRequestFactory,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val translationHandler: TranslationHandler,
    private val metaGenerator: ConversationMetaGenerator,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val folderRepository: FolderRepository,
    private val agentTeamRepository: me.rerere.rikkahub.data.repository.AgentTeamRepository,
    private val teamExecutionGate: TeamExecutionGate,
    private val runCoordinator: RunCoordinator,
    private val agentRunStore: AgentRunStore,
    private val agentMailboxStore: AgentMailboxStore,
    private val toolOperationStore: ToolOperationStore,
    private val operationStore: OperationStore,
    private val operationRecorder: OperationRecorder,
    private val operationRuntime: OperationRuntime,
    private val operationInbox: OperationInbox,
) {
    // 删除队友会话时暂存名册行，撤销删除时放回去（正常撤销后立即移除）
    private val deletedTeamMembers: MutableMap<Uuid, TeamMember> = java.util.concurrent.ConcurrentHashMap()

    /** 一轮生成的结果 */
    private sealed interface TurnResult {
        data class Finished(val reason: AgentFinishReason) : TurnResult

        /** 没能完成：错误已经报告给用户，消息队列也已暂停 */
        data object Failed : TurnResult
    }

    private val conversationStore = object : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation {
            // 会话落库即视为开始，此时把助手的配置固定到会话上
            val settings = settingsStore.awaitLoaded()
            return { it.bindConfig(settings).fillModelSnapshots(settings) }
        }

        override suspend fun write(previous: Conversation?, current: Conversation) {
            if (previous == null && !conversationRepo.existsConversationById(current.id)) {
                conversationRepo.insertConversation(current)
            } else {
                conversationRepo.updateConversation(current, previous)
            }
            if (previous != null && previous.messageNodes !== current.messageNodes) {
                // 按 messages === 逐节点比对：变更节点的"曾引用附件"经 DB instr 兜底校验后回收；
                // 未变行（含未物化压缩行）不扫附件，避免每次落库全量物化
                val prevById = previous.messageNodes.associateBy { it.id }
                val changedPrev = previous.messageNodes.filter { prev ->
                    val next = current.messageNodes.firstOrNull { it.id == prev.id }
                    next == null || next.messages !== prev.messages
                }
                if (changedPrev.isNotEmpty()) {
                    withContext(Dispatchers.IO) { deleteUnreferencedFiles(changedPrev, current.id) }
                }
            }
        }
    }

    /** 同一会话的迟到工具回填串行化：并发的两个结果不能同时判断"空闲"然后只起一个续跑 */
    private val deferredToolLocks = java.util.concurrent.ConcurrentHashMap<Uuid, Mutex>()

    /** 待触发的自动续跑（按会话去抖） */
    private val autoResumeJobs = java.util.concurrent.ConcurrentHashMap<Uuid, Job>()

    /** 连续自动续跑次数，用户自己发消息时清零 */
    private val autoResumeStreak = java.util.concurrent.ConcurrentHashMap<Uuid, Int>()

    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        store = conversationStore,
        createInitialConversation = { id ->
            Conversation.ofId(id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
        },
        onGenerationFinished = ::onSessionGenerationFinished,
        onSessionRemoved = memoryConsolidationScheduler::onConversationLeft,
    )

    // 生成中的增量落库：流式热路径只追加新写出的文本，进程被杀也不丢整轮输出
    private val streamChunks = StreamChunkStore(conversationRepo)

    // 本轮成员结构化汇报（conversationId -> payload JSON）；turn 开始时清空，终态投递时消费
    private val memberReports = java.util.concurrent.ConcurrentHashMap<Uuid, String>()

    // 启动恢复屏障：本进程第一次 RUNNING 迁移前必须完成（声明在 init 之前，由 init 完成它）
    private val runRecoveryDone = CompletableDeferred<Unit>()

    private val _runEvents = MutableSharedFlow<RunLifecycleEvent>(extraBufferCapacity = 64)
    val runEvents: SharedFlow<RunLifecycleEvent> = _runEvents.asSharedFlow()

    init {
        // report_back 的落点：校验成员身份与非终态 Run 后暂存本轮汇报
        MemberReportSink.recorder = { conversationId, payload -> recordMemberReport(conversationId, payload) }
        // ask_lead 的落点：成员提问行进领队收件箱，Run 在同一事务里转入等待答复
        TeamAskSink.asker = { conversationId, question -> recordTeamAsk(conversationId, question) }
        // 让 gate 能在一轮真正结束时采样一次审批状态（只读、不建会话），
        // 这样队友会话被空闲回收后分组仍稳定；任务结束时无 UI 观察也照常记录。
        teamExecutionGate.approvalSampler = { conversationId -> peekAwaitingApproval(conversationId) }
        // Run 恢复屏障：先把上次进程残留的 RUNNING 保守标为 INTERRUPTED，
        // 本进程第一次 RUNNING 迁移前 await 它，避免扫描把刚开始的 Run 误判中断。
        appScope.launch(Dispatchers.IO) {
            // 恢复结果必须如实传到 await 方：成功才算屏障通过，失败要能被诊断
            try {
                agentRunStore.recoverInterrupted()
                // 残留 INTENT 说明工具可能已执行但结果没落库：标 UNCERTAIN，只作「必须停」的证据
                runCatching { toolOperationStore.markInterruptedAsUncertain() }
                    .onSuccess { if (it > 0) Log.w(TAG, "marked $it leftover tool operations as UNCERTAIN") }
                    .onFailure { Log.w(TAG, "mark leftover tool operations failed", it) }
                // 跨进程残留的 operation 只降级为 UNKNOWN；失败不得让恢复屏障整体失败
                runCatching { operationStore.markInterruptedAsUnknown() }
                    .onSuccess { if (it > 0) Log.w(TAG, "marked $it leftover runtime operations as UNKNOWN") }
                    .onFailure { Log.w(TAG, "mark leftover runtime operations failed", it) }
                runRecoveryDone.complete(Unit)
            } catch (e: CancellationException) {
                runRecoveryDone.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "run recovery failed", e)
                runRecoveryDone.completeExceptionally(e)
            }
        }
        // 上次进程被杀留下的增量片段：启动时拼回消息再清掉
        appScope.launch(Dispatchers.IO) {
            runCatching { streamChunks.recoverPending() }
                .onFailure { Log.w(TAG, "stream chunk recovery failed", it) }
        }
    }

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            (it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)).takeLast(32)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching { sessionManager.cleanup() }

    suspend fun trimIdleSessions() = sessionManager.trimIdleSessions()

    private fun onSessionGenerationFinished(session: ConversationSession, cause: Throwable?) {
        // 记进队列的那条本来就会在轮次结束时落到会话里，这里只清掉没赶上插话的残留
        // 未 ack 的 STEER 行留给 TTL 后重领，不在这里补发
        pendingSteerAcks.remove(session.id)
        // 取消只来自停止（已自行暂停）或删除，这里再暂停会盖掉停止后用户发送时恢复的队列
        if (cause != null && cause !is CancellationException) session.messageQueue.pause()
        if (session.state.value.isAwaitingToolApproval) {
            session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
        }
        appScope.launch { dispatchNextQueuedMessage(session.id) }
    }

    // 保留 UI/Web 的入口，生命周期和状态查询统一交给 SessionManager。
    fun addConversationReference(conversationId: Uuid) {
        sessionManager.acquire(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionManager.release(conversationId)
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    /** 会话的最新内容：已加载的以内存为准，否则读数据库 */
    suspend fun getConversation(conversationId: Uuid): Conversation? =
        sessionManager.get(conversationId)?.takeIf { it.isInitialized }?.state?.value
            ?: conversationRepo.getConversationById(conversationId)

    suspend fun getLastMessage(conversationId: Uuid): UIMessage? =
        sessionManager.get(conversationId)?.takeIf { it.isInitialized }?.state?.value
            ?.currentMessages?.lastOrNull() ?: conversationRepo.getLastMessage(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessionManager.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        sessionManager.getProcessingStatusFlow(conversationId)

    fun getReplyMessageIdFlow(conversationId: Uuid): StateFlow<Uuid?> =
        sessionManager.getReplyMessageIdFlow(conversationId)

    fun getGenerationStartedAtFlow(conversationId: Uuid): StateFlow<Long?> =
        sessionManager.getGenerationStartedAtFlow(conversationId)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessionManager.getConversationJobs()

    /**
     * 只读试探：已缓存且已加载的会话当前是否有工具调用在等审批。
     * null 表示会话未缓存/未加载（未知）——绝不为查询而创建会话或加载历史。
     */
    fun peekAwaitingApproval(conversationId: Uuid): Boolean? =
        sessionManager.get(conversationId)
            ?.takeIf { it.isInitialized }
            ?.state?.value?.isAwaitingToolApproval

    // ---- Run 生命周期（只跟踪团队成员的 Run；普通/领队会话保持原样） ----

    private suspend fun awaitRunRecovery() {
        // 失败会在这里抛出；不能用 isCompleted 绕过异常
        runRecoveryDone.await()
    }

    /** 供团队运行时等待启动恢复屏障（mailbox 消费在屏障之后才开始） */
    suspend fun awaitRunRecoveryReady() = awaitRunRecovery()

    private suspend fun emitRunEvent(run: AgentRun?, snapshot: UIMessage? = null) {
        if (run == null) return
        _runEvents.tryEmit(RunLifecycleEvent(run.runId, run.conversationId, run.state, snapshot))
    }

    /** 有限次 CAS 迁移；终态/非法迁移返回 null，不覆盖别人先写的状态 */
    private suspend fun transitionRun(
        runId: Uuid,
        to: AgentRunState,
        reason: String? = null,
    ): AgentRun? {
        repeat(3) {
            val current = agentRunStore.get(runId) ?: return null
            when (val result = agentRunStore.transition(runId, current.stateRevision, to, reason)) {
                is RunTransitionResult.Applied -> return result.run
                is RunTransitionResult.Conflict -> Unit
                is RunTransitionResult.IllegalTransition -> {
                    Log.w(TAG, "run $runId transition ${result.from}->${result.to} rejected")
                    // 等待答复的轮次结束时不该判成完成：Run 留在 WAITING_INPUT，等 resume
                    if (result.from == AgentRunState.WAITING_INPUT && result.to == AgentRunState.COMPLETED) {
                        Log.i(TAG, "run $runId ended the turn while waiting for an answer; keeping WAITING_INPUT")
                    }
                    return null
                }

                RunTransitionResult.NotFound -> return null
            }
        }
        return null
    }

    /** 精确绑定本轮回复节点；已绑同一 id 幂等，终态拒绝；fence 失效则跳过 */
    private suspend fun bindRunReply(runId: Uuid?, replyMessageId: Uuid?, fence: FenceToken? = null) {
        if (runId == null || replyMessageId == null) return
        // fence 只收紧本处写入：失效 lease 的迟到绑定直接跳过
        if (fence != null && !runCoordinator.isCurrent(fence)) return
        repeat(3) {
            val current = agentRunStore.get(runId) ?: return
            when (val result = agentRunStore.bindReply(runId, current.stateRevision, replyMessageId.toString())) {
                is BindReplyResult.Applied, is BindReplyResult.AlreadyBound -> return
                is BindReplyResult.Conflict -> Unit
                is BindReplyResult.Rejected -> {
                    Log.w(TAG, "bind reply rejected for $runId: ${result.reason}")
                    return
                }

                BindReplyResult.NotFound -> return
            }
        }
    }

    /**
     * 记累计步数：必须显式成功或确认幂等（已记录更大值）。
     * 冲突有限重试；缺行/非法拒绝/DB 失败向上抛，阻止继续烧模型并留下可诊断失败。
     */
    private suspend fun recordRunSteps(
        runId: Uuid?,
        baseline: RunStepBaseline,
        messageStepCount: Int,
        fence: FenceToken? = null,
    ) {
        if (runId == null) return
        // fence 只收紧本处写入：失效 lease 的迟到计步直接跳过
        if (fence != null && !runCoordinator.isCurrent(fence)) return
        val used = AgentRunLifecycle.stepsAfterStep(baseline, messageStepCount)
        repeat(3) {
            val current = agentRunStore.get(runId)
                ?: error("run $runId missing while recording steps")
            // 终态已有完成计步：不再反写
            if (current.isTerminal) return
            when (val result = agentRunStore.recordSteps(runId, current.stateRevision, used)) {
                is RecordStepsResult.Applied -> {
                    // 根账本按本次增量累加：同一 Run 的重复记录不会重复计费
                    addRootStepsQuietly(current.teamId, result.run.stepsUsed - current.stepsUsed)
                    return
                }
                is RecordStepsResult.Conflict -> Unit
                is RecordStepsResult.Rejected -> {
                    // DECREASING 表示库中已是更大的累计值：按幂等成功返回
                    if (result.reason == StepProgressRejection.DECREASING) return
                    error("run $runId record steps rejected: ${result.reason}")
                }

                RecordStepsResult.NotFound -> error("run $runId not found while recording steps")
            }
        }
        error("run $runId record steps conflicted after retries")
    }

    /** 步数落库成功后把增量记到根团队；记账失败只记日志，不回滚已落库的 Run */
    private suspend fun addRootStepsQuietly(teamId: Uuid?, delta: Int) {
        if (teamId == null || delta <= 0) return
        runCatching {
            val rootTeamId = agentTeamRepository.getTeam(teamId)?.rootTeamId ?: return@runCatching
            agentTeamRepository.addRootSteps(rootTeamId, delta)
        }.onFailure { Log.w(TAG, "add root steps failed for team $teamId (+$delta)", it) }
    }

    /** 最终转录已 flush 后提交本轮迁移；只映射 finish reason，不改 PostTurn 判断 */
    private suspend fun commitRunAfterTurn(
        runId: Uuid?,
        reason: AgentFinishReason,
        snapshot: UIMessage? = null,
    ) {
        if (runId == null) return
        // 本回合结束时仍有已受理的后台 operation：领队进入 WAITING_OPERATION，等消费侧唤醒
        val hasOpenOperations = runCatching {
            operationStore.listForRun(runId).any { me.rerere.rikkahub.data.model.RuntimeOperationRules.isOpen(it.state) }
        }.getOrDefault(false)
        val to = AgentRunLifecycle.stateFor(reason, hasOpenOperations)
        val body = snapshot?.toText()
        transitionRunWithResultNotice(runId, to, AgentRunLifecycle.reasonFor(reason), body, snapshot?.id)
            ?.let { emitRunEvent(it, snapshot.takeIf { _ -> reason is AgentFinishReason.Completed }) }
    }

    /**
     * 团队成员会话的终态迁移：Run 迁移与结果通知行在同一事务里落库。
     * 普通/领队会话或非终态仍走原来的 transitionRun。
     */
    private suspend fun transitionRunWithResultNotice(
        runId: Uuid,
        to: AgentRunState,
        reason: String?,
        bodyText: String?,
        replyMessageId: Uuid?,
    ): AgentRun? {
        val entry = buildResultMailboxEntry(runId, to, bodyText, replyMessageId)
            ?: return transitionRun(runId, to, reason).also { if (it != null) cancelDescendantsIfTerminal(runId, to) }
        val applied = when (
            val result = agentRunStore.transitionAndEnqueue(
                runId = runId,
                expectedRevision = agentRunStore.get(runId)?.stateRevision
                    ?: return null,
                to = to,
                reason = reason,
                now = Instant.now(),
                entry = entry,
            )
        ) {
            is RunTransitionAndEnqueueResult.Applied -> result.run
            // 迁移被拒：与普通 transition 同样的处理，不写通知
            is RunTransitionAndEnqueueResult.Rejected -> null
            is RunTransitionAndEnqueueResult.MailboxRejected -> when (result.reason) {
                // 容量被拒：Run 终态必须落库，通知丢弃但要留下可诊断错误
                MailboxEnqueueRejection.CAPACITY_EXCEEDED -> {
                    Log.e(TAG, "result notice rejected (capacity), run=$runId state=$to; committing state only")
                    transitionRun(runId, to, reason)
                }
                // 同 runId 已登记过通知：不再写新行，但终态仍要落库
                MailboxEnqueueRejection.DUPLICATE -> {
                    Log.i(TAG, "result notice already enqueued for run=$runId; committing state only")
                    transitionRun(runId, to, reason)
                }
            }
        }
        // 迁移真的落库了才级联：被拒/冲突时后代照旧
        if (applied != null) cancelDescendantsIfTerminal(runId, to)
        return applied
    }

    /** 取消/失败落库后，沿 parentRunId 链路把后代非终态 Run 一起收尾 */
    private suspend fun cancelDescendantsIfTerminal(runId: Uuid, to: AgentRunState) {
        if (to != AgentRunState.CANCELLED && to != AgentRunState.FAILED) return
        runCatching {
            for (child in agentRunStore.listDescendantRuns(runId)) {
                // 终态判定复用 Run 自己的规则，不在这里硬编码状态集合
                if (AgentRunRules.isTerminal(child.state)) continue
                when (
                    val result = agentRunStore.transition(
                        child.runId,
                        child.stateRevision,
                        AgentRunState.CANCELLED,
                        PARENT_CANCELLED_REASON,
                    )
                ) {
                    is RunTransitionResult.Applied -> emitRunEvent(result.run)
                    // 别人刚改了它的状态：它自己的收尾路径会处理，不覆盖
                    is RunTransitionResult.Conflict -> Log.i(TAG, "descendant run ${child.runId} changed; keep as is")
                    else -> Unit
                }
            }
        }.onFailure { Log.w(TAG, "cancel descendants of run $runId failed", it) }
    }

    /** 只有团队成员会话的终态才需要给领队投递；返回 null 表示走普通迁移 */
    private suspend fun buildResultMailboxEntry(
        runId: Uuid,
        to: AgentRunState,
        bodyText: String?,
        replyMessageId: Uuid?,
    ): NewMailboxEntry? {
        val kind = AgentRunLifecycle.mailboxKindFor(to) ?: return null
        val run = agentRunStore.get(runId) ?: return null
        val member = agentTeamRepository.getMemberByConversation(run.conversationId) ?: return null
        val team = agentTeamRepository.getTeam(member.teamId) ?: return null
        // 只有 RESULT 才投结构化 payload；FAILURE/NOTICE 保持原文本摘要，不消费、不写 JSON
        val report = if (kind == MailboxKind.RESULT) takeMemberReport(run.conversationId) else null
        val text = report ?: bodyText?.takeIf { it.isNotBlank() }
        val inline = text?.takeIf { AgentRunLifecycle.bodyFitsInline(it) }
        return NewMailboxEntry(
            targetConversationId = team.leadConversationId,
            kind = kind,
            idempotencyKey = AgentRunLifecycle.resultIdempotencyKey(runId),
            teamId = member.teamId,
            sourceConversationId = run.conversationId,
            runId = runId,
            bodyText = inline,
            // 超限不截断：改引用
            bodyRef = when {
                inline != null -> null
                replyMessageId != null -> "message:$replyMessageId"
                else -> "run:$runId"
            },
        )
    }

    /** 异常/取消路径的有限提交：不覆盖已是终态的历史 */
    private suspend fun finishRunQuietly(
        runId: Uuid?,
        to: AgentRunState,
        reason: String,
        detail: String? = null,
    ) {
        if (runId == null) return
        withContext(NonCancellable) {
            runCatching {
                transitionRunWithResultNotice(runId, to, reason, detail, null)?.let { emitRunEvent(it) }
            }.onFailure { Log.w(TAG, "finish run failed", it) }
        }
    }

    /**
     * 领队会话的回合 Run：只在该会话确实拥有一支队时创建，用于步数/预算/终态。
     * 普通聊天没有队，返回 null，行为保持不变。
     */
    private suspend fun createLeadRunIfTeam(conversationId: Uuid): Uuid? {
        val team = agentTeamRepository.getTeamByLeadConversation(conversationId) ?: return null
        // 上一次领队回合在等后台结果：复用它，而不是每轮新建一个 Run（adopt）
        val latest = agentRunStore.latestForConversation(conversationId)
        if (latest != null && latest.state == me.rerere.rikkahub.data.model.AgentRunState.WAITING_OPERATION) {
            return latest.runId
        }
        return when (
            val created = agentRunStore.create(
                NewAgentRun(
                    conversationId = conversationId,
                    teamId = team.id,
                    agentId = null,
                    sourceKey = "lead:$conversationId:${Uuid.random()}",
                    attemptNo = 1,
                    // null = 沿用助手自己的 maxSteps，不用 Run 的 256 覆盖普通对话配置
                    stepLimit = null,
                )
            )
        ) {
            is CreateRunResult.Created -> created.run.runId
            is CreateRunResult.Duplicate -> created.existing.runId
            is CreateRunResult.Rejected -> {
                Log.w(TAG, "lead run rejected: ${created.reason}")
                null
            }
        }
    }

    /** 成员直发/重生成时补建新 attempt；非成员返回 null（普通会话不跟踪） */
    private suspend fun createMemberRun(
        conversationId: Uuid,
        text: String?,
        previousRunId: Uuid? = null,
        attemptNo: Int = 1,
        sourceKey: String? = null,
    ): AgentRun? {
        val member = agentTeamRepository.getMemberByConversation(conversationId) ?: return null
        awaitRunRecovery()
        val inline = text?.takeIf { it.isNotBlank() }?.let { raw ->
            buildJsonObject { put("text", raw) }.toString()
                .takeIf { it.length <= AgentRunRules.MAX_INPUT_JSON_CHARS }
        }
        // 父 Run = 该成员所属队的 lead 会话当前仍在跑的 Run；与派活路径同一口径（终态不接）
        val parentRunId = agentTeamRepository.getTeam(member.teamId)
            ?.leadConversationId
            ?.takeIf { it != conversationId }
            ?.let { lead -> agentRunStore.latestForConversation(lead)?.takeIf { !it.isTerminal }?.runId }
        return when (
            val result = agentRunStore.create(
                NewAgentRun(
                    conversationId = conversationId,
                    teamId = member.teamId,
                    agentId = member.id,
                    parentRunId = parentRunId,
                    previousRunId = previousRunId,
                    sourceKey = sourceKey,
                    attemptNo = attemptNo,
                    // 超长输入不截断也不拒绝：改用引用；R1 不保证 queued 输入可恢复
                    inputRef = if (inline == null) "dispatch:$conversationId" else null,
                    inputJson = inline,
                )
            )
        ) {
            is CreateRunResult.Created -> result.run.also { emitRunEvent(it) }
            is CreateRunResult.Duplicate -> result.existing
            // 已知成员却建不了 Run：不能静默退回 untracked 继续执行
            is CreateRunResult.Rejected -> error("run create rejected for $conversationId: ${result.reason}")
        }
    }

    /** report_back 落点：只有团队成员且存在非终态 Run 才记录 */
    private suspend fun recordMemberReport(conversationId: Uuid, payload: String): String? {
        val member = agentTeamRepository.getMemberByConversation(conversationId)
            ?: return "report_back is only available to teammates"
        val run = agentRunStore.latestForConversation(conversationId)
        if (run == null || run.isTerminal) {
            return "no active run for teammate '${member.name}'; start one before reporting"
        }
        memberReports[conversationId] = payload
        return null
    }

    /** 取走本轮汇报（终态投递时消费一次） */
    private fun takeMemberReport(conversationId: Uuid): String? = memberReports.remove(conversationId)

    /**
     * ask_lead 落点：只有正在跑一轮的成员能提问。
     *
     * 提问行与 Run→WAITING_INPUT 在同一事务里落库；失败时不改 Run 状态，异常向上不吞。
     */
    private suspend fun recordTeamAsk(conversationId: Uuid, question: String): AskLeadRecord {
        val member = agentTeamRepository.getMemberByConversation(conversationId)
            ?: return AskLeadRecord(null, "ask_lead is only available to teammates")
        val team = agentTeamRepository.getTeam(member.teamId)
            ?: return AskLeadRecord(null, "team not found; ask_lead unavailable")
        val run = agentRunStore.latestForConversation(conversationId)
        if (run == null || run.state != AgentRunState.RUNNING) {
            return AskLeadRecord(null, "ask_lead requires a running run (state=${run?.state ?: "none"})")
        }
        val correlationId = TeamAskAnswerRules.newCorrelationId()
        TeamAskAnswerRules.validateAsk(correlationId, question)?.let { return AskLeadRecord(null, it) }
        val entry = NewMailboxEntry(
            targetConversationId = team.leadConversationId,
            kind = MailboxKind.ASK,
            idempotencyKey = TeamAskAnswerRules.askIdempotencyKey(correlationId),
            teamId = member.teamId,
            sourceConversationId = conversationId,
            runId = run.runId,
            bodyText = TeamAskAnswerRules.encodeAsk(TeamAsk(correlationId, question.trim(), member.name)),
            bodyRef = null,
        )
        return when (
            val result = agentRunStore.transitionAndEnqueue(
                runId = run.runId,
                expectedRevision = run.stateRevision,
                to = AgentRunState.WAITING_INPUT,
                reason = "awaiting_input",
                now = Instant.now(),
                entry = entry,
            )
        ) {
            is RunTransitionAndEnqueueResult.Applied -> {
                // 提问也是一次可观测的状态迁移：领队的 await_teammate 靠它被唤醒拿问题
                emitRunEvent(result.run)
                AskLeadRecord(correlationId, null)
            }
            is RunTransitionAndEnqueueResult.Rejected ->
                AskLeadRecord(null, "run could not be moved to waiting_input; question not sent")

            is RunTransitionAndEnqueueResult.MailboxRejected ->
                AskLeadRecord(null, "question not queued (${result.reason.name.lowercase()}); run unchanged")
        }
    }

    /**
     * 成员轮开始前把领队答复注入转录，每条只注入一次。
     * FIFO：遇到非 ANSWER 行就停下，不跳过队首的其它类别；DB 失败只记日志、不 ack。
     */
    private suspend fun drainTeamAnswers(session: ConversationSession) {
        if (agentTeamRepository.getMemberByConversation(session.id) == null) return
        while (true) {
            try {
                // 只领 ANSWER：按任意类别领取会把队首的 RESULT 抢走并留在 CLAIMED，结果永远送不到
                val entry = when (
                    val claimed = agentMailboxStore.claimNextOfKinds(
                        session.id,
                        setOf(MailboxKind.ANSWER),
                    )
                ) {
                    is MailboxClaimResult.Claimed -> claimed.entry
                    MailboxClaimResult.None -> return
                }
                if (entry.kind != MailboxKind.ANSWER) return
                // 重投去重：转录里已有同 id 就不再追加，直接确认
                if (session.state.value.currentMessages.any { it.id == entry.id }) {
                    ackTeamAnswerQuietly(entry)
                    continue
                }
                val parsed = entry.bodyText?.let { TeamAskAnswerRules.parseAnswer(it) }
                val text = if (parsed != null) {
                    TeamAskAnswerRules.renderAnswerForMember(parsed)
                } else {
                    entry.bodyText?.takeIf { it.isNotBlank() }
                        ?: "【领队答复正文缺失：${entry.bodyRef ?: "-"}】"
                }
                session.update { conversation ->
                    conversation.copy(
                        messageNodes = conversation.messageNodes + UIMessage(
                            id = entry.id,
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text(text)),
                        ).toMessageNode()
                    )
                }
                session.flush()
                ackTeamAnswerQuietly(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "drain team answers failed", e)
                return
            }
        }
    }

    /** ack 失败只记日志：下次租约过期重领会因转录里同 id 去重 */
    private suspend fun ackTeamAnswerQuietly(entry: AgentMailboxEntry) {
        runCatching {
            agentMailboxStore.ack(entry.id, entry.claimToken.orEmpty(), entry.revision)
        }.onFailure { Log.w(TAG, "ack team answer ${entry.id} failed", it) }
    }

    /** 按精确消息 id 取当前输出：先内存态，再库；不为查询建会话 */
    suspend fun getMessage(conversationId: Uuid, messageId: Uuid): UIMessage? {
        sessionManager.get(conversationId)?.takeIf { it.isInitialized }?.state?.value
            ?.currentMessages?.firstOrNull { it.id == messageId }?.let { return it }
        return conversationRepo.getConversationById(conversationId)?.currentMessages
            ?.firstOrNull { it.id == messageId }
    }

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) { block() }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        withLoadedSession(conversationId) { session ->
            settingsStore.selectAssistant(session.state.value.assistantId)
        }
    }

    /** 会话是否已经读过库；UI 据此区分占位 Conversation 与真正的空会话。 */
    fun isConversationInitialized(conversationId: Uuid): Boolean =
        sessionManager.get(conversationId)?.isInitialized == true

    private suspend fun ensureInitialized(session: ConversationSession) {
        session.initialize(
            load = { loadConversation(session.id) },
            create = { newConversation(session.id) },
        )
    }

    /** 持有会话引用并确保已加载后执行 [block]；[createIfMissing] 为 false 时数据库里没有就抛 NotFoundException */
    private suspend fun <T> withLoadedSession(
        conversationId: Uuid,
        createIfMissing: Boolean = true,
        block: suspend (ConversationSession) -> T,
    ): T = sessionManager.withSession(conversationId) { session ->
        session.initialize(
            load = { loadConversation(conversationId) },
            create = {
                if (!createIfMissing) throw NotFoundException("Conversation not found")
                newConversation(conversationId)
            },
        )
        block(session)
    }

    // 新建对话, 并添加预设消息
    private suspend fun newConversation(conversationId: Uuid): Conversation {
        val assistant = settingsStore.awaitLoaded().getCurrentAssistant()
        return Conversation.ofId(
            id = conversationId,
            assistantId = assistant.id,
            newConversation = true
        ).updateCurrentMessages(assistant.presetMessages)
    }

    // 引入会话配置之前创建的会话没有固定配置，加载时按助手当前的值补上，之后不再随助手变化。
    private suspend fun loadConversation(conversationId: Uuid): Conversation? {
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        // settingsFlow 在启动初期还是占位值，固定配置必须基于真实设置
        val bound = conversation.bindConfig(settingsStore.awaitLoaded())
        if (bound !== conversation) conversationRepo.updateConversationConfig(bound)
        return bound
    }

    /**
     * 加载会话后在最新状态上修改并落库。
     * 删除消息或切换分支也可能解除工具审批阻塞，落库后重新检查队列。
     */
    private suspend fun mutate(conversationId: Uuid, transform: (Conversation) -> Conversation): Conversation =
        withLoadedSession(conversationId) { session ->
            session.update(transform)
            session.flush().also { dispatchNextQueuedMessage(conversationId) }
        }

    // 只删变更节点曾引用、落库后不再被任何会话或队列引用的文件；宁可漏删也不误删
    private suspend fun deleteUnreferencedFiles(
        changedPrevious: List<me.rerere.rikkahub.data.model.MessageNode>,
        conversationId: Uuid,
    ) {
        val session = sessionManager.get(conversationId)
        // 队列与提交中附件只算精确 URL 引用，编码后比对保持与 hasFileReference 一致
        val inMemoryUrls = (session?.messageQueue?.state?.value?.messages.orEmpty() +
            listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls()
        val candidateUrls = changedPrevious
            .flatMap { node -> node.messages.flatMap { it.parts }.localFileUrls() }
            .toSet()
        // DB 此刻已落新状态；仍被任何行 JSON 引用（其他分支/未变节点）的 URL 保留
        val deleted = candidateUrls.filterNot {
            it in inMemoryUrls || conversationRepo.hasFileReference(it)
        }
        if (deleted.isNotEmpty()) {
            filesManager.deleteChatFiles(deleted.map { it.toUri() })
            Log.w(TAG, "deleteUnreferencedFiles: $deleted")
        }
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        sessionManager.getMessageQueueFlow(conversationId)

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessionManager.get(conversationId)?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessionManager.get(conversationId)?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessionManager.snapshot()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        runId: Uuid? = null,
        mailboxId: Uuid? = null,
    ) {
        if (content.isEmptyInputMessage()) return
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            // 派活方先持久 create 再入队；这里只承载，不生成第二个 runId
            session.messageQueue.enqueue(content, answer, runId = runId, mailboxId = mailboxId)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /**
     * 用户输入：正在生成就把这句话插进当前轮（steer），插不了再走普通入队。
     * 返回 true 表示已插话、没有进队列。
     */
    suspend fun sendUserMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        allowSteer: Boolean = true,
    ): Boolean {
        if (answer && allowSteer && steerGeneration(conversationId, content)) return true
        sendMessage(conversationId, content, answer)
        return false
    }

    /** 只读：该会话的消息队列是否处于暂停（失败或用户停止后）；未加载时不创建会话。 */
    fun isMessageQueuePaused(conversationId: Uuid): Boolean =
        sessionManager.get(conversationId)?.messageQueue?.state?.value?.paused == true

    /** 本轮已 claim、等 finishStep 之后再 ack 的 STEER 行（会话作用域） */
    private val pendingSteerAcks =
        java.util.concurrent.ConcurrentHashMap<Uuid, List<AgentMailboxEntry>>()

    /**
     * 团队通知：对方正在生成就作为插话在下一步送达；否则只入队，
     * 且不解除由「用户停止 / 上一轮失败」造成的队列暂停（暂停期间留在队列里等用户恢复）。
     */
    /**
     * 迟到的工具结果回填：把报告写进当初那条 `expect_report`（旧名 await_teammate）调用的 output，并在会话空闲时自动续跑。
     * 不新增任何用户消息；会话正忙时返回 false（结果留在 mailbox，交给回合结束补投）。
     */
    suspend fun completeDeferredToolCall(
        conversationId: Uuid,
        toolName: String,
        handle: String,
        output: List<UIMessagePart>,
        deliveryKey: String? = null,
    ): Boolean = deferredToolLocks.getOrPut(conversationId) { Mutex() }.withLock {
        completeDeferredToolCallLocked(conversationId, toolName, handle, output, deliveryKey)
    }

    private suspend fun completeDeferredToolCallLocked(
        conversationId: Uuid,
        toolName: String,
        handle: String,
        output: List<UIMessagePart>,
        deliveryKey: String?,
    ): Boolean {
        val session = sessionManager.getOrCreate(conversationId)
        ensureInitialized(session)
        // 正在生成时不能改写历史：AgentLoop 手里还有同一消息的副本，会被下一次 update 覆盖
        if (session.getJob() != null) return false
        // 用户停过（队列暂停或上一轮就是被取消的）：结果只留邮箱，等用户下次开口再投
        if (isMessageQueuePaused(conversationId)) return false
        if (isStoppedByUser(conversationId)) return false
        // 同一条 mailbox 行可能被重复投递：这个键已经写过就直接算成功，避免二次改写和二次续跑
        if (deliveryKey != null && session.state.value.currentMessages.any { message ->
                message.parts.any { part ->
                    part is UIMessagePart.Tool &&
                        (part.metadata?.get("deliveredKey") as? JsonPrimitive)?.contentOrNull == deliveryKey
                }
            }
        ) return true
        var matched = false
        var replacedAnchor = false
        session.update { conversation ->
            val nodes = conversation.messageNodes.map { node ->
                val message = node.currentMessage
                if (message.role != MessageRole.ASSISTANT) return@map node
                var nodeChanged = false
                val parts = message.parts.map { part ->
                    // 旧会话里可能还挂着 await_teammate 的占位，改名后也要能回填
                    val nameMatches = part is UIMessagePart.Tool && (
                        part.toolName == toolName ||
                            (toolName == AgentTeamToolNames.EXPECT_REPORT &&
                                part.toolName == AgentTeamToolNames.LEGACY_AWAIT_TEAMMATE)
                        )
                    if (!replacedAnchor && part is UIMessagePart.Tool && nameMatches &&
                        handle.trim().lowercase() in part.inputHandles() &&
                        part.isDeferredPlaceholder
                    ) {
                        nodeChanged = true
                        replacedAnchor = true
                        part.copy(
                            output = output,
                            metadata = deliveryMetadata(part.metadata, deliveryKey),
                        )
                    } else {
                        part
                    }
                }
                if (!nodeChanged) {
                    node
                } else {
                    matched = true
                    node.copy(
                        messages = node.messages.toMutableList()
                            .also { it[node.selectIndex] = message.copy(parts = parts) }
                    )
                }
            }
            if (!matched) conversation else conversation.copy(messageNodes = nodes)
        }
        if (!matched) {
            // 模型派完活没登记 expect_report 就结束了回合：没有可回填的锚点，也不能把结果丢掉。
            // 补一条独立的 team_event（不是 await 调用）再续跑，不伪造用户消息，也不冒充工具调用。
            val text = output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (text.isBlank()) return false
            val synthetic = UIMessagePart.Tool(
                toolCallId = Uuid.random().toString(),
                toolName = AgentTeamToolNames.TEAM_EVENT,
                input = buildJsonObject {
                    put("event", "settled")
                    put("member", handle)
                }.toString(),
                output = output,
                metadata = deliveryMetadata(null, deliveryKey),
            )
            session.update { conversation ->
                val lastNode = conversation.messageNodes.lastOrNull()
                val last = lastNode?.currentMessage
                if (lastNode != null && last != null && last.role == MessageRole.ASSISTANT) {
                    // 追加到本轮最后一条助手消息：请求侧就是「这一轮调用了 expect_report 并拿到结果」
                    conversation.copy(
                        messageNodes = conversation.messageNodes.dropLast(1) + lastNode.copy(
                            messages = lastNode.messages.toMutableList()
                                .also { it[lastNode.selectIndex] = last.copy(parts = last.parts + synthetic) }
                        )
                    )
                } else {
                    conversation.copy(
                        messageNodes = conversation.messageNodes +
                            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(synthetic)).toMessageNode()
                    )
                }
            }
            session.flush()
            scheduleAutoResume(conversationId)
            return true
        }
        session.flush()
        scheduleAutoResume(conversationId)
        return true
    }

    /**
     * 自动续跑去抖：同一窗口内到达的多条队友结果合并成一次续跑；
     * 连续自动续跑超过上限就停下，结果留在历史里等用户开口。
     */
    private fun scheduleAutoResume(conversationId: Uuid) {
        autoResumeJobs.remove(conversationId)?.cancel()
        autoResumeJobs[conversationId] = appScope.launch {
            delay(AUTO_RESUME_COALESCE_MILLIS)
            autoResumeJobs.remove(conversationId)
            val streak = autoResumeStreak.merge(conversationId, 1, Int::plus) ?: 1
            if (streak > MAX_AUTO_RESUME_STREAK) {
                Log.w(TAG, "auto resume streak=$streak reached limit, waiting for user")
                return@launch
            }
            // 会话可能已被空闲回收：库里还有才重建，避免把已删除的会话复活成空会话
            val session = sessionManager.get(conversationId)
                ?: if (conversationRepo.existsConversationById(conversationId)) {
                    sessionManager.getOrCreate(conversationId)
                } else {
                    null
                }
            session?.let { resumeConversationIfIdle(it) }
        }
    }

    /** 新路径唤醒：只复用既有的去抖/限流自动续跑，不写转录、不 ACK。 */
    fun resumeForInbox(conversationId: Uuid) = scheduleAutoResume(conversationId)

    /** 回填时记录的投递键：同一条 mailbox 行重复投递时据此跳过 */
    private fun deliveryMetadata(base: JsonObject?, key: String?): JsonObject? =
        if (key == null) base
        else JsonObject((base ?: JsonObject(emptyMap())) + ("deliveredKey" to JsonPrimitive(key)))

    /** 没有用户消息的续跑：只在空闲、无待审批、队列未暂停、且不是用户刚停过的会话才启动。 */
    private suspend fun resumeConversationIfIdle(session: ConversationSession) {
        if (session.getJob() != null) return
        if (session.state.value.isAwaitingToolApproval) return
        if (isMessageQueuePaused(session.id)) return
        if (isStoppedByUser(session.id)) return
        val conversationId = session.id
        val job = launchGenerationJob(conversationId = conversationId, keepAliveInBackground = true) {
            try {
                ensureInitialized(session)
                runTurn(session)
                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(session, e, R.string.error_title_generation)
            }
        }
        session.setJob(job)
    }

    /**
     * 这条会话是不是「用户主动停过、之后没再开新一轮」：看最近一条 Run。
     * 停过就既不回填也不自动续跑，异步结果留在 mailbox 等用户下次说话。
     */
    private suspend fun isStoppedByUser(conversationId: Uuid): Boolean {
        val run = agentRunStore.latestForConversation(conversationId) ?: return false
        if (run.state != AgentRunState.CANCELLED) return false
        return run.reason == AgentRunLifecycle.CANCELLED_REASON || run.reason == PARENT_CANCELLED_REASON
    }

    /** 兼容 await_teammate 的两种入参：handle（单数）与 handles（数组），统一 trim+小写 */
    private fun UIMessagePart.Tool.inputHandles(): Set<String> {
        val obj = inputAsJson() as? JsonObject ?: return emptySet()
        val raw = buildList<String> {
            (obj["handle"] as? JsonPrimitive)?.contentOrNull?.let { add(it) }
            (obj["handles"] as? JsonArray)?.forEach { element ->
                (element as? JsonPrimitive)?.contentOrNull?.let { add(it) }
            }
        }
        return raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    suspend fun sendTeamNotice(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        messageId: Uuid? = null,
    ) {
        if (content.isEmptyInputMessage()) return
        val session = sessionManager.getOrCreate(conversationId)
        if (messageId != null) {
            // mailbox 重投：稳定 id 去重后追加；生成中插到当前助手节点之前，保持安全插入语义
            ensureInitialized(session)
            synchronized(session) {
                if (session.state.value.currentMessages.none { it.id == messageId }) {
                    val notice = UIMessage(id = messageId, role = MessageRole.USER, parts = content.toList())
                    val replyNodeId = session.replyNodeId
                    session.update { conversation ->
                        if (session.getJob() != null && replyNodeId != null) {
                            conversation.insertMessageBefore(replyNodeId, notice)
                        } else {
                            conversation.copy(messageNodes = conversation.messageNodes + notice.toMessageNode())
                        }
                    }
                }
            }
            session.flush()
            return
        }

        val texts = content.filterIsInstance<UIMessagePart.Text>().map { it.text }
        val noticeId = Uuid.random()
        var steered = false
        synchronized(session) {
            val busy = session.getJob() != null && !session.state.value.isAwaitingToolApproval
            if (busy && texts.isNotEmpty()) {
                // 本轮新开助手消息时把通知插到该助手节点之前：立刻可见，且会话仍以助手消息结尾
                val before = session.replyNodeId
                if (before != null) {
                    val notice = UIMessage(id = noticeId, role = MessageRole.USER, parts = content.toList())
                    session.update { it.insertMessageBefore(before, notice) }
                    session.turnNotices.add(notice)
                } else {
                    // 续写/重新生成这类轮次没法安全插节点：入队等本轮结束
                    session.messageQueue.enqueue(content, answer = false)
                }
                steered = true
            } else if (!isMessageQueuePaused(session.id)) {
                session.messageQueue.enqueue(content, answer = true)
                dispatchNextQueuedMessage(conversationId)
            }
            // 队列被用户停住时什么都不做：提示留在 mailbox，等用户下次开口再投
        }
        if (steered) enqueueSteerNotice(session, noticeId, texts)
    }

    /**
     * 给正在生成的会话插话（领队改队友方向用）：和 sendTeamNotice 的忙分支同一条路径，
     * 返回 false 表示没插——空闲、等审批、续写/重新生成没有安全锚点，调用方改走普通派活。
     */
    suspend fun steerGeneration(conversationId: Uuid, content: List<UIMessagePart>): Boolean {
        if (content.isEmptyInputMessage()) return false
        val texts = content.filterIsInstance<UIMessagePart.Text>().map { it.text }
        if (texts.isEmpty()) return false
        // 会话不在内存就不为一句插话把整段历史读起来
        val session = sessionManager.get(conversationId) ?: return false
        ensureInitialized(session)
        val noticeId = Uuid.random()
        val inserted = synchronized(session) {
            // 忙/可插只认实际 Job：假活跃或已取消的 Job 没有安全插入点，暂停/待审批的轮次也不插
            val busy = session.getJob()?.isActive == true &&
                !session.state.value.isAwaitingToolApproval &&
                !session.messageQueue.state.value.paused
            val before = session.replyNodeId
            if (!busy || before == null) return@synchronized false
            val notice = UIMessage(id = noticeId, role = MessageRole.USER, parts = content.toList())
            session.update { it.insertMessageBefore(before, notice) }
            session.turnNotices.add(notice)
            true
        }
        if (!inserted) return false
        // 插话行落不下去就撤回刚插的节点并退回普通入队：不能让用户看见消息却没送进模型
        if (!enqueueSteerNotice(session, noticeId, texts)) {
            synchronized(session) {
                session.update { conversation ->
                    conversation.copy(
                        messageNodes = conversation.messageNodes.filterNot { it.currentMessage.id == noticeId }
                    )
                }
                session.turnNotices.removeAll { it.id == noticeId }
            }
            return false
        }
        return true
    }

    /** 插话持久化：同一条可见通知只落一行 STEER（idempotencyKey 由通知 id 派生） */
    private suspend fun enqueueSteerNotice(
        session: ConversationSession,
        noticeMessageId: Uuid,
        texts: List<String>,
    ): Boolean {
        val text = texts.joinToString("\n")
        val inline = text.takeIf { AgentRunLifecycle.bodyFitsInline(it) }
        return runCatching {
            agentMailboxStore.enqueue(
                NewMailboxEntry(
                    targetConversationId = session.id,
                    kind = MailboxKind.STEER,
                    idempotencyKey = "steer:$noticeMessageId",
                    runId = agentRunStore.latestForConversation(session.id)
                        ?.takeIf { !it.isTerminal }?.runId,
                    bodyText = inline,
                    bodyRef = if (inline == null) "notice:$noticeMessageId" else null,
                )
            )
            true
        }.onFailure { Log.w(TAG, "enqueue steer failed", it) }.getOrDefault(false)
    }

    /** ASSIGN 的送达确认：拿不到可领行只记日志，不阻断本轮 */
    private suspend fun confirmAssignment(mailboxId: Uuid?) {
        if (mailboxId == null) return
        runCatching {
            when (val claimed = agentMailboxStore.claimOne(mailboxId)) {
                is MailboxClaimResult.Claimed -> agentMailboxStore.ack(
                    id = mailboxId,
                    claimToken = claimed.entry.claimToken.orEmpty(),
                    expectedRevision = claimed.entry.revision,
                )

                MailboxClaimResult.None -> Log.w(TAG, "assign mailbox $mailboxId not claimable yet")
            }
        }.onFailure { Log.w(TAG, "assign mailbox confirm failed", it) }
    }

    /**
     * 取走待插话内容交给循环，只进这一次请求、不写会话：
     * 会话结构的可见部分由 sendTeamNotice 负责（新开助手消息时插节点，否则入队等本轮结束）。
     */
    private suspend fun drainSteers(session: ConversationSession, runId: Uuid?): List<UIMessage> {
        val accepted = mutableListOf<UIMessage>()
        val claimed = mutableListOf<AgentMailboxEntry>()
        while (true) {
            // 只领 STEER：按任意类别领取会顺手领走 RESULT 行，导致结果卡在 CLAIMED 无法投递
            val entry = when (
                val result = agentMailboxStore.claimNextOfKinds(
                    session.id,
                    setOf(MailboxKind.STEER),
                )
            ) {
                is MailboxClaimResult.Claimed -> result.entry
                MailboxClaimResult.None -> break
            }
            if (entry.kind != MailboxKind.STEER) break
            // 不是本轮的插话：它已作为可见节点留在转录里，直接 ack，别占着 CLAIMED 卡到 TTL
            if (entry.runId != null && runId != null && entry.runId != runId) {
                runCatching {
                    agentMailboxStore.ack(entry.id, entry.claimToken.orEmpty(), entry.revision)
                }.onFailure { Log.w(TAG, "ack stale steer ${entry.id} failed", it) }
                continue
            }
            val text = entry.bodyText?.takeIf { it.isNotBlank() }
                ?: "（插话内容缺失：${entry.bodyRef ?: "-"}）"
            accepted.add(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text))))
            claimed.add(entry)
        }
        // 同一轮可能有多次 drain（工具检查点）：原子累加，别覆盖前一次未 ack 的 claim
        if (claimed.isNotEmpty()) pendingSteerAcks.merge(session.id, claimed) { old, new -> old + new }
        return accepted
    }

    /** 已纳入本步请求的 STEER 在检查点提交后确认；未确认的行 TTL 后可重领（at-least-once） */
    private suspend fun ackPendingSteers(conversationId: Uuid) {
        val rows = pendingSteerAcks.remove(conversationId) ?: return
        rows.forEach { entry ->
            runCatching {
                agentMailboxStore.ack(entry.id, entry.claimToken.orEmpty(), entry.revision)
            }.onFailure { Log.w(TAG, "ack steer ${entry.id} failed", it) }
        }
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = sessionManager.getOrCreate(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(!session.state.value.isAwaitingToolApproval) {
                context.getString(R.string.chat_page_voice_tools_before_resume)
            }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessionManager.get(conversationId) ?: return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.isAwaitingToolApproval) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        // 用户自己开口：连续自动续跑计数清零，并取消还没触发的合并续跑（这一轮会带上已写入的结果）
        autoResumeStreak.remove(conversationId)
        autoResumeJobs.remove(conversationId)?.cancel()
        // 新消息开始初始化前就清掉旧回复，初始化失败或任务启动前取消也不能留下旧预览。
        session.replyMessageId.value = null
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = queued.answer,
        ) {
            try {
                ensureInitialized(session)
                finishInterruptedPendingTools(session)
                // 成员直发（UI/工具）在真正执行前补建 Run；非成员保持原样
                val queuedRunId = queued.runId ?: createMemberRun(
                    conversationId = conversationId,
                    text = queued.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text },
                )?.runId

                val assistant = settingsStore.awaitLoaded().getStoredAssistantOf(session.state.value)
                val processedContent = queued.parts.replaceUserRegexes(assistant)

                // 添加消息到列表
                val previousIds = session.state.value.currentMessages.mapTo(HashSet()) { it.id }
                session.update {
                    it.copy(
                        messageNodes = it.messageNodes + UIMessage(
                            role = MessageRole.USER,
                            parts = processedContent,
                        ).toMessageNode(),
                    )
                }
                session.flush()
                session.submittingMessage = null
                // ASSIGN 在消息真正写进会话之后确认；拿不到只记日志，TTL 后会重领
                confirmAssignment(queued.mailboxId)

                // 开始补全
                val result = if (queued.answer) runTurn(session, runId = queuedRunId) else null

                queued.reply?.completeWith(runCatching {
                    check(result != TurnResult.Failed) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check((result as? TurnResult.Finished)?.reason !is AgentFinishReason.AwaitingApproval) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    session.state.value.currentMessages
                        .filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                reportFailure(session, e, R.string.error_title_send_message)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    // 发送、重新生成和审批失败时报告错误并暂停队列，排队的消息等用户处理完再继续
    private fun reportFailure(session: ConversationSession, error: Exception, @StringRes title: Int) {
        if (error is CancellationException) throw error
        session.messageQueue.pause()
        addError(error, session.id, title = context.getString(title))
    }

    // ---- 重新生成消息 ----

    /** 重新生成用户消息时去掉它之后的节点再回复；重新生成助手消息时在它的节点上新开一个分支 */
    fun regenerateAtMessage(conversationId: Uuid, messageId: Uuid) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            val previousJob = session.getJob()
            val job = launchGenerationJob(conversationId = conversationId) {
                try {
                    previousJob?.join()
                    session.replyMessageId.value = null
                    ensureInitialized(session)
                    val node = session.state.value.getMessageNodeByMessageId(messageId)
                        ?: return@launchGenerationJob

                    // 重生成/续写旧输出是新 attempt；只有成员会话才建 Run 并查 Run 库
                    val attemptRun = if (agentTeamRepository.getMemberByConversation(conversationId) != null) {
                        val previousRun = agentRunStore.latestForConversation(conversationId)
                        createMemberRun(
                            conversationId = conversationId,
                            text = null,
                            previousRunId = previousRun?.runId,
                            attemptNo = (previousRun?.attemptNo ?: 0) + 1,
                        )
                    } else {
                        null
                    }
                    if (node.role == MessageRole.USER) {
                        session.tryUpdate { conversation ->
                            val index = conversation.messageNodes.indexOfFirst { it.id == node.id }
                            // 查找之后这条消息被删掉了
                            if (index == -1) return@tryUpdate null
                            conversation.copy(messageNodes = conversation.messageNodes.take(index + 1))
                        } ?: return@launchGenerationJob
                        session.flush()
                        runTurn(session, runId = attemptRun?.runId)
                    } else {
                        runTurn(session, regenerateNodeId = node.id, runId = attemptRun?.runId)
                    }

                    _generationDoneFlow.emit(conversationId)
                } catch (e: Exception) {
                    reportFailure(session, e, R.string.error_title_regenerate_message)
                }
            }

            session.setJob(job)
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            val previousJob = session.getJob()

            val hasOtherPendingTools = session.state.value.currentMessages.any { message ->
                message.getTools().any { it.isPending && it.toolCallId != toolCallId }
            }

            val job = launchGenerationJob(
                conversationId = conversationId,
                keepAliveInBackground = !hasOtherPendingTools,
            ) {
                try {
                    afterPreviousGeneration(previousJob) {
                        ensureInitialized(session)
                        val newApprovalState = when {
                            answer != null -> ToolApprovalState.Answered(answer)
                            approved -> ToolApprovalState.Approved
                            else -> ToolApprovalState.Denied(reason)
                        }

                        val updated = session.tryUpdate { it.withToolApproval(toolCallId, newApprovalState) }
                            ?: return@afterPreviousGeneration
                        session.flush()

                        // Only continue generation when all pending tools are handled
                        if (!updated.isAwaitingToolApproval) {
                            // 审批续跑按精确 replyMessageId 找仍非终态的 Run，不取 latest 误认下一单
                            val continuation = session.state.value.currentMessages.lastOrNull()
                                ?.takeIf { it.role == MessageRole.ASSISTANT }?.id
                            val isMember =
                                agentTeamRepository.getMemberByConversation(conversationId) != null
                            if (!isMember) {
                                runTurn(session)
                                return@afterPreviousGeneration
                            }
                            val bound = continuation?.let {
                                agentRunStore.findLatestByReply(conversationId, it.toString())
                            }
                            when (val plan = AgentRunLifecycle.planResume(bound, continuation)) {
                                is RunResume.RefusedStepLimit -> addError(
                                    IllegalStateException(
                                        "步数预算已用尽（${plan.run.stepsUsed}/${plan.run.stepLimit ?: "?"}），" +
                                            "先扩展预算再续跑；不会用新 Run 重置同一暂停输出。"
                                    ),
                                    conversationId,
                                )

                                is RunResume.SameRun -> runTurn(session, runId = plan.run.runId)

                                is RunResume.NewAttempt -> {
                                    val previous = bound
                                    val next = createMemberRun(
                                        conversationId = conversationId,
                                        text = null,
                                        previousRunId = previous?.runId,
                                        attemptNo = (previous?.attemptNo ?: 0) + 1,
                                    )
                                    runTurn(session, runId = next?.runId)
                                }
                            }
                        }

                        _generationDoneFlow.emit(conversationId)
                    }
                } catch (e: Exception) {
                    reportFailure(session, e, R.string.error_title_tool_approval)
                }
            }

            session.setJob(job, cancelPrevious = false)
        }
    }

    // ---- 生成回复 ----

    /**
     * 生成一轮回复：请求模型、执行工具，直到模型不再调用工具、有工具需要审批或达到步数上限。
     *
     * 默认接着当前分支的末尾回复；[regenerateNodeId] 不为空时只发送这个节点之前的消息，回复作为它的新分支。
     * 发送的最后一条是助手消息时从它继续（比如审批之后），回复写回那条消息。
     * 失败时报告错误、暂停消息队列并返回 [TurnResult.Failed]；取消时直接抛出。
     */
    private suspend fun runTurn(
        session: ConversationSession,
        regenerateNodeId: Uuid? = null,
        runId: Uuid? = null,
    ): TurnResult {
        val member = agentTeamRepository.getMemberByConversation(session.id)
        // 领队回合也建 Run：计入根账本、留下终态与失败记录（普通会话没有队就保持不跟踪）
        val leadRunId = if (member == null && runId == null) {
            runCatching { createLeadRunIfTeam(session.id) }
                .onFailure { Log.w(TAG, "create lead run failed", it) }
                .getOrNull()
        } else {
            null
        }
        // 普通聊天（无 Run）保持旧路径；成员行已消失却带旧 runId 时与旧行为一致地不跟踪
        if (member == null && leadRunId == null) {
            return runAdmittedTurn(session, regenerateNodeId, runId = null, fence = null)
        }
        // 恢复屏障先于本进程第一次 RUNNING（领队与成员一致）
        awaitRunRecovery()
        if (member == null) {
            // LEAD：只 CAS RUNNING，不取成员 gate、不调 onFinished
            val request = AdmitRequest(
                runId = leadRunId,
                conversationId = session.id,
                teamId = null,
                kind = AdmitKind.LEAD,
            )
            return runCoordinator.runAdmitted(request) { admission ->
                dispatchAdmission(session, regenerateNodeId, admission, leadRunId)
            }
        }
        if (member.status == me.rerere.rikkahub.data.model.TeamMemberStatus.FAILED) {
            addError(IllegalStateException("failed teammate cannot generate"), session.id)
            return TurnResult.Failed
        }
        session.processingStatus.value = "等待团队并发名额…"
        // 并发桶按根团队：整棵嵌套树共用根队名额（maxDepth=1 时 root 就是自己）
        val request = AdmitRequest(
            runId = runId,
            conversationId = session.id,
            teamId = member.teamId,
            kind = AdmitKind.MEMBER,
        )
        try {
            return runCoordinator.runAdmitted(
                request = request,
                onFinished = { elapsedMillis ->
                    try {
                        agentTeamRepository.updateMemberElapsed(session.id, elapsedMillis)
                    } catch (e: Exception) {
                        addError(e, session.id)
                    }
                },
            ) { admission ->
                session.processingStatus.value = null
                dispatchAdmission(session, regenerateNodeId, admission, runId)
            }
        } finally {
            session.processingStatus.value = null
        }
    }

    /** 准入结果分派：Admitted 按旧时点发事件再跑；Rejected fail-closed，不落转录、不改队列 */
    private suspend fun dispatchAdmission(
        session: ConversationSession,
        regenerateNodeId: Uuid?,
        admission: Admission,
        requestedRunId: Uuid?,
    ): TurnResult = when (admission) {
        is Admission.Admitted -> {
            emitRunEvent(admission.run)
            runAdmittedTurn(session, regenerateNodeId, admission.run.runId, admission.fence)
        }

        is Admission.Untracked -> runAdmittedTurn(session, regenerateNodeId, null, null)

        is Admission.Rejected -> {
            Log.w(TAG, "run admission rejected for $requestedRunId: ${admission.reason} state=${admission.current?.state}")
            addError(
                IllegalStateException("Run $requestedRunId 无法进入 RUNNING（${admission.reason}），已放弃本次执行"),
                session.id,
            )
            TurnResult.Failed
        }
    }

    private suspend fun runAdmittedTurn(
        session: ConversationSession,
        regenerateNodeId: Uuid?,
        runId: Uuid?,
        fence: FenceToken?,
    ): TurnResult {
        val conversationId = session.id
        // 先清理上一轮状态；确定历史后，仅为真正的续写恢复消息 ID。
        session.replyMessageId.value = null
        // 本轮从空汇报开始：上一轮没被消费的 report 不跨轮沿用
        memberReports.remove(conversationId)
        // 生成开始前失败时通知只用来取消 Live Update，用不到发送者名称
        var senderName = ""
        try {
            // 领队答复先注入转录，再算本轮历史，答复才会进入本轮上下文
            drainTeamAnswers(session)
            val settings = settingsStore.awaitLoaded()
            // 清掉上一轮的建议；没法继续执行的工具调用发给模型会被拒绝，先去掉。
            // 重新生成时只清理要发给模型的节点，被重新生成的节点保持原样，旧回复留作分支
            val conversation = session.update { latest ->
                val untilNode = regenerateNodeId
                    ?.let { nodeId -> latest.messageNodes.indexOfFirst { it.id == nodeId } }
                    ?.takeIf { it != -1 }
                    ?: latest.messageNodes.size
                latest.copy(chatSuggestions = emptyList()).withoutUnresumableToolCalls(untilNode)
            }

            val nodes = conversation.messageNodes
            val history = if (regenerateNodeId != null) {
                val index = nodes.indexOfFirst { it.id == regenerateNodeId }
                check(index != -1) { "Message node $regenerateNodeId not found" }
                conversation.currentMessages.take(index)
            } else {
                conversation.currentMessages
            }
            val replyNodeId = when {
                history.lastOrNull()?.role == MessageRole.ASSISTANT -> nodes[history.lastIndex].id
                regenerateNodeId != null -> regenerateNodeId
                else -> Uuid.random()
            }
            // 接着上一条助手消息写时就是它，否则等第一次输出。要在 createContext 之前换掉，
            // 它会挂起也可能失败，期间或失败后不能还指着上一轮的回复
            session.replyMessageId.value = history.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id
            // 先清上一轮的通知再放开本轮插节点：否则两步之间到达的通知会被下一次 clear 抹掉
            session.turnNotices.clear()
            // 只有新开一条助手消息的轮次才允许把队友通知插到它之前；续写/重新生成沿用旧队列路径
            session.replyNodeId = replyNodeId.takeIf {
                regenerateNodeId == null && history.lastOrNull()?.role != MessageRole.ASSISTANT
            }

            // 本段步数基线：Run 自己已记录的步数 + 续写消息此刻的 stepCount（只累加之后的增量）
            val trackedRun = runId?.let { agentRunStore.get(it) }
            val continuation = history.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
            // 本段续写的助手消息已带多少步，就只加这之后的增量（新 attempt 的 runStepsUsed 仍为 0）
            val startMessageStepCount = continuation?.stepCount ?: 0
            val stepBaseline = AgentRunLifecycle.baseline(trackedRun, startMessageStepCount)
            // 精确绑定本轮回复锚点：审批续跑只认这条消息，不猜会话最新一轮
            var boundReply: Uuid? = session.replyMessageId.value
            if (trackedRun != null && boundReply != null) {
                bindRunReply(trackedRun.runId, boundReply, fence)
            }

            val turn = requestFactory.createContext(settings, conversation)
            senderName = if (turn.assistant.useAssistantAvatar) {
                turn.assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
            } else {
                turn.model.displayName
            }
            warnIfToolsUnavailable(turn, conversationId)

            val (baseRequest, baseHooks) = requestFactory.createAgent(
                turn, history, session.processingStatus,
                onStreamInterrupted = { message ->
                    session.update { it.upsertMessage(replyNodeId, message) }
                    session.replyMessageId.value = message.id
                },
            )
            // 续跑沿用同一 Run 的已用步数，预算不因审批/暂停重置
            val request = if (trackedRun != null) {
                val limit = trackedRun.stepLimit ?: baseRequest.maxSteps
                baseRequest.copy(
                    maxSteps = limit,
                    initialSteps = trackedRun.stepsUsed.coerceIn(0, limit),
                )
            } else {
                baseRequest
            }
            // 预算已用尽且没有可收尾的助手消息：明确失败，不重置步数继续请求模型
            if (trackedRun != null && continuation == null && trackedRun.stepsUsed >= request.maxSteps) {
                addError(
                    IllegalStateException(
                        "该 Run 步数预算已用尽且没有可收尾的助手消息；不重置步数，请先扩展预算或重开任务。"
                    ),
                    conversationId,
                )
                return TurnResult.Failed
            }
            val hooks = object : AgentHooks by baseHooks {
                override suspend fun drainSteer(): List<UIMessage> =
                    drainSteers(session, trackedRun?.runId)

                override suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage {
                    val finished = baseHooks.finishStep(message, newPartsFrom)
                    recordRunSteps(trackedRun?.runId, stepBaseline, finished.stepCount ?: 0, fence)
                    // 跟随检查点提交：只有走完这一步才确认插话
                    ackPendingSteers(session.id)
                    return finished
                }

                // operation + 检查点只对受跟踪的 Run 落库；普通聊天（runId 为 null）不写表，行为不变
                override suspend fun onToolStarted(toolCallId: String, toolName: String, input: String) {
                    val run = trackedRun ?: return
                    val token = fence
                    if (token == null) {
                        Log.w(TAG, "tool operation skipped: no active fence")
                        return
                    }
                    operationRecorder.onToolStarted(run, token, toolCallId, toolName, input)
                }

                override suspend fun onToolFinished(toolCallId: String, output: String?, error: Throwable?) {
                    val run = trackedRun ?: return
                    // 受理回执：该 call 以 Accepted 结束，后台结果只能由 receive_events 交付
                    val receipt = acceptedReceiptOf(output)
                    if (receipt != null) {
                        val accepted = operationRuntime.accept(
                            OperationAccept(
                                runId = run.runId,
                                attemptNo = run.attemptNo,
                                originCallId = toolCallId,
                                deliveryKey = receipt.deliveryKey,
                                output = output,
                            )
                        )
                        val ok = accepted is me.rerere.rikkahub.runtime.OperationAcceptResult.Accepted ||
                            accepted is me.rerere.rikkahub.runtime.OperationAcceptResult.AlreadyAccepted
                        if (!ok) {
                            // 受理没落成：不能只留回执，退回 R2 的结算并留痕
                            Log.w(TAG, "operation accept not applied: $accepted")
                            operationRecorder.onToolFinished(run.runId, run.attemptNo, toolCallId, output, error)
                        }
                        return
                    }
                    operationRecorder.onToolFinished(run.runId, run.attemptNo, toolCallId, output, error)
                }
            }
            var finishReason: AgentFinishReason? = null
            var completedMessage: UIMessage? = null
            // 本 Run 输出的不可变快照（最终 flush 之后取），供 relay 使用
            var completedSnapshot: UIMessage? = null
            try {
                runAgent(request, hooks).flowOn(Dispatchers.IO).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdated -> {
                            // 生成过程中只有网络重试的提示会留着，收到新内容说明重试已经成功
                            session.processingStatus.value = null
                            session.update { it.upsertMessage(replyNodeId, event.message) }
                            session.replyMessageId.value = event.message.id
                            if (trackedRun != null && boundReply != event.message.id) {
                                boundReply = event.message.id
                                bindRunReply(trackedRun.runId, event.message.id, fence)
                            }
                            // 只追加增量文本；失败不能打断生成链
                            runCatching { streamChunks.append(conversationId, event.message) }
                                .onFailure { Log.w(TAG, "append stream chunk failed", it) }
                            // 通知等边缘副作用由 ChatNotificationManager 消费；
                            // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, event.message, senderName)
                            )
                        }

                        is AgentEvent.Retrying -> {
                            logRetry(event.attempt)
                            session.processingStatus.value = retryStatus(event.attempt)
                        }

                        is AgentEvent.Finished -> {
                            Log.i(TAG, "runTurn: finished (${event.reason})")
                            finishReason = event.reason
                            completedMessage = event.message
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    session.processingStatus.value = null
                    // 可能被取消了，或者意外结束，兜底保存
                    val finished = session.finishGeneration()
                    completedSnapshot = session.replyMessageId.value
                        ?.let { id -> finished.currentMessages.firstOrNull { it.id == id } }
                    session.replyNodeId = null
                    // 没走完这一步就不 ack：STEER 行 TTL 后可重领
                    pendingSteerAcks.remove(conversationId)
                    // 整条消息已经落库，增量片段不再需要
                    runCatching { streamChunks.clear(conversationId) }
                        .onFailure { Log.w(TAG, "clear stream chunks failed", it) }
                    // 生成结束：取消 Live Update 通知，后台时发送完成通知
                    appEventBus.emit(
                        AppEvent.ChatGenerationEnded(
                            conversationId = conversationId,
                            senderName = senderName,
                            contentPreview = finished.currentMessages.lastOrNull()
                                ?.toText()?.take(50)?.trim() ?: "",
                        )
                    )
                }
            }

            val reason = checkNotNull(finishReason)
            // 最终转录已 flush：先提交本轮 Run 迁移，再做 PostTurn 维护（条件保持原样）
            commitRunAfterTurn(runId, reason, completedSnapshot)
            // T2：转录已落库才 ACK；成功确认的事件再结算对应 operation（ACCEPTED 的出口）
            runCatching {
                val acked = operationInbox.ackPending(conversationId)
                for (entry in acked) {
                    val memberRunId = entry.runId ?: continue
                    val key = OperationRuntime.deliveryKeyFor(memberRunId)
                    for (operation in operationRuntime.listAcceptedByDelivery(key)) {
                        when (entry.kind) {
                            me.rerere.rikkahub.data.model.MailboxKind.FAILURE ->
                                operationRuntime.settleFailed(operation, "member_failed")
                            // 通知类不代表任务成败：保持 ACCEPTED，等真正的 RESULT/FAILURE
                            me.rerere.rikkahub.data.model.MailboxKind.NOTICE -> Unit
                            else -> operationRuntime.settleAccepted(operation, entry.bodyText?.take(500))
                        }
                    }
                }
                // T2 结算之后才判：本轮把最后一个结果消费掉时，Run 应从等待态正常收尾
                if (runId != null) {
                    val run = agentRunStore.get(runId)
                    val stillOpen = operationStore.listForRun(runId)
                        .any { me.rerere.rikkahub.data.model.RuntimeOperationRules.isOpen(it.state) }
                    if (run != null && run.state == me.rerere.rikkahub.data.model.AgentRunState.WAITING_OPERATION && !stillOpen) {
                        agentRunStore.transition(
                            runId = runId,
                            expectedRevision = run.stateRevision,
                            to = me.rerere.rikkahub.data.model.AgentRunState.COMPLETED,
                            reason = "operations_consumed",
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "inbox ack/settle failed", it) }
            if (reason is AgentFinishReason.Completed) {
                // 拿不到最终消息只是自动压缩做不了：记一条维护失败，不能把已完成的任务改判成失败
                val finalMessage = completedMessage
                if (finalMessage == null) {
                    addError(
                        IllegalStateException("任务已完成，但缺少最终消息，跳过自动压缩；原记录已保留。"),
                        conversationId,
                        title = "自动压缩失败",
                    )
                } else {
                    // 本轮消息现在会进入压缩范围。expected 仍用本轮实际生成的内容（历史 + 本轮消息），
                    // 供安装前的比对使用；finishGeneration 给未闭合 reasoning 补的 finishedAt
                    // 由 sameContextExceptReasoningFinishTime 单独容忍，不用 canonical 覆盖 expected。
                    // 插进对话的队友通知按实际顺序参与 expected，否则 verifySource 会判「消息已编辑」
                    val finalHistory = if (history.lastOrNull()?.id == finalMessage.id) {
                        history.dropLast(1) + finalMessage
                    } else {
                        history + session.turnNotices.toList() + finalMessage
                    }
                    try {
                        requestFactory.compressAfterTask(
                            turn = turn,
                            messages = finalHistory,
                            completed = true,
                            processingStatus = session.processingStatus,
                            // 规划出覆盖范围后、付摘要请求前先核一次：编辑/换分支就地失败，不白付请求
                            verifySource = { prefix ->
                                val snapshot = session.state.value
                                val anchor = snapshot.messageNodes
                                    .indexOfFirst { it.currentMessage.id == prefix.last().id }
                                check(anchor >= 0) { "自动压缩期间对话分支已变化" }
                                check(
                                    sameContextExceptReasoningFinishTime(
                                        snapshot.currentMessages.take(anchor + 1).limitContext(0),
                                        prefix.limitContext(0),
                                    )
                                ) { "自动压缩期间消息已编辑，请重试" }
                            },
                            onAutoCheckpoint = { prefix, checkpoint ->
                                // 只提交仍与本轮分支相符的历史；保留最新任务回复，不修改工具状态。
                                var anchorIndex = -1
                                session.update { latest ->
                                    val anchor = latest.messageNodes
                                        .indexOfFirst { it.currentMessage.id == prefix.last().id }
                                    anchorIndex = anchor
                                    check(anchor >= 0) { "自动压缩期间对话分支已变化" }
                                    val actual = latest.currentMessages.take(anchor + 1).limitContext(0)
                                    // 顺序/内容必须一致；只容忍 finishGeneration 补 reasoning.finishedAt 这一种差异
                                    check(
                                        sameContextExceptReasoningFinishTime(actual, prefix.limitContext(0))
                                    ) { "自动压缩期间消息已编辑，请重试" }
                                    check(latest.currentMessages.take(anchor + 1).lastOrNull { it.isContextCheckpoint }?.id ==
                                        prefix.lastOrNull { it.isContextCheckpoint }?.id) { "自动压缩期间检查点已变化" }
                                    latest.copy(
                                        messageNodes = latest.messageNodes.take(anchor + 1) +
                                            checkpoint.toMessageNode() + latest.messageNodes.drop(anchor + 1),
                                        chatSuggestions = emptyList(),
                                    )
                                }
                                session.flush()
                                // 安装点诊断：新增的检查点应紧跟在本轮回复之后，其后不应还有消息
                                val installed = session.state.value
                                val index = installed.messageNodes
                                    .indexOfLast { it.currentMessage.isContextCheckpoint }
                                val retained = installed.messageNodes.size - 1 - index
                                Log.i(
                                    TAG,
                                    "auto checkpoint installed: anchor=$anchorIndex nodes=${installed.messageNodes.size} " +
                                        "checkpointIndex=$index retainedAfter=$retained id=${checkpoint.id}",
                                )
                                if (retained > 0) {
                                    Log.w(TAG, "auto checkpoint is not last: $retained messages stay after it")
                                }
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The task and its answer are already complete; compression is maintenance, not failure.
                        Log.w(TAG, "auto compression failed: " + e.message, e)
                        addError(
                            IllegalStateException("任务已完成，但自动压缩失败；原记录和任务结果均已保留。原因：${e.message}", e),
                            conversationId,
                            title = "自动压缩失败",
                        )
                    }
                }
            }
            // 等待审批时这一轮还没结束，标题、建议和记忆整理等审批之后再做
            if (reason !is AgentFinishReason.AwaitingApproval) {
                sessionManager.launchWithSession(conversationId) { generateTitle(conversationId) }
                sessionManager.launchWithSession(conversationId) { generateSuggestion(conversationId) }
                if (turn.assistant.enableMemory) memoryConsolidationScheduler.onTurnFinished(session.state.value)
            }
            return TurnResult.Finished(reason)
        } catch (e: CancellationException) {
            finishRunQuietly(runId, AgentRunState.CANCELLED, AgentRunLifecycle.CANCELLED_REASON, "cancelled by user")
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            throw e
        } catch (e: InvalidMcpServerNamesException) {
            finishRunQuietly(runId, AgentRunState.FAILED, AgentRunLifecycle.FAILED_REASON, e.message)
            session.messageQueue.pause()
            addError(
                error = IllegalStateException(
                    context.getString(R.string.error_mcp_invalid_server_name, e.names.joinToString(", "))
                ),
                conversationId = conversationId,
            )
            return TurnResult.Failed
        } catch (e: Exception) {
            finishRunQuietly(runId, AgentRunState.FAILED, AgentRunLifecycle.FAILED_REASON, e.message)
            // 兜底取消 Live Update 通知（生成开始前失败时上面的 finally 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            session.messageQueue.pause()
            e.printStackTrace()
            addError(e, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "runTurn: $e")
            Logging.log(TAG, e.stackTraceToString())
            return TurnResult.Failed
        }
    }

    // 模型不支持工具调用时，搜索和 MCP 工具都不会生效，提醒一下
    private fun warnIfToolsUnavailable(turn: ChatTurnContext, conversationId: Uuid) {
        if (ModelAbility.TOOL in turn.model.abilities) return
        if (shouldUseExternalWebSearch(turn.assistant, turn.model) ||
            mcpManager.getAllAvailableTools(turn.assistant).isNotEmpty()
        ) {
            addError(
                IllegalStateException(context.getString(R.string.tools_warning)),
                conversationId,
                title = context.getString(R.string.error_title_tool_unavailable)
            )
        }
    }

    private fun logRetry(attempt: RetryAttempt) {
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${attempt.delay} (${attempt.retry}/${attempt.maxRetries})",
            attempt.cause,
        )
    }

    private fun retryStatus(attempt: RetryAttempt): String {
        val reason = when (attempt.cause) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(
            R.string.chat_generation_network_retrying,
            context.getString(reason),
            attempt.retry,
            attempt.maxRetries,
        )
    }

    private suspend fun finishInterruptedPendingTools(session: ConversationSession) {
        session.tryUpdate { it.cancelInterruptedTools() }
        session.flush()
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        force: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        withLoadedSession<Unit>(conversationId) { session ->
            val incarnation = session.incarnation
            val conversation = session.state.value
            val teammateName = agentTeamRepository.getMemberByConversation(conversationId)?.name
            if (!force && !needsAutomaticConversationTitle(conversation.title, teammateName)) return@withLoadedSession

            val settings = settingsStore.awaitLoaded()
            // 关掉自动生成后只跳过对话结束时的自动调用，手动重新生成照常执行
            if (!force && !settings.enableAutoTitle) return@withLoadedSession

            runCatching {
                val title = metaGenerator.generateTitle(settings, conversation)
                // 只写标题这一列：这时可能已经在生成下一轮，不能把还没生成完的内容一起落库。
                // 生成期间会话被删除的话不写入
                session.updateMetadata(
                    transform = { it.copy(title = title) },
                    persist = { conversationRepo.updateTitle(conversationId, title) },
                    expectedIncarnation = incarnation,
                )
            }.onFailure {
                it.printStackTrace()
                addError(
                    error = it,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generate_title),
                    solution = ChatErrorSolution.CheckFastModelSettings,
                )
            }
        }
    }

    // ---- 生成建议 ----

    private suspend fun generateSuggestion(conversationId: Uuid): Unit = withContext(Dispatchers.IO) {
        sessionManager.withSession<Unit>(conversationId) { session ->
            runCatching {
                val settings = settingsStore.awaitLoaded()
                if (!settings.enableSuggestion) return@runCatching

                // 上一轮的建议在这一轮开始时已经清掉了
                ensureInitialized(session)
                val incarnation = session.incarnation
                val suggestions = metaGenerator.generateSuggestions(settings, session.state.value)
                    ?: return@runCatching

                // 只写建议这一列，理由同标题
                session.updateMetadata(
                    transform = { it.copy(chatSuggestions = suggestions) },
                    persist = { conversationRepo.updateChatSuggestions(conversationId, suggestions) },
                    expectedIncarnation = incarnation,
                )
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    // ---- 压缩对话历史 ----

    /**
     * 把上一个检查点之后的消息压缩成摘要检查点。
     *
     * [keepRecentMessages] 为 0 时压缩全部消息，请求和上一轮对话前缀一致，缓存命中最好；
     * 大于 0 时最近的这些消息不参与压缩、留在检查点之后，对话已经超出模型上下文、整段发不出去时用来缩小压缩请求。
     */
    suspend fun compressConversation(
        conversationId: Uuid,
        additionalPrompt: String,
        keepRecentMessages: Int = 0,
    ): Result<Unit> = runCatching {
        withLoadedSession<Unit>(conversationId) { session ->
            // 生成中的回复还没写完，压缩不到它，检查点也不能插到它前面
            check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }

            val conversation = session.state.value
            val nodes = conversation.messageNodes
            val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
            // 压缩范围是 (checkpointIndex, endIndex)，之后的节点原样保留
            val endIndex = nodes.size - keepRecentMessages.coerceAtLeast(0)
            // 上一个检查点之后没有可压缩的消息
            if (endIndex <= checkpointIndex + 1) {
                throw IllegalStateException(
                    context.getString(
                        if (keepRecentMessages > 0) {
                            R.string.chat_page_compress_not_enough_messages
                        } else {
                            R.string.chat_page_compress_nothing_to_compress
                        }
                    )
                )
            }
            // 未完成的工具调用没有结果，请求会被服务端拒绝；压到检查点之前也再不会执行。
            // 保留区里的不发给压缩请求，压缩后照常留在检查点之后，不用检查
            check(nodes.subList(checkpointIndex + 1, endIndex).none { node ->
                node.currentMessage.getTools().any { it.isPending || it.canResumeExecution }
            }) { context.getString(R.string.chat_page_compress_handle_pending_tools) }

            // 必须和对话用同一个模型、同一套请求组装，才能命中提示词缓存
            val turn = requestFactory.createContext(settingsStore.awaitLoaded(), conversation)
            // 预算可行性/摘要输出预算同样在装配后统一计算；手动压缩只需要正文
            val summary = requestFactory.generateCompressionSummary(
                turn = turn,
                messages = conversation.currentMessages.take(endIndex),
                compressPrompt = buildCompressPrompt(
                    additionalPrompt = additionalPrompt,
                    locale = Locale.getDefault().displayName,
                ),
            ).summary

            // 原消息原样保留，摘要插在压缩范围的最后一条消息之后。摘要生成期间对话可能已变化，
            // 因此按节点定位插入到最新状态。
            session.update { latest ->
                check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }
                val updated = latest.insertContextCheckpoint(
                    afterNodeId = nodes[endIndex - 1].id,
                    summary = summary,
                ) ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
                updated.copy(chatSuggestions = emptyList())
            }
            session.flush()
        }
    }

    // ---- 聊天页配置 ----

    /**
     * 聊天页对助手的修改：会话持有的字段只改当前会话，其余写回助手设置。
     *
     * @param update 接收会话视角下的助手，返回修改后的助手
     */
    suspend fun updateChatAssistant(conversationId: Uuid, update: (Assistant) -> Assistant) {
        withLoadedSession(conversationId) { session ->
            val settings = settingsStore.awaitLoaded()
            val conversation = session.state.value
            val stored = settings.getStoredAssistantOf(conversation)
            val updated = update(settings.getAssistantOf(conversation))
            val assistant = updated.withoutConversationFields(conversation, stored)
            session.updateMetadata(
                transform = { it.withAssistantUpdate(updated, settings) },
                persist = conversationRepo::updateConversationConfig,
            )
            if (assistant != stored) {
                settingsStore.update { latest ->
                    latest.copy(assistants = latest.assistants.map { if (it.id == assistant.id) assistant else it })
                }
            }
        }
    }

    /**
     * 切换搜索方式，传 null 的一项保持不变。
     * 模型内置搜索在会话开始前是模型自身的开关，开始后固定在会话上。
     */
    suspend fun updateChatSearch(
        conversationId: Uuid,
        enableWebSearch: Boolean? = null,
        builtInSearch: Boolean? = null,
    ) {
        withLoadedSession(conversationId) { session ->
            val conversation = session.state.value
            if (conversation.config != null) {
                session.updateMetadata(
                    transform = {
                        it.copy(
                            config = it.config?.let { config ->
                                config.copy(
                                    enableWebSearch = enableWebSearch ?: config.enableWebSearch,
                                    builtInSearch = builtInSearch ?: config.builtInSearch,
                                )
                            }
                        )
                    },
                    persist = conversationRepo::updateConversationConfig,
                )
                return@withLoadedSession
            }
            settingsStore.update { settings ->
                val assistant = settings.getAssistantOf(conversation)
                val model = settings.getChatModelOf(conversation)
                settings.copy(
                    assistants = if (enableWebSearch == null) {
                        settings.assistants
                    } else {
                        settings.assistants.map {
                            if (it.id == assistant.id) it.copy(enableWebSearch = enableWebSearch) else it
                        }
                    },
                    providers = if (builtInSearch == null || model == null) {
                        settings.providers
                    } else {
                        settings.providers.map { provider ->
                            provider.editModel(
                                model.copy(
                                    tools = if (builtInSearch) {
                                        model.tools + BuiltInTools.Search
                                    } else {
                                        model.tools - BuiltInTools.Search
                                    }
                                )
                            )
                        }
                    },
                )
            }
        }
    }

    // 工作目录和配置一起按列落库；还没落过库的新会话先留在内存里，发出第一条消息时一起写入
    suspend fun updateWorkspaceCwd(conversationId: Uuid, workspaceCwd: String?) {
        withLoadedSession(conversationId) { session ->
            session.updateMetadata(
                transform = { it.copy(workspaceCwd = workspaceCwd) },
                persist = conversationRepo::updateConversationConfig,
            )
        }
    }

    suspend fun updateConversationSystemPrompt(conversationId: Uuid, systemPrompt: String?) {
        updateColumn(
            conversationId = conversationId,
            transform = { it.copy(customSystemPrompt = systemPrompt) },
            persist = { conversationRepo.updateCustomSystemPrompt(conversationId, systemPrompt) },
        )
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        updateColumn(
            conversationId = conversationId,
            transform = { it.copy(title = title) },
            persist = { conversationRepo.updateTitle(conversationId, title) },
        )
    }

    /**
     * 用户在聊天页改的单列字段：已落库的会话只写这一列，生成中也不会顺带写入还没生成完的回复；
     * 还没落过库的新会话整份落库，留下记录（没有内容的新会话仍然不写）。
     */
    private suspend fun updateColumn(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        persist: suspend () -> Unit,
    ) {
        withLoadedSession(conversationId) { session ->
            session.updateMetadata(transform, persist = { persist() })
            if (!session.existsInDatabase) session.flush()
        }
    }

    // ---- 会话元数据 ----

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        withLoadedSession(conversationId, createIfMissing = false) { session ->
            session.updateMetadata(transform, persist)
        }
    }

    suspend fun toggleConversationPinned(conversationId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            transform = { it.copy(isPinned = !it.isPinned) },
            persist = { conversationRepo.updatePinStatus(conversationId, it.isPinned) },
        )
    }

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            // 文件夹属于助手，移动后清除原助手的文件夹归属。
            transform = { it.copy(assistantId = assistantId, folderId = null) },
            persist = { conversationRepo.updateConversationAssistant(conversationId, it.assistantId) },
        )
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     * 有 session 的会话先改内存，免得之后整份落库时写回旧的文件夹；没有的只改这一列，不必加载整个会话。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessionManager.get(conversationId) == null) {
            conversationRepo.updateConversationFolderId(conversationId, folderId)
            // 写库期间页面可能刚好打开了这个会话，加载时读到的可能是旧值，再同步一次内存
            if (sessionManager.get(conversationId) == null) return
        }
        updateConversationMetadata(
            conversationId = conversationId,
            transform = { it.copy(folderId = folderId) },
            persist = { conversationRepo.updateConversationFolderId(conversationId, folderId) },
        )
    }

    /**
     * 还没开始的新会话跟随抽屉里选中的文件夹，首次落库时归入；已经落库的会话不受影响，移动走 [moveConversationToFolder]。
     */
    suspend fun updateDraftConversationFolder(conversationId: Uuid, folderId: Uuid?) {
        val folder = folderId?.let { folderRepository.getFolderById(it) }
        withLoadedSession(conversationId) { session ->
            session.updateDraft { latest ->
                // 文件夹属于助手：已被删除或不属于会话的助手时不归入
                val target = folder?.takeIf { it.assistantId == latest.assistantId }?.id
                if (latest.folderId == target) latest else latest.copy(folderId = target)
            }
        }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionManager.snapshot().any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     * 已加载的会话先改内存再删库，之后落库不会写回已删除的文件夹。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessionManager.snapshot()
            .filter { it.isInitialized && it.state.value.folderId == folderId }
            .forEach { session ->
                // 这一列由下面删文件夹时统一清空，这里只改内存，不让会话因此变成有未落库的修改
                session.updateMetadata(
                    transform = { if (it.folderId == folderId) it.copy(folderId = null) else it },
                    persist = {},
                )
            }
        folderRepository.deleteFolder(folderId)
    }

    // ---- 删除会话 ----

    /**
     * 删除会话：停掉生成后在会话的写锁内删库，内存换成一个还没落过库的新会话。
     * 还停在这个会话上的页面看到的是空会话，之后再发消息会新建记录，不会写回已删除的那条。
     */
    suspend fun deleteConversation(conversationId: Uuid) {
        val session = sessionManager.getOrCreate(conversationId)
        discardQueuedMessages(session)
        session.replace(newConversation(conversationId), existsInDatabase = false) { previous ->
            conversationRepo.getConversationById(conversationId)?.let { conversationRepo.deleteConversation(it) }
            // 该会话自己就是一队的 lead：先级联删整支队，别留下 lead_conversation_id 悬空的孤儿队
            runCatching {
                agentTeamRepository.ownedTeam(conversationId)?.let { agentTeamRepository.deleteTeamCascade(it.id) }
            }.onFailure { Log.w(TAG, "delete team cascade for $conversationId failed", it) }
            // 队友会话被删时同步清名册行，避免团队视图里留下打不开的孤儿成员；
            // 先记下这一行，撤销删除时放回去，否则撤销后会变成主列表里的野会话
            agentTeamRepository.getMemberByConversation(conversationId)?.let { deletedTeamMembers[conversationId] = it }
            agentTeamRepository.deleteMemberByConversation(conversationId)
            // 数据库那份不知道只在内存里出现过的附件
            previous?.let { filesManager.deleteChatFiles(it.files) }
        }
        sessionManager.removeIfIdle(session)
    }

    /** 撤销删除：写回删除前的会话，还停在这个会话上的页面也一并换回 */
    suspend fun restoreConversation(conversation: Conversation) {
        val session = sessionManager.getOrCreate(conversation.id)
        session.replace(conversation, existsInDatabase = true) {
            conversationStore.write(previous = null, current = conversation)
        }
        deletedTeamMembers.remove(conversation.id)?.let { agentTeamRepository.upsertMember(it) }
        sessionManager.removeIfIdle(session)
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        // 队友会话属于团队，不进「删除全部会话」的范围（列表查询已排除，这里补内存里还活着的）
        val persisted = conversationRepo.getConversationsOfAssistant(assistantId).first().map { it.id }
        val loaded = mutableListOf<Uuid>()
        for (session in sessionManager.snapshot()) {
            if (!session.isInitialized || session.state.value.assistantId != assistantId) continue
            if (agentTeamRepository.getMemberByConversation(session.id) != null) continue
            loaded += session.id
        }
        (persisted + loaded).distinct().forEach { deleteConversation(it) }
    }

    private fun discardQueuedMessages(session: ConversationSession) {
        session.messageQueue.pause()
        session.messageQueue.state.value.messages.forEach { queued ->
            session.messageQueue.remove(queued.id)?.let(::cleanupQueuedAttachments)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            sessionManager.withSession<Unit>(conversationId) { session ->
                try {
                    ensureInitialized(session)
                    val settings = settingsStore.awaitLoaded()

                    val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text }
                        .trim()

                    if (messageText.isBlank()) return@withSession

                    // Set loading state for translation
                    val loadingText = context.getString(R.string.translating)
                    session.update { it.withTranslation(message.id, loadingText) }

                    translationHandler.translateText(
                        settings = settings,
                        sourceText = messageText,
                        targetLanguage = targetLanguage
                    ) { translatedText ->
                        // Update translation field in real-time
                        session.update { it.withTranslation(message.id, translatedText) }
                    }.collect { /* Final translation already handled in onStreamUpdate */ }

                    session.flush()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Clear translation field on error
                    if (session.isInitialized) session.update { it.withTranslation(message.id, null) }
                    addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
                }
            }
        }
    }

    suspend fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        mutate(conversationId) { it.withTranslation(messageId, null) }
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val settings = settingsStore.awaitLoaded()

        mutate(conversationId) { conversation ->
            conversation.editMessage(messageId, parts.replaceUserRegexes(settings.getStoredAssistantOf(conversation)))
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val source = withLoadedSession(conversationId) { it.state.value }
        val existingTitles = conversationRepo.getTitlesOfAssistant(source.assistantId)
        val fork = source.fork(messageId, existingTitles, copyFile = ::copyChatFile)
            ?: throw NotFoundException("Message not found")
        // 新会话直接落库，打开时再从数据库加载
        val forkConversation = conversationStore.prepareWrite()(fork)
        conversationRepo.insertConversation(forkConversation)
        memoryConsolidationScheduler.onConversationForked(forkConversation)
        return forkConversation
    }

    private fun copyChatFile(url: String): String =
        filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()?.toString() ?: url

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        mutate(conversationId) { conversation ->
            val targetNode = conversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")
            if (selectIndex !in targetNode.messages.indices) {
                throw BadRequestException("Invalid selectIndex")
            }
            if (targetNode.selectIndex == selectIndex) return@mutate conversation
            conversation.selectBranch(nodeId, selectIndex)
        }
    }

    // 收藏记录在单独的表里，节点上的标记只用于显示，不需要落库
    suspend fun setNodeFavorite(conversationId: Uuid, nodeId: Uuid, favorite: Boolean) {
        val session = sessionManager.get(conversationId)?.takeIf { it.isInitialized } ?: return
        session.updateMetadata(transform = { it.withNodeFavorite(nodeId, favorite) }, persist = {})
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        mutate(conversationId) { conversation ->
            conversation.deleteMessage(messageId)
                ?: if (failIfMissing) throw NotFoundException("Message not found") else conversation
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessionManager.get(conversationId) ?: return
        val jobs = synchronized(session) {
            session.messageQueue.pause()
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        if (session.isInitialized) finishInterruptedPendingTools(session)
    }
}
