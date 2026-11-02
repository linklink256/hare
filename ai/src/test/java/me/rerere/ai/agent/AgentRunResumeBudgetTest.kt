package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `runAgent` 的 initialSteps 预算行为（续跑/审批后同 Run 带入已用步数）。
 *
 * 只验证 AI 循环本身：fake Provider 按顺序回放预设流，不触网、不调用真实模型；
 * 服务层（AgentRunLifecycle 之外的接线）与整链路不在本文件范围内。
 * 每个用例都同时核对「模型请求次数」和「工具真实执行次数」，不只看 Finished 枚举。
 */
class AgentRunResumeBudgetTest {

    private val model = Model(modelId = "budget-test-model")
    private val provider = BudgetFakeProvider()
    private val hooks = NoopHooks(model)

    private fun request(
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        initialSteps: Int = 0,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(),
        messages = messages,
        tools = tools,
        stream = true,
        maxSteps = maxSteps,
        initialSteps = initialSteps,
    )

    private suspend fun runToEnd(request: AgentRequest = request()) = runAgent(request, hooks).toList()

    @Test
    fun `default initialSteps keeps the original single-request completion`() = runTest {
        provider.stream {
            emitText("hello")
            emitFinish()
        }

        val events = runToEnd()
        val finished = events.finished()

        assertEquals(0, request().initialSteps)
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(1, provider.requests.size)
        assertEquals(listOf("hello"), finished.message.texts())
    }

    @Test
    fun `initial steps are not reset when the last message is not an assistant`() = runTest {
        var runs = 0
        val search = resumeTool("search") {
            runs++
            ok()
        }
        // 只准备一次响应：若循环把 initialSteps 归零，就会再要第二次响应并在取空时炸掉
        provider.stream {
            emitToolCall("call-1", "search")
            emitFinish()
        }

        val finished = runToEnd(
            request(
                messages = listOf(UIMessage.user("hi")),
                tools = listOf(search),
                maxSteps = 2,
                initialSteps = 1,
            )
        ).finished()

        assertEquals(AgentFinishReason.StepLimitReached, finished.reason)
        assertEquals(1, provider.requests.size)
        assertEquals(1, runs)
        assertTrue(finished.message.tool("call-1").isExecuted)
    }

    @Test
    fun `approved tools run before StepLimit when the budget is already exhausted`() = runTest {
        var runs = 0
        val write = resumeTool("write", needsApproval = true) {
            runs++
            ok()
        }
        val message = resumeAssistant(
            UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = "write",
                input = "{}",
                approvalState = ToolApprovalState.Approved,
            )
        )

        val finished = runToEnd(
            request(
                messages = listOf(UIMessage.user("hi"), message),
                tools = listOf(write),
                maxSteps = 2,
                initialSteps = 2,
            )
        ).finished()

        assertEquals(AgentFinishReason.StepLimitReached, finished.reason)
        assertEquals(0, provider.requests.size)
        assertEquals(1, runs)
        assertTrue(finished.message.tool("call-1").isExecuted)
    }

    @Test
    fun `pending approval wins over StepLimit when the budget is exhausted`() = runTest {
        var runs = 0
        val write = resumeTool("write", needsApproval = true) {
            runs++
            ok()
        }
        val message = resumeAssistant(UIMessagePart.Tool(toolCallId = "call-1", toolName = "write", input = "{}"))

        val finished = runToEnd(
            request(
                messages = listOf(UIMessage.user("hi"), message),
                tools = listOf(write),
                maxSteps = 2,
                initialSteps = 2,
            )
        ).finished()

        assertEquals(AgentFinishReason.AwaitingApproval(setOf("call-1")), finished.reason)
        assertEquals(0, provider.requests.size)
        assertEquals(0, runs)
        assertTrue(finished.message.tool("call-1").isPending)
    }

    @Test
    fun `exhausted budget without a trailing assistant message is rejected`() = runTest {
        val error = runCatching {
            runToEnd(request(messages = listOf(UIMessage.user("hi")), maxSteps = 2, initialSteps = 2))
        }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        assertTrue(
            "message should explain the trailing assistant requirement: ${error?.message}",
            error?.message?.contains("trailing assistant") == true,
        )
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun `initial steps below zero are rejected`() = runTest {
        val error = runCatching {
            runToEnd(request(messages = listOf(UIMessage.user("hi")), maxSteps = 2, initialSteps = -1))
        }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        assertTrue(
            "message should name the allowed range: ${error?.message}",
            error?.message?.contains("initialSteps") == true,
        )
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun `initial steps above the limit are rejected`() = runTest {
        val error = runCatching {
            runToEnd(request(messages = listOf(UIMessage.user("hi")), maxSteps = 2, initialSteps = 3))
        }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $error", error is IllegalArgumentException)
        assertTrue(
            "message should name the allowed range: ${error?.message}",
            error?.message?.contains("initialSteps") == true,
        )
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun `a finished assistant message completes instead of hitting StepLimit at the same budget`() = runTest {
        val message = UIMessage.assistant("done")

        val finished = runToEnd(
            request(messages = listOf(UIMessage.user("hi"), message), maxSteps = 2, initialSteps = 2)
        ).finished()

        // 完成判定在 StepLimit 之前：同一预算下不能一律宣告步数用尽
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(message.id, finished.message.id)
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun `initial steps leave only the remaining requests`() = runTest {
        var runs = 0
        val search = resumeTool("search") {
            runs++
            ok()
        }
        provider.stream {
            emitToolCall("call-1", "search")
            emitFinish()
        }
        provider.stream {
            emitText("answer")
            emitFinish()
        }

        val finished = runToEnd(
            request(
                messages = listOf(UIMessage.user("hi")),
                tools = listOf(search),
                maxSteps = 3,
                initialSteps = 1,
            )
        ).finished()

        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(2, provider.requests.size)
        assertEquals(1, runs)
        assertEquals(listOf("answer"), finished.message.texts())
    }
}

/** 按调用顺序回放预设流并记录每次请求；从不访问网络或真实模型。 */
private class BudgetFakeProvider : Provider<ProviderSetting> {
    val requests = mutableListOf<List<UIMessage>>()
    private val streams = ArrayDeque<suspend FlowCollector<StreamChunk>.() -> Unit>()

    fun stream(block: suspend FlowCollector<StreamChunk>.() -> Unit) {
        streams += block
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = error("this test only exercises streaming")

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        requests += messages
        val block = streams.removeFirst()
        return flow { block() }
    }
}

private class NoopHooks(private val model: Model) : AgentHooks {
    override suspend fun prepareStep(messages: List<UIMessage>) =
        StepRequest(messages, TextGenerationParams(model = model))
}

private suspend fun FlowCollector<StreamChunk>.emitText(text: String, id: String = "text") {
    emit(StreamChunk.TextStart(id))
    emit(StreamChunk.TextDelta(id, text))
    emit(StreamChunk.TextEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.emitToolCall(id: String, name: String, input: String = "{}") {
    emit(StreamChunk.ToolCallStart(id, name))
    emit(StreamChunk.ToolCallDelta(id, inputDelta = input))
    emit(StreamChunk.ToolCallEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.emitFinish() {
    emit(StreamChunk.Finish(finishReason = "stop"))
}

private fun resumeTool(
    name: String,
    needsApproval: Boolean = false,
    execute: suspend (JsonElement) -> List<UIMessagePart> = { ok() },
) = Tool(name = name, description = name, needsApproval = { needsApproval }, execute = execute)

private fun ok() = listOf(UIMessagePart.Text("ok"))

private fun resumeAssistant(vararg parts: UIMessagePart) =
    UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

private fun UIMessage.tool(id: String) = getTools().single { it.toolCallId == id }

private fun UIMessage.texts() = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

private fun List<AgentEvent>.finished() = last() as AgentEvent.Finished
