package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant

/** 成员汇报的最终状态；只有这两种。 */
@Serializable
enum class MemberReportStatus {
    @SerialName("done")
    DONE,

    @SerialName("blocked")
    BLOCKED,
}

/** 一条交付物引用：路径/URI + 可选 hash 与说明。 */
@Serializable
data class MemberReportArtifact(
    val ref: String,
    val sha256: String? = null,
    val note: String? = null,
)

/**
 * 成员的结构化汇报（v1）。纯数据 + 纯规则，无 Android 依赖。
 * 超限一律拒绝并给出可读原因，不截断字段。
 */
@Serializable
data class MemberReport(
    val v: Int = MemberReportRules.VERSION,
    val status: MemberReportStatus,
    val summary: String,
    val artifacts: List<MemberReportArtifact> = emptyList(),
    val evidence: String? = null,
)

object MemberReportRules {
    const val VERSION = 1
    const val MAX_SUMMARY_CHARS = 4000
    const val MAX_ARTIFACTS = 20
    const val MAX_REF_CHARS = 512
    const val MAX_EVIDENCE_CHARS = 2000
    const val MAX_ENCODED_CHARS = 15000

    /** null 表示通过；否则是给模型/用户看的可读原因 */
    fun validate(report: MemberReport): String? {
        if (report.v != VERSION) return "unsupported report version ${report.v} (expected $VERSION)"
        val summary = report.summary.trim()
        if (summary.isEmpty()) return "summary must not be empty"
        if (report.summary.length > MAX_SUMMARY_CHARS) {
            return "summary too long (${report.summary.length} > $MAX_SUMMARY_CHARS); shorten it instead of truncating"
        }
        if (report.artifacts.size > MAX_ARTIFACTS) {
            return "too many artifacts (${report.artifacts.size} > $MAX_ARTIFACTS)"
        }
        report.artifacts.forEachIndexed { index, artifact ->
            if (artifact.ref.isBlank()) return "artifacts[$index].ref must not be empty"
            if (artifact.ref.length > MAX_REF_CHARS) {
                return "artifacts[$index].ref too long (${artifact.ref.length} > $MAX_REF_CHARS)"
            }
            val sha = artifact.sha256
            if (sha != null && (sha.isBlank() || sha.length > 128)) {
                return "artifacts[$index].sha256 must be 1..128 chars when present"
            }
            val note = artifact.note
            if (note != null && note.length > MAX_EVIDENCE_CHARS) {
                return "artifacts[$index].note too long (${note.length} > $MAX_EVIDENCE_CHARS)"
            }
        }
        val evidence = report.evidence
        if (evidence != null && evidence.length > MAX_EVIDENCE_CHARS) {
            return "evidence too long (${evidence.length} > $MAX_EVIDENCE_CHARS)"
        }
        val encoded = encode(report)
        if (encoded.length > MAX_ENCODED_CHARS) {
            return "encoded report too large (${encoded.length} > $MAX_ENCODED_CHARS); shorten fields"
        }
        return null
    }

    fun encode(report: MemberReport): String = JsonInstant.encodeToString(report)

    /** 非 JSON / 版本不符 / 字段非法一律返回 null（调用方回退到旧文本路径） */
    fun parse(text: String): MemberReport? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        val report = runCatching { JsonInstant.decodeFromString<MemberReport>(trimmed) }.getOrNull()
            ?: return null
        if (report.v != VERSION) return null
        if (validate(report) != null) return null
        return report
    }

    /** 领队侧渲染：状态、摘要、产物清单（短 hash）、证据 */
    fun renderForLead(memberName: String, report: MemberReport): String = buildString {
        append("【队友「").append(memberName).append("」")
        append(if (report.status == MemberReportStatus.DONE) "完成了】" else "卡住了】")
        append('\n').append(report.summary.trim())
        if (report.artifacts.isNotEmpty()) {
            append("\n\n产物（").append(report.artifacts.size).append("）：")
            report.artifacts.forEach { artifact ->
                append("\n- ").append(artifact.ref)
                artifact.sha256?.takeIf { it.isNotBlank() }?.let { append("  sha256:").append(it.take(12)) }
                artifact.note?.takeIf { it.isNotBlank() }?.let { append("  ").append(it) }
            }
        }
        report.evidence?.takeIf { it.isNotBlank() }?.let { append("\n\n证据：").append(it.trim()) }
    }
}
