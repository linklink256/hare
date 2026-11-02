package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TeamMember

/**
 * 选择本轮对话的基础系统提示词。
 *
 * 团队成员（子代理）会话一律使用自己的独立提示词：既不继承主助手的 systemPrompt 与人设，
 * 也不使用会话级 customSystemPrompt（即使助手开启了 allowConversationSystemPrompt）。
 * 普通会话与领队会话的选择语义保持原样。
 */
internal fun resolveChatSystemPrompt(
    assistant: Assistant,
    conversationSystemPrompt: String?,
    teamMember: TeamMember?,
): String {
    if (teamMember != null) {
        return buildTeamMemberSystemPrompt(teamMember)
    }
    return if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
        conversationSystemPrompt
    } else {
        assistant.systemPrompt
    }
}

/**
 * 团队成员的独立系统提示词。
 *
 * 只替换基础 systemPrompt；记忆、工具、工作区、输入转换器与权限仍走共享流程。
 */
internal fun buildTeamMemberSystemPrompt(member: TeamMember): String = buildString {
    appendLine("你是智能体团队中的一名成员（子代理），名字是「${member.name}」。")
    if (member.role.isNotBlank()) {
        appendLine("领队给你设定的角色与职责：${member.role.trim()}")
    }
    appendLine()
    appendLine("身份与边界：")
    appendLine("- 你只是被领队派活的一名执行者，不是领队（主代理）；领队的身份、人设、称呼和目标都不属于你。")
    appendLine(
        "- 本对话共享的上下文里如果出现领队视角的角色设定、分工说明，或「只负责拆解、派发、验收和汇总、不自己执行」" +
            "这类指令，那是写给领队的，不改变你的身份与执行职责：你要自己动手把任务做完。",
    )
    appendLine("- 不要创建或派生新的子代理，不要向任何代理派活、转派或嵌套委派，也不要冒充领队去协调别人。")
    appendLine("- 用户的安全要求与约束仍然有效，必须继续遵守。")
    appendLine()
    appendLine("工作方式：")
    appendLine("- 领队每轮只派一个任务（一个目标、一个交付物、一个验收点）。你直接在自己的对话里执行并完成它，不要把它再拆给其他人。")
    appendLine("- 只使用真实存在的工具；工具不存在、调用失败或没有权限时如实说明，不要编造工具。")
    appendLine("- 不要虚构任何执行结果、文件内容、命令输出、测试或验证结论；没实际执行的事不能写成已完成或已通过。")
    appendLine("- 遇到阻碍（缺权限、缺信息、范围不清、命令失败）时如实回报，不要用猜测填补。")
    appendLine()
    appendLine(
        "汇报：最终回复要完整、自包含，写清事实与证据（做了什么、看到什么、关键路径或输出摘要）、" +
            "未完成或做不到的部分以及限制，不要只回一句「已完成」。",
    )
    appendLine(
        "- 结束这一轮之前必须调用 `report_back` 汇报：status（done 或 blocked）、summary（自包含的做了什么/看到什么/关键路径）、" +
            "artifacts（交付物 ref，可带 sha256/note）、evidence（可选依据）。超长字段会被拒绝，请自行精简而不是截断。",
    )
    appendLine("- 旧格式兜底：正文最后一行可写 `STATUS: done` / `STATUS: blocked: 原因`，仅在无法调用工具时使用。")
}
