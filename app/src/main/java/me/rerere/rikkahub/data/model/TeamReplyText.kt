package me.rerere.rikkahub.data.model

/** Full text-only team report. No preview cap, trimming, paging or reasoning/tool-log expansion. */
internal fun fullTeamReplyText(textParts: Sequence<String>): String? =
    textParts.joinToString("\n").takeIf { it.isNotBlank() }

/** 队友自报的收尾状态：最后一行 `STATUS: done` 或 `STATUS: blocked: <原因>`。 */
internal enum class TeamReportStatus { DONE, BLOCKED, UNKNOWN }

internal data class TeamReport(
    val status: TeamReportStatus,
    val reason: String?,
    val body: String,
)

private val STATUS_LINE = Regex(
    """^[\s*_`>#-]*STATUS\s*[:：]\s*(done|blocked)\b\s*[:：]?\s*(.*?)[\s*_`]*$""",
    RegexOption.IGNORE_CASE,
)

/** 从最终回复里摘出收尾状态行；没有就按未标注处理，正文原样保留。 */
internal fun parseTeamReport(text: String): TeamReport {
    val lines = text.lines()
    for (index in lines.indices.reversed()) {
        val line = lines[index].trim()
        // 空行和代码块围栏行（```）跳过，状态行常被模型包在围栏里
        if (line.isEmpty() || line.all { it == '`' }) continue
        val match = STATUS_LINE.find(line)
            ?: return TeamReport(TeamReportStatus.UNKNOWN, null, text.trim())
        val status = if (match.groupValues[1].equals("done", ignoreCase = true)) {
            TeamReportStatus.DONE
        } else {
            TeamReportStatus.BLOCKED
        }
        val reason = match.groupValues[2].trim().takeIf { it.isNotBlank() }
        val body = lines.filterIndexed { i, _ -> i != index }.joinToString("\n").trim()
        return TeamReport(status, reason, body)
    }
    return TeamReport(TeamReportStatus.UNKNOWN, null, text.trim())
}
