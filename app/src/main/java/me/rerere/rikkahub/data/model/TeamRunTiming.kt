package me.rerere.rikkahub.data.model

/** An in-process monotonic clock sample, never persisted across process/device restarts. */
data class TeamRunTiming(
    val startedAtNanos: Long,
    val finishedElapsedMillis: Long? = null,
    /**
     * 这一轮刚结束时是否还有工具在等审批；null 表示当时没能采样（会话已回收/未加载）。
     * 只为跨会话回收维持分组，不代表成功与否。
     */
    val awaitingApprovalAtFinish: Boolean? = null,
) {
    val running: Boolean get() = finishedElapsedMillis == null

    fun elapsedMillisAt(nowNanos: Long): Long =
        finishedElapsedMillis ?: ((nowNanos - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)
}

/** Locale-independent compact elapsed time; no clock-of-day/timezone interpretation. */
fun formatTeamTaskElapsed(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0L) / 1000L
    val minutes = seconds / 60L
    val ss = (seconds % 60L).toString().padStart(2, '0')
    return if (minutes < 60L) "$minutes:$ss" else {
        val mm = (minutes % 60L).toString().padStart(2, '0')
        "${minutes / 60L}:$mm:$ss"
    }
}
