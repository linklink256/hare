package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.TeamAskAnswerRules
import kotlin.uuid.Uuid

object TeamAskToolNames {
    const val ASK_LEAD = "ask_lead"
}

/** ask_lead 的结果：成功带 correlationId，失败带给人看的 error */
data class AskLeadRecord(val correlationId: String?, val error: String?)

/** ChatService 在 init 里注册；返回 error 时直接回给模型，不抛异常 */
object TeamAskSink {
    @Volatile
    var asker: (suspend (conversationId: Uuid, question: String) -> AskLeadRecord)? = null
}

/** 成员专属工具（ask_lead）：与领队工具互斥，只有团队成员会话会被加入 */
fun createTeamAskTools(conversationId: Uuid): List<Tool> = listOf(
    Tool(
        name = TeamAskToolNames.ASK_LEAD,
        description = """
            Ask the lead a question when you are missing information or a decision only the lead can make.
            State what you need and what you already tried. The question is recorded into the lead's inbox and
            this run is marked as waiting for an answer, so do not invent an answer and continue.
            The lead's answer appears in this conversation at the start of your next round.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("question", buildJsonObject {
                        put("type", "string")
                        put("description", "What you need from the lead; <= ${TeamAskAnswerRules.MAX_QUESTION_CHARS} chars")
                    })
                },
                required = listOf("question"),
            )
        },
        execute = { input -> listOf(UIMessagePart.Text(handleAsk(input, conversationId))) },
    ),
)

private suspend fun handleAsk(input: JsonElement, conversationId: Uuid): String {
    // 缺失/非字符串一律按空白处理，交给 sink 里的统一校验给说明
    val question = (input as? JsonObject)
        ?.get("question")
        ?.let { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull }
        ?.trim()
        .orEmpty()
    // 失败一律走 record.error；sink 内部抛出的异常不在这里吞掉，交给上层变成错误消息
    val asker = TeamAskSink.asker ?: return askErrorJson("ask_lead is not available right now")
    val record = asker(conversationId, question)
    val correlationId = record.correlationId
    val error = record.error
    if (error != null || correlationId == null) {
        return askErrorJson(error ?: "ask_lead failed")
    }
    return buildJsonObject {
        put("recorded", true)
        put("correlation_id", correlationId)
        put("waiting", true)
    }.toString()
}

private fun askErrorJson(message: String): String = buildJsonObject { put("error", message) }.toString()
