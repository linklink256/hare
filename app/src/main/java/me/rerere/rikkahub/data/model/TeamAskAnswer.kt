package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/** 队友的一次提问：correlationId 由提问方生成，答复按同一 id 幂等回填。 */
data class TeamAsk(val correlationId: String, val question: String, val memberName: String? = null)

/** 领队的一条答复：只写进对方收件箱，不自动唤醒。 */
data class TeamAnswer(val correlationId: String, val answer: String)

/**
 * ask/answer 的纯规则：校验、幂等键与 JSON 编解码。
 *
 * 无 Android 依赖，JVM 单测可直接覆盖。超限一律显式拒绝并给出可读说明，**绝不截断**。
 */
object TeamAskAnswerRules {
    const val PROTOCOL_VERSION = 1
    const val MAX_CORRELATION_ID_CHARS = 64
    const val MAX_QUESTION_CHARS = 4000
    const val MAX_ANSWER_CHARS = 4000
    const val MAX_ENCODED_CHARS = 12000
    const val ASK_KEY_PREFIX = "ask:"
    const val ANSWER_KEY_PREFIX = "answer:"

    fun askIdempotencyKey(correlationId: String): String = ASK_KEY_PREFIX + correlationId

    fun answerIdempotencyKey(correlationId: String): String = ANSWER_KEY_PREFIX + correlationId

    fun newCorrelationId(): String = Uuid.random().toString()

    fun normalizeText(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** 只接受 1-64 位的 [A-Za-z0-9_-]；不接受空白（不去 trim 后放过） */
    fun isValidCorrelationId(raw: String?): Boolean {
        val value = raw ?: return false
        if (value.isEmpty() || value.length > MAX_CORRELATION_ID_CHARS) return false
        return value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
    }

    fun validateAsk(correlationId: String?, question: String?): String? {
        if (!isValidCorrelationId(correlationId)) {
            return "correlationId must be 1-$MAX_CORRELATION_ID_CHARS chars of [A-Za-z0-9_-]"
        }
        val text = question?.trim().orEmpty()
        if (text.isEmpty()) return "question must not be blank"
        if (text.length > MAX_QUESTION_CHARS) {
            return "question too long (${text.length} > $MAX_QUESTION_CHARS); shorten it instead of truncating"
        }
        return null
    }

    fun validateAnswer(correlationId: String?, answer: String?): String? {
        if (!isValidCorrelationId(correlationId)) {
            return "correlationId must be 1-$MAX_CORRELATION_ID_CHARS chars of [A-Za-z0-9_-]"
        }
        val text = answer?.trim().orEmpty()
        if (text.isEmpty()) return "answer must not be blank"
        if (text.length > MAX_ANSWER_CHARS) {
            return "answer too long (${text.length} > $MAX_ANSWER_CHARS); shorten it instead of truncating"
        }
        return null
    }

    fun encodeAsk(ask: TeamAsk): String {
        val name = normalizeText(ask.memberName)
        val encoded = buildJsonObject {
            put("v", PROTOCOL_VERSION)
            put("correlationId", ask.correlationId)
            put("question", ask.question)
            // 空名字不写字段（等价 encodeDefaults=false），避免下游把空串当名字
            if (name != null) put("memberName", name)
        }.toString()
        if (encoded.length > MAX_ENCODED_CHARS) {
            throw IllegalArgumentException(
                "ask payload too large (${encoded.length} > $MAX_ENCODED_CHARS); shorten it instead of truncating"
            )
        }
        return encoded
    }

    fun encodeAnswer(answer: TeamAnswer): String {
        val encoded = buildJsonObject {
            put("v", PROTOCOL_VERSION)
            put("correlationId", answer.correlationId)
            put("answer", answer.answer)
        }.toString()
        if (encoded.length > MAX_ENCODED_CHARS) {
            throw IllegalArgumentException(
                "answer payload too large (${encoded.length} > $MAX_ENCODED_CHARS); shorten it instead of truncating"
            )
        }
        return encoded
    }

    /** 解析失败一律返回 null，不抛异常，也不做宽松修补 */
    fun parseAsk(text: String?): TeamAsk? {
        val raw = text?.trim().orEmpty()
        if (!raw.startsWith("{")) return null
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (!hasProtocolVersion(obj)) return null
        val correlationId = stringField(obj, "correlationId") ?: return null
        if (!isValidCorrelationId(correlationId)) return null
        val question = stringField(obj, "question")?.trim()
        if (question.isNullOrEmpty() || question.length > MAX_QUESTION_CHARS) return null
        val memberName = stringField(obj, "memberName")?.trim()?.takeIf { it.isNotEmpty() }
        return TeamAsk(correlationId, question, memberName)
    }

    fun parseAnswer(text: String?): TeamAnswer? {
        val raw = text?.trim().orEmpty()
        if (!raw.startsWith("{")) return null
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (!hasProtocolVersion(obj)) return null
        val correlationId = stringField(obj, "correlationId") ?: return null
        if (!isValidCorrelationId(correlationId)) return null
        val answer = stringField(obj, "answer")?.trim()
        if (answer.isNullOrEmpty() || answer.length > MAX_ANSWER_CHARS) return null
        return TeamAnswer(correlationId, answer)
    }

    /** 领队会话里的提问通知；名字缺失用「队友」 */
    fun renderAskForLead(memberName: String?, ask: TeamAsk): String {
        val name = normalizeText(memberName) ?: "队友"
        val hint = "用 send_message 回复：target=<队友名>，text=<你的答复>，reply_to=${ask.correlationId}。" +
            "默认会唤醒它继续；只想记录答复就传 resume=false。"
        return "【队友「$name」提问 · correlationId=${ask.correlationId}】\n${ask.question}\n\n$hint"
    }

    /** 成员下一轮上下文里的答复正文 */
    fun renderAnswerForMember(answer: TeamAnswer): String =
        "【领队答复 · correlationId=${answer.correlationId}】\n${answer.answer}"

    /** v 必须是 JSON 整数 1（字符串 "1" 不接受） */
    private fun hasProtocolVersion(obj: JsonObject): Boolean {
        val primitive = obj["v"] as? JsonPrimitive ?: return false
        if (primitive.isString) return false
        return primitive.intOrNull == PROTOCOL_VERSION
    }

    /** 只接受 JSON 字符串字段；数字/布尔/对象一律当缺失 */
    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
