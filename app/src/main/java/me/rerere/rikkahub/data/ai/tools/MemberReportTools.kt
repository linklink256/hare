package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MemberReport
import me.rerere.rikkahub.data.model.MemberReportArtifact
import me.rerere.rikkahub.data.model.MemberReportRules
import me.rerere.rikkahub.data.model.MemberReportStatus
import kotlin.uuid.Uuid

object MemberReportToolNames {
    const val REPORT_BACK = "report_back"
}

/**
 * `report_back` 的落点：ChatService 在 init 时注册。
 * 返回 null 表示已记录；返回字符串是给模型的错误说明（校验成员身份 + 非终态 Run 都在实现里做）。
 */
object MemberReportSink {
    @Volatile
    var recorder: (suspend (conversationId: Uuid, payload: String) -> String?)? = null
}

/** 成员结构化汇报工具：只有成员会话会被 [ChatToolFactory] 加入。 */
fun createMemberReportTools(conversationId: Uuid): List<Tool> = listOf(
    Tool(
        name = MemberReportToolNames.REPORT_BACK,
        description = """
            Report your final result back to the lead in a structured form.
            Call this before you finish the round: status is "done" or "blocked",
            summary is a self-contained account (what you did, what you saw, key paths),
            artifacts list deliverables (ref plus optional sha256/note), evidence is optional context.
            Oversized fields are rejected with a readable error; shorten them instead of truncating.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "status",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { add("done"); add("blocked") })
                            put("description", "done = finished, blocked = stuck and needs the lead")
                        },
                    )
                    put(
                        "summary",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "self-contained result; <= ${MemberReportRules.MAX_SUMMARY_CHARS} chars")
                        },
                    )
                    put(
                        "artifacts",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "deliverables; <= ${MemberReportRules.MAX_ARTIFACTS} items")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put(
                                                "ref",
                                                buildJsonObject {
                                                    put("type", "string")
                                                    put(
                                                        "description",
                                                        "path/URI; <= ${MemberReportRules.MAX_REF_CHARS} chars",
                                                    )
                                                },
                                            )
                                            put("sha256", buildJsonObject { put("type", "string") })
                                            put("note", buildJsonObject { put("type", "string") })
                                        },
                                    )
                                    put("required", buildJsonArray { add("ref") })
                                },
                            )
                        },
                    )
                    put(
                        "evidence",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "optional; <= ${MemberReportRules.MAX_EVIDENCE_CHARS} chars")
                        },
                    )
                },
                required = listOf("status", "summary"),
            )
        },
        execute = { input ->
            listOf(UIMessagePart.Text(handleReport(input.jsonObjectOrNull(), conversationId)))
        },
    ),
)

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private suspend fun handleReport(input: JsonObject?, conversationId: Uuid): String {
    if (input == null) return errorJson("input must be a JSON object")
    val status = when (input["status"]?.jsonPrimitive?.contentOrNull) {
        "done" -> MemberReportStatus.DONE
        "blocked" -> MemberReportStatus.BLOCKED
        else -> return errorJson("status must be \"done\" or \"blocked\"")
    }
    val report = MemberReport(
        status = status,
        summary = input["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        artifacts = parseArtifacts(input["artifacts"]),
        evidence = input["evidence"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    )
    MemberReportRules.validate(report)?.let { return errorJson(it) }
    val recorder = MemberReportSink.recorder
        ?: return errorJson("report_back is not available right now")
    val denied = recorder(conversationId, MemberReportRules.encode(report))
    return if (denied == null) "{\"recorded\":true}" else errorJson(denied)
}

private fun parseArtifacts(element: JsonElement?): List<MemberReportArtifact> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val ref = obj["ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        MemberReportArtifact(
            ref = ref,
            sha256 = obj["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            note = obj["note"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }
}

private fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()
