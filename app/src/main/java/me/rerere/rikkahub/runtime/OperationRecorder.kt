package me.rerere.rikkahub.runtime

import android.util.Log
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.repository.OperationStore
import kotlin.uuid.Uuid

/**
 * 工具 hook 的唯一落点：把「开始/结束」翻译成 operation + 检查点。
 * 任何失败只记日志，绝不打断工具执行（与既有检查点语义一致；取消路径归 R4）。
 */
class OperationRecorder(private val store: OperationStore) {

    suspend fun onToolStarted(
        run: AgentRun,
        fence: FenceToken,
        toolCallId: String,
        toolName: String,
        input: String,
    ): OperationStartResult? = runCatching {
        store.start(
            OperationStart(
                runId = run.runId,
                attemptNo = run.attemptNo,
                conversationId = run.conversationId,
                originCallId = toolCallId,
                toolName = toolName,
                input = input,
                generation = fence.generation,
            )
        )
    }.onFailure { Log.w(TAG, "operation start failed", it) }.getOrNull()

    suspend fun onToolFinished(
        runId: Uuid,
        attemptNo: Int,
        toolCallId: String,
        output: String?,
        error: Throwable?,
    ): OperationFinishResult? = runCatching {
        val result = store.finish(
            OperationFinish(
                runId = runId,
                attemptNo = attemptNo,
                originCallId = toolCallId,
                output = output,
                error = error?.message,
            )
        )
        if (result is OperationFinishResult.Unsettled) {
            val rows = runCatching { store.listForRun(runId) }.getOrDefault(emptyList())
            Log.w(TAG, "operation unfinished after failed transaction: rows=${rows.size} reason=${result.reason}", result.cause)
        }
        result
    }.onFailure { Log.w(TAG, "operation finish failed", it) }.getOrNull()

    private companion object {
        const val TAG = "OperationRecorder"
    }
}
