package me.rerere.rikkahub.data.repository

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test

class MessageNodeChangesTest {
    private fun nodes() = List(4) { MessageNode.of(UIMessage.user("history $it")) }
    @Test fun unchangedNodesNotEncoded() {
        val old = nodes()
        assertEquals(emptyList<Int>(), changedMessageNodeIndices(old, old))
        assertEquals(emptyList<Int>(), changedMessageNodeIndices(old, old.map { it.copy() }))
    }
    @Test fun appendOnlyWritesNewNode() {
        val old = nodes()
        assertEquals(listOf(4), changedMessageNodeIndices(old, old + MessageNode.of(UIMessage.user("new"))))
    }
    @Test fun editsAndBranchSelectionSaved() {
        val old = nodes()
        val next = old.toMutableList()
        next[2] = next[2].copy(messages = next[2].messages + UIMessage.user("alternative"), selectIndex = 1)
        assertEquals(listOf(2), changedMessageNodeIndices(old, next))
        val selected = next.toMutableList()
        selected[2] = selected[2].copy(selectIndex = 0)
        assertEquals(listOf(2), changedMessageNodeIndices(next, selected))
    }
    @Test fun insertingCheckpointRewritesMovedNodeIndices() {
        val old = nodes()
        val next = old.take(2) + MessageNode.of(UIMessage.user("summary").copy(isContextCheckpoint = true)) + old.drop(2)
        assertEquals(listOf(2, 3, 4), changedMessageNodeIndices(old, next))
    }
    @Test fun deletingAndReorderingUpdatesPositions() {
        val old = nodes()
        assertEquals(listOf(1, 2), changedMessageNodeIndices(old, listOf(old[0], old[2], old[3])))
        assertEquals(listOf(0, 1), changedMessageNodeIndices(old, listOf(old[1], old[0], old[2], old[3])))
        assertEquals(emptyList<Int>(), changedMessageNodeIndices(old, emptyList()))
    }
}
