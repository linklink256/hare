package me.rerere.rikkahub.data.model

/** Full text-only team report. No preview cap, trimming, paging or reasoning/tool-log expansion. */
internal fun fullTeamReplyText(textParts: Sequence<String>): String? =
    textParts.joinToString("\n").takeIf { it.isNotBlank() }
