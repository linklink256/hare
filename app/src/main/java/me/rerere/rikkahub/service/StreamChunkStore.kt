package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.StreamChunkEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "StreamChunkStore"
private const val KIND_TEXT = "text"
private const val KIND_REASONING = "reasoning"
private const val KIND_TOOL = "tool"

/** 工具输入在流式增长，每长这么多字符才重记一次快照；执行状态变化会立刻重记 */
private const val TOOL_SNAPSHOT_INPUT_STEP = 2048

/** 工具快照只给同一个类用，单独一个 Json，不动全局序列化行为 */
private val toolChunkJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 生成中的增量落库：流式热路径上只把这次新写出的那一段文本追加进 stream_chunk，
 * 不碰 message_node / conversation 行，也不触发 FTS 重建；轮次结束整条落库后清掉，
 * 进程被杀则下次启动把残留片段拼回消息，避免整轮输出只剩内存里那一份。
 */
class StreamChunkStore(
    private val conversationRepo: ConversationRepository,
) : KoinComponent {
    private data class StreamedPart(val kind: String, val text: String)

    /** 只用来判断工具快照是否需要重记；不持有 tool 本体，免得把大输出留在内存里 */
    private data class ToolSnapshot(
        val toolCallId: String,
        val toolName: String,
        val inputLength: Int,
        val outputSize: Int,
        val approvalState: ToolApprovalState,
        val startedAt: kotlin.time.Instant?,
        val finishedAt: kotlin.time.Instant?,
        val metadata: JsonObject?,
    )

    private val database: AppDatabase by inject()

    // messageId -> 每个 part 上次追加到的文本；null 表示该 part 不落库
    private val lastParts = ConcurrentHashMap<Uuid, List<StreamedPart?>>()

    // messageId -> (partIndex -> 已经落库的那份工具快照)
    private val lastTools = ConcurrentHashMap<Uuid, MutableMap<Int, ToolSnapshot>>()

    // conversationId -> 本次生成正在写的 messageId
    private val messagesOfConversation = ConcurrentHashMap<Uuid, MutableSet<Uuid>>()

    /** 追加 [message] 相比上次新增的文本/工具状态。只写增量，不做整条会话序列化。 */
    suspend fun append(conversationId: Uuid, message: UIMessage) {
        val current = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> StreamedPart(KIND_TEXT, part.text)
                is UIMessagePart.Reasoning -> StreamedPart(KIND_REASONING, part.reasoning)
                // 工具调用与输出整份快照，text 字段不用
                is UIMessagePart.Tool -> StreamedPart(KIND_TOOL, "")
                else -> null
            }
        }
        val previous = lastParts[message.id]
        val previousTools = lastTools[message.id]
        val toolWrites = HashMap<Int, ToolSnapshot>()
        val rows = buildList {
            current.forEachIndexed { index, part ->
                if (part == null) return@forEachIndexed
                if (part.kind == KIND_TOOL) {
                    val tool = message.parts.getOrNull(index) as? UIMessagePart.Tool
                        ?: return@forEachIndexed
                    val snapshot = tool.snapshot()
                    if (!snapshot.shouldWrite(previousTools?.get(index))) return@forEachIndexed
                    val encoded = runCatching {
                        toolChunkJson.encodeToString(UIMessagePart.Tool.serializer(), tool)
                    }.getOrNull() ?: return@forEachIndexed
                    toolWrites[index] = snapshot
                    add(row(conversationId, message.id, index, part, encoded, reset = true))
                    return@forEachIndexed
                }
                if (part.text.isEmpty()) return@forEachIndexed
                val old = previous?.getOrNull(index)?.takeIf { it.kind == part.kind }?.text.orEmpty()
                when {
                    part.text.length > old.length && part.text.startsWith(old) -> add(
                        row(conversationId, message.id, index, part, part.text.substring(old.length), reset = false)
                    )
                    // 重试/回滚把这一段改短或改掉：整段重记，恢复时以最后一条 reset 为起点
                    part.text != old -> add(
                        row(conversationId, message.id, index, part, part.text, reset = true)
                    )
                }
            }
        }
        lastParts[message.id] = current
        if (toolWrites.isNotEmpty()) {
            lastTools.getOrPut(message.id) { HashMap() }.putAll(toolWrites)
        }
        messagesOfConversation.getOrPut(conversationId) { ConcurrentHashMap.newKeySet<Uuid>() }.add(message.id)
        if (rows.isEmpty()) return
        withContext(Dispatchers.IO) { database.streamChunkDao().insertAll(rows) }
    }

    /** 这一轮已经整条落库（或已放弃），增量片段不再需要 */
    suspend fun clear(conversationId: Uuid) {
        messagesOfConversation.remove(conversationId)?.forEach {
            lastParts.remove(it)
            lastTools.remove(it)
        }
        withContext(Dispatchers.IO) { database.streamChunkDao().deleteOfConversation(conversationId.toString()) }
    }

    /** 把上次进程留下的增量片段拼回消息并落库；返回恢复的会话数 */
    suspend fun recoverPending(): Int = withContext(Dispatchers.IO) {
        val dao = database.streamChunkDao()
        var recovered = 0
        for (conversationId in dao.getConversationIds()) {
            val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
            val chunks = if (uuid == null) emptyList() else dao.getOfConversation(conversationId)
            val conversation = uuid?.let { conversationRepo.getConversationById(it) }
            if (conversation == null || chunks.isEmpty()) {
                dao.deleteOfConversation(conversationId)
                continue
            }
            var updated: Conversation = conversation
            var changed = false
            chunks.groupBy { it.messageId }.forEach { (messageId, rows) ->
                val messageUuid = runCatching { Uuid.parse(messageId) }.getOrNull() ?: return@forEach
                val recoveredParts = rebuildParts(rows)
                if (recoveredParts.none { it != null }) return@forEach
                val node = updated.messageNodes.firstOrNull { node ->
                    node.messages.any { it.id == messageUuid }
                }
                if (node == null) {
                    val message = UIMessage(
                        id = messageUuid,
                        role = MessageRole.ASSISTANT,
                        parts = recoveredParts.filterNotNull(),
                    )
                    updated = updated.copy(messageNodes = updated.messageNodes + message.toMessageNode())
                } else {
                    val messageIndex = node.messages.indexOfFirst { it.id == messageUuid }
                    val existing = node.messages[messageIndex]
                    val merged = mergeParts(existing.parts, recoveredParts)
                    val messages = node.messages.toMutableList().also {
                        it[messageIndex] = existing.copy(parts = merged)
                    }
                    updated = updated.copy(
                        messageNodes = updated.messageNodes.map { current ->
                            if (current.id == node.id) current.copy(messages = messages) else current
                        }
                    )
                }
                changed = true
            }
            if (!changed) {
                dao.deleteOfConversation(conversationId)
                continue
            }
            conversationRepo.updateConversation(updated.copy(updateAt = Instant.now()), conversation)
            dao.deleteOfConversation(conversationId)
            recovered++
        }
        if (recovered > 0) Log.i(TAG, "recovered $recovered conversation(s) from stream chunks")
        recovered
    }

    private fun row(
        conversationId: Uuid,
        messageId: Uuid,
        partIndex: Int,
        part: StreamedPart,
        text: String,
        reset: Boolean,
    ) = StreamChunkEntity(
        conversationId = conversationId.toString(),
        messageId = messageId.toString(),
        partIndex = partIndex,
        partKind = part.kind,
        chunkText = text,
        isReset = reset,
        createdAt = System.currentTimeMillis(),
    )

    /** 按 part_index 对齐拼回：返回的下标与消息里的 part 下标一致，空位为 null */
    private fun rebuildParts(rows: List<StreamChunkEntity>): List<UIMessagePart?> {
        val kinds = HashMap<Int, String>()
        val texts = HashMap<Int, StringBuilder>()
        val tools = HashMap<Int, UIMessagePart.Tool>()
        rows.sortedBy { it.id }.forEach { chunk ->
            // 以最后一条为准：同一位在重试里换过 part 类型时，不能停在第一次的类型上
            kinds[chunk.partIndex] = chunk.partKind
            if (chunk.partKind == KIND_TOOL) {
                // 工具快照是整份 JSON，取最后一条
                runCatching {
                    toolChunkJson.decodeFromString(UIMessagePart.Tool.serializer(), chunk.chunkText)
                }.getOrNull()?.let { tools[chunk.partIndex] = it }
                return@forEach
            }
            val builder = texts.getOrPut(chunk.partIndex) { StringBuilder() }
            if (chunk.isReset) builder.setLength(0)
            builder.append(chunk.chunkText)
        }
        val maxIndex = kinds.keys.maxOrNull() ?: return emptyList()
        return (0..maxIndex).map { index ->
            when (kinds[index]) {
                KIND_TEXT -> UIMessagePart.Text(texts[index]?.toString().orEmpty())
                KIND_REASONING -> UIMessagePart.Reasoning(reasoning = texts[index]?.toString().orEmpty())
                KIND_TOOL -> tools[index]
                else -> null
            }
        }
    }

    private fun mergeParts(
        existing: List<UIMessagePart>,
        recovered: List<UIMessagePart?>,
    ): List<UIMessagePart> {
        val merged = existing.toMutableList()
        recovered.forEachIndexed { index, part ->
            if (part == null) return@forEachIndexed
            // 工具按 toolCallId 认，不靠下标：库里的那份已经有输出就留它
            if (part is UIMessagePart.Tool) {
                val at = merged.indexOfFirst {
                    it is UIMessagePart.Tool && it.toolCallId == part.toolCallId
                }
                if (at < 0) {
                    merged.add(part)
                } else if ((merged[at] as UIMessagePart.Tool).output.isEmpty() && part.output.isNotEmpty()) {
                    merged[at] = part
                }
                return@forEachIndexed
            }
            val current = merged.getOrNull(index)
            if (current != null && sameKind(current, part)) {
                // 两边的文本一般都互为前缀，留长的那个
                if (partText(part).length >= partText(current).length) merged[index] = part
            } else {
                merged.add(part)
            }
        }
        return merged
    }

    private fun UIMessagePart.Tool.snapshot() = ToolSnapshot(
        toolCallId = toolCallId,
        toolName = toolName,
        inputLength = input.length,
        outputSize = output.size,
        approvalState = approvalState,
        startedAt = startedAt,
        finishedAt = finishedAt,
        metadata = metadata,
    )

    /** 与上次落库的那份比，这一次是否值得再写一行 */
    private fun ToolSnapshot.shouldWrite(previous: ToolSnapshot?): Boolean {
        if (previous == null) return true
        if (toolCallId != previous.toolCallId || toolName != previous.toolName) return true
        if (outputSize != previous.outputSize || approvalState != previous.approvalState) return true
        if (startedAt != previous.startedAt || finishedAt != previous.finishedAt) return true
        if (metadata != previous.metadata) return true
        // 参数在流式增长：按步进记，崩掉也不会只剩一个空参数
        return inputLength - previous.inputLength >= TOOL_SNAPSHOT_INPUT_STEP ||
            inputLength < previous.inputLength
    }

    private fun sameKind(a: UIMessagePart, b: UIMessagePart): Boolean = when {
        a is UIMessagePart.Text && b is UIMessagePart.Text -> true
        a is UIMessagePart.Reasoning && b is UIMessagePart.Reasoning -> true
        else -> false
    }

    private fun partText(part: UIMessagePart): String = when (part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Reasoning -> part.reasoning
        else -> ""
    }
}
