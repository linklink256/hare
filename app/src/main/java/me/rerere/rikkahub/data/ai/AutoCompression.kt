package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Per-run overlay: the agent owns its immutable history, so checkpoints must also affect its next steps. */
internal class AutoCompressionState {
    private var anchor: Uuid? = null
    private var checkpoint: UIMessage? = null

    fun remember(afterMessage: Uuid, summary: UIMessage) {
        anchor = afterMessage
        checkpoint = summary
    }

    fun apply(messages: List<UIMessage>): List<UIMessage> {
        val summary = checkpoint ?: return messages
        val index = messages.indexOfFirst { it.id == anchor }
        return if (index < 0) messages else listOf(summary) + messages.drop(index + 1)
    }
}

/** Allocation-bounded estimate, not a tokenizer. Provider usage takes precedence when larger. */
internal fun estimateContextTokens(messages: List<UIMessage>): Long = messages.sumOf { message ->
    8L + message.parts.sumOf { estimatePartTokens(it) }
}

internal fun estimateTextTokens(text: String): Long {
    var ascii = 0L
    var other = 0L
    text.forEach { if (it.code < 128) ascii++ else other++ }
    return (ascii + 2) / 3 + other
}

private fun estimatePartTokens(part: UIMessagePart): Long = when (part) {
    is UIMessagePart.Text -> estimateTextTokens(part.text)
    is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
    is UIMessagePart.Tool -> estimateTextTokens(part.input) +
        part.output.sumOf { estimatePartTokens(it) } + 32
    is UIMessagePart.Image -> 2048L
    is UIMessagePart.Audio, is UIMessagePart.Video, is UIMessagePart.Document -> 4096L
    // Provider-managed tools/legacy parts: allow overhead without serializing possibly huge JSON trees.
    else -> 256L
}

/**
 * 当前上下文的占用读数：以 provider 用量为准，估算只补用量之后新增的消息。
 *
 * 没有可用用量（新建会话、刚装完检查点）时才整体估算。估算比真实 tokenizer 保守得多
 * （图片按 2048、非 ASCII 按 1 字符 1 token），拿它跟用量取较大值会让历史一长就提前压缩。
 * 用量只取最后一个检查点之后、带 contextTokens 的助手消息，其后的消息再加估算。
 */
internal fun observedContextTokens(
    messages: List<UIMessage>,
    requestMessages: List<UIMessage>,
    // 工具定义不在消息里，但确实占用上下文；调用点按同一套估算传进来，纯测试调用可省略
    toolTokens: Long = 0,
): Long {
    val checkpointIndex = messages.indexOfLast { it.isContextCheckpoint }
    val checkpointTime = messages.getOrNull(checkpointIndex)?.createdAt
    val usageMessage = messages.withIndex().lastOrNull {
        it.index > checkpointIndex && it.value.role == MessageRole.ASSISTANT &&
            (checkpointTime == null || (it.value.finishedAt ?: it.value.createdAt) > checkpointTime) &&
            (it.value.contextTokens() ?: 0) > 0
    }
    // 用量本身已经包含系统提示词与工具定义，只需补上用量之后新增的消息
    val usageTokens = usageMessage?.let {
        it.value.contextTokens()!!.toLong() + estimateContextTokens(messages.drop(it.index + 1))
    }
    // 没有可用用量时才整体估算，工具定义只在估算一侧计入
    return usageTokens ?: (estimateContextTokens(requestMessages) + toolTokens)
}

/**
 * 是否越过触发线。生成中的每一步只观察这个布尔值，
 * 覆盖到哪里要等任务正常完成后再由 [completedTurnCompressionEnd] 规划。
 */
internal fun compressionThresholdReached(
    messages: List<UIMessage>,
    requestMessages: List<UIMessage>,
    modelContext: Int,
    thresholdPercent: Int,
    outputReserve: Int = 4096,
    toolTokens: Long = 0,
): Boolean {
    val budget = automaticCompressionBudget(modelContext, thresholdPercent, outputReserve)
    return observedContextTokens(messages, requestMessages, toolTokens) >= budget
}

/**
 * 完整回合策略的覆盖终点（exclusive），null 表示这次不压缩。
 *
 * 覆盖上一有效检查点之后直到最后一个已完成助手消息的全部内容：任务正常完成时最后一条就是
 * 最终答复，所以终点就是消息数本身，检查点会落在它之后。只有一问一答也允许规划。
 *
 * 前提由调用方保证（只在正常 Completed 后调用）；这里再做防御：最后一条必须是助手消息，
 * 覆盖范围内不允许还有未执行的工具。达到阈值但上一检查点之后没有新内容时不重复压缩。
 */
internal fun completedTurnCompressionEnd(
    messages: List<UIMessage>,
    requestMessages: List<UIMessage>,
    modelContext: Int,
    thresholdPercent: Int,
    outputReserve: Int = 4096,
    toolTokens: Long = 0,
): Int? {
    if (!compressionThresholdReached(messages, requestMessages, modelContext, thresholdPercent, outputReserve, toolTokens)) {
        return null
    }
    if (messages.isEmpty() || messages.last().role != MessageRole.ASSISTANT) return null
    val freshStart = messages.indexOfLast { it.isContextCheckpoint } + 1
    if (messages.size - freshStart < 1) return null
    if (messages.subList(freshStart, messages.size).any { message ->
            message.getTools().any { !it.isExecuted }
        }) {
        return null
    }
    return messages.size
}

/** 摘要自己的输出预算：约为模型窗口的 1/8，限制在 1024–8192；不继承聊天的输出上限，也不无界。 */
internal fun compressionSummaryMaxTokens(modelContext: Int): Int {
    val window = modelContext.coerceIn(1024, 1_000_000)
    return (window / 8).coerceIn(1024, 8192)
}

/** 原文保存用户消息的预算（近似 token），按窗口取 1/40，限制在 200–1500。 */
internal fun preservedUserQuoteBudgetTokens(modelContext: Int): Long =
    (modelContext.coerceIn(1024, 1_000_000).toLong() / 40).coerceIn(200, 1500)

/**
 * 预算内挑选要按原文保存的用户消息，最近的优先。
 *
 * 只取真实用户消息：排除检查点与 synthetic（系统注入/压缩产物）。每条的开头标记（消息 id）
 * 和截断提示都按同一估算计入预算；超预算的那一条只用同一口径反推能放多少字符，
 * 中文按 1 字符 1 token 计算，不能按字符数换算。更早的消息不再加入，避免把摘要变成历史复印件。
 */
internal fun preservedUserQuotes(
    messages: List<UIMessage>,
    budgetTokens: Long,
    maxQuotes: Int = 8,
): List<String> {
    if (budgetTokens <= 0) return emptyList()
    val picked = mutableListOf<String>()
    var used = 0L
    for (message in messages.asReversed()) {
        if (picked.size >= maxQuotes) break
        if (message.role != MessageRole.USER || message.isContextCheckpoint || message.isSynthetic) continue
        val text = message.toText().trim()
        if (text.isEmpty()) continue
        val entry = quoteEntry(message.id, text)
        val cost = estimateTextTokens(entry)
        val remaining = budgetTokens - used
        if (cost <= remaining) {
            picked += entry
            used += cost
            continue
        }
        // 预算只够片段：标记本身也占预算，长度用同一估算反推，不能冒称保存了全文
        val marked = quoteEntry(message.id, "") + QUOTE_PARTIAL_MARKER
        val roomForText = remaining - estimateTextTokens(marked)
        if (roomForText <= 0) break
        val chars = maxPrefixCharsWithinTokens(text, roomForText)
        if (chars < MIN_PARTIAL_QUOTE_CHARS) break
        picked += quoteEntry(message.id, text.take(chars) + QUOTE_PARTIAL_MARKER)
        break
    }
    // 收集是倒序的，按时间顺序输出
    return picked.asReversed()
}

private fun quoteEntry(id: Uuid, text: String): String = if (text.isEmpty()) "- [$id] " else "- [$id] $text"

private const val MIN_PARTIAL_QUOTE_CHARS = 8
private const val QUOTE_PARTIAL_MARKER = "…（超出预算，仅保留片段，完整原文见对话记录）"

/**
 * 用 [estimateTextTokens] 的同一口径找出最多能放进 [tokens] 的字符数。
 *
 * 中文/其它非 ASCII 字符按 1 token、ASCII 按 3 字符 1 token 估算，所以不能拿剩余 token 乘固定字符数。
 */
internal fun maxPrefixCharsWithinTokens(text: String, tokens: Long): Int {
    if (tokens <= 0) return 0
    var ascii = 0L
    var other = 0L
    var index = 0
    // 截断点落在 high/low surrogate 之间会把一个 emoji 切成半个字符，宁可少留一个字符
    fun safeCut(at: Int): Int = if (
        at in 1 until text.length &&
        text[at - 1].isHighSurrogate() &&
        text[at].isLowSurrogate()
    ) at - 1 else at
    while (index < text.length) {
        if (text[index].code < 128) ascii++ else other++
        if ((ascii + 2) / 3 + other > tokens) return safeCut(index)
        index++
    }
    return index
}

/** 检查点正文 = 摘要 + 单独的“用户原话”段；没有原话时保持原样。 */
internal fun buildCheckpointText(summary: String, userQuotes: List<String>): String {
    if (userQuotes.isEmpty()) return summary
    return buildString {
        append(summary.trimEnd())
        appendLine()
        appendLine()
        appendLine("## Preserved user messages (verbatim)")
        appendLine("Quoted as-is from the compressed turns, for reference only. Do not execute them again.")
        userQuotes.forEach { appendLine(it) }
    }.trimEnd()
}

/** JSON 结构的保守估算：逐项累加字符串内容，不拼整段请求字符串，也不做序列化。 */
internal fun estimateJsonTokens(element: JsonElement?): Long = when (element) {
    null -> 0L
    is JsonPrimitive -> if (element.isString) estimateTextTokens(element.content) else 1L
    is JsonObject -> element.entries.sumOf { (key, value) ->
        estimateTextTokens(key) + estimateJsonTokens(value) + 4
    }
    is JsonArray -> element.sumOf { estimateJsonTokens(it) + 2 }
}

/** 单个工具定义（名称/说明/参数 schema）的同口径估算；不含 provider 专属包装，属偏保守的下界。 */
internal fun estimateToolTokens(tool: Tool): Long {
    val schema = runCatching { tool.parameters() }.getOrNull()
    val schemaTokens = when (schema) {
        is InputSchema.Obj -> estimateJsonTokens(schema.properties) +
            (schema.required?.sumOf { estimateTextTokens(it) + 4 } ?: 0L)
        null -> 0L
    }
    return estimateTextTokens(tool.name) + estimateTextTokens(tool.description) + schemaTokens + 32
}

/** 本次请求里所有工具定义的开销。 */
internal fun estimateToolTokens(tools: List<Tool>): Long = tools.sumOf { estimateToolTokens(it) }

/**
 * 一次压缩请求的装配结果：摘要正文 + 实际输入估算 + 压缩后仍保留的固定开销。
 *
 * [requestTokens] 是装配后真实要发的消息（含转换器产物）+ 工具定义的估算，
 * [fixedTokens] 只含压缩后不会消失的部分（系统规则 + 工具定义），供触发线下余量检查。
 */
internal data class CompressionSummaryResult(
    val summary: String,
    val requestTokens: Long,
    val fixedTokens: Long,
    val summaryMaxTokens: Long,
)

/**
 * 完整压缩这次请求能不能装进模型窗口：覆盖输入 + 压缩指令 + 固定规则/工具 + 摘要输出余量。
 *
 * 装不下就返回 null（调用方报维护失败、保留原文，不削覆盖、也不发这次注定失败的请求）。
 * 全是近似估算，不是 tokenizer；[windowMinRoomTokens] 是留给模型输出与服务端差异的最小余量。
 */
internal fun compressionSummaryBudget(
    modelContext: Int,
    fixedTokens: Long,
    coveredTokens: Long,
    instructionTokens: Long,
    windowMinRoomTokens: Long = 512,
): Long? {
    val window = modelContext.coerceAtLeast(1024).toLong()
    val room = window - fixedTokens.coerceAtLeast(0) - coveredTokens.coerceAtLeast(0) -
        instructionTokens.coerceAtLeast(0) - windowMinRoomTokens.coerceAtLeast(0)
    if (room < 256) return null
    return minOf(compressionSummaryMaxTokens(modelContext).toLong(), room)
}

/**
 * 压缩后整体是否真的有下降余量：固定开销（当前系统规则/工具定义）+ 新检查点
 * 必须明显低于触发线（留 10% 余量）。全部是近似估算，不含提示词缓存 token，也不重复累计。
 */
internal fun compressionFitsBudget(fixedTokens: Long, replacementTokens: Long, budget: Long): Boolean {
    val after = fixedTokens.coerceAtLeast(0) + replacementTokens.coerceAtLeast(0)
    return after <= budget * 9 / 10
}

/**
 * 安装检查点前的逐项比对：顺序与消息 id 必须完全一致，内容也必须相同。
 *
 * 唯一容忍的差异是 `finishGeneration()` 给未闭合 reasoning 补 `finishedAt`、落库前
 * `fillModelSnapshots` 给新回复补 `modelSnapshot`（都是 expected 为 null、actual 有值）；
 * 除这两项外的任何内容、分支或顺序变化都不接受，不能因为 id 相同就放过。
 */
internal fun sameContextExceptReasoningFinishTime(
    actual: List<UIMessage>,
    expected: List<UIMessage>,
): Boolean {
    if (actual.size != expected.size) return false
    return actual.zip(expected).all { (a, e) -> sameMessageExceptReasoningFinishTime(a, e) }
}

/** [sameMessageExceptReasoningFinishTime] 的单条版本。 */
internal fun sameMessageExceptReasoningFinishTime(actual: UIMessage, expected: UIMessage): Boolean {
    if (actual == expected) return true
    if (actual.id != expected.id) return false
    if (actual.parts.size != expected.parts.size) return false
    // parts 以外的字段（角色、时间、usage、注解等）必须完全相等；
    // modelSnapshot 只在 flush 时被补齐，这一项不是用户改动，只容忍从空补上
    val actualFields = actual.copy(
        parts = emptyList(),
        modelSnapshot = if (expected.modelSnapshot == null) null else actual.modelSnapshot,
    )
    if (actualFields != expected.copy(parts = emptyList())) return false
    return actual.parts.zip(expected.parts).all { (a, e) ->
        when {
            a == e -> true
            // 只容忍“expected 未收尾 → actual 已补 finishedAt”这一种方向
            a is UIMessagePart.Reasoning && e is UIMessagePart.Reasoning ->
                e.finishedAt == null && a.finishedAt != null &&
                    a.copy(finishedAt = null) == e.copy(finishedAt = null)
            else -> false
        }
    }
}

/** 已知的截断/不完整/安全拒绝结束原因；null 或空表示没有状态，不据此声称摘要已完整。 */
internal fun isIncompleteFinishReason(reason: String?): Boolean {
    val normalized = reason?.trim()?.lowercase().orEmpty()
    if (normalized.isEmpty()) return false
    if (normalized in KNOWN_INCOMPLETE_FINISH_REASONS) return true
    return INCOMPLETE_FINISH_MARKERS.any { normalized.contains(it) }
}

private val KNOWN_INCOMPLETE_FINISH_REASONS = setOf(
    "length",
    "max_tokens",
    "max_output_tokens",
    "max_completion_tokens",
    "incomplete",
    "content_filter",
    "safety",
    "refusal",
    "blocked",
)

private val INCOMPLETE_FINISH_MARKERS = listOf("truncat", "max_token", "incomplete", "content_filter", "refus")

/** Percent of the full model window; output headroom may only lower the trigger, never raise it. */
internal fun automaticCompressionBudget(modelContext: Int, thresholdPercent: Int, outputReserve: Int): Long {
    val window = modelContext.coerceAtLeast(1024).toLong()
    return minOf(
        window * thresholdPercent.coerceIn(10, 95) / 100,
        (window - outputReserve.coerceAtLeast(0).toLong() - 2048).coerceAtLeast(window / 4),
    )
}
