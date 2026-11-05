package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.prompts.resolveChatSystemPrompt
import me.rerere.rikkahub.data.ai.prompts.buildCompressPrompt
import java.util.Locale
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.TeamMember
import me.rerere.rikkahub.data.model.TeamNestingRules
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.AgentTeamRepository
import me.rerere.rikkahub.runtime.OperationInbox
import me.rerere.rikkahub.data.ai.tools.RECEIVE_EVENTS
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ChatRequestFactory"

/**
 * 一轮对话的请求上下文，按会话固定的配置解析一次。
 *
 * 正常对话和压缩用同一份上下文组装请求，系统提示词、工具定义、参数和 sessionId 都相同，
 * 压缩请求的前缀才能和上一轮对话一致，命中提示词缓存。
 */
class ChatTurnContext(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val conversationId: Uuid,
    val conversationSystemPrompt: String?,
    val workspaceCwd: String?,
    val memories: List<MemoryFile>,
    val tools: List<Tool>,
    /** 本会话是团队成员（子代理）会话时，对应名册记录；普通与领队会话为 null */
    val teamMember: TeamMember? = null,
    /** 成员还能再建一层子队（maxDepth 门禁通过）；只给提示词说明边界，非成员恒 false */
    val teamCanNest: Boolean = false,
) {
    internal val deferredCompression = DeferredTaskCompression()
}

/** 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    private val agentTeamRepository: AgentTeamRepository,
    templateTransformer: TemplateTransformer,
    workspaceRepository: WorkspaceRepository,
    private val operationInbox: OperationInbox,
) {
    private val inputTransformers: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        templateTransformer,
        WorkspaceReminderTransformer(workspaceRepository),
    )

    private val outputTransformers: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /** 解析本轮的助手、模型、记忆和工具；MCP 服务名不合法时抛出 InvalidMcpServerNamesException */
    suspend fun createContext(settings: Settings, conversation: Conversation): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val assistant = settings.getAssistantOf(conversation)
        val model = settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val teamMember = agentTeamRepository.getMemberByConversation(conversation.id)
        // 与工具层同一口径：成员所属队的 depth 再加一层仍在 maxDepth 之内才算可嵌套
        val teamCanNest = teamMember
            ?.let { member -> agentTeamRepository.getTeam(member.teamId) }
            ?.let { team ->
                TeamNestingRules.canNest(
                    parentDepth = team.depth,
                    maxDepth = TeamNestingRules.normalizeMaxDepth(settings.agentTeamMaxDepth),
                )
            } ?: false
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList(),
            tools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                conversationId = conversation.id,
                workspaceCwd = conversation.workspaceCwd,
            ),
            // 按名册记录判定成员身份，不依赖团队开关、标题或工具是否存在；旧成员后续轮同样覆盖
            teamMember = teamMember,
            teamCanNest = teamCanNest,
        )
    }

    /**
     * 本轮交给 runAgent 的请求和钩子。[messages] 的最后一条是助手消息时从它继续，否则新建一条助手消息。
     * [processingStatus] 用来显示 OCR 这类输入转换的进度。
     */
    fun createAgent(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?>,
        onStreamInterrupted: suspend (UIMessage) -> Unit = {},
    ): Pair<AgentRequest, AgentHooks> {
        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId),
            ),
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools,
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy(),
        )
        val hooks = object : AgentHooks {
            override suspend fun interruptedStep(message: UIMessage) = onStreamInterrupted(message)

            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                Log.i(TAG, "prepareStep: ${turn.model.id}")
                val step = buildStep(turn, messages, processingStatus)
                if (turn.settings.autoCompressionEnabled) {
                    // 生成中只观察阈值，不规划覆盖范围：覆盖到哪里要等任务正常完成后决定
                    turn.deferredCompression.observeThreshold(thresholdReachedForTurn(turn, messages, step))
                }
                // Never summarize or replace history while this task is still executing.
                return step
            }

            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }
        }
        return request to hooks
    }

    /** Recheck the final task context and compress only after normal completion, never before a step. */
    suspend fun compressAfterTask(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        completed: Boolean,
        processingStatus: MutableStateFlow<String?>,
        // 覆盖范围规划完成后、付摘要请求之前的一致性校验（编辑/换分支时早失败，不白花一次请求）
        verifySource: suspend (prefix: List<UIMessage>) -> Unit = {},
        onAutoCheckpoint: suspend (List<UIMessage>, UIMessage) -> Unit,
    ) {
        if (!turn.settings.autoCompressionEnabled) return
        val keepRecent = turn.settings.autoCompressionKeepRecentMessages
            .coerceIn(0, MAX_AUTO_COMPRESSION_KEEP_RECENT)
        var end: Int? = null
        // 覆盖段之外仍保留的固定开销（系统规则 + 工具定义），缩减保留区前先用它估算
        var planFixedTokens = 0L
        try {
            turn.deferredCompression.afterTask(
                completed = completed,
                finalThresholdReached = {
                    val step = buildStep(turn, messages, processingStatus)
                    end = compressionEnd(turn, messages, step, keepRecent)
                    planFixedTokens = estimateContextTokens(step.messages.filter { it.isSynthetic }) +
                        estimateToolTokens(step.params.tools)
                    end != null
                },
                compress = {
                    var covered = checkNotNull(end)
                    val compressPrompt = buildCompressPrompt(
                        additionalPrompt = compressionAdditionalPrompt(),
                        locale = Locale.getDefault().displayName,
                        template = turn.settings.compressionPrompt,
                    )
                    val budget = automaticCompressionBudget(
                        modelContext = turn.model.contextLength,
                        thresholdPercent = turn.settings.autoCompressionThresholdPercent,
                        outputReserve = turn.assistant.maxTokens ?: 4096,
                    )
                    // 保留区同样占上下文：压缩后仍贴着触发线就多压几条，宁可少留也不要白花一次请求
                    val checkpointCap = compressionSummaryMaxTokens(turn.model.contextLength) +
                        preservedUserQuoteBudgetTokens(turn.model.contextLength) +
                        estimateTextTokens(compressPrompt)
                    while (
                        covered < messages.size &&
                        !compressionFitsBudget(
                            fixedTokens = planFixedTokens,
                            replacementTokens = checkpointCap + estimateContextTokens(messages.drop(covered)),
                            budget = budget,
                        )
                    ) {
                        covered++
                    }
                    val prefix = messages.take(covered)
                    // 先确认来源仍是本轮那个分支与内容，再花一次摘要请求
                    verifySource(prefix)
                    processingStatus.value = "任务已完成，正在自动压缩上下文…"
                    // 预算可行性、摘要输出预算与生成后长度验收都在 generateCompressionSummary 内部
                    // 按装配后的实际输入计算一次，避免重复读/重复转换
                    val compression = try {
                        generateCompressionSummary(
                            turn = turn,
                            messages = prefix,
                            // 覆盖范围不能被普通条数限制再裁一次
                            applyContextLimit = false,
                            compressPrompt = compressPrompt,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 分层压缩仍失败时不阻塞会话：退化为确定性检查点，说明裁剪范围与近期摘录
                        Log.w(TAG, "compression summary unavailable, falling back to a trimmed checkpoint", e)
                        fallbackCompression(prefix, e.message)
                    }
                    val summary = compression.summary
                    val fixedTokens = compression.fixedTokens
                    val quotes = preservedUserQuotes(
                        messages = prefix,
                        budgetTokens = preservedUserQuoteBudgetTokens(turn.model.contextLength),
                    )
                    val checkpointText = buildCheckpointText(summary, quotes)
                    val replacement = estimateContextTokens(
                        listOf(UIMessage.user(checkpointText).copy(isContextCheckpoint = true))
                    )
                    // 检查点之后保留的原文也计入这次压缩的结果
                    val retained = estimateContextTokens(messages.drop(covered))
                    // 摘要过长、或保留区太多导致压缩后仍贴着触发线时不安装检查点：原文和旧检查点都保留
                    check(compressionFitsBudget(fixedTokens, replacement + retained, budget)) {
                        "压缩后的上下文没有明显下降（估算 ${fixedTokens + replacement + retained} / 触发线 $budget），本次不保存摘要"
                    }
                    onAutoCheckpoint(prefix, UIMessage.user(checkpointText).copy(isContextCheckpoint = true))
                },
            )
        } finally {
            processingStatus.value = null
        }
    }

    /**
     * 自动压缩给模型的附加要求；七节结构和失败/取消/待办状态要求由 [buildCompressPrompt] 统一提供。
     *
     * “本轮已正常完成”只在这里出现：只有自动压缩的 Completed 路径会走到它，
     * 手动压缩（compressConversation）传的是用户自己的附加说明，不会继承这个前提。
     */
    private fun compressionAdditionalPrompt(): String =
        "本次自动压缩的前提：上一回合已经正常结束（Completed）。记录累计仍有效的已决事项、关键证据（路径/命令/数值）和未授权的待办；" +
            "不要把已完成的事写成需要再执行一遍的指令，也不要重复执行任何工具。最近用户消息的原话会单独附在摘要后，不要逐字复制。"

    /**
     * 阈值观察与覆盖规划共用同一组参数；观察只看是否越线，规划在任务完成后才调用。
     * 两者都按装配后的实际请求计量，并带上这一步的工具定义估算（工具不占消息，但占上下文）。
     */
    private fun thresholdReachedForTurn(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        step: StepRequest,
    ): Boolean = compressionThresholdReached(
        messages = messages,
        requestMessages = step.messages,
        modelContext = turn.model.contextLength,
        thresholdPercent = turn.settings.autoCompressionThresholdPercent,
        outputReserve = turn.assistant.maxTokens ?: 4096,
        toolTokens = estimateToolTokens(step.params.tools),
    )

    private fun compressionEnd(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        step: StepRequest,
        keepRecentMessages: Int = 0,
    ): Int? = completedTurnCompressionEnd(
        messages = messages,
        requestMessages = step.messages,
        modelContext = turn.model.contextLength,
        thresholdPercent = turn.settings.autoCompressionThresholdPercent,
        outputReserve = turn.assistant.maxTokens ?: 4096,
        toolTokens = estimateToolTokens(step.params.tools),
        keepRecentMessages = keepRecentMessages,
    )

    /**
     * 生成压缩摘要。
     *
     * 请求按正常对话组装（系统提示词、工具定义、参数和 sessionId 都相同），只在末尾追加压缩指令，
     * 前缀与上一轮对话一致，可以命中提示词缓存，模型也能看到工具调用和结果。
     * 工具定义必须保留：它属于请求前缀，去掉或改动 tool_choice 都会让缓存失效。
     *
     * 返回 internal 的 [CompressionSummaryResult]，public API 不能暴露 internal 类型，所以本函数为 internal；
     * 调用方（自动压缩与手动压缩）都在 app 模块内。
     */
    internal suspend fun generateCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
        applyContextLimit: Boolean = true,
    ): CompressionSummaryResult {
        // 1) 原样装得下就一次摘要，能命中提示词缓存
        summarizeOnce(turn, messages, compressPrompt, applyContextLimit)?.let { return it }
        // 2) 装不下先剪枝：工具输出头尾、推理只留开头、附件换占位符
        val pruned = pruneForCompression(messages)
        if (pruned != messages) {
            summarizeOnce(turn, pruned, compressPrompt, applyContextLimit)?.let { return it }
        }
        // 3) 仍装不下就分块摘要，再把分段摘要合并（map-reduce）
        return summarizeInChunks(turn, pruned, compressPrompt)
    }

    /** 单次摘要；预算装不下返回 null，由上一层升级处理。 */
    private suspend fun summarizeOnce(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
        applyContextLimit: Boolean = true,
    ): CompressionSummaryResult? {
        // 装配完成后的实际输入：转换器已经跑过，条数限制按 applyContextLimit 决定
        val (requestMessages, params) = buildStep(turn, messages, applyContextLimit = applyContextLimit)
        val toolsTokens = estimateToolTokens(params.tools)
        val requestTokens = estimateContextTokens(requestMessages) + toolsTokens
        // 装不下就直接升级到下一层，削覆盖或发注定失败的请求都不做
        val summaryBudget = compressionSummaryBudget(
            modelContext = turn.model.contextLength,
            fixedTokens = requestTokens,
            coveredTokens = 0,
            instructionTokens = estimateTextTokens(compressPrompt),
        ) ?: return null
        // 压缩指令不经过输入转换器，模板转换器会把其中的花括号当成模板语法
        val result = withRetry(
            policy = turn.settings.retryPolicy(),
            onRetry = { Log.w(TAG, "Compression request failed, retrying in ${it.delay}", it.cause) },
        ) {
            usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.COMPRESSION, turn.assistant.id, turn.conversationId),
            ).generateText(
                providerSetting = turn.providerSetting,
                messages = requestMessages + UIMessage.user(compressPrompt),
                // 摘要用自己的输出预算：不继承聊天的输出上限（结构化摘要会被截断），也不再用 null 无界输出；
                // max_tokens 不属于请求前缀，改了不影响缓存
                params = params.copy(maxTokens = summaryBudget.toInt()),
            )
        }
        // 明确的截断/不完整/安全拒绝状态不保存摘要；null（provider 没给状态）保留兼容，但不据此声称完整
        check(!isIncompleteFinishReason(result.finishReason)) {
            "摘要生成未正常结束（finishReason=${result.finishReason}），本次不保存压缩"
        }
        val summary = result.message.compressionSummaryOrNull()
            ?: error(context.getString(R.string.chat_page_compress_tool_call_response))
        val summaryText = summary.ifBlank { error("Failed to generate compressed summary") }
        // provider 没遵守 max_tokens 或没给 finishReason 时，用同一估算口径再做一次长度验收；
        // 超预算就报维护失败，不截断摘要来“凑”进预算
        val summaryTokens = estimateTextTokens(summaryText)
        check(summaryTokens <= summaryBudget) {
            "摘要超出输出预算（估算 $summaryTokens / $summaryBudget），本次不保存压缩"
        }
        // 压缩后仍保留的固定开销（系统规则 + 工具定义），供调用方做触发线下余量检查
        val fixedTokens = estimateContextTokens(requestMessages.filter { it.isSynthetic }) + toolsTokens
        return CompressionSummaryResult(
            summary = summaryText,
            requestTokens = requestTokens,
            fixedTokens = fixedTokens,
            summaryMaxTokens = summaryBudget,
        )
    }

    /** 摘要彻底不可用时的兜底：不生成模型内容，只如实说明裁剪范围与最近几条摘录。 */
    private fun fallbackCompression(
        messages: List<UIMessage>,
        reason: String?,
    ): CompressionSummaryResult = CompressionSummaryResult(
        summary = fallbackCompactionText(messages, reason),
        requestTokens = estimateContextTokens(messages),
        fixedTokens = 0,
        summaryMaxTokens = 0,
    )

    /**
     * 分块摘要：按窗口 55% 切块，逐块生成摘要，再把分段摘要当输入做一次合并。
     * 合并仍装不下时对分段摘要继续分块，最多三层；全部失败才抛给调用方降级。
     */
    private suspend fun summarizeInChunks(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
    ): CompressionSummaryResult {
        val window = turn.model.contextLength.coerceAtLeast(1024).toLong()
        val chunkTokens = (window * 55 / 100).coerceAtLeast(1024)
        var level = messages
        repeat(3) {
            val chunks = splitForCompression(level, chunkTokens)
            if (chunks.size > 1) {
                val partials = chunks.mapNotNull { chunk ->
                    summarizeOnce(turn, chunk, compressPrompt, applyContextLimit = false)?.summary
                }
                if (partials.isNotEmpty()) {
                    val merged = partials.mapIndexed { index, text ->
                        UIMessage.user("Part ${index + 1} of ${partials.size}:\n" + text)
                    }
                    val mergePrompt = compressPrompt +
                        "\n\n以上是同一段历史的分段摘要。请把它们合并成一份完整摘要，" +
                        "保留七节结构、去掉重复，不要遗漏任何分段的要点。"
                    summarizeOnce(turn, merged, mergePrompt, applyContextLimit = false)?.let { return it }
                    level = merged
                }
            }
        }
        error("分块摘要未能合并进模型窗口（窗口 ${turn.model.contextLength}）")
    }

    /** 一步请求实际发出的消息和参数；正常对话和压缩共用，二者的请求前缀必须一致才能命中提示词缓存 */
    private suspend fun buildStep(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        applyContextLimit: Boolean = true,
    ): StepRequest {
        val assistant = turn.assistant
        val requestMessages = buildList {
            val system = buildString {
                // 团队成员用独立提示词；普通与领队会话仍按 allowConversationSystemPrompt 选择
                val effectiveSystemPrompt = resolveChatSystemPrompt(
                    assistant = assistant,
                    conversationSystemPrompt = turn.conversationSystemPrompt,
                    teamMember = turn.teamMember,
                    canNest = turn.teamCanNest,
                )
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(turn.memories))
                }
                // 工具prompt
                turn.tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(turn.model, messages))
                }
                // 有界注意提示：每步至多一行，只说明有未读，不催、不累计
                if (turn.tools.any { it.name == RECEIVE_EVENTS }) {
                    val unread = operationInbox.unreadCount(turn.conversationId)
                    if (unread > 0) {
                        appendLine()
                        append("$unread unread team event(s) are waiting. Call receive_events to consume them when you need them; do not poll.")
                    }
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            // limitContext(0) 表示不限制；压缩时按覆盖范围原样读入
            addAll(messages.limitContext(if (applyContextLimit) assistant.contextMessageLimit else 0))
        }.transforms(
            transformers = inputTransformers,
            context = context,
            model = turn.model,
            assistant = assistant,
            settings = turn.settings,
            processingStatus = processingStatus,
            workspaceCwd = turn.workspaceCwd,
        )
        val params = TextGenerationParams(
            model = turn.model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = turn.tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(turn.model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(turn.model.customBodies)
            },
            sessionId = turn.conversationId.toString(),
        )
        return StepRequest(requestMessages, params)
    }
}

/**
 * 取出压缩请求回复里的摘要正文。
 *
 * 指令要求不调用工具，模型仍可能发起调用。这时附带的文本只是"我先看看文件"之类的说明，
 * 当成摘要保存会让之后的请求丢掉全部历史，所以返回 null 交给调用方报错。
 * 压缩请求不经过输出转换器，正文开头的 <think> 思考内容也要在这里剥掉，否则会存进摘要。
 */
internal fun UIMessage.compressionSummaryOrNull(): String? {
    if (getTools().isNotEmpty()) return null
    return listOf(this)
        .transformThinkTags(now = Clock.System.now(), generationFinished = true)
        .single()
        .toText()
        .trim()
}

/**
 * 输出转换只处理这一步新生成的 part：之前步骤的内容已经处理过，再处理一遍会让正则这类改写叠加，
 * 也能让每一步开头的 <think> 都被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val transformed = transform(listOf(copy(parts = parts.drop(newPartsFrom)))).single()
    return transformed.copy(parts = parts.take(newPartsFrom) + transformed.parts)
}

/** 标题、建议、记忆整理这类后台请求的参数：只带模型自己的自定义请求头和请求体 */
internal fun backgroundTextGenerationParams(
    model: Model,
    sessionId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = sessionId.toString(),
)

internal fun Settings.retryPolicy() =
    if (networkSetting.enableAutoRetry) RetryPolicy.NETWORK else RetryPolicy.NONE
