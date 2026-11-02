package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 分层压缩的确定性部分：
 * 1) 剪枝——把要喂给摘要模型的视图压到窗口内（工具输出头尾、推理只留开头、附件换占位符）；
 * 2) 分块——剪枝后仍超窗口时按消息边界切块，供 map-reduce 摘要；
 * 3) 降级——摘要不可用时生成不含模型输出的检查点文本。
 * 都只作用于“发给模型的视图”，不改保存的历史。
 */

internal const val PRUNE_TOOL_OUTPUT_HEAD = 4000
internal const val PRUNE_TOOL_OUTPUT_TAIL = 1000
internal const val PRUNE_TOOL_INPUT_CHARS = 2000
internal const val PRUNE_REASONING_CHARS = 1200
internal const val PRUNE_TEXT_HEAD = 8000
internal const val PRUNE_TEXT_TAIL = 2000
private const val PRUNE_MARK = "\n…[truncated for compression]…\n"

internal fun pruneForCompression(messages: List<UIMessage>): List<UIMessage> =
    messages.map { message -> message.copy(parts = message.parts.map(::prunePartForCompression)) }

internal fun prunePartForCompression(part: UIMessagePart): UIMessagePart = when (part) {
    is UIMessagePart.Text ->
        if (part.text.length > PRUNE_TEXT_HEAD + PRUNE_TEXT_TAIL) {
            part.copy(text = clipMiddle(part.text, PRUNE_TEXT_HEAD, PRUNE_TEXT_TAIL))
        } else {
            part
        }

    is UIMessagePart.Reasoning ->
        if (part.reasoning.length > PRUNE_REASONING_CHARS) {
            part.copy(reasoning = part.reasoning.take(PRUNE_REASONING_CHARS) + PRUNE_MARK)
        } else {
            part
        }

    is UIMessagePart.Tool -> part.copy(
        input = clipMiddle(part.input, PRUNE_TOOL_INPUT_CHARS, 0),
        // 工具输出单独用更紧的头尾上限，避免一次日志/文件读取把整段历史撑爆
        output = part.output.map { output ->
            if (output is UIMessagePart.Text) {
                output.copy(text = clipMiddle(output.text, PRUNE_TOOL_OUTPUT_HEAD, PRUNE_TOOL_OUTPUT_TAIL))
            } else {
                prunePartForCompression(output)
            }
        },
    )

    is UIMessagePart.Image -> UIMessagePart.Text("[image omitted for compression]")
    is UIMessagePart.Audio -> UIMessagePart.Text("[audio omitted for compression]")
    is UIMessagePart.Video -> UIMessagePart.Text("[video omitted for compression]")
    is UIMessagePart.Document -> UIMessagePart.Text("[document omitted for compression: ${part.fileName}]")

    // 旧格式与 provider 托管工具：保持原样，避免改变语义
    else -> part
}

private fun clipMiddle(text: String, head: Int, tail: Int): String {
    if (text.length <= head + tail) return text
    val headPart = text.take(head)
    val tailPart = if (tail > 0) text.takeLast(tail) else ""
    return headPart + PRUNE_MARK + tailPart
}

/** 按消息边界切块，保证每块估算不超过 [chunkTokens]；单条消息本身超限时仍然独占一块。 */
internal fun splitForCompression(messages: List<UIMessage>, chunkTokens: Long): List<List<UIMessage>> {
    if (messages.isEmpty()) return emptyList()
    if (chunkTokens <= 0) return listOf(messages)
    val chunks = mutableListOf<List<UIMessage>>()
    var current = mutableListOf<UIMessage>()
    var used = 0L
    for (message in messages) {
        val cost = estimateContextTokens(listOf(message))
        if (current.isNotEmpty() && used + cost > chunkTokens) {
            chunks += current
            current = mutableListOf()
            used = 0L
        }
        current += message
        used += cost
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

/**
 * 摘要不可用时的降级检查点：不编造内容，只如实说明覆盖了多少、最近几条的摘录是什么。
 * 原文仍在对话记录里，可随时查看。
 */
internal fun fallbackCompactionText(
    messages: List<UIMessage>,
    reason: String?,
    excerptCount: Int = 5,
    excerptChars: Int = 300,
): String {
    val users = messages.count { it.role == MessageRole.USER }
    val assistants = messages.count { it.role == MessageRole.ASSISTANT }
    val tools = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
    val toolNames = tools.map { it.toolName }.groupingBy { it }.eachCount()
        .entries.joinToString(", ") { (name, count) -> "$name x$count" }
    val first = messages.firstOrNull()?.createdAt
    val last = messages.lastOrNull()?.createdAt
    return buildString {
        appendLine("[Context trimmed without a model summary]")
        appendLine(
            "The conversation before this note (${messages.size} messages) was replaced by this note " +
                "to fit the model context window."
        )
        if (first != null && last != null) {
            appendLine("Range: $first — $last")
        }
        appendLine(
            "Composition: user $users, assistant $assistants, tool calls ${tools.size}" +
                if (toolNames.isNotBlank()) " ($toolNames)" else ""
        )
        if (!reason.isNullOrBlank()) {
            appendLine("Summary was not generated: $reason")
        }
        val excerpts = messages.takeLast(excerptCount)
            .mapNotNull { message ->
                val text = message.toText().trim().replace('\n', ' ')
                if (text.isEmpty()) null else "- ${message.role}: ${text.take(excerptChars)}"
            }
        if (excerpts.isNotEmpty()) {
            appendLine()
            appendLine("## Recent messages (excerpts)")
            excerpts.forEach { appendLine(it) }
        }
        appendLine()
        append(
            "The replaced messages are still stored in the conversation record and can be reviewed there. " +
                "Do not assume details that are not listed above; re-read files or re-run tools if needed."
        )
    }.trimEnd()
}
