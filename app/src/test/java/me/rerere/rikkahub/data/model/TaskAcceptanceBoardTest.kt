package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * acceptedTaskIds(tasks, acceptances) 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的字面量真值表，只使用 SPEC-R5C §1 声明的 API
 * （TeamTask / TaskAcceptance / acceptedTaskIds）；不从产品实现反推期望。
 * 本轮不运行测试（executed: false），未覆盖项见 TEST-R5C.md。
 */
class TaskAcceptanceBoardTest {

    // ---------- helpers ----------

    private fun task(
        id: Uuid,
        status: TeamTaskStatus = TeamTaskStatus.DONE,
        revision: Long = 1,
    ) = TeamTask(
        id = id,
        teamId = TEST_TEAM,
        title = "task",
        status = status,
        revision = revision,
    )

    private fun acceptance(
        taskId: Uuid,
        taskRevision: Long,
    ) = TaskAcceptance(
        teamId = TEST_TEAM,
        taskId = taskId,
        taskRevision = taskRevision,
        resultRef = "ref-1",
        evidenceRef = "ev-1",
        acceptedBy = "lead",
    )

    // ---------- 1. 无凭据 / 空输入 ----------

    @Test
    fun `no acceptance means nothing is accepted`() {
        val a = task(T1, revision = 1)
        val b = task(T2, revision = 4)
        assertEquals(emptySet<Uuid>(), acceptedTaskIds(listOf(a, b), emptyList()))
    }

    @Test
    fun `empty task list yields an empty set`() {
        assertEquals(
            emptySet<Uuid>(),
            acceptedTaskIds(emptyList(), listOf(acceptance(T1, 1))),
        )
    }

    @Test
    fun `both inputs empty yields an empty set`() {
        assertEquals(emptySet<Uuid>(), acceptedTaskIds(emptyList(), emptyList()))
    }

    // ---------- 2. 同 revision 命中 ----------

    @Test
    fun `done task with an acceptance at its revision is accepted`() {
        val t = task(T1, revision = 3)
        assertEquals(setOf(T1), acceptedTaskIds(listOf(t), listOf(acceptance(T1, 3))))
    }

    @Test
    fun `each task matches its own revision`() {
        val a = task(T1, revision = 1)
        val b = task(T2, revision = 7)
        assertEquals(
            setOf(T1, T2),
            acceptedTaskIds(listOf(a, b), listOf(acceptance(T2, 7), acceptance(T1, 1))),
        )
    }

    // ---------- 3. 旧 revision（reopen 后）不命中 ----------

    @Test
    fun `stale acceptance after reopen is not accepted`() {
        val reopened = task(T1, revision = 2)
        assertEquals(
            emptySet<Uuid>(),
            acceptedTaskIds(listOf(reopened), listOf(acceptance(T1, 1))),
        )
    }

    @Test
    fun `acceptance at a future revision does not match`() {
        val t = task(T1, revision = 2)
        assertEquals(
            emptySet<Uuid>(),
            acceptedTaskIds(listOf(t), listOf(acceptance(T1, 3))),
        )
    }

    @Test
    fun `many acceptances for one task match only the current revision`() {
        val t = task(T1, revision = 2)
        val history = listOf(
            acceptance(T1, 1),
            acceptance(T1, 2),
            acceptance(T1, 3),
        )
        assertEquals(setOf(T1), acceptedTaskIds(listOf(t), history))
    }

    // ---------- 4. 别的任务 / 不在列表里的任务 ----------

    @Test
    fun `acceptance of another task never matches`() {
        val t = task(T1, revision = 1)
        assertEquals(
            emptySet<Uuid>(),
            acceptedTaskIds(listOf(t), listOf(acceptance(T2, 1))),
        )
    }

    @Test
    fun `acceptance for a task outside the list is ignored`() {
        val t = task(T1, revision = 1)
        assertEquals(
            emptySet<Uuid>(),
            acceptedTaskIds(listOf(t), listOf(acceptance(T3, 1))),
        )
    }

    // ---------- 5. 混合任务：只返回命中集合 ----------

    @Test
    fun `mixed tasks return only the accepted ids`() {
        // 手写真值：只有 T1（rev 2 + rev 2 凭据）命中；T2 凭据是未来 revision，
        // T3 凭据是旧 revision，T4 没有任何凭据
        val accepted = task(T1, revision = 2)
        val future = task(T2, revision = 1)
        val stale = task(T3, revision = 4)
        val noCredential = task(T4, revision = 1)
        val acceptances = listOf(
            acceptance(T1, 2),
            acceptance(T2, 3),
            acceptance(T3, 3),
        )
        assertEquals(
            setOf(T1),
            acceptedTaskIds(
                listOf(accepted, future, stale, noCredential),
                acceptances,
            ),
        )
    }

    // ---------- 6. 重复凭据只算一个 id ----------

    @Test
    fun `duplicate acceptances at the current revision collapse to one id`() {
        val t = task(T1, revision = 5)
        val duplicates = listOf(acceptance(T1, 5), acceptance(T1, 5))
        val ids = acceptedTaskIds(listOf(t), duplicates)
        assertEquals(setOf(T1), ids)
        assertEquals(1, ids.size)
    }
}

/** 固定 team id 与任务 id：让期望值是字面事实而不是随机巧合 */
private val TEST_TEAM: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
private val T1: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000001")
private val T2: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000002")
private val T3: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000003")
private val T4: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000004")
