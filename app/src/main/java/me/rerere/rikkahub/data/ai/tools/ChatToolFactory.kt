package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TeamMemberToolScope
import me.rerere.rikkahub.data.model.TeamNestingRules
import me.rerere.rikkahub.data.repository.AgentTeamRepository
import me.rerere.rikkahub.runtime.OperationInbox
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.service.AgentTeamService
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val agentTeamRepository: AgentTeamRepository,
    private val operationInbox: OperationInbox,
    /** 懒解析：AgentTeamService 依赖 ChatService，而本类又在 ChatService 的依赖链上 */
    private val agentTeamService: Lazy<AgentTeamService>,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        conversationId: Uuid,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val member = agentTeamRepository.getMemberByConversation(conversationId)
        val isTeamMember = member != null
        // 队友的工具白名单；空表示沿用默认工具集（既有行为完全不变）
        val memberScope = member?.allowedTools.orEmpty()
        // 成员兼子队 lead 的条件：它所属队的深度再加一层仍在 maxDepth 之内
        val memberTeam = member?.let { agentTeamRepository.getTeam(it.teamId) }
        val canNest = memberTeam != null && TeamNestingRules.canNest(
            parentDepth = memberTeam.depth,
            maxDepth = TeamNestingRules.normalizeMaxDepth(settings.agentTeamMaxDepth),
        )
        // 领队工具集单独持有：成员工具白名单会把它们静默过滤掉，过滤之后要再加回来
        val leaderTools = if (canUseTeamTools(assistant.enableAgentTeam, isTeamMember, canNest)) {
            createAgentTeamTools(
                teamService = agentTeamService.value,
                teamRepository = agentTeamRepository,
                leadConversationId = conversationId,
            )
        } else {
            emptyList()
        }
        val workspaceTools = createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd)
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools, assistant.workspaceId?.toString()))
            // 事件消费：与领队工具同一判据；成员白名单仍会在后面过滤
            if (canUseTeamTools(assistant.enableAgentTeam, isTeamMember, canNest)) {
                add(createReceiveEventsTool(operationInbox, conversationId))
            }
            if (canUseMemberReportTools(assistant.enableAgentTeam, isTeamMember)) {
                addAll(createMemberReportTools(conversationId))
            }
            if (canUseMemberAskTools(assistant.enableAgentTeam, isTeamMember)) {
                addAll(createTeamAskTools(conversationId))
            }
            // 成员视图：领队工具集里已有 team_status 时不重复注册（它已按领队视图返回）
            if (canUseMemberStatusTools(assistant.enableAgentTeam, isTeamMember) &&
                leaderTools.none { it.name == AgentTeamToolNames.TEAM_STATUS }
            ) {
                addAll(
                    createTeamStatusTools(
                        teamService = agentTeamService.value,
                        teamRepository = agentTeamRepository,
                        conversationId = conversationId,
                    )
                )
            }
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回。
        // 文件放在本机, 只有本地 Rootfs 挂载了 /tool_outputs, 远程工作区同样读不到
        val canReadToolOutputs = workspaceTools.isNotEmpty() &&
            assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }?.capabilities()?.rootfs == true
        // 声明层过滤 + 执行层再校验，report_back/ask_lead 由 allows 保证保留
        val scoped = if (memberScope.isEmpty()) tools else tools
            .filter { TeamMemberToolScope.allows(memberScope, it.name) }
            .map { guardMemberToolScope(it, memberScope) }
        // 白名单只约束成员工具：成员兼子队 lead 时把领队工具补回，不受白名单过滤
        val withLeaderTools = if (leaderTools.isEmpty()) scoped else scoped + leaderTools
        return if (canReadToolOutputs) withLeaderTools.map(::withOutputTruncation) else withLeaderTools
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
