package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.TeamMemberToolScope
import me.rerere.rikkahub.service.AgentTeamException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 领队工具：领队恒可用；成员只在还能再建一层（canNest）时拿到，否则与以前一样不给 */
internal fun canUseTeamTools(enabled: Boolean, isMember: Boolean, canNest: Boolean = false): Boolean =
    enabled && (!isMember || canNest)

/** 成员专属工具（report_back）：与领队工具互斥，只有团队成员会话可选 */
internal fun canUseMemberReportTools(enabled: Boolean, isMember: Boolean): Boolean = enabled && isMember

/** 成员专属工具（ask_lead）：同样只给成员，领队会话不能反问自己 */
internal fun canUseMemberAskTools(enabled: Boolean, isMember: Boolean): Boolean = enabled && isMember

/** 成员专属工具（team_status）：只读全队概览，同样只给成员 */
internal fun canUseMemberStatusTools(enabled: Boolean, isMember: Boolean): Boolean = enabled && isMember

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

/** 成员工具白名单的执行期兜底：旧历史缓存/恢复的审批也不能绕过 */
internal fun guardMemberToolScope(tool: Tool, allowed: List<String>): Tool = tool.copy(
    execute = { input ->
        if (TeamMemberToolScope.allows(allowed, tool.name)) {
            tool.execute(input)
        } else {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "tool '${tool.name}' is not allowed for this teammate")
            }.toString()))
        }
    },
)
