package me.rerere.rikkahub.data.datastore

import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "ProviderDecode"

/**
 * 只给 providers 列表用的逐条容错解码。
 *
 * 先按原路径整表解码，成功即返回（正常数据字节等价）。整表失败时只跳过「能确认是未知 provider 类型」的条目；
 * `type` 缺失/非字符串、或已知类型的字段损坏，仍抛原异常（保留 PreferencesStore 的失败语义）。
 * 结构不是 JSON 数组、或所有条目都被丢弃时同样抛原异常，避免把用户数据静默替换成默认值。
 */
internal object ProviderDecodeTolerance {
    /** 多态描述符里的已知子类 serialName 集合；不手写常量表 */
    private val knownSerialNames: Set<String> by lazy {
        ProviderSetting.serializer().descriptor.elementDescriptors
            .mapTo(LinkedHashSet()) { it.serialName }
    }

    fun decodeProviders(raw: String): List<ProviderSetting> {
        val strict = runCatching { JsonInstant.decodeFromString<List<ProviderSetting>>(raw) }
        strict.getOrNull()?.let { return it }
        val failure = strict.exceptionOrNull()
        val array = runCatching { JsonInstant.parseToJsonElement(raw) }.getOrNull() as? JsonArray
            ?: throw failure ?: SerializationException("providers is not a JSON array")
        val kept = ArrayList<ProviderSetting>(array.size)
        array.forEachIndexed { index, element ->
            val decoded = runCatching { JsonInstant.decodeFromJsonElement<ProviderSetting>(element) }
            decoded.getOrNull()?.let { kept.add(it) }
            if (decoded.isFailure) {
                // 只有「能确认是未知类型」才允许丢弃，其它失败保持原 halt 语义
                val type = readTypeOrNull(element)
                if (type == null || type in knownSerialNames) {
                    throw failure ?: SerializationException("undecodable provider entry index=$index")
                }
                logSkip(index, type, element)
            }
        }
        // 全部丢弃等于没有可用数据：抛原异常，不返回空列表（否则下次写盘会用默认 provider 覆盖）
        if (kept.isEmpty() && array.isNotEmpty()) {
            throw failure ?: SerializationException("no decodable provider entry")
        }
        return kept
    }

    /** type 非字符串/缺失/读取失败一律当 unknown（null），本函数不抛异常 */
    private fun readTypeOrNull(element: Any?): String? = runCatching {
        ((element as? JsonObject)?.get("type") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    }.getOrNull()

    /** 日志自身不得抛异常，否则容错路径会重新变成崩溃 */
    private fun logSkip(index: Int, type: String, element: Any?) {
        runCatching {
            Log.w(TAG, "skip unknown provider index=$index type=$type length=${element.toString().length}")
        }
    }
}
