package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid

/** 一次结果验收（append-only）：`taskRevision` 是被接受并置 DONE 的那一版 */
data class TaskAcceptance(
    val id: Uuid = Uuid.random(),
    val teamId: Uuid,
    val taskId: Uuid,
    val taskRevision: Long,
    val runId: Uuid? = null,
    val resultRef: String,
    val resultSha256: String? = null,
    val evidenceRef: String,
    val acceptedBy: String,
    val createdAt: Instant = Instant.now(),
)

/** 验收判定：能接受就给出被验收的任务与验证者，否则给出可读原因 */
sealed interface AcceptancePlan {
    data class Apply(val task: TeamTask, val verifier: String) : AcceptancePlan
    data class Rejected(val reason: AcceptanceRejection, val message: String) : AcceptancePlan
}

enum class AcceptanceRejection {
    /** 不是 CLAIMED（PENDING/DONE/FAILED 都不接受） */
    NOT_CLAIMED,

    /** CLAIMED 但没有 owner，无法证明验收者独立于执行者 */
    NO_EXECUTOR,

    /** 验收者就是执行者（worker 自报完成） */
    SELF_ACCEPT,
}

/** 验收的纯规则：无 Android 依赖，JVM 单测可直接覆盖；超限一律拒绝，不截断 */
object TaskAcceptanceRules {
    const val MAX_REF_CHARS = 512
    const val MAX_VERIFIER_CHARS = 32

    /** 非空白且 <=512 才返回 trim 后的值 */
    fun normalizeRef(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value.length > MAX_REF_CHARS) return null
        return value
    }

    /** 64 位十六进制（大小写都接受，返回小写）；null/空返回 null */
    fun normalizeSha256(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.length != 64) return null
        if (!value.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return value
    }

    /** 校验三个引用与验收者；返回 null 表示通过，否则是给人看的一句话 */
    fun validate(
        resultRef: String?,
        resultSha256: String?,
        evidenceRef: String?,
        verifier: String?,
    ): String? {
        val ref = resultRef?.trim().orEmpty()
        if (ref.isEmpty()) return "result_ref must not be blank"
        if (ref.length > MAX_REF_CHARS) return "result_ref too long (${ref.length} > $MAX_REF_CHARS)"

        // sha 是可选字段：只有给了值才必须合法
        val sha = resultSha256?.trim().orEmpty()
        if (sha.isNotEmpty() && normalizeSha256(sha) == null) {
            return "result_sha256 must be 64 hex chars"
        }

        val evidence = evidenceRef?.trim().orEmpty()
        if (evidence.isEmpty()) return "evidence_ref must not be blank"
        if (evidence.length > MAX_REF_CHARS) {
            return "evidence_ref too long (${evidence.length} > $MAX_REF_CHARS)"
        }

        val who = verifier?.trim().orEmpty()
        if (who.isEmpty()) return "verifier must not be blank"
        if (who.length > MAX_VERIFIER_CHARS) {
            return "verifier too long (${who.length} > $MAX_VERIFIER_CHARS)"
        }
        return null
    }

    /** 只有 CLAIMED、owner 已知、验收者 != owner（忽略大小写）才能接受 */
    fun planAccept(task: TeamTask, verifier: String?): AcceptancePlan {
        if (task.status != TeamTaskStatus.CLAIMED) {
            return AcceptancePlan.Rejected(
                AcceptanceRejection.NOT_CLAIMED,
                "task ${task.title} is ${task.status.name}; only a claimed task can be accepted",
            )
        }
        val owner = task.ownerName ?: return AcceptancePlan.Rejected(
            AcceptanceRejection.NO_EXECUTOR,
            "task ${task.title} has no executor; cannot verify independence",
        )
        val who = verifier.orEmpty().trim()
        if (who.equals(owner, ignoreCase = true)) {
            return AcceptancePlan.Rejected(
                AcceptanceRejection.SELF_ACCEPT,
                "verifier must differ from the executor ($owner)",
            )
        }
        return AcceptancePlan.Apply(task, who)
    }
}

/** 面板/工具共用的「哪些任务在当前 revision 已被验收」判定 */
fun acceptedTaskIds(tasks: List<TeamTask>, acceptances: List<TaskAcceptance>): Set<Uuid> =
    tasks.filter { task ->
        acceptances.any { it.taskId == task.id && it.taskRevision == task.revision }
    }.map { it.id }.toSet()
