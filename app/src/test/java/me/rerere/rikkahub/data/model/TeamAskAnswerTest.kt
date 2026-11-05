package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TeamAskAnswerRules 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的字面量真值表；不从产品常量/encode 结果反推期望再断言它自己。
 * 未覆盖：ask_lead / send_message(reply_to) 工具、AgentTeamService.answerAsk 的 DB 事务、ChatService 的
 * WAITING_INPUT 迁移与答复注入、投递渲染，见 TEST-R2C2.md。
 */
class TeamAskAnswerTest {

    private fun ask(
        correlationId: String = "c1",
        question: String = "what is the plan",
        memberName: String? = "Alice",
    ) = TeamAsk(correlationId = correlationId, question = question, memberName = memberName)

    private fun answer(
        correlationId: String = "c1",
        answer: String = "use option B",
    ) = TeamAnswer(correlationId = correlationId, answer = answer)

    // ---------- 冻结常量 ----------

    @Test
    fun `protocol constants are the frozen values`() {
        assertEquals(1, TeamAskAnswerRules.PROTOCOL_VERSION)
        assertEquals(64, TeamAskAnswerRules.MAX_CORRELATION_ID_CHARS)
        assertEquals(4000, TeamAskAnswerRules.MAX_QUESTION_CHARS)
        assertEquals(4000, TeamAskAnswerRules.MAX_ANSWER_CHARS)
        assertEquals(12000, TeamAskAnswerRules.MAX_ENCODED_CHARS)
        assertEquals("ask:", TeamAskAnswerRules.ASK_KEY_PREFIX)
        assertEquals("answer:", TeamAskAnswerRules.ANSWER_KEY_PREFIX)
    }

    // ---------- newCorrelationId ----------

    @Test
    fun `newCorrelationId returns a valid distinct 36 char id`() {
        val a = TeamAskAnswerRules.newCorrelationId()
        val b = TeamAskAnswerRules.newCorrelationId()
        assertEquals(36, a.length)
        assertEquals(36, b.length)
        assertTrue(TeamAskAnswerRules.isValidCorrelationId(a))
        assertTrue(TeamAskAnswerRules.isValidCorrelationId(b))
        assertNotEquals(a, b)
    }

    // ---------- isValidCorrelationId ----------

    @Test
    fun `isValidCorrelationId accepts uuid and the allowed characters`() {
        assertTrue(TeamAskAnswerRules.isValidCorrelationId("3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
        assertTrue(TeamAskAnswerRules.isValidCorrelationId("a-b_c"))
        assertTrue(TeamAskAnswerRules.isValidCorrelationId("A"))
        assertTrue(TeamAskAnswerRules.isValidCorrelationId("0"))
        assertTrue(TeamAskAnswerRules.isValidCorrelationId("Ab_9-Zz"))
    }

    @Test
    fun `isValidCorrelationId accepts exactly 64 chars and rejects 65`() {
        val at64 = "a".repeat(64)
        assertEquals(64, at64.length)
        assertTrue(TeamAskAnswerRules.isValidCorrelationId(at64))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId(at64 + "a"))
        assertTrue(TeamAskAnswerRules.isValidCorrelationId("_".repeat(63) + "-"))
    }

    @Test
    fun `isValidCorrelationId rejects null empty blank and illegal characters`() {
        assertFalse(TeamAskAnswerRules.isValidCorrelationId(null))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId(""))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId("   "))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId("a b"))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId("a\tb"))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId("队友"))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId("a.b"))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId("a/b"))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId("a:b"))
        assertFalse(TeamAskAnswerRules.isValidCorrelationId("a\nb"))
    }

    // ---------- normalizeText ----------

    @Test
    fun `normalizeText trims and maps blank to null`() {
        assertNull(TeamAskAnswerRules.normalizeText(null))
        assertNull(TeamAskAnswerRules.normalizeText(""))
        assertNull(TeamAskAnswerRules.normalizeText("   "))
        assertNull(TeamAskAnswerRules.normalizeText("\n\t "))
        assertEquals("hello", TeamAskAnswerRules.normalizeText("  hello  "))
    }

    @Test
    fun `normalizeText keeps inner whitespace and case`() {
        assertEquals("a  b", TeamAskAnswerRules.normalizeText("\t a  b \n"))
        assertEquals("ABC-1", TeamAskAnswerRules.normalizeText("ABC-1"))
    }

    // ---------- 幂等键 ----------

    @Test
    fun `idempotency keys use the ask and answer prefixes verbatim`() {
        assertEquals("ask:c1", TeamAskAnswerRules.askIdempotencyKey("c1"))
        assertEquals("answer:c1", TeamAskAnswerRules.answerIdempotencyKey("c1"))
        assertEquals(
            "ask:3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            TeamAskAnswerRules.askIdempotencyKey("3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
        )
        assertEquals(
            "answer:3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            TeamAskAnswerRules.answerIdempotencyKey("3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
        )
    }

    @Test
    fun `ask and answer keys never collide for the same correlation id`() {
        assertNotEquals(
            TeamAskAnswerRules.askIdempotencyKey("c1"),
            TeamAskAnswerRules.answerIdempotencyKey("c1"),
        )
        assertNotEquals(
            TeamAskAnswerRules.askIdempotencyKey("c1"),
            TeamAskAnswerRules.askIdempotencyKey("c2"),
        )
    }

    // ---------- validateAsk ----------

    @Test
    fun `validateAsk accepts a normal ask and the 4000 char boundary`() {
        assertNull(TeamAskAnswerRules.validateAsk("c1", "what is the plan"))
        assertNull(TeamAskAnswerRules.validateAsk("c1", "q".repeat(4000)))
        assertNull(TeamAskAnswerRules.validateAsk("3f2504e0-4f89-11d3-9a0c-0305e82c3301", "x"))
    }

    @Test
    fun `validateAsk rejects 4001 chars with the exact no truncation message`() {
        assertEquals(
            "question too long (4001 > 4000); shorten it instead of truncating",
            TeamAskAnswerRules.validateAsk("c1", "q".repeat(4001)),
        )
    }

    @Test
    fun `validateAsk rejects blank and null questions`() {
        assertEquals("question must not be blank", TeamAskAnswerRules.validateAsk("c1", null))
        assertEquals("question must not be blank", TeamAskAnswerRules.validateAsk("c1", ""))
        assertEquals("question must not be blank", TeamAskAnswerRules.validateAsk("c1", "   "))
        assertEquals("question must not be blank", TeamAskAnswerRules.validateAsk("c1", "\n\t "))
    }

    @Test
    fun `validateAsk rejects an illegal correlation id with the exact message`() {
        assertEquals(
            "correlationId must be 1-64 chars of [A-Za-z0-9_-]",
            TeamAskAnswerRules.validateAsk(null, "q"),
        )
        assertEquals(
            "correlationId must be 1-64 chars of [A-Za-z0-9_-]",
            TeamAskAnswerRules.validateAsk("", "q"),
        )
        assertEquals(
            "correlationId must be 1-64 chars of [A-Za-z0-9_-]",
            TeamAskAnswerRules.validateAsk("bad corr", "q"),
        )
        assertEquals(
            "correlationId must be 1-64 chars of [A-Za-z0-9_-]",
            TeamAskAnswerRules.validateAsk("a".repeat(65), "q"),
        )
    }

    @Test
    fun `validateAsk checks the correlation id before the question`() {
        assertEquals(
            "correlationId must be 1-64 chars of [A-Za-z0-9_-]",
            TeamAskAnswerRules.validateAsk("bad corr", "   "),
        )
        assertEquals(
            "correlationId must be 1-64 chars of [A-Za-z0-9_-]",
            TeamAskAnswerRules.validateAsk("bad corr", "q".repeat(4001)),
        )
    }

    // ---------- validateAnswer ----------

    @Test
    fun `validateAnswer accepts a normal answer and the 4000 char boundary`() {
        assertNull(TeamAskAnswerRules.validateAnswer("c1", "use option B"))
        assertNull(TeamAskAnswerRules.validateAnswer("c1", "a".repeat(4000)))
    }

    @Test
    fun `validateAnswer rejects 4001 chars with the exact no truncation message`() {
        assertEquals(
            "answer too long (4001 > 4000); shorten it instead of truncating",
            TeamAskAnswerRules.validateAnswer("c1", "a".repeat(4001)),
        )
    }

    @Test
    fun `validateAnswer rejects blank and null answers`() {
        assertEquals("answer must not be blank", TeamAskAnswerRules.validateAnswer("c1", null))
        assertEquals("answer must not be blank", TeamAskAnswerRules.validateAnswer("c1", ""))
        assertEquals("answer must not be blank", TeamAskAnswerRules.validateAnswer("c1", " \n "))
    }

    @Test
    fun `validateAnswer checks the correlation id first`() {
        assertEquals(
            "correlationId must be 1-64 chars of [A-Za-z0-9_-]",
            TeamAskAnswerRules.validateAnswer(null, "ok"),
        )
        assertEquals(
            "correlationId must be 1-64 chars of [A-Za-z0-9_-]",
            TeamAskAnswerRules.validateAnswer("bad corr", "   "),
        )
    }

    // ---------- encodeAsk / parseAsk ----------

    @Test
    fun `encodeAsk then parseAsk round trips with a member name`() {
        val original = ask(correlationId = "c1", question = "what is the plan", memberName = "Alice")
        val text = TeamAskAnswerRules.encodeAsk(original)
        assertTrue("encoded should carry v:1: $text", text.contains("\"v\":1"))
        assertTrue("encoded should carry the correlation id: $text", text.contains("\"correlationId\":\"c1\""))
        assertEquals(original, TeamAskAnswerRules.parseAsk(text))
    }

    @Test
    fun `encodeAsk without a member name omits the field and parses back to null`() {
        val text = TeamAskAnswerRules.encodeAsk(ask(memberName = null))
        assertFalse("memberName must be omitted when null: $text", text.contains("memberName"))
        assertEquals(TeamAsk("c1", "what is the plan", null), TeamAskAnswerRules.parseAsk(text))
    }

    @Test
    fun `encodeAsk omits a blank member name`() {
        val text = TeamAskAnswerRules.encodeAsk(ask(memberName = "   "))
        assertFalse("memberName must be omitted when blank: $text", text.contains("memberName"))
        assertEquals(TeamAsk("c1", "what is the plan", null), TeamAskAnswerRules.parseAsk(text))
    }

    @Test
    fun `encodeAsk and parseAsk keep quotes newlines and unicode verbatim`() {
        val question = "他说：\"用哪个？\"\n第二行\t带制表符 \\ 反斜杠"
        val original = ask(correlationId = "a-b_c-123", question = question, memberName = "队友甲")
        val parsed = TeamAskAnswerRules.parseAsk(TeamAskAnswerRules.encodeAsk(original))
        assertEquals(original, parsed)
        assertEquals(question, parsed?.question)
    }

    @Test
    fun `parseAsk tolerates surrounding whitespace`() {
        val text = "  \n{\"v\":1,\"correlationId\":\"c1\",\"question\":\"q\"}\n  "
        assertEquals(TeamAsk("c1", "q", null), TeamAskAnswerRules.parseAsk(text))
    }

    @Test
    fun `parseAsk returns null for empty and non object input`() {
        assertNull(TeamAskAnswerRules.parseAsk(null))
        assertNull(TeamAskAnswerRules.parseAsk(""))
        assertNull(TeamAskAnswerRules.parseAsk("   "))
        assertNull(TeamAskAnswerRules.parseAsk("[]"))
        assertNull(TeamAskAnswerRules.parseAsk("null"))
        assertNull(TeamAskAnswerRules.parseAsk("not json"))
        assertNull(TeamAskAnswerRules.parseAsk("{"))
        assertNull(TeamAskAnswerRules.parseAsk("\"text\""))
    }

    @Test
    fun `parseAsk returns null for a wrong or missing protocol version`() {
        assertNull(TeamAskAnswerRules.parseAsk("{\"correlationId\":\"c1\",\"question\":\"q\"}"))
        assertNull(TeamAskAnswerRules.parseAsk("{\"v\":2,\"correlationId\":\"c1\",\"question\":\"q\"}"))
        assertNull(TeamAskAnswerRules.parseAsk("{\"v\":0,\"correlationId\":\"c1\",\"question\":\"q\"}"))
    }

    @Test
    fun `parseAsk returns null for an illegal correlation id`() {
        assertNull(TeamAskAnswerRules.parseAsk("{\"v\":1,\"correlationId\":\"bad corr\",\"question\":\"q\"}"))
        assertNull(TeamAskAnswerRules.parseAsk("{\"v\":1,\"correlationId\":\"\",\"question\":\"q\"}"))
        assertNull(
            TeamAskAnswerRules.parseAsk(
                "{\"v\":1,\"correlationId\":\"" + "a".repeat(65) + "\",\"question\":\"q\"}",
            ),
        )
    }

    @Test
    fun `parseAsk returns null for a blank or missing question`() {
        assertNull(TeamAskAnswerRules.parseAsk("{\"v\":1,\"correlationId\":\"c1\",\"question\":\"   \"}"))
        assertNull(TeamAskAnswerRules.parseAsk("{\"v\":1,\"correlationId\":\"c1\",\"question\":\"\"}"))
        assertNull(TeamAskAnswerRules.parseAsk("{\"v\":1,\"correlationId\":\"c1\"}"))
    }

    @Test
    fun `parseAsk returns null for a 4001 character question`() {
        val text = "{\"v\":1,\"correlationId\":\"c1\",\"question\":\"" + "q".repeat(4001) + "\"}"
        assertNull(TeamAskAnswerRules.parseAsk(text))
    }

    @Test
    fun `parseAsk maps a blank member name to null`() {
        assertEquals(
            TeamAsk("c1", "q", null),
            TeamAskAnswerRules.parseAsk("{\"v\":1,\"correlationId\":\"c1\",\"question\":\"q\",\"memberName\":\"   \"}"),
        )
    }

    @Test
    fun `parseAsk returns null instead of throwing on hostile input`() {
        val hostile = listOf(
            "{",
            "}",
            "{\"v\":1",
            "{\"v\":1,}",
            "{\"v\":1,\"correlationId\":\"c1\",\"question\":\"q\"",
        )
        for (text in hostile) {
            try {
                assertNull("hostile input must parse to null: $text", TeamAskAnswerRules.parseAsk(text))
            } catch (e: Exception) {
                throw AssertionError("parseAsk threw on: $text", e)
            }
        }
    }

    // ---------- encodeAnswer / parseAnswer ----------

    @Test
    fun `encodeAnswer then parseAnswer round trips`() {
        val original = answer(correlationId = "c1", answer = "use option B")
        val text = TeamAskAnswerRules.encodeAnswer(original)
        assertTrue("encoded should carry v:1: $text", text.contains("\"v\":1"))
        assertTrue("encoded should carry the correlation id: $text", text.contains("\"correlationId\":\"c1\""))
        assertEquals(original, TeamAskAnswerRules.parseAnswer(text))
    }

    @Test
    fun `encodeAnswer and parseAnswer keep newlines quotes and unicode`() {
        val body = "第一步：改 A\n第二步：\t跑 \"测试\" \\ 完成"
        val original = TeamAnswer(correlationId = "a-b_c-123", answer = body)
        val parsed = TeamAskAnswerRules.parseAnswer(TeamAskAnswerRules.encodeAnswer(original))
        assertEquals(original, parsed)
        assertEquals(body, parsed?.answer)
    }

    @Test
    fun `parseAnswer returns null for malformed input`() {
        assertNull(TeamAskAnswerRules.parseAnswer(null))
        assertNull(TeamAskAnswerRules.parseAnswer(""))
        assertNull(TeamAskAnswerRules.parseAnswer("   "))
        assertNull(TeamAskAnswerRules.parseAnswer("[]"))
        assertNull(TeamAskAnswerRules.parseAnswer("not json"))
        assertNull(TeamAskAnswerRules.parseAnswer("{\"correlationId\":\"c1\",\"answer\":\"ok\"}"))
        assertNull(TeamAskAnswerRules.parseAnswer("{\"v\":2,\"correlationId\":\"c1\",\"answer\":\"ok\"}"))
        assertNull(TeamAskAnswerRules.parseAnswer("{\"v\":1,\"correlationId\":\"bad corr\",\"answer\":\"ok\"}"))
        assertNull(TeamAskAnswerRules.parseAnswer("{\"v\":1,\"correlationId\":\"c1\",\"answer\":\"  \"}"))
        assertNull(TeamAskAnswerRules.parseAnswer("{\"v\":1,\"correlationId\":\"c1\"}"))
    }

    @Test
    fun `parseAnswer returns null for a 4001 character answer`() {
        val text = "{\"v\":1,\"correlationId\":\"c1\",\"answer\":\"" + "a".repeat(4001) + "\"}"
        assertNull(TeamAskAnswerRules.parseAnswer(text))
    }

    // ---------- 渲染（精确整串） ----------

    @Test
    fun `renderAskForLead matches the exact text`() {
        val expected = "【队友「Alice」提问 · correlationId=c1】\n" +
            "what is the plan\n" +
            "\n" +
            "用 send_message 回复：target=<队友名>，text=<你的答复>，reply_to=c1。默认会唤醒它继续；只想记录答复就传 resume=false。"
        val rendered = TeamAskAnswerRules.renderAskForLead(
            "Alice",
            ask(question = "what is the plan", memberName = "Alice"),
        )
        assertEquals(expected, rendered)
        assertFalse("must not end with a newline", rendered.endsWith("\n"))
    }

    @Test
    fun `renderAskForLead falls back to 队友 when memberName is null`() {
        val expected = "【队友「队友」提问 · correlationId=c1】\n" +
            "what is the plan\n" +
            "\n" +
            "用 send_message 回复：target=<队友名>，text=<你的答复>，reply_to=c1。默认会唤醒它继续；只想记录答复就传 resume=false。"
        val rendered = TeamAskAnswerRules.renderAskForLead(null, ask(memberName = null))
        assertEquals(expected, rendered)
        assertFalse("must not end with a newline", rendered.endsWith("\n"))
    }

    @Test
    fun `renderAskForLead keeps a multi line question verbatim`() {
        val question = "第一行\n第二行\n\n第四行"
        val expected = "【队友「Alice」提问 · correlationId=a-b_c】\n" +
            question + "\n" +
            "\n" +
            "用 send_message 回复：target=<队友名>，text=<你的答复>，reply_to=a-b_c。默认会唤醒它继续；只想记录答复就传 resume=false。"
        assertEquals(expected, TeamAskAnswerRules.renderAskForLead("Alice", TeamAsk("a-b_c", question, "Alice")))
    }

    @Test
    fun `renderAnswerForMember matches the exact text`() {
        val expected = "【领队答复 · correlationId=c1】\nuse option B"
        val rendered = TeamAskAnswerRules.renderAnswerForMember(answer(answer = "use option B"))
        assertEquals(expected, rendered)
        assertFalse("must not end with a newline", rendered.endsWith("\n"))
        assertFalse("must not reuse the ask header", rendered.startsWith("【队友"))
    }

    @Test
    fun `renderAnswerForMember keeps a multi line answer verbatim`() {
        val body = "第一行\n第二行"
        val expected = "【领队答复 · correlationId=a-b_c】\n" + body
        assertEquals(expected, TeamAskAnswerRules.renderAnswerForMember(TeamAnswer("a-b_c", body)))
    }

    // ---------- 编码体积 ----------

    @Test
    fun `a 4000 character question still encodes and parses back`() {
        val question = "问".repeat(4000)
        val encoded = TeamAskAnswerRules.encodeAsk(TeamAsk("c1", question, "Alice"))
        assertTrue("encoded length must stay under 12000, got ${encoded.length}", encoded.length <= 12000)
        assertEquals(TeamAsk("c1", question, "Alice"), TeamAskAnswerRules.parseAsk(encoded))
    }

    @Test
    fun `a 4000 character answer still encodes and parses back`() {
        val body = "答".repeat(4000)
        val encoded = TeamAskAnswerRules.encodeAnswer(TeamAnswer("c1", body))
        assertTrue("encoded length must stay under 12000, got ${encoded.length}", encoded.length <= 12000)
        assertEquals(TeamAnswer("c1", body), TeamAskAnswerRules.parseAnswer(encoded))
    }

    @Test
    fun `a 4000 char question and a 4000 char member name still fit the encoded limit`() {
        val question = "q".repeat(4000)
        val name = "n".repeat(4000)
        val encoded = TeamAskAnswerRules.encodeAsk(TeamAsk("c1", question, name))
        assertTrue("expected < 12000, got ${encoded.length}", encoded.length < 12000)
        assertEquals(TeamAsk("c1", question, name), TeamAskAnswerRules.parseAsk(encoded))
    }

    @Test
    fun `encodeAsk throws instead of truncating an oversized payload`() {
        val tooBig = TeamAsk("c1", "q", "x".repeat(20000))
        val error = assertThrows(IllegalArgumentException::class.java) {
            TeamAskAnswerRules.encodeAsk(tooBig)
        }
        val message = error.message.orEmpty()
        assertTrue(
            "message should start with 'ask payload too large': $message",
            message.startsWith("ask payload too large"),
        )
    }
}
