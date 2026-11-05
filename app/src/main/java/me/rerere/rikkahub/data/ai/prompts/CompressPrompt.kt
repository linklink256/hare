package me.rerere.rikkahub.data.ai.prompts

/**
 * 默认的压缩指令模板，作为一条 user 消息追加在对话末尾，模型直接总结上方的对话历史。
 * 摘要会替代之前的全部历史，按固定的结构输出，避免遗漏继续对话需要的信息。
 *
 * `{locale}` 在使用时替换成当前语言；用户可在「模型设置 → 提示词」里改写这份模板。
 */
internal val DEFAULT_COMPRESSION_PROMPT = """
        Stop the current task and write a structured summary of the conversation so far. The summary will replace the conversation history as the context for continuing it, so anything not in the summary will be lost.

        Do not call any tools. Respond with the summary only, using exactly the following Markdown structure. Omit a section if there is nothing to put in it.

        [Summary of previous conversation]

        ## User Goals
        What the user wants to achieve, including how their requests evolved over the conversation.

        ## Key Context
        Facts, background, constraints, and preferences the user stated or that were established, including any earlier summary at the start of the conversation.

        ## Decisions and Conclusions
        Choices that were made, answers that were reached, and the reasons behind them.

        ## Tool Results and Artifacts
        Important details from tool calls and their results that are needed to continue: file paths, commands, code, data, links, and search findings. Quote exact values instead of describing them.

        ## Errors and Resolutions
        Problems that came up and how they were resolved, or that they remain unresolved.

        ## Open Items
        Unfinished tasks, open questions, and anything the user is waiting for.

        ## Current State
        What was being worked on right before this summary, and the next step if one is clear.

        Requirements:
        - Keep the section headings as given above, but write the content in the same language as the conversation; if unclear, use {locale}
        - Record cumulative information: carry forward still-valid decisions, constraints and evidence from any earlier summary, and state each fact only once
        - Record the real state of every item: completed, failed, cancelled, waiting for the user, or not yet executed; never describe a failed or cancelled step as done
        - Be specific and concrete; prefer exact names and values over general descriptions
        - Do not add any explanation or commentary outside the summary
""".trimIndent()

internal fun buildCompressPrompt(
    additionalPrompt: String,
    locale: String,
    template: String = DEFAULT_COMPRESSION_PROMPT,
): String = buildString {
    append(template.ifBlank { DEFAULT_COMPRESSION_PROMPT }.replace("{locale}", locale).trimEnd())
    if (additionalPrompt.isNotBlank()) {
        appendLine()
        appendLine()
        append("Additional instructions from user: $additionalPrompt")
    }
}.trimEnd()
