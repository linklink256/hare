package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
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

private fun estimateTextTokens(text: String): Long {
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
 * Exclusive end of a compressible prefix, or null. Never includes the active last message,
 * pending tools or only a previous checkpoint. The configured percentage follows the current model window.
 */
internal fun automaticCompressionEnd(
    messages: List<UIMessage>,
    requestMessages: List<UIMessage>,
    modelContext: Int,
    thresholdPercent: Int,
    keepRecent: Int,
    outputReserve: Int = 4096,
): Int? {
    if (messages.size < 3) return null
    val checkpointIndex = messages.indexOfLast { it.isContextCheckpoint }
    val freshStart = checkpointIndex + 1
    val checkpointTime = messages.getOrNull(checkpointIndex)?.createdAt
    val usageMessage = messages.withIndex().lastOrNull {
        it.index > checkpointIndex && it.value.role == MessageRole.ASSISTANT &&
            (checkpointTime == null || (it.value.finishedAt ?: it.value.createdAt) > checkpointTime) &&
            (it.value.contextTokens() ?: 0) > 0
    }
    val usageTokens = usageMessage?.let {
        it.value.contextTokens()!!.toLong() + estimateContextTokens(messages.drop(it.index + 1))
    } ?: 0L
    val budget = automaticCompressionBudget(modelContext, thresholdPercent, outputReserve)
    if (maxOf(estimateContextTokens(requestMessages), usageTokens) < budget) return null

    // Always leave at least the active message. If the configured tail is too large, still make progress.
    val keep = keepRecent.coerceIn(1, 100).coerceAtMost((messages.size - freshStart - 2).coerceAtLeast(1))
    val end = messages.size - keep
    if (end - freshStart < 2) return null
    if (messages.take(end).any { message -> message.getTools().any { !it.isExecuted } }) return null
    // Do not repeatedly summarize history already omitted by the assistant's count limit.
    val activeIds = requestMessages.mapTo(hashSetOf()) { it.id }
    if (messages.subList(freshStart, end).none { it.id in activeIds }) return null
    return end
}

/** Percent of the full model window; output headroom may only lower the trigger, never raise it. */
internal fun automaticCompressionBudget(modelContext: Int, thresholdPercent: Int, outputReserve: Int): Long {
    val window = modelContext.coerceAtLeast(1024).toLong()
    return minOf(
        window * thresholdPercent.coerceIn(10, 95) / 100,
        (window - outputReserve.coerceAtLeast(0).toLong() - 2048).coerceAtLeast(window / 4),
    )
}
