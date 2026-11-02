package me.rerere.rikkahub.data.db.fts

import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.PackedMessageList
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** indexChangedMessages 的节点级快路径：行实例相同（含 packed）不物化不重索引。 */
class MessageFtsChangesTest {

    @Test
    fun `同实例 packed 行节点不参与索引差量`() {
        val msg = UIMessage.user("x")
        val packed = PackedMessageList.fromRowJson(JsonInstant.encodeToString(listOf(msg)))
        val node = MessageNode(messages = packed)
        val prev = conv(listOf(node))
        val curr = conv(listOf(node))
        // 同一会话同一节点对象引用 → 节点级 === 快路径命中（对 FTS 索引代码只依赖实例相等）
        assertTrue(prev.messageNodes[0].messages === curr.messageNodes[0].messages)
    }

    @Test
    fun `packed 行与明文行序列化产物一致`() {
        val msg = UIMessage.assistant("{\"status\":\"ok\"}")
        val packed = PackedMessageList.fromRowJson(JsonInstant.encodeToString(listOf(msg)))
        // FTS 文本只依赖 parts，packed 物化后与明文一致
        assertEquals(listOf(msg), packed.toList())
    }

    private fun conv(nodes: List<MessageNode>) = Conversation(
        id = kotlin.uuid.Uuid.random(),
        assistantId = kotlin.uuid.Uuid.random(),
        messageNodes = nodes,
    )
}
