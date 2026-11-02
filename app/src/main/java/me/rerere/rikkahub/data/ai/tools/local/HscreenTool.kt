package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val HSCREEN_BIN = "/root/bin/hscreen"
private const val HSCREEN_NATIVE_BIN = "/root/bin/hscreen-native"
private const val HSCREEN_ASSET_CLI = "hscreen/hscreen"
private const val HSCREEN_ASSET_NATIVE = "hscreen/hscreen-native"

/** 本进程内已确认 /root/bin 下 CLI 与引擎就绪的工作区；同一工作区只自愈一次 */
private val hscreenBootstrapped: MutableSet<String> = ConcurrentHashMap.newKeySet()

private val HSCREEN_ACTIONS = listOf(
    "up", "down", "status", "doctor", "tree", "shot",
    "tap", "swipe", "key", "type", "launch",
)

private val HSCREEN_KEYS = listOf("back", "enter", "delete", "tab", "up", "down", "left", "right")

/** 单引号包裹并转义，保证拼接进 shell 时不会被二次解释 */
private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

/** 参数错误：只在工具内部抛出并就地转成结构化 JSON，不让堆栈外泄 */
private class BadArgs(message: String) : Exception(message)

private val COMPONENT_REGEX = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")

/** 从 APK assets 流式算 sha256：期望值的唯一来源就是打进包里的那份文件 */
private fun assetSha256(context: Context, assetName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    context.assets.open(assetName).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** `sha256sum a b` 输出 -> path 到 hash */
private fun parseSha256Sum(stdout: String): Map<String, String> = stdout.lineSequence()
    .mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[0].length == 64) parts[1].trim() to parts[0].lowercase() else null
    }
    .toMap()

/**
 * 保证工作区 /root/bin 下的 CLI 与 native 引擎就是本包内置的那两份：先比 hash，
 * 缺或旧就删掉重写再授权。返回 null 表示就绪，否则是给模型看的失败原因。
 */
private suspend fun ensureHscreenBins(
    context: Context,
    repository: WorkspaceRepository,
    workspaceId: String,
): String? {
    if (workspaceId in hscreenBootstrapped) return null
    val expected = mapOf(
        HSCREEN_BIN to assetSha256(context, HSCREEN_ASSET_CLI),
        HSCREEN_NATIVE_BIN to assetSha256(context, HSCREEN_ASSET_NATIVE),
    )
    val probe = repository.executeCommand(
        id = workspaceId,
        command = "sha256sum $HSCREEN_BIN $HSCREEN_NATIVE_BIN 2>/dev/null || true",
        timeoutMillis = 20_000L,
    )
    val present = parseSha256Sum(probe.stdout)
    val stale = expected.filter { (path, hash) -> present[path] != hash }
    if (stale.isEmpty()) {
        hscreenBootstrapped.add(workspaceId)
        return null
    }
    val remove = repository.executeCommand(
        id = workspaceId,
        command = "rm -f ${stale.keys.joinToString(" ")}",
        timeoutMillis = 20_000L,
    )
    if (remove.timedOut || remove.exitCode != 0) {
        return "cannot remove stale file (exit ${remove.exitCode}): ${remove.stderr.trim().take(200)}"
    }
    for (path in stale.keys) {
        val assetName = if (path == HSCREEN_BIN) HSCREEN_ASSET_CLI else HSCREEN_ASSET_NATIVE
        context.assets.open(assetName).use { input ->
            repository.importFile(
                id = workspaceId,
                area = WorkspaceStorageArea.LINUX,
                destinationPath = "/root/bin",
                fileName = path.substringAfterLast('/'),
                inputStream = input,
            )
        }
    }
    val install = repository.executeCommand(
        id = workspaceId,
        command = "chmod 755 $HSCREEN_BIN $HSCREEN_NATIVE_BIN && sha256sum $HSCREEN_BIN $HSCREEN_NATIVE_BIN",
        timeoutMillis = 20_000L,
    )
    if (install.timedOut || install.exitCode != 0) {
        val detail = install.stderr.trim().ifBlank { install.stdout.trim() }
        return "cannot install bundled hscreen (exit ${install.exitCode}): ${detail.take(200)}"
    }
    val installed = parseSha256Sum(install.stdout)
    val wrong = expected.filter { (path, hash) -> installed[path] != hash }
    if (wrong.isNotEmpty()) {
        return "installed hscreen hash mismatch: ${wrong.keys.joinToString(", ")}"
    }
    hscreenBootstrapped.add(workspaceId)
    return null
}

/**
 * 隐幕（隐藏副屏）工具：在平板的虚拟副屏里起 App、截图、读控件树、注入点击/按键/文本。
 *
 * 实际执行走当前工作区的 [WorkspaceRepository.executeCommand]（容器内 `/root/bin/hscreen`，native 引擎、零 JVM）。
 */
internal fun buildHscreenTool(
    workspaceRepository: WorkspaceRepository,
    context: Context,
    workspaceId: String?,
): Tool = Tool(
    name = "hscreen",
    description = """
        Control the tablet's hidden secondary display ("隐幕"/HiddenScreen) — a virtual screen that does not
        disturb the user's main screen. Actions:
        status/doctor (state), up (start the screen), down (tear it down), tree (read the on-screen control
        tree as JSON: text, class, bounds, clickable/scrollable/editable/password flags), shot (JPEG of the
        screen), tap/swipe/key/type (input), launch (start an app on the secondary screen).
        The secondary screen is 360x640 in its own coordinate space (not the main 2944x1840 screen).
        Always call up before other actions, and call down when you are done — the screen keeps a display
        wake lock while alive (the device will not suspend). Never use it to control the user's own foreground
        app; it cannot see the main screen.
        The CLI and native engine are bundled inside the app: on the first call they are installed automatically
        into /root/bin of the workspace, so no manual setup is needed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { HSCREEN_ACTIONS.forEach { add(it) } })
                    put("description", "Operation to perform")
                })
                put("max_nodes", buildJsonObject {
                    put("type", "integer")
                    put("description", "tree only: max nodes to return (1-120, default 120)")
                })
                put("x", buildJsonObject { put("type", "integer"); put("description", "tap only: x (0-360)") })
                put("y", buildJsonObject { put("type", "integer"); put("description", "tap only: y (0-640)") })
                put("x1", buildJsonObject { put("type", "integer"); put("description", "swipe start x") })
                put("y1", buildJsonObject { put("type", "integer"); put("description", "swipe start y") })
                put("x2", buildJsonObject { put("type", "integer"); put("description", "swipe end x") })
                put("y2", buildJsonObject { put("type", "integer"); put("description", "swipe end y") })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "swipe duration in ms (50-1500, default 300)")
                })
                put("key", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { HSCREEN_KEYS.forEach { add(it) } })
                    put("description", "key only: key name")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "type only: visible ASCII text (no IME / CJK)")
                })
                put("component", buildJsonObject {
                    put("type", "string")
                    put("description", "launch only: component like com.android.settings/.Settings")
                })
            },
            required = listOf("action")
        )
    },
    execute = {
        val params = it as? JsonObject
        fun arg(name: String): String? = (params?.get(name) as? JsonPrimitive)?.contentOrNull
        // 参数错误一律回结构化 JSON：抛异常会被 AgentLoop 统一包成
        // "[java.lang.IllegalArgumentException] xxx\n <12 行堆栈>"，模型读到全是噪音。
        fun reject(
            message: String,
            action: String? = null,
            code: String = "invalid_argument",
        ): List<UIMessagePart> = listOf(
            UIMessagePart.Text(buildJsonObject {
                put("ok", false)
                put("code", code)
                if (!action.isNullOrBlank()) put("action", action)
                put("error", message)
            }.toString())
        )

        if (params == null) return@Tool reject("arguments must be a JSON object")
        val action = arg("action")?.trim()
            ?: return@Tool reject("action is required, one of $HSCREEN_ACTIONS")
        if (action !in HSCREEN_ACTIONS) {
            return@Tool reject("unknown action '$action', must be one of $HSCREEN_ACTIONS", action)
        }
        if (workspaceId.isNullOrBlank()) {
            return@Tool reject("no workspace bound to this assistant (hscreen runs in the llink-droid workspace)", action)
        }

        // 首次调用前把内置 CLI/引擎解到 /root/bin（同一进程每个工作区只做一次）
        val bootstrapError = try {
            ensureHscreenBins(context, workspaceRepository, workspaceId)
        } catch (e: Exception) {
            e.message?.take(200) ?: e::class.simpleName ?: "bootstrap failed"
        }
        if (bootstrapError != null) {
            return@Tool reject(bootstrapError, action, code = "bootstrap_failed")
        }

        fun intArg(name: String, range: IntRange, default: Int? = null): Int {
            val element = params[name] ?: return default ?: throw BadArgs("$name is required for $action")
            val primitive = element as? JsonPrimitive
            val raw = primitive?.intOrNull ?: primitive?.contentOrNull?.trim()?.toIntOrNull()
                ?: throw BadArgs("$name must be an integer, got ${element.toString().take(40)}")
            if (raw !in range) throw BadArgs("$name out of range ${range.first}..${range.last}, got $raw")
            return raw
        }

        val command = try {
            buildList {
                add(HSCREEN_BIN)
                add(action)
                when (action) {
                    "tree" -> add(intArg("max_nodes", 1..120, 120).toString())
                    "tap" -> {
                        add(intArg("x", 0..359).toString())
                        add(intArg("y", 0..639).toString())
                    }
                    "swipe" -> {
                        add(intArg("x1", 0..359).toString())
                        add(intArg("y1", 0..639).toString())
                        add(intArg("x2", 0..359).toString())
                        add(intArg("y2", 0..639).toString())
                        add(intArg("duration_ms", 50..1500, 300).toString())
                    }
                    "key" -> {
                        val key = arg("key") ?: throw BadArgs("key is required for key action")
                        if (key !in HSCREEN_KEYS) throw BadArgs("unknown key '$key', must be one of $HSCREEN_KEYS")
                        add(shellQuote(key))
                    }
                    "type" -> {
                        val text = arg("text") ?: throw BadArgs("text is required for type action")
                        if (text.isEmpty()) throw BadArgs("text must not be empty")
                        add(shellQuote(text))
                    }
                    "launch" -> {
                        val component = arg("component") ?: throw BadArgs("component is required for launch action")
                        if (!component.matches(COMPONENT_REGEX)) {
                            throw BadArgs("invalid component '$component' (expected pkg/.Activity)")
                        }
                        add(component)
                    }
                }
            }.joinToString(" ")
        } catch (e: BadArgs) {
            return@Tool reject(e.message ?: "invalid arguments", action)
        }

        val timeoutMillis = when (action) {
            "down" -> 25_000L
            "up" -> 25_000L
            else -> 20_000L
        }
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = command,
            timeoutMillis = timeoutMillis,
        )
        val payload = buildJsonObject {
            put("ok", result.exitCode == 0 && !result.timedOut)
            put("action", action)
            put("exit_code", result.exitCode)
            if (result.timedOut) put("timed_out", true)
            put("output", result.stdout.trim().ifBlank { result.stderr.trim() })
            if (result.stdout.isNotBlank() && result.stderr.isNotBlank()) {
                put("stderr", result.stderr.trim())
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
