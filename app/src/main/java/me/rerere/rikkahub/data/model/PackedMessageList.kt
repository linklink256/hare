package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.ref.SoftReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 消息行（一个 MessageNode 的全部分支）的 gzip 常驻替身：
 * 装包后强引用只持有压缩字节，物化结果挂在 SoftReference 上，
 * 内存紧张时可被回收、下次读再解压解码。
 *
 * 行为契约：只读；isEmpty 走装包时的顶层扫描（不物化），
 * size 在首次物化后确定（首次访问会物化，之后的读取廉价）；
 * equals/hashCode/迭代/按下标读与解码后的 List<UIMessage> 完全一致。
 */
internal class PackedMessageList private constructor(
    private val packed: ByteArray,
    private val empty: Boolean,
) : AbstractList<UIMessage>() {

    @Volatile
    private var materialized: SoftReference<List<UIMessage>> = SoftReference(null)

    @Volatile
    private var sizeCached: Int = -1

    private fun materialize(): List<UIMessage> {
        materialized.get()?.let { return it }
        synchronized(this) {
            materialized.get()?.let { return it }
            val decoded: List<UIMessage> = JsonInstant.decodeFromString(gunzip(packed).decodeToString())
            sizeCached = decoded.size
            materialized = SoftReference(decoded)
            return decoded
        }
    }

    override val size: Int
        get() = sizeCached.takeIf { it >= 0 } ?: materialize().size

    override fun isEmpty(): Boolean = empty

    override fun get(index: Int): UIMessage = materialize()[index]

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is List<*>) return false
        if (other.size != size) return false
        return materialize() == other
    }

    override fun hashCode(): Int = materialize().hashCode()

    override fun toString(): String = materialize().toString()

    override fun iterator(): Iterator<UIMessage> = materialize().iterator()

    override fun listIterator(index: Int): ListIterator<UIMessage> = materialize().listIterator(index)

    override fun subList(fromIndex: Int, toIndex: Int): List<UIMessage> = materialize().subList(fromIndex, toIndex)

    /** 落库行 JSON：与重新 encode 等价，不重复序列化 */
    fun toRowJson(): String = gunzip(packed).decodeToString()

    companion object {
        /**
         * 从 DB 行 JSON 装包：不解码，只压一次；
         * isEmpty 由"顶层首元素是否为 `]`"一次性判定（与 size 不同，size 需物化）。
         */
        fun fromRowJson(json: String): PackedMessageList =
            PackedMessageList(packed = gzip(json.encodeToByteArray()), empty = jsonArrayIsEmpty(json))

        /** 内存里新建的列表压包（新分支/编辑后的全新列表），empty 来自调用方；当前产品路径未使用。 */
        fun pack(messages: List<UIMessage>): PackedMessageList =
            PackedMessageList(
                packed = gzip(JsonInstant.encodeToString(messages).encodeToByteArray()),
                empty = messages.isEmpty(),
            )

        /** 顶层数组 "[ ... ]" 是否为空：第一个非空白字符是 `[`、其后的首个非空白是 `]`。 */
        private fun jsonArrayIsEmpty(json: String): Boolean {
            var i = 0
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '[') return false
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            return i < json.length && json[i] == ']'
        }

        private fun gzip(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size / 2)
            GZIPOutputStream(out).use { it.write(data) }
            return out.toByteArray()
        }

        private fun gunzip(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size * 4)
            GZIPInputStream(ByteArrayInputStream(data)).use { it.copyTo(out) }
            return out.toByteArray()
        }
    }
}
