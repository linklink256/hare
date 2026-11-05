package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.runtime.OperationInbox
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

/** 唯一的消费工具名；注册与门禁都以此为常量。 */
const val RECEIVE_EVENTS = "receive_events"

/**
 * 事件消费工具：只把「本会话可领取的事件」交给模型，ACK 由 ChatService 在转录落库后做。
 * 空快照返回 count=0 且不 claim，宿主从不主动构造这个调用。
 */
fun createReceiveEventsTool(inbox: OperationInbox, conversationId: Uuid): Tool = Tool(
    name = RECEIVE_EVENTS,
    description = """
        Consume team events (member results, failures, notices) addressed to you.
        Call it only when you actually want the next events; an empty result is normal and means nothing is pending.
        This is the only way background results are delivered - they are never written back into the original tool call.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", OperationInbox.MAX_EVENTS_PER_CALL)
                    put("default", 4)
                    put("description", "Maximum events to consume in this call")
                })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val obj = args as? JsonObject
        val limit = (obj?.get("limit") as? JsonPrimitive)?.content?.toIntOrNull()
            ?.coerceIn(1, OperationInbox.MAX_EVENTS_PER_CALL) ?: 4
        val events = inbox.read(conversationId, limit)
        val body = buildJsonObject {
            put("count", events.size)
            put("acknowledged", 0)
            put("events", buildJsonArray {
                events.forEach { entry ->
                    add(buildJsonObject {
                        put("event_id", entry.id.toString())
                        put("kind", entry.kind.name)
                        entry.runId?.let { put("run_id", it.toString()) }
                        put("text", (entry.bodyText ?: entry.bodyRef ?: "").take(2000))
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(body)))
    },
)
