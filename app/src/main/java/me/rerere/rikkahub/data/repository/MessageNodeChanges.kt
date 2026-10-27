package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.MessageNode

/** Preserve unchanged JSON rows, but rewrite moved nodes and changed branch selection as well. */
internal fun changedMessageNodeIndices(previous: List<MessageNode>, current: List<MessageNode>): List<Int> {
    val old = previous.withIndex().associate { it.value.id to it }
    return current.indices.filter { index ->
        val prior = old[current[index].id]
        prior == null || prior.index != index || prior.value != current[index]
    }
}
