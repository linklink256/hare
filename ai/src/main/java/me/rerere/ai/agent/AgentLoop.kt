package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.sum
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// 输出为空的工具会被当成没执行过，之后每一步都会再执行一次，所以换成占位文本
private const val EMPTY_TOOL_OUTPUT = "(no output)"

private class StreamChunkHandlingException(override val cause: Throwable) : RuntimeException(cause)

internal class AgentLoop(
    private val request: AgentRequest,
    private val hooks: AgentHooks,
    private val events: FlowCollector<AgentEvent>,
) {
    private val history: List<UIMessage>

    // 本轮的助手消息；新的一轮在第一次请求模型时才创建
    private var message: UIMessage?

    // 还没送达的插话：只在下一步请求末尾作为 user 消息出现一次
    private var pendingSteers: List<UIMessage> = emptyList()

    // 已送达的插话：之后放进历史前缀，不能再追加到尾部，否则模型每一步都看到同一条新 user 消息
    private var deliveredSteers: List<UIMessage> = emptyList()

    // 连续相同工具调用的步数：模型自己反复调同一个工具时兜底，别把步数预算烧光
    private var lastToolSignature: String? = null
    private var sameToolSteps = 0

    init {
        val last = request.messages.lastOrNull()
        if (last?.role == MessageRole.ASSISTANT) {
            history = request.messages.dropLast(1)
            message = last
        } else {
            history = request.messages
            message = null
        }
    }

    suspend fun run() {
        // 步数是同一 Run 的累计预算：始终从 initialSteps 起步，不因会话末条不是助手消息而归零
        var steps = request.initialSteps
        while (true) {
            val current = message
            // 收尾前再看一眼插话：有新插话就继续这一轮，不提前完成
            val fresh = hooks.drainSteer()
            if (fresh.isNotEmpty()) pendingSteers = pendingSteers + fresh
            if (current != null && current.getTools().any { !it.isExecuted }) {
                val batch = current.getTools().filter { !it.isExecuted }
                if (pendingSteers.isNotEmpty()) {
                    // 插话已在等：旧调用不再审批/执行，收口后直接让模型重新判断
                    supersedeRemainingTools(batch)
                    lastToolSignature = null
                    sameToolSteps = 0
                } else {
                    val pending = requestApprovals(current)
                    if (pending.isNotEmpty()) return finish(AgentFinishReason.AwaitingApproval(pending))
                    // 同一条消息里把同一个调用复制 N 份也算无进展：先收尾再执行，避免重复副作用
                    val duplicated = batch.groupingBy { "${it.toolName}:${it.input}" }.eachCount().values.maxOrNull() ?: 0
                    if (duplicated >= STALL_LIMIT) return finish(AgentFinishReason.Stalled)
                    val superseded = executeTools()
                    if (superseded) {
                        // 带插话的批不算无进展：清掉旧签名，别让 Stalled 抢先收尾
                        lastToolSignature = null
                        sameToolSteps = 0
                    } else {
                        // 这一批工具的名字与入参和上一步完全一样：算无进展，连续几次就收尾
                        val signature = batch.joinToString("\u0001") { "${it.toolName}:${it.input}" }
                        sameToolSteps = if (signature == lastToolSignature) sameToolSteps + 1 else 0
                        lastToolSignature = signature
                        if (sameToolSteps >= STALL_LIMIT) return finish(AgentFinishReason.Stalled)
                    }
                }
            } else if (current != null && steps > 0 && fresh.isEmpty()) {
                return finish(AgentFinishReason.Completed)
            }
            if (steps == request.maxSteps) return finish(AgentFinishReason.StepLimitReached)
            generateStep()
            steps++
        }
    }

    // 新的工具调用按定义判断是否需要审批，需要的标为 Pending；返回仍在等待审批的调用
    private suspend fun requestApprovals(current: UIMessage): Set<String> {
        val updated = current.copy(
            parts = current.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted &&
                    part.approvalState == ToolApprovalState.Auto &&
                    findTool(part.toolName)?.needsApproval?.invoke(part.inputAsJson()) == true
                ) {
                    part.copy(approvalState = ToolApprovalState.Pending)
                } else {
                    part
                }
            }
        )
        if (updated != current) update(updated)
        return updated.getTools().filter { it.isPending }.mapTo(LinkedHashSet()) { it.toolCallId }
    }

    private companion object {
        // 正常任务不会连续 6 步发同一批一模一样的工具调用
        const val STALL_LIMIT = 6
    }

    private suspend fun generateStep() {
        // 请求前最后再看一眼插话：缩小最后一个工具与这次请求之间的窗口
        val fresh = hooks.drainSteer()
        if (fresh.isNotEmpty()) pendingSteers = pendingSteers + fresh
        val current = message
        val messages = history + deliveredSteers + listOfNotNull(current) + pendingSteers
        val step = hooks.prepareStep(messages)
        val params = step.params.copy(tools = request.tools)
        val base = current ?: UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            modelId = params.model.id,
        )
        val generated = if (request.stream) {
            stream(step.messages, params, base)
        } else {
            generate(step.messages, params, base)
        }
        update(hooks.finishStep(generated, newPartsFrom = base.parts.size).copy(finishedAt = now()))
        // 这一步已经把插话带给模型；之后它只作为历史前缀，不再重复追加在末尾
        if (pendingSteers.isNotEmpty()) {
            deliveredSteers = deliveredSteers + pendingSteers
            pendingSteers = emptyList()
        }
    }

    private suspend fun stream(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 多轮工具调用共用同一条助手消息，消息上已有的 usage 和耗时来自之前的步骤。
        // 本步从清空的统计开始（merge 只适合同一次请求内的快照），再与之前的步骤累加。
        val stepStart = base.copy(usage = null, generationTimeMs = null)
        return try {
            withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
                // 每次重试都从本步开始时的消息重新合并，不会把重试的响应接在已经展示的半截回复后面；
                // 各次尝试共用同一个消息 id，调用方直接覆盖即可。
                val handler = StreamChunkHandler(params.model)
                var attempt = stepStart
                var firstChunk: TimeMark? = null
                val batch = StreamDeltaBatch()
                suspend fun accept(chunk: StreamChunk) {
                    try {
                        val mark = firstChunk ?: TimeSource.Monotonic.markNow().also { firstChunk = it }
                        attempt = handler.handle(listOf(attempt), chunk).single()
                        preview(attempt.withStepStats(base, mark.elapsedNow()), newPartsFrom = base.parts.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 合并失败或下游处理失败都不是网络故障，不能重放模型请求
                        throw StreamChunkHandlingException(e)
                    }
                }
                try {
                    request.provider.streamText(request.providerSetting, messages, params).collect { chunk ->
                        batch.offer(chunk, System.nanoTime()).forEach { accept(it) }
                    }
                } finally {
                    batch.drain()?.let { tail ->
                        if (currentCoroutineContext().isActive) {
                            accept(tail)
                        } else {
                            attempt = handler.handle(listOf(attempt), tail).single()
                            withContext(NonCancellable) {
                                hooks.interruptedStep(attempt.withStepStats(base, firstChunk?.elapsedNow() ?: Duration.ZERO))
                            }
                        }
                    }
                }
                attempt.withStepStats(base, firstChunk?.elapsedNow() ?: Duration.ZERO)
            }
        } catch (e: StreamChunkHandlingException) {
            throw e.cause
        }
    }

    private suspend fun generate(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 非流式拿不到首 token 时间，只能统计最后一次尝试的整个请求耗时
        var requestMark = TimeSource.Monotonic.markNow()
        val result = withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
            requestMark = TimeSource.Monotonic.markNow()
            request.provider.generateText(request.providerSetting, messages, params)
        }
        val requestTime = requestMark.elapsedNow()
        // 和流式一样只追加 part，不并进之前步骤的文本或推理里，否则 newPartsFrom 之后就不全是这一步的内容了。
        // 一次请求只有一份 usage；没有返回时保持为空，累加时沿用之前步骤的统计
        val generated = base.copy(
            parts = base.parts + result.message.parts,
            annotations = result.message.annotations.ifEmpty { base.annotations },
            modelId = params.model.id,
            usage = result.usage,
        ).finishReasoning().withStepStats(base, requestTime)
        preview(generated, newPartsFrom = base.parts.size)
        return generated
    }

    // 按调用顺序逐个执行；每个工具完成后检查插话，有插话就收口剩余调用并返回 true
    private suspend fun executeTools(): Boolean {
        val tools = checkNotNull(message).getTools().filter { !it.isExecuted }
        for ((index, tool) in tools.withIndex()) {
            // 只给真正运行的工具计时；开始时先写一次，界面才能在执行中实时显示耗时
            val runs = tool.approvalState == ToolApprovalState.Auto || tool.approvalState == ToolApprovalState.Approved
            if (runs) updateTool(tool.toolCallId) { it.copy(startedAt = Clock.System.now(), finishedAt = null) }
            // 副作用检查点：只给真正运行的工具记；hook 失败不能拖垮工具执行，取消仍要向上传播
            if (runs) runCatching { hooks.onToolStarted(tool.toolCallId, tool.toolName, tool.input) }
                .onFailure { if (it is CancellationException) throw it }
            val output = execute(tool)
            if (runs) runCatching {
                hooks.onToolFinished(
                    tool.toolCallId,
                    output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
                    null,
                )
            }.onFailure { if (it is CancellationException) throw it }
            updateTool(tool.toolCallId) {
                it.copy(output = output, finishedAt = if (runs) Clock.System.now() else it.finishedAt)
            }
            // 工具期间到达的插话：剩余调用不再盲跑，补明确结果后由下一次请求重新评估
            val fresh = hooks.drainSteer()
            if (fresh.isNotEmpty()) {
                pendingSteers = pendingSteers + fresh
                supersedeRemainingTools(tools.drop(index + 1))
                return true
            }
        }
        return false
    }

    // 被插话取代的调用不执行，但必须补 output，避免留下没有结果的 tool_call
    private suspend fun supersedeRemainingTools(tools: List<UIMessagePart.Tool>) {
        for (tool in tools) {
            updateTool(tool.toolCallId) { it.copy(output = supersededOutput()) }
        }
    }

    private suspend fun updateTool(toolCallId: String, transform: (UIMessagePart.Tool) -> UIMessagePart.Tool) {
        val current = checkNotNull(message)
        update(
            current.copy(
                parts = current.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) transform(part) else part
                }
            )
        )
    }

    private suspend fun execute(tool: UIMessagePart.Tool): List<UIMessagePart> =
        when (val state = tool.approvalState) {
            is ToolApprovalState.Denied -> errorOutput(
                "Tool execution denied by user. Reason: ${state.reason.ifBlank { "No reason provided" }}"
            )

            is ToolApprovalState.Answered -> listOf(UIMessagePart.Text(state.answer))
            ToolApprovalState.Auto, ToolApprovalState.Approved -> runTool(tool)
            ToolApprovalState.Pending -> error("Tool ${tool.toolCallId} is still waiting for approval")
        }

    private suspend fun runTool(tool: UIMessagePart.Tool): List<UIMessagePart> = try {
        // 旧历史里可能留下已下线的工具调用（如 wait_agent）：给模型一个错误结果，不要让整轮崩掉
        val definition = findTool(tool.toolName)
            ?: return errorOutput("Tool ${tool.toolName} is not available in this conversation; do not call it again.")
        val args = runCatching { json.parseToJsonElement(tool.input.ifBlank { "{}" }) }
            .getOrElse { error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}") }
        definition.execute(args).ifEmpty { listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)) }
    } catch (e: CancellationException) {
        // 取消必须向上传播，否则停止生成会被误报为工具执行错误
        throw e
    } catch (e: Throwable) {
        errorOutput("[${e.javaClass.name}] ${e.message}\n${e.stackTraceToString()}")
    }

    private fun findTool(name: String) = request.tools.find { it.name == name }

    private suspend fun update(updated: UIMessage) {
        message = updated
        events.emit(AgentEvent.MessageUpdated(updated))
    }

    // 生成中途的内容只发给调用方，这一步结束前循环内部一直基于原始内容合并
    private suspend fun preview(generating: UIMessage, newPartsFrom: Int) {
        events.emit(AgentEvent.MessageUpdated(hooks.previewStep(generating, newPartsFrom)))
    }

    private suspend fun finish(reason: AgentFinishReason) {
        events.emit(AgentEvent.Finished(checkNotNull(message), reason))
    }
}

// 插话到达时还没执行的调用：明确告诉模型没跑，让它带着新指令重新判断
private fun supersededOutput(): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        """{"status":"superseded","error":"A new user message arrived while the tool batch """ +
            """was running; this call was not executed. Re-evaluate before calling it again."}"""
    )
)

private fun errorOutput(error: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", error) }.toString()))

// 把本步的 usage 和生成耗时累加到之前步骤的统计上；接收者只带本步的 usage
private fun UIMessage.withStepStats(stepBase: UIMessage, stepTime: Duration): UIMessage = copy(
    usage = stepBase.usage.sum(usage),
    generationTimeMs = (stepBase.generationTimeMs ?: 0L) + stepTime.inWholeMilliseconds,
    // 上下文占用只看最后一次请求，usage 是多步累计值；本步没返回 usage 时沿用之前步骤的
    lastUsage = usage ?: stepBase.lastUsage,
    stepCount = (stepBase.stepCount ?: 0) + 1,
)

private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
