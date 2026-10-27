package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.AgentTeamException
import org.junit.Assert.*
import org.junit.Test

class TeamToolAccessTest {
    @Test fun childrenCannotInheritLeaderTools() {
        assertTrue(canUseTeamTools(true, false))
        assertFalse(canUseTeamTools(true, true))
        assertFalse(canUseTeamTools(false, false))
        assertFalse(canUseTeamTools(false, true))
    }
    @Test fun allowedLeaderExecutesOriginalTool() = runTest {
        var executions = 0
        val tool = Tool("test", "", execute = { executions++; listOf(UIMessagePart.Text("ok")) })
        assertEquals(listOf(UIMessagePart.Text("ok")), guardTeamTool(tool) {}.execute(JsonNull))
        assertEquals(1, executions)
    }
    @Test fun cachedToolRechecksAuthorizationOnEachExecution() = runTest {
        var member = false; var executions = 0
        val tool = guardTeamTool(Tool("test", "", execute = { executions++; emptyList() })) {
            if (member) throw AgentTeamException("teammates denied")
        }
        tool.execute(JsonNull); member = true
        val result = tool.execute(JsonNull)
        assertEquals(1, executions)
        assertTrue((result.single() as UIMessagePart.Text).text.contains("teammates denied"))
    }
    @Test fun cancellationNotSwallowedByGuard() = runTest {
        val tool = guardTeamTool(Tool("test", "", execute = { fail(); emptyList() })) {
            throw CancellationException("cancel")
        }
        try { tool.execute(JsonNull); fail() } catch (_: CancellationException) { }
    }
}
