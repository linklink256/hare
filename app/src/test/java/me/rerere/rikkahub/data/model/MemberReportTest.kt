package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemberReport 的纯规则单测（JUnit4，无 Android 依赖）。
 *
 * 期望值全部是本文件手写的字面量；只有「整体编码长度」用例需要用 encode 测出夹具的当前长度
 * 再调整到 15000/15001（调整的是输入数据，不是用产品规则生成通过/拒绝的期望）。
 * 工具注册与权限、ChatService 暂存/终态取用、mailbox 消费渲染、真实模型行为均未覆盖，见 TEST-PLAN.md。
 */
class MemberReportTest {

    private fun report(
        status: MemberReportStatus = MemberReportStatus.DONE,
        summary: String = "done",
        artifacts: List<MemberReportArtifact> = emptyList(),
        evidence: String? = null,
        v: Int = MemberReportRules.VERSION,
    ) = MemberReport(v = v, status = status, summary = summary, artifacts = artifacts, evidence = evidence)

    private fun artifact(ref: String = "a.txt", sha256: String? = null, note: String? = null) =
        MemberReportArtifact(ref = ref, sha256 = sha256, note = note)

    // ---------- validate：summary ----------

    @Test
    fun `validate rejects an empty or blank summary`() {
        assertEquals("summary must not be empty", MemberReportRules.validate(report(summary = "")))
        assertEquals("summary must not be empty", MemberReportRules.validate(report(summary = "   ")))
        assertEquals("summary must not be empty", MemberReportRules.validate(report(summary = "\n\t")))
    }

    @Test
    fun `validate accepts 4000 summary chars and rejects 4001 without truncating`() {
        assertNull(MemberReportRules.validate(report(summary = "a".repeat(4000))))

        val oversized = report(summary = "a".repeat(4001))
        val reason = MemberReportRules.validate(oversized).orEmpty()
        assertTrue("expected a rejection, got empty", reason.isNotEmpty())
        assertTrue("reason should carry the actual length: $reason", reason.contains("4001"))
        assertTrue("reason should carry the limit: $reason", reason.contains("4000"))
        assertTrue("rejection must not truncate: $reason", reason.contains("truncating"))
        // 校验不修改入参：字段保持原长度
        assertEquals(4001, oversized.summary.length)
    }

    // ---------- validate：artifacts / ref / evidence ----------

    @Test
    fun `validate accepts 0 and 20 artifacts and rejects 21`() {
        assertNull(MemberReportRules.validate(report(artifacts = emptyList())))
        assertNull(MemberReportRules.validate(report(artifacts = List(20) { artifact(ref = "r$it") })))

        val reason = MemberReportRules.validate(report(artifacts = List(21) { artifact(ref = "r$it") })).orEmpty()
        assertTrue("expected a rejection, got empty", reason.isNotEmpty())
        assertTrue("reason should carry the actual count: $reason", reason.contains("21"))
        assertTrue("reason should carry the limit: $reason", reason.contains("20"))
    }

    @Test
    fun `validate accepts a 512 char ref and rejects 513`() {
        assertNull(MemberReportRules.validate(report(artifacts = listOf(artifact(ref = "a".repeat(512))))))

        val reason = MemberReportRules.validate(report(artifacts = listOf(artifact(ref = "a".repeat(513))))).orEmpty()
        assertTrue("expected a rejection, got empty", reason.isNotEmpty())
        assertTrue("reason should name the artifact: $reason", reason.contains("artifacts[0].ref"))
        assertTrue("reason should carry the actual length: $reason", reason.contains("513"))
        assertTrue("reason should carry the limit: $reason", reason.contains("512"))
    }

    @Test
    fun `validate accepts 2000 evidence chars and rejects 2001`() {
        assertNull(MemberReportRules.validate(report(evidence = "e".repeat(2000))))

        val reason = MemberReportRules.validate(report(evidence = "e".repeat(2001))).orEmpty()
        assertTrue("expected a rejection, got empty", reason.isNotEmpty())
        assertTrue("reason should mention evidence: $reason", reason.contains("evidence"))
        assertTrue("reason should carry the actual length: $reason", reason.contains("2001"))
        assertTrue("reason should carry the limit: $reason", reason.contains("2000"))
    }

    @Test
    fun `validate rejects an unsupported version`() {
        val reason = MemberReportRules.validate(report(v = 2)).orEmpty()

        assertTrue("expected a rejection, got empty", reason.isNotEmpty())
        assertTrue("reason should name the version: $reason", reason.contains("version 2"))
        assertTrue("reason should name the expected version: $reason", reason.contains("expected 1"))
    }

    // ---------- validate：整体编码长度 ----------

    @Test
    fun `validate accepts a report whose encoding is exactly 15000 chars`() {
        val tuned = reportEncodedTo(15000)

        assertNull(MemberReportRules.validate(tuned))
        assertEquals(15000, MemberReportRules.encode(tuned).length)
    }

    @Test
    fun `validate rejects a report whose encoding is 15001 chars and does not truncate it`() {
        val tuned = reportEncodedTo(15001)

        val reason = MemberReportRules.validate(tuned).orEmpty()
        assertTrue("expected a rejection, got empty", reason.isNotEmpty())
        assertTrue("reason should carry the actual length: $reason", reason.contains("15001"))
        assertTrue("reason should carry the limit: $reason", reason.contains("15000"))
        assertTrue("reason should ask to shorten fields: $reason", reason.contains("shorten"))
        // 拒绝不等于截断：字段与编码长度原样保留
        assertEquals(15001, MemberReportRules.encode(tuned).length)
    }

    /**
     * 把 20 条 artifact 的 note 当作可调负载，将整体编码长度精确调到 target。
     * note 恒为非 null 字符串，每个字符对 JSON 的贡献固定为 1（只用 'a'，无转义），
     * 所以这里只调整输入夹具；通过/拒绝的期望仍是手写常量。
     */
    private fun reportEncodedTo(target: Int): MemberReport {
        fun build(extraChars: Int): MemberReport {
            var remaining = extraChars
            val artifacts = List(20) { index ->
                val take = minOf(remaining, MemberReportRules.MAX_EVIDENCE_CHARS)
                remaining -= take
                MemberReportArtifact(ref = "r$index", note = "a".repeat(take))
            }
            return MemberReport(status = MemberReportStatus.DONE, summary = "s", artifacts = artifacts)
        }

        val baseLength = MemberReportRules.encode(build(0)).length
        val tuned = build(target - baseLength)
        assertEquals("fixture must hit the exact encoded length", target, MemberReportRules.encode(tuned).length)
        return tuned
    }

    // ---------- encode → parse 往返 ----------

    @Test
    fun `encode then parse round trips a done report with artifacts and evidence`() {
        val original = report(
            status = MemberReportStatus.DONE,
            summary = " 完成 A，产物见下 ",
            artifacts = listOf(
                artifact(ref = "build/a.apk", sha256 = "0123456789abcdef", note = "已签名"),
                artifact(ref = "docs/b.md"),
            ),
            evidence = "日志 run-1",
        )

        val parsed = MemberReportRules.parse(MemberReportRules.encode(original))

        assertEquals(original, parsed)
    }

    @Test
    fun `encode then parse round trips a blocked report without artifacts`() {
        val original = report(status = MemberReportStatus.BLOCKED, summary = "缺权限", evidence = null)

        assertEquals(original, MemberReportRules.parse(MemberReportRules.encode(original)))
    }

    @Test
    fun `parse returns null for non-JSON input without throwing`() {
        for (text in listOf("", "   ", "plain text", "```json\n{}\n```", "[1,2]", "<xml/>")) {
            assertNull("input '$text' must not parse", MemberReportRules.parse(text))
        }
    }

    @Test
    fun `parse returns null for an unsupported version`() {
        val json = """{"v":2,"status":"done","summary":"s"}"""

        assertNull(MemberReportRules.parse(json))
    }

    @Test
    fun `parse returns null for an unknown status`() {
        val json = """{"v":1,"status":"OK","summary":"s"}"""

        assertNull(MemberReportRules.parse(json))
    }

    @Test
    fun `parse returns null when the summary field is missing`() {
        val json = """{"v":1,"status":"done"}"""

        assertNull(MemberReportRules.parse(json))
    }

    @Test
    fun `parse returns null when artifacts is not an array`() {
        val json = """{"v":1,"status":"done","summary":"s","artifacts":{}}"""

        assertNull(MemberReportRules.parse(json))
    }

    @Test
    fun `parse returns null when the encoded body breaks a field limit`() {
        val json = """{"v":1,"status":"done","summary":"s","evidence":"${"e".repeat(2001)}"}"""

        assertNull(MemberReportRules.parse(json))
    }

    // ---------- renderForLead ----------

    @Test
    fun `renderForLead renders member name status summary artifacts and evidence`() {
        val rendered = MemberReportRules.renderForLead(
            memberName = "小张",
            report = report(
                status = MemberReportStatus.DONE,
                summary = " 已完成 A ",
                artifacts = listOf(
                    artifact(ref = "build/a.apk", sha256 = "abcdef1234567890", note = "注意签名"),
                    artifact(ref = "docs/b.md"),
                ),
                evidence = "  日志见 run1  ",
            ),
        )

        val expected = listOf(
            "【队友「小张」完成了】",
            "已完成 A",
            "",
            "产物（2）：",
            "- build/a.apk  sha256:abcdef123456  注意签名",
            "- docs/b.md",
            "",
            "证据：日志见 run1",
        ).joinToString("\n")
        assertEquals(expected, rendered)
    }

    @Test
    fun `renderForLead uses a different title for blocked`() {
        val done = MemberReportRules.renderForLead("小张", report(status = MemberReportStatus.DONE, summary = "s"))
        val blocked = MemberReportRules.renderForLead("小张", report(status = MemberReportStatus.BLOCKED, summary = "s"))

        assertEquals("【队友「小张」完成了】\ns", done)
        assertEquals("【队友「小张」卡住了】\ns", blocked)
        assertFalse(done == blocked)
    }

    @Test
    fun `renderForLead prints every artifact ref with a 12 char sha prefix`() {
        val longSha = "0123456789abcdef0123456789abcdef"
        val rendered = MemberReportRules.renderForLead(
            memberName = "worker",
            report = report(
                artifacts = listOf(
                    artifact(ref = "first.txt", sha256 = longSha),
                    artifact(ref = "second.txt", sha256 = "short"),
                ),
            ),
        )

        assertTrue(rendered.contains("- first.txt  sha256:0123456789ab"))
        assertFalse("only the first 12 chars may be printed", rendered.contains(longSha))
        assertTrue(rendered.contains("- second.txt  sha256:short"))
        assertTrue(rendered.contains("产物（2）："))
    }

    @Test
    fun `renderForLead skips blank sha and blank evidence`() {
        val rendered = MemberReportRules.renderForLead(
            memberName = "worker",
            report = report(
                summary = "done",
                artifacts = listOf(artifact(ref = "a.txt", sha256 = "   ")),
                evidence = "   ",
            ),
        )

        assertFalse("blank sha must not be printed", rendered.contains("sha256:"))
        assertFalse("blank evidence must not be printed", rendered.contains("证据："))
        assertTrue(rendered.contains("- a.txt"))
    }

    @Test
    fun `renderForLead with no artifacts emits no empty list`() {
        val rendered = MemberReportRules.renderForLead("worker", report(summary = "done", artifacts = emptyList()))

        assertEquals("【队友「worker」完成了】\ndone", rendered)
        assertFalse("no artifact header for an empty list", rendered.contains("产物（"))
        assertFalse("no bullet lines for an empty list", rendered.contains("\n- "))
    }
}
