package me.rerere.ai.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮 debug 修复 A（工具执行期间到达的插话）的回归测试。
 *
 * 契约见 `/root/results/hare-debug-fix-20261104-1545/CONTRACT.md` A 段：
 * 工具运行期间到达的新指令必须进入该工具完成后的下一次模型请求；不打断正在执行的工具；
 * 同批尚未执行的调用不继续盲跑，用明确的 cancelled/superseded 输出闭合后交模型重新判断；
 * 不制造没有 output 的孤儿 tool_call；无插话时工具/预算/取消契约不变。
 *
 * 期望值全部手写，不读取产品实现内部状态（只看工具 execute 的副作用与发给模型的 messages）。
 * 本文件不改任何产品代码，也不依赖旧 `AgentLoopTest` 的私有 helper（自带一份 fake）。
 */
class AgentLoopSteerDeliveryFixTest {

    private val model = Model(modelId = "test-model")
    private val provider = FixSteerProvider()
    private val hooks = FixSteerHooks(model)

    private fun request(
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
    ) = AgentRequest(
        provider = provider,
        providerSetting = ProviderSetting.OpenAI(),
        messages = messages,
        tools = tools,
        stream = true,
        maxSteps = maxSteps,
    )

    private suspend fun runToEnd(
        request: AgentRequest = request(),
        hooks: AgentHooks = this.hooks,
    ) = runAgent(request, hooks).toList()

    // ---------- A1：工具执行期间到达的插话，进入该工具完成后的下一次请求 ----------

    @Test
    fun `steer arriving while a tool runs is delivered in the next model request`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executed = mutableListOf<String>()
        val tools = listOf(
            fixTool("slow") {
                executed += "slow"
                toolStarted.complete(Unit)
                release.await()
                listOf(UIMessagePart.Text("slow done"))
            },
        )
        provider.stream {
            fixToolCall("c1", "slow")
            fixFinish()
        }
        provider.stream {
            fixText("done")
            fixFinish()
        }
        // 兜底：修复前插话会被推迟一个请求，用它保证失败是断言而不是 removeFirst 异常
        provider.stream {
            fixText("late")
            fixFinish()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(tools = tools), hooks).toList(events) }
        toolStarted.await()
        hooks.push(UIMessage.user("STEER-A1"))
        release.complete(Unit)
        job.join()

        // 执行中的工具不被中断，正常跑完
        assertEquals(listOf("slow"), executed)
        assertEquals(2, provider.requests.size)
        val second = provider.requests[1]
        assertEquals(MessageRole.USER, second.messages.last().role)
        assertEquals(listOf("STEER-A1"), second.messages.last().texts())
        val assistant = second.messages.filter { it.role == MessageRole.ASSISTANT }.last()
        assertTrue(assistant.tool("c1").isExecuted)
        assertEquals("slow done", assistant.tool("c1").outputText())
        assertEquals(AgentFinishReason.Completed, events.finished().reason)
    }

    // ---------- A2：同批剩余工具不再执行，但必须补 output ----------

    @Test
    fun `steer during a tool supersedes the remaining tools of the same batch`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executed = mutableListOf<String>()
        val tools = listOf(
            fixTool("slow") {
                executed += "slow"
                toolStarted.complete(Unit)
                release.await()
                listOf(UIMessagePart.Text("slow done"))
            },
            fixTool("second") {
                executed += "second"
                listOf(UIMessagePart.Text("second done"))
            },
        )
        provider.stream {
            fixToolCall("c1", "slow")
            fixToolCall("c2", "second")
            fixFinish()
        }
        provider.stream {
            fixText("ok")
            fixFinish()
        }
        // 兜底：修复前剩余工具仍会执行并多走一次请求
        provider.stream {
            fixText("late")
            fixFinish()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(tools = tools), hooks).toList(events) }
        toolStarted.await()
        hooks.push(UIMessage.user("STEER-A2"))
        release.complete(Unit)
        job.join()

        assertEquals(listOf("slow"), executed)
        val message = events.finished().message
        assertEquals("slow done", message.tool("c1").outputText())
        assertTrue(message.tool("c2").isExecuted)
        assertTrue(
            "superseded output must say it did not run: ${message.tool("c2").outputText()}",
            Regex("(?i)(cancel|supersed|skip)").containsMatchIn(message.tool("c2").outputText()),
        )
        // 被取代的调用从未真正开始：不写执行计时
        assertNull(message.tool("c2").startedAt)
        assertNull(message.tool("c2").finishedAt)
        assertEquals(AgentFinishReason.Completed, events.finished().reason)
    }

    // ---------- A3：下一次请求里没有孤儿 tool_call ----------

    @Test
    fun `every tool call keeps an output so the next request has no orphan tool call`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tools = listOf(
            fixTool("slow") {
                toolStarted.complete(Unit)
                release.await()
                listOf(UIMessagePart.Text("slow done"))
            },
            fixTool("second") { listOf(UIMessagePart.Text("second done")) },
        )
        provider.stream {
            fixToolCall("c1", "slow")
            fixToolCall("c2", "second")
            fixFinish()
        }
        provider.stream {
            fixText("ok")
            fixFinish()
        }
        // 兜底：修复前会多走一次请求
        provider.stream {
            fixText("late")
            fixFinish()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(tools = tools), hooks).toList(events) }
        toolStarted.await()
        hooks.push(UIMessage.user("STEER-A3"))
        release.complete(Unit)
        job.join()

        val second = provider.requests[1]
        val assistant = second.messages.filter { it.role == MessageRole.ASSISTANT }.last()
        val toolParts = assistant.getTools()
        assertEquals(2, toolParts.size)
        assertTrue(toolParts.all { it.isExecuted })
        assertTrue(
            "every tool part must carry non-blank output: $toolParts",
            toolParts.all { it.outputText().isNotBlank() },
        )
        // 插话是最后一条消息；承载工具结果的 assistant 在它之前
        assertEquals(MessageRole.USER, second.messages.last().role)
        assertEquals(listOf("STEER-A3"), second.messages.last().texts())
        assertEquals(events.finished().message.id, assistant.id)
    }

    // ---------- A4：插话只送达一次，后续步骤不重复追加 ----------

    @Test
    fun `a steer is delivered exactly once across later steps`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tools = listOf(
            fixTool("slow") {
                toolStarted.complete(Unit)
                release.await()
                listOf(UIMessagePart.Text("slow done"))
            },
            fixTool("next") { listOf(UIMessagePart.Text("next done")) },
        )
        provider.stream {
            fixToolCall("c1", "slow")
            fixFinish()
        }
        provider.stream {
            fixToolCall("c2", "next")
            fixFinish()
        }
        provider.stream {
            fixText("done")
            fixFinish()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(tools = tools), hooks).toList(events) }
        toolStarted.await()
        hooks.push(UIMessage.user("STEER-A4"))
        release.complete(Unit)
        job.join()

        assertEquals(3, provider.requests.size)
        assertEquals(1, provider.requests[1].messages.count { it.role == MessageRole.USER && it.texts() == listOf("STEER-A4") })
        assertEquals(1, provider.requests[2].messages.count { it.role == MessageRole.USER && it.texts() == listOf("STEER-A4") })
        assertEquals(AgentFinishReason.Completed, events.finished().reason)
    }

    // ---------- A5：工具刚生成、尚未开始执行时插话已到 -> 一个都不跑 ----------

    @Test
    fun `a steer already queued when the tools are generated supersedes all of them`() = runTest {
        val executed = mutableListOf<String>()
        val tools = listOf(
            fixTool("first") {
                executed += "first"
                listOf(UIMessagePart.Text("first done"))
            },
            fixTool("second") {
                executed += "second"
                listOf(UIMessagePart.Text("second done"))
            },
        )
        provider.stream {
            fixToolCall("c1", "first")
            fixToolCall("c2", "second")
            // 刚生成完工具、还没开始执行，新话已经到了
            hooks.push(UIMessage.user("STEER-TOP"))
            fixFinish()
        }
        provider.stream {
            fixText("ok")
            fixFinish()
        }

        val events = runToEnd(request(tools = tools))

        assertTrue("no tool may run when a steer is already queued: $executed", executed.isEmpty())
        val message = events.finished().message
        assertEquals(2, message.getTools().size)
        assertTrue(message.getTools().all { it.isExecuted })
        assertTrue(message.getTools().all { it.outputText().isNotBlank() })
        // 一个都没跑：全部没有执行计时
        assertTrue(message.getTools().all { it.startedAt == null && it.finishedAt == null })
        assertEquals(2, provider.requests.size)
        assertEquals(MessageRole.USER, provider.requests[1].messages.last().role)
        assertEquals(listOf("STEER-TOP"), provider.requests[1].messages.last().texts())
        assertEquals(AgentFinishReason.Completed, events.finished().reason)
    }

    // ---------- A6/A7：无插话时既有工具与预算契约不变 ----------

    @Test
    fun `without a steer the whole batch runs in call order`() = runTest {
        val executed = mutableListOf<String>()
        val tools = listOf(
            fixTool("first") {
                executed += "first"
                listOf(UIMessagePart.Text("first done"))
            },
            fixTool("second") {
                executed += "second"
                listOf(UIMessagePart.Text("second done"))
            },
        )
        provider.stream {
            fixToolCall("c1", "first")
            fixToolCall("c2", "second")
            fixFinish()
        }
        provider.stream {
            fixText("done")
            fixFinish()
        }

        val finished = runToEnd(request(tools = tools)).finished()

        assertEquals(listOf("first", "second"), executed)
        assertEquals("first done", finished.message.tool("c1").outputText())
        assertEquals("second done", finished.message.tool("c2").outputText())
        assertEquals(AgentFinishReason.Completed, finished.reason)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun `without a steer the step budget still ends the run after the tools ran`() = runTest {
        val executed = mutableListOf<String>()
        val tools = listOf(
            fixTool("only") {
                executed += "only"
                listOf(UIMessagePart.Text("only done"))
            },
        )
        provider.stream {
            fixToolCall("c1", "only")
            fixFinish()
        }

        val finished = runToEnd(request(tools = tools, maxSteps = 1)).finished()

        assertEquals(AgentFinishReason.StepLimitReached, finished.reason)
        assertEquals(listOf("only"), executed)
        assertTrue(finished.message.tool("c1").isExecuted)
        assertEquals(1, provider.requests.size)
    }

    // ---------- A8/A9：取消仍然向上传播 ----------

    @Test
    fun `cancellation during a tool still propagates and keeps earlier results`() = runTest {
        val secondStarted = CompletableDeferred<Unit>()
        val tools = listOf(
            fixTool("first") { listOf(UIMessagePart.Text("first done")) },
            fixTool("second") {
                secondStarted.complete(Unit)
                awaitCancellation()
            },
        )
        provider.stream {
            fixToolCall("c1", "first")
            fixToolCall("c2", "second")
            fixFinish()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(tools = tools), hooks).toList(events) }
        secondStarted.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(events.none { it is AgentEvent.Finished })
        val last = (events.last() as AgentEvent.MessageUpdated).message
        assertEquals("first done", last.tool("c1").outputText())
        assertFalse(last.tool("c2").isExecuted)
    }

    @Test
    fun `cancellation still propagates when a steer is queued`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val tools = listOf(
            fixTool("hang") {
                toolStarted.complete(Unit)
                awaitCancellation()
            },
        )
        provider.stream {
            fixToolCall("c1", "hang")
            fixFinish()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(tools = tools), hooks).toList(events) }
        toolStarted.await()
        hooks.push(UIMessage.user("STEER-CANCEL"))
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(events.none { it is AgentEvent.Finished })
        // 取消后不再为插话发新请求
        assertEquals(1, provider.requests.size)
    }

    // ---------- A10：同一步到达的多条插话按 FIFO 各送达一次 ----------

    @Test
    fun `two steers queued during one tool are delivered in FIFO order once each`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tools = listOf(
            fixTool("slow") {
                toolStarted.complete(Unit)
                release.await()
                listOf(UIMessagePart.Text("done"))
            },
        )
        provider.stream {
            fixToolCall("c1", "slow")
            fixFinish()
        }
        provider.stream {
            fixText("ok")
            fixFinish()
        }
        // 兜底：修复前插话会被推迟一个请求
        provider.stream {
            fixText("late")
            fixFinish()
        }

        val events = mutableListOf<AgentEvent>()
        val job = launch { runAgent(request(tools = tools), hooks).toList(events) }
        toolStarted.await()
        hooks.push(UIMessage.user("STEER-1"))
        hooks.push(UIMessage.user("STEER-2"))
        release.complete(Unit)
        job.join()

        val tail = provider.requests[1].messages.takeLast(2)
        assertEquals(listOf("STEER-1", "STEER-2"), tail.map { it.texts().single() })
        assertEquals(listOf(MessageRole.USER, MessageRole.USER), tail.map { it.role })
        assertEquals(AgentFinishReason.Completed, events.finished().reason)
    }
}

/** 按调用顺序依次返回预设的流式响应，并记录每次请求。 */
private class FixSteerProvider : Provider<ProviderSetting> {
    data class Request(val messages: List<UIMessage>, val params: TextGenerationParams)

    val requests = mutableListOf<Request>()
    private val streams = ArrayDeque<suspend FlowCollector<StreamChunk>.() -> Unit>()

    fun stream(block: suspend FlowCollector<StreamChunk>.() -> Unit) {
        streams += block
    }

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = error("non-streaming is not used in this test")

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        requests += Request(messages, params)
        val block = streams.removeFirst()
        return flow { block() }
    }
}

/** 记录每次 prepareStep 的 messages，并从可随时 push 的队列里取插话。 */
private class FixSteerHooks(private val model: Model) : AgentHooks {
    val prepared = mutableListOf<List<UIMessage>>()
    private val steerQueue = mutableListOf<UIMessage>()

    fun push(message: UIMessage) {
        steerQueue += message
    }

    override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
        prepared += messages
        return StepRequest(messages, TextGenerationParams(model = model))
    }

    override suspend fun drainSteer(): List<UIMessage> {
        if (steerQueue.isEmpty()) return emptyList()
        val drained = steerQueue.toList()
        steerQueue.clear()
        return drained
    }
}

private fun fixTool(
    name: String,
    needsApproval: Boolean = false,
    execute: suspend (JsonElement) -> List<UIMessagePart> = { listOf(UIMessagePart.Text("ok")) },
) = Tool(name = name, description = name, needsApproval = { needsApproval }, execute = execute)

private suspend fun FlowCollector<StreamChunk>.fixText(text: String, id: String = "text") {
    emit(StreamChunk.TextStart(id))
    emit(StreamChunk.TextDelta(id, text))
    emit(StreamChunk.TextEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.fixToolCall(id: String, name: String, input: String = "{}") {
    emit(StreamChunk.ToolCallStart(id, name))
    emit(StreamChunk.ToolCallDelta(id, inputDelta = input))
    emit(StreamChunk.ToolCallEnd(id))
}

private suspend fun FlowCollector<StreamChunk>.fixFinish() {
    emit(StreamChunk.Finish(finishReason = "stop"))
}

private fun UIMessage.tool(id: String) = getTools().single { it.toolCallId == id }

private fun UIMessage.texts() = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

private fun UIMessagePart.Tool.outputText() =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

private fun List<AgentEvent>.finished() = last() as AgentEvent.Finished
