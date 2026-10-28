package me.rerere.rikkahub.data.model

/** An in-process monotonic clock sample, never persisted across process/device restarts. */
data class TeamRunTiming(
    val startedAtNanos: Long,
    val finishedElapsedMillis: Long? = null,
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
