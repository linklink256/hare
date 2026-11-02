package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackedMessageListTest {

    private val sample = listOf(
        UIMessage.user("hello"),
        UIMessage.assistant("world, with [brackets] and {\"json\":\"inside\",\"n\":1}"),
    )
    private val sampleJson = JsonInstant.encodeToString(sample)

    @Test
    fun `fromRowJson 不解码也能拿到 size 与 empty`() {
        val list = PackedMessageList.fromRowJson(sampleJson)
        assertEquals(2, list.size)
        assertFalse(list.isEmpty())
    }

    @Test
    fun `空行 size 0 且 isEmpty`() {
        assertTrue(PackedMessageList.fromRowJson("[]").isEmpty())
        assertTrue(PackedMessageList.fromRowJson(" [ ] ").isEmpty())
        assertEquals(0, PackedMessageList.fromRowJson("[]").size)
    }

    @Test
    fun `物化后与明文列表值相等`() {
        val list = PackedMessageList.fromRowJson(sampleJson)
        assertEquals(sample, list.toList())
        assertEquals(sample[1], list[1])
        // equals/hashCode 对未物化与物化两侧都一致
        assertEquals(sample.hashCode(), list.hashCode())
        assertTrue(list == sample)
    }

    @Test
    fun `顶层计数对嵌套与字符串里的括号逗号不敏感`() {
        val messages = listOf(
            UIMessage.user("plain"),
            UIMessage.user("contains , and ] and { and \"quoted\""),
            UIMessage.assistant("nested [a,b] {\"k\":\"v,v\"}"),
        )
        val list = PackedMessageList.fromRowJson(JsonInstant.encodeToString(messages))
        assertEquals(3, list.size)
        assertEquals(messages, list.toList())
    }

    @Test
    fun `toRowJson 还原原始行`() {
        val list = PackedMessageList.fromRowJson(sampleJson)
        assertEquals(sampleJson, list.toRowJson())
    }

    @Test
    fun `pack 内存列表与 fromRowJson 行为一致`() {
        val list = PackedMessageList.pack(sample)
        assertEquals(2, list.size)
        assertFalse(list.isEmpty())
        assertEquals(sample, list.toList())
        assertEquals(sampleJson, list.toRowJson())
    }

    @Test
    fun `不同内容的 packed 列表 equals 不等`() {
        val a = PackedMessageList.fromRowJson(JsonInstant.encodeToString(listOf(UIMessage.user("a"))))
        val b = PackedMessageList.fromRowJson(JsonInstant.encodeToString(listOf(UIMessage.user("b"))))
        assertNotEquals(a.toList(), b.toList())
        assertFalse(a == b)
    }
}
