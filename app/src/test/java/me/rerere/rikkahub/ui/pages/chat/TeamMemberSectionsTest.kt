package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.TeamMember
import me.rerere.rikkahub.data.model.TeamMemberListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 共享纯分组规则（TeamMemberSections.kt）的边界与稳定性测试。
 * 只构造内存对象，不加载会话、不碰 DB、不需要 Android 运行时。
 */
class TeamMemberSectionsTest {
    private val teamId = Uuid.random()

    private fun member(name: String, lastRunElapsedMillis: Long? = null): TeamMember = TeamMember(
        teamId = teamId,
        name = name,
        conversationId = Uuid.random(),
        lastRunElapsedMillis = lastRunElapsedMillis,
    )

    private fun row(
        name: String = "m",
        running: Boolean = false,
        hasActiveJob: Boolean = running,
        elapsedMillis: Long? = null,
        lastRunElapsedMillis: Long? = null,
        awaitingApproval: Boolean? = null,
        hasError: Boolean = false,
    ) = TeamMemberListItem(
        member = member(name, lastRunElapsedMillis),
        running = running,
        title = name,
        elapsedMillis = elapsedMillis,
        hasActiveJob = hasActiveJob,
        awaitingApproval = awaitingApproval,
        hasError = hasError,
    )

    /** 明确收尾的一轮：无 job、有耗时、审批 false、无错误。 */
    private fun finishedRow(name: String, elapsed: Long = 3_400L) = row(
        name = name,
        running = false,
        hasActiveJob = false,
        elapsedMillis = elapsed,
        awaitingApproval = false,
    )

    // ---- 分组优先级 ----

    @Test
    fun `running member sorts before finished peer even with stale elapsed record`() {
        val oldRun = finishedRow("done_earlier")
        val restarted = row(
            name = "restarted",
            running = true,
            hasActiveJob = true,
            elapsedMillis = 9_000L,
            awaitingApproval = false,
        )
        val sections = groupTeamMembers(listOf(oldRun, restarted))

        assertEquals(listOf("restarted"), sections.running.map { it.member.name })
        assertEquals(listOf("done_earlier"), sections.finished.map { it.member.name })
        assertFalse(restarted.isFinished)
        assertEquals(TeamMemberSection.RUNNING, restarted.teamMemberSection())
    }

    @Test
    fun `active job that is admitted is running`() {
        val item = row(running = true, hasActiveJob = true)
        assertEquals(TeamMemberSection.RUNNING, item.teamMemberSection())
        assertFalse(item.isFinished)
    }

    @Test
    fun `active job still waiting for a concurrency slot is queued not finished`() {
        // 已派活但尚未 admitted：running=false，同时带着上一轮的结束时间
        val waiting = row(
            name = "waiting",
            running = false,
            hasActiveJob = true,
            elapsedMillis = 5_000L,
            awaitingApproval = false,
        )
        val sections = groupTeamMembers(listOf(waiting))

        assertEquals(TeamMemberSection.QUEUED, waiting.teamMemberSection())
        assertFalse(waiting.isFinished)
        assertEquals(listOf("waiting"), sections.queued.map { it.member.name })
        assertTrue(sections.finished.isEmpty())
        assertTrue(sections.running.isEmpty())
    }

    // ---- 不可收纳（必须留在活跃/未知一侧） ----

    @Test
    fun `pending approval is never finished`() {
        val item = row(
            name = "approval",
            hasActiveJob = false,
            elapsedMillis = 1_200L,
            awaitingApproval = true,
        )
        assertEquals(TeamMemberSection.PENDING, item.teamMemberSection())
        assertFalse(item.isFinished)
        assertEquals(1, groupTeamMembers(listOf(item)).pending.size)
    }

    @Test
    fun `unknown approval for a historical member stays outside finished`() {
        // 历史会话未缓存 / 未加载：审批状态未知，只能 fail-closed 留在外侧
        val historical = row(
            name = "historical",
            hasActiveJob = false,
            elapsedMillis = null,
            lastRunElapsedMillis = 7_700L,
            awaitingApproval = null,
        )
        val sections = groupTeamMembers(listOf(historical))

        assertFalse(historical.isFinished)
        assertEquals(TeamMemberSection.PENDING, historical.teamMemberSection())
        assertEquals(listOf("historical"), sections.pending.map { it.member.name })
        assertTrue(sections.finished.isEmpty())
    }

    @Test
    fun `member with live error is never finished`() {
        val broken = row(
            name = "broken",
            hasActiveJob = false,
            elapsedMillis = 2_000L,
            awaitingApproval = false,
            hasError = true,
        )
        assertFalse(broken.isFinished)
        assertEquals(TeamMemberSection.PENDING, broken.teamMemberSection())
        assertTrue(groupTeamMembers(listOf(broken)).finished.isEmpty())
    }

    @Test
    fun `member without any elapsed record is not finished`() {
        val neverRan = row(name = "never_ran", hasActiveJob = false, awaitingApproval = false)
        val noPersisted = row(
            name = "no_persisted",
            hasActiveJob = false,
            elapsedMillis = null,
            lastRunElapsedMillis = null,
            awaitingApproval = false,
        )
        assertFalse(neverRan.isFinished)
        assertFalse(noPersisted.isFinished)
        assertEquals(TeamMemberSection.PENDING, neverRan.teamMemberSection())
        assertEquals(TeamMemberSection.PENDING, noPersisted.teamMemberSection())

        val sections = groupTeamMembers(listOf(neverRan, noPersisted))
        assertEquals(2, sections.pending.size)
        assertTrue(sections.finished.isEmpty())
    }

    // ---- 唯一可收纳的组合 ----

    @Test
    fun `only idle member with elapsed time and clearly no approval and no error is finished`() {
        val clean = finishedRow("clean")
        assertTrue(clean.isFinished)
        assertEquals(TeamMemberSection.FINISHED, clean.teamMemberSection())

        val sections = groupTeamMembers(listOf(clean))
        assertEquals(listOf("clean"), sections.finished.map { it.member.name })
        assertEquals(1, sections.totalSize)
        assertTrue(sections.running.isEmpty() && sections.queued.isEmpty() && sections.pending.isEmpty())
    }

    @Test
    fun `persisted elapsed is enough when the drawer row has no ticker sample`() {
        // 抽屉列表不跑 ticker：elapsedMillis 为 null，退回 member.lastRunElapsedMillis
        val drawerRow = row(
            name = "drawer",
            hasActiveJob = false,
            elapsedMillis = null,
            lastRunElapsedMillis = 4_500L,
            awaitingApproval = false,
        )
        assertTrue(drawerRow.isFinished)
        assertEquals(TeamMemberSection.FINISHED, drawerRow.teamMemberSection())
    }

    // ---- 稳定性：顺序、不改输入、不按秒排序 ----

    @Test
    fun `section keeps relative input order instead of sorting`() {
        val a = finishedRow("a", 9_000L)
        val b = finishedRow("b", 100L)
        val c = row(name = "c", running = true, hasActiveJob = true)
        val d = row(name = "d", running = true, hasActiveJob = true)
        val sections = groupTeamMembers(listOf(a, b, c, d))

        assertEquals(listOf("c", "d"), sections.running.map { it.member.name })
        assertEquals(listOf("a", "b"), sections.finished.map { it.member.name })
    }

    @Test
    fun `grouping does not mutate the caller list`() {
        val input = mutableListOf(finishedRow("a"), row(name = "b", running = true, hasActiveJob = true))
        val before = input.toList()

        groupTeamMembers(input)

        assertEquals(before, input)
        assertEquals(listOf("a", "b"), input.map { it.member.name })
    }

    @Test
    fun `changing elapsed seconds does not reorder members`() {
        val first = row(name = "first", running = true, hasActiveJob = true, elapsedMillis = 1_000L)
        val second = row(name = "second", running = true, hasActiveJob = true, elapsedMillis = 2_000L)

        val before = groupTeamMembers(listOf(first, second))
        val after = groupTeamMembers(
            listOf(first.copy(elapsedMillis = 60_000L), second.copy(elapsedMillis = 61_000L))
        )

        assertEquals(listOf("first", "second"), before.running.map { it.member.name })
        assertEquals(listOf("first", "second"), after.running.map { it.member.name })
    }

    @Test
    fun `redispatch after finishing moves the member back out of finished`() {
        val done = finishedRow("worker")
        assertTrue(done.isFinished)

        val redispatched = done.copy(running = true, hasActiveJob = true)
        val sections = groupTeamMembers(listOf(done, redispatched))

        // 同一个成员两个快照：旧的是结束，新派活后必须是 RUNNING，且不再计入结束组
        assertEquals(TeamMemberSection.RUNNING, redispatched.teamMemberSection())
        assertEquals(TeamMemberSection.FINISHED, done.teamMemberSection())
        assertEquals(1, sections.running.size)
        assertEquals(1, sections.finished.size)

        val onlyNew = groupTeamMembers(listOf(redispatched))
        assertTrue(onlyNew.finished.isEmpty())
        assertEquals(listOf("worker"), onlyNew.running.map { it.member.name })
    }

    // ---- 集合完整性 ----

    @Test
    fun `empty input yields four empty sections`() {
        val sections = groupTeamMembers(emptyList())
        assertTrue(sections.running.isEmpty())
        assertTrue(sections.queued.isEmpty())
        assertTrue(sections.pending.isEmpty())
        assertTrue(sections.finished.isEmpty())
        assertEquals(0, sections.totalSize)
    }

    @Test
    fun `all finished input only fills the finished bucket`() {
        val input = listOf(finishedRow("a"), finishedRow("b"), finishedRow("c"))
        val sections = groupTeamMembers(input)

        assertEquals(3, sections.totalSize)
        assertEquals(listOf("a", "b", "c"), sections.finished.map { it.member.name })
        assertTrue(sections.running.isEmpty())
        assertTrue(sections.queued.isEmpty())
        assertTrue(sections.pending.isEmpty())
    }

    @Test
    fun `mixed input loses and duplicates nothing`() {
        val input = listOf(
            row(name = "running", running = true, hasActiveJob = true),
            row(name = "queued", hasActiveJob = true),
            row(name = "pending", awaitingApproval = true),
            finishedRow("finished"),
        )
        val sections = groupTeamMembers(input)

        assertEquals(input.size, sections.totalSize)
        val ids = sections.running + sections.queued + sections.pending + sections.finished
        assertEquals(input.map { it.member.id }.sortedBy { it.toString() }, ids.map { it.member.id }.sortedBy { it.toString() })
        assertEquals(input.map { it.member.id }.toSet(), ids.map { it.member.id }.toSet())
        assertEquals(4, ids.size)
    }

    @Test
    fun `sectionOf maps every bucket back to its list`() {
        val running = row(name = "r", running = true, hasActiveJob = true)
        val queued = row(name = "q", hasActiveJob = true)
        val pending = row(name = "p")
        val finished = finishedRow("f")
        val sections = groupTeamMembers(listOf(running, queued, pending, finished))

        assertEquals(listOf(running), sections.of(TeamMemberSection.RUNNING))
        assertEquals(listOf(queued), sections.of(TeamMemberSection.QUEUED))
        assertEquals(listOf(pending), sections.of(TeamMemberSection.PENDING))
        assertEquals(listOf(finished), sections.of(TeamMemberSection.FINISHED))
    }

    // ---- 字段默认兼容旧调用点 ----

    @Test
    fun `legacy rows without the new fields stay conservative`() {
        val legacyIdle = TeamMemberListItem(member = member("legacy_idle"), running = false, title = "legacy_idle")
        assertFalse(legacyIdle.hasActiveJob)
        assertNull(legacyIdle.awaitingApproval)
        assertFalse(legacyIdle.hasError)
        assertNull(legacyIdle.elapsedMillis)
        assertNull(legacyIdle.member.lastRunElapsedMillis)
        assertFalse(legacyIdle.isFinished)
        assertEquals(TeamMemberSection.PENDING, legacyIdle.teamMemberSection())

        val legacyRunning = TeamMemberListItem(member = member("legacy_running"), running = true, title = "")
        assertTrue(legacyRunning.hasActiveJob)
        assertEquals(TeamMemberSection.RUNNING, legacyRunning.teamMemberSection())
    }
}
