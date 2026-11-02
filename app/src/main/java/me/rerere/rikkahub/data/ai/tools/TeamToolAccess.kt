package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.AgentTeamException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun canUseTeamTools(enabled: Boolean, isMember: Boolean): Boolean = enabled && !isMember

/** 成员专属工具（report_back）：与领队工具互斥，只有团队成员会话可选 */
internal fun canUseMemberReportTools(enabled: Boolean, isMember: Boolean): Boolean = enabled && isMember

/** Recheck at execution time, including cached tools / resumed approvals from old histories. */
internal fun guardTeamTool(tool: Tool, authorize: suspend () -> Unit): Tool = tool.copy(
    execute = { input ->
        try {
            authorize()
            tool.execute(input)
        } catch (e: AgentTeamException) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("error", e.message ?: "team access denied")
            }.toString()))
        }
    },
)
