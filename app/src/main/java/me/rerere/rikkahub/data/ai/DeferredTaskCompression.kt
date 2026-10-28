package me.rerere.rikkahub.data.ai

/** One task owns one schedule. Reaching a threshold never performs work in the model/tool loop. */
internal class DeferredTaskCompression {
    var pending: Boolean = false
        private set
    private var finished = false

    fun observeThreshold(reached: Boolean) {
        if (!finished && reached) pending = true
    }

    suspend fun afterTask(
        completed: Boolean,
        finalThresholdReached: suspend () -> Boolean,
        compress: suspend () -> Unit,
    ) {
        if (!completed || finished) return
        // Reserve this completion before suspending; one task may create at most one checkpoint.
        finished = true
        val stillNeeded = finalThresholdReached()
        pending = pending || stillNeeded
        try {
            if (stillNeeded) compress()
        } finally {
            pending = false
        }
    }
}
