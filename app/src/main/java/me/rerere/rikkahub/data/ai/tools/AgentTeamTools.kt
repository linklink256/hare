package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AgentTeamRules
import me.rerere.rikkahub.data.model.TaskAcceptanceRules
import me.rerere.rikkahub.data.model.TeamAskAnswerRules
import me.rerere.rikkahub.data.model.TeamMemberStatus
import me.rerere.rikkahub.data.model.TeamTask
import me.rerere.rikkahub.data.model.TeamTaskStatus
import me.rerere.rikkahub.data.model.TeamTaskUpdateResult
import me.rerere.rikkahub.data.repository.AgentTeamRepository
import me.rerere.rikkahub.service.AgentTeamException
import me.rerere.rikkahub.service.AgentTeamService
import me.rerere.rikkahub.service.TeamAcceptOutcome
import me.rerere.rikkahub.service.TeamAnswerOutcome
import me.rerere.rikkahub.service.TeamInterruptOutcome
import me.rerere.rikkahub.service.TeamSendOutcome
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

object AgentTeamToolNames {
    const val SPAWN_TEAMMATE = "spawn_teammate"
    const val SEND_MESSAGE = "send_message"
    const val TEAM_STATUS = "team_status"
    const val INTERRUPT_AGENT = "interrupt_agent"
    const val ACCEPT_TASK = "accept_task"
    const val EXPECT_REPORT = "expect_report"
    const val TEAM_TASK = "team_task"

    val ALL = listOf(
        SPAWN_TEAMMATE, SEND_MESSAGE, TEAM_STATUS, INTERRUPT_AGENT,
        ACCEPT_TASK, EXPECT_REPORT, TEAM_TASK,
    )

    // 合并/改名前的旧名字：只留给文档与历史会话兼容引用，不再注册成工具
    /** 系统补写的事件 part：不注册成工具，只作消息标记 */
    const val TEAM_EVENT = "team_event"

    const val LEGACY_ACCEPT_RESULT = "accept_result"
    const val LEGACY_AWAIT_TEAMMATE = "await_teammate"
    const val LEGACY_LIST_AGENTS = "list_agents"
    const val LEGACY_ANSWER_MEMBER = "answer_member"
    const val LEGACY_MY_TASKS = "my_tasks"
    const val LEGACY_TEAM_TASK_CREATE = "team_task_create"
    const val LEGACY_TEAM_TASK_LIST = "team_task_list"
    const val LEGACY_TEAM_TASK_GET = "team_task_get"
    const val LEGACY_TEAM_TASK_UPDATE = "team_task_update"
}

/** team_status 的合法分区；未知值忽略 */
private val TEAM_STATUS_SECTIONS = listOf("agents", "tasks", "events", "budget")
private const val DEFAULT_ROSTER_PREVIEW_CHARS = 2000
private const val MAX_STATUS_TASKS = 50
private const val MAX_TASK_LIST = 100

/**
 * 团队工具（合并后 8 个）。
 *
 * 每个队友都是一个独立会话：`spawn_teammate` 建会话并登记名册，
 * `send_message` 往对方收件箱投递（非阻塞），队友跑完的结果自动投递回来，
 * `interrupt_agent` 按范围打断，`team_task` 管共享任务 DAG + revision compare-and-set，
 * `team_status` 一次读全队名册/任务/事件/额度。
 *
 * 只有 Lead（[leadConversationId] 所属会话）会拿到这些工具。
 */
fun createAgentTeamTools(
    teamService: AgentTeamService,
    teamRepository: AgentTeamRepository,
    leadConversationId: Uuid,
): List<Tool> {
    suspend fun teamId(): Uuid = teamService.getOrCreateTeam(leadConversationId).id
    // 只读解析：不建队；成员解析到父队，嵌套 lead 解析到自己的子队
    suspend fun existingTeam() = teamService.resolveTeamForCaller(leadConversationId)

    return listOf(
        // ---------------- 组建名册 ----------------
        Tool(
            name = AgentTeamToolNames.SPAWN_TEAMMATE,
            description = """
                Create a new teammate that works in its own conversation with its own context.
                Teammates are long-lived: you can message them again later without re-explaining background.
                The name is permanent and never reused (even if creation fails), so pick a clear lowercase name.
                Optionally pass an initial task, which is sent to the teammate immediately (non-blocking).
                This returns a handle (the teammate name) immediately. The round's report is NOT delivered
                as a chat message and needs no polling: when the teammate finishes, the report is pushed into
                this conversation as a tool result and you are woken up automatically (call expect_report if
                you want the report attached to that specific call instead). Never busy-wait for it with
                workspace_shell sleep or repeated team_status calls.
                Only this team's Lead can create teammates.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Unique lowercase name, e.g. researcher1 (letters/digits/_/-)")
                        })
                        put("role", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional role / responsibility for this teammate")
                        })
                        put("task", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional initial task to send right away")
                        })
                        put("allowed_tools", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put(
                                "description",
                                "Optional tool-name allowlist for this teammate. Empty means unrestricted. " +
                                    "report_back/ask_lead are always kept."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = { args ->
                val team = teamId()
                val name = args.obj().str("name")
                    ?: return@Tool toolError("name is required")
                try {
                    val member = teamService.spawnTeammate(
                        teamId = team,
                        caller = leadConversationId,
                        name = name,
                        role = args.obj().str("role").orEmpty(),
                        allowedTools = args.obj().stringList("allowed_tools"),
                        initialTask = args.obj().str("task"),
                    )
                    toolJson {
                        put("created", true)
                        put("name", member.name)
                        put("handle", member.name)
                        put("conversation_id", member.conversationId.toString())
                        put("status", member.status.name)
                        if (member.allowedTools.isNotEmpty()) {
                            put("allowed_tools", buildJsonArray { member.allowedTools.forEach { add(it) } })
                        }
                    }
                } catch (e: AgentTeamException) {
                    toolError(e.message ?: "spawn failed")
                } catch (e: IllegalArgumentException) {
                    toolError(e.message ?: "spawn failed")
                }
            }
        ),

        // ---------------- 全队概览 ----------------
        teamStatusTool(teamService, teamRepository, leadConversationId),

        // ---------------- 与名册对话 / 回答提问 ----------------
        Tool(
            name = AgentTeamToolNames.SEND_MESSAGE,
            description = """
                Send text to a teammate. Three modes:
                (1) plain send: target + text. If you omit steer and pass no task_id/request_key this is a
                    supplement to what that teammate is doing right now: while it is generating the text is
                    injected into the running round (delivery=steered); if it is idle a new round starts
                    (delivery=dispatched); otherwise the call returns delivery=rejected + reason and nothing
                    is queued. steer=false always dispatches a new round (queued behind the current one) and
                    also resumes a queue paused by a user stop or a failed round. task_id/request_key mark an
                    explicit assignment: they keep task binding / idempotency and are never turned into a
                    steer. Non-blocking: the round's report comes back as a tool result and wakes this
                    conversation, so never poll or sleep for it.
                (2) answer a pending question: target + text + reply_to=<correlationId from the question
                    notice>. The answer is recorded into that teammate's inbox and by default re-queues its
                    waiting run (resume=false records it without waking). reply_to and task_id are
                    mutually exclusive.
                (3) force injection: target + text + steer=true. The text goes into the round that is
                    generating right now and is seen at its next model step; no new round, no task binding,
                    no request_key. A tool call that is already executing is NOT interrupted, so the text
                    arrives after it returns. If it cannot be inserted the call returns delivery=rejected +
                    reason and does NOT fall back to a new round. A round paused by a stop/failure or waiting
                    for tool approval is never auto-resumed: pass steer=false to dispatch a new round.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("target", buildJsonObject {
                            put("type", "string")
                            put("description", "Teammate name")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Message body, or the answer when reply_to is set")
                        })
                        put("steer", buildJsonObject {
                            put("type", "boolean")
                            put(
                                "description",
                                "Optional. Omit = auto: inject into the running round when that teammate is " +
                                    "generating, otherwise dispatch a new round. true = inject only (rejected when " +
                                    "it cannot be inserted). false = always dispatch/queue a new round, and resume " +
                                    "a queue paused by a stop or failure."
                            )
                        })
                        put("task_id", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional task id from the shared board. When set, dispatch is refused " +
                                    "unless every blocked_by dependency is DONE, and the created Run is " +
                                    "bound to that task."
                            )
                        })
                        put("reply_to", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "correlationId of a pending question from this team; switches to answer mode"
                            )
                        })
                        put("resume", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Only with reply_to: re-queue the waiting run (default true)")
                        })
                        put("request_key", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional stable key for retry-safe dispatch (send mode only). " +
                                    "Reuse the same value when retrying the same assignment; omitting it uses a fresh key (no dedupe)."
                            )
                        })
                    },
                    required = listOf("target", "text")
                )
            },
            execute = { args ->
                val team = teamId()
                val target = args.obj().str("target")
                    ?: return@Tool toolError("target is required")
                val text = args.obj().str("text")
                    ?: return@Tool toolError("text is required")
                val replyTo = args.obj().str("reply_to")?.trim()?.ifBlank { null }
                val requestKey = args.obj().str("request_key")?.trim()?.ifBlank { null }
                // 未传（null）走自动路由；只有在明确写 false 时才算“派新轮”
                val steer = args.obj().bool("steer")
                val taskId = if (args.obj().containsKey("task_id")) {
                    args.obj().uuid("task_id") ?: return@Tool toolError("task_id must be a valid uuid")
                } else {
                    null
                }
                if (replyTo != null) {
                    // 回答模式：correlationId 决定收件人，target 只用来防止模型张冠李戴
                    if (taskId != null) {
                        return@Tool toolError("reply_to and task_id are mutually exclusive")
                    }
                    if (requestKey != null) {
                        return@Tool toolError("request_key is only valid in send mode")
                    }
                    if (steer == true) return@Tool toolError("steer is only valid in send mode")
                    val invalid = TeamAskAnswerRules.validateAnswer(replyTo, text.trim())
                    if (invalid != null) return@Tool toolError(invalid)
                    try {
                        when (val outcome = teamService.answerAsk(
                            teamId = team,
                            leadConversationId = leadConversationId,
                            correlationId = replyTo,
                            answer = text.trim(),
                            resume = args.obj().bool("resume") ?: true,
                            expectedMember = target,
                        )) {
                            is TeamAnswerOutcome.Queued -> toolJson {
                                put("mode", "answer")
                                put("answered", true)
                                put("correlation_id", outcome.correlationId)
                                put("member", outcome.memberName)
                                put("resumed", outcome.resumed)
                                put("member_waiting", outcome.memberWaiting)
                            }

                            is TeamAnswerOutcome.AlreadyAnswered -> toolJson {
                                put("mode", "answer")
                                put("answered", false)
                                put("reason", "already_answered")
                                put("correlation_id", outcome.correlationId)
                            }

                            TeamAnswerOutcome.AskNotFound ->
                                toolError("no pending question with correlation_id '$replyTo'")

                            TeamAnswerOutcome.NotThisLead ->
                                toolError("that question does not belong to this team")

                            is TeamAnswerOutcome.WrongMember -> toolError(
                                "that question was asked by '${outcome.actualMember}', not '$target'"
                            )
                        }
                    } catch (e: AgentTeamException) {
                        toolError(e.message ?: "answer failed")
                    }
                } else {
                    if (args.obj().containsKey("resume")) {
                        return@Tool toolError("resume is only valid with reply_to")
                    }
                    // 插话不建 Run、不认领任务，也不做幂等键：每次都是一句新话
                    if (steer == true && taskId != null) return@Tool toolError("steer cannot be combined with task_id")
                    if (steer == true && requestKey != null) {
                        return@Tool toolError("request_key is not supported with steer")
                    }
                    try {
                        when (val outcome = teamService.sendToMember(
                            team, leadConversationId, target, text, taskId, requestKey, steer,
                        )) {
                            is TeamSendOutcome.Sent -> toolJson {
                                // 受理回执：这是该 call 的最终输出，结果之后只由 receive_events 交付
                                put("status", "accepted")
                                put("final", true)
                                put("result_via", "receive_events")
                                put("delivery_key", "waiting:${outcome.runId}")
                                put("tool", "send_message")
                                // 普通派发只是投递时快照：dup 命中时既不新建也没新启动
                                put("delivery", if (outcome.queued || outcome.deduplicated) "queued" else "dispatched")
                                put("mode", "send")
                                put("sent", true)
                                put("steered", false)
                                put("target", outcome.member.name)
                                put("handle", outcome.member.name)
                                put("running", outcome.running)
                                if (outcome.queued) put("queued", true)
                                if (outcome.deduplicated) {
                                    put("deduplicated", true)
                                    put(
                                        "note",
                                        "this request_key was already enqueued; nothing new was enqueued or started"
                                    )
                                } else if (outcome.queued) {
                                    put("note", "teammate is still busy on the current round; this runs after it")
                                }
                                // 绑定任务时回填任务状态，模型才知道自己认领了什么
                                outcome.task?.let { task ->
                                    put("task_id", task.id.toString())
                                    put("task_status", task.status.name)
                                }
                            }

                            is TeamSendOutcome.Steered -> toolJson {
                                put("delivery", "steered")
                                put("mode", "steer")
                                put("sent", true)
                                put("steered", true)
                                put("target", outcome.member.name)
                                put("handle", outcome.member.name)
                                put(
                                    "note",
                                    "delivered into the running round; it is seen at the next step, no new round started"
                                )
                            }

                            is TeamSendOutcome.Rejected -> toolJson {
                                put("delivery", "rejected")
                                put("mode", if (steer == true) "steer" else "send")
                                put("sent", false)
                                put("steered", false)
                                put("target", outcome.member.name)
                                put("handle", outcome.member.name)
                                put("reason", outcome.reason.wire)
                                put("note", outcome.reason.hint)
                            }

                            TeamSendOutcome.MemberNotFound ->
                                toolError("no teammate named '$target'; use team_status to see the roster")
                        }
                    } catch (e: AgentTeamException) {
                        toolError(e.message ?: "send failed")
                    }
                }
            }
        ),

        Tool(
            name = AgentTeamToolNames.INTERRUPT_AGENT,
            description = """
                Stop running rounds. scope=one stops just that teammate (default); scope=subtree also stops
                every teammate of the sub-teams it created itself, recursively; scope=all stops every member
                of this team and all descendant teams (never the caller). target is required for one/subtree.
                Interrupted teammates stay on the roster and can be messaged again.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("target", buildJsonObject {
                            put("type", "string")
                            put("description", "Teammate name (required for scope=one|subtree)")
                        })
                        put("scope", buildJsonObject {
                            put("type", "string")
                            put("description", "one (default) | subtree | all")
                            put("enum", buildJsonArray {
                                add("one"); add("subtree"); add("all")
                            })
                        })
                    }
                )
            },
            execute = { args ->
                val team = teamId()
                val scope = args.obj().str("scope")?.trim()?.lowercase()?.ifBlank { null } ?: "one"
                if (scope != "one" && scope != "subtree" && scope != "all") {
                    return@Tool toolError("unknown scope '$scope'; expected one|subtree|all")
                }
                val target = args.obj().str("target")?.trim()?.ifBlank { null }
                if (scope != "all" && target == null) {
                    return@Tool toolError("target is required for scope '$scope'")
                }
                try {
                    val outcomes = teamService.interruptScoped(team, leadConversationId, target, scope)
                    if (outcomes.any { it is TeamInterruptOutcome.MemberNotFound }) {
                        return@Tool toolError("no teammate named '$target'; use team_status to see the roster")
                    }
                    val interrupted = outcomes.filterIsInstance<TeamInterruptOutcome.Interrupted>()
                    toolJson {
                        put("interrupted", interrupted.isNotEmpty())
                        put("count", interrupted.size)
                        put("agents", buildJsonArray {
                            interrupted.forEach { outcome ->
                                add(buildJsonObject {
                                    put("name", outcome.member.name)
                                    put("was_running", outcome.wasRunning)
                                })
                            }
                        })
                        // 单个目标时保留旧字段，历史调用方不用改
                        if (interrupted.size == 1) {
                            put("target", interrupted.single().member.name)
                            put("was_running", interrupted.single().wasRunning)
                        }
                    }
                } catch (e: AgentTeamException) {
                    toolError(e.message ?: "interrupt failed")
                }
            }
        ),

        Tool(
            name = AgentTeamToolNames.ACCEPT_TASK,
            description = """
                Accept one revision of a task (task_id + expected_revision) and mark it done. You must give
                a stable result_ref and a stable evidence_ref so the acceptance can be re-checked later.
                Only the Lead can accept, and the verifier must differ from the task's executor. A task
                becomes done only after acceptance; only then do tasks depending on it become dispatchable.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("task_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Task id")
                        })
                        put("expected_revision", buildJsonObject {
                            put("type", "integer")
                            put("description", "Revision you are accepting (the claimed revision)")
                        })
                        put("result_ref", buildJsonObject {
                            put("type", "string")
                            put("description", "Stable pointer to the result; <= ${TaskAcceptanceRules.MAX_REF_CHARS} chars")
                        })
                        put("result_sha256", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional 64-hex digest of that result")
                        })
                        put("evidence_ref", buildJsonObject {
                            put("type", "string")
                            put("description", "Stable pointer to the evidence you checked; <= ${TaskAcceptanceRules.MAX_REF_CHARS} chars")
                        })
                        put("verifier", buildJsonObject {
                            put("type", "string")
                            put("description", "Who verified it (default lead); must differ from the executor")
                        })
                    },
                    required = listOf("task_id", "expected_revision", "result_ref", "evidence_ref")
                )
            },
            execute = { args ->
                val team = teamId()
                val id = args.obj().uuid("task_id")
                    ?: return@Tool toolError("task_id must be a valid uuid")
                val expectedRevision = args.obj().int("expected_revision")
                    ?: return@Tool toolError("expected_revision is required")
                val resultRef = args.obj().str("result_ref")
                val resultSha256 = args.obj().str("result_sha256")
                val evidenceRef = args.obj().str("evidence_ref")
                val verifier = args.obj().str("verifier")?.trim()?.ifBlank { null } ?: "lead"
                // 工具层先给可读错误；仓库层会再校验一次
                val invalid = TaskAcceptanceRules.validate(resultRef, resultSha256, evidenceRef, verifier)
                if (invalid != null) return@Tool toolError(invalid)
                try {
                    when (val outcome = teamService.acceptResult(
                        teamId = team,
                        caller = leadConversationId,
                        taskId = id,
                        expectedRevision = expectedRevision.toLong(),
                        resultRef = resultRef.orEmpty(),
                        resultSha256 = resultSha256,
                        evidenceRef = evidenceRef.orEmpty(),
                        verifier = verifier,
                    )) {
                        is TeamAcceptOutcome.Accepted -> toolJson {
                            put("accepted", true)
                            put("task_id", outcome.task.id.toString())
                            put("revision", outcome.task.revision)
                            put("accepted_by", outcome.acceptance.acceptedBy)
                        }

                        is TeamAcceptOutcome.AlreadyAccepted -> toolJson {
                            put("accepted", false)
                            put("reason", "already_accepted")
                            put("task_id", outcome.task.id.toString())
                            put("revision", outcome.acceptance.taskRevision)
                        }

                        is TeamAcceptOutcome.Conflict -> toolJson {
                            put("accepted", false)
                            put("reason", "stale_revision")
                            put("task_id", outcome.current.id.toString())
                            put("revision", outcome.current.revision)
                        }

                        TeamAcceptOutcome.NotFound -> toolError("no task with id $id")
                    }
                } catch (e: AgentTeamException) {
                    toolError(e.message ?: "accept failed")
                }
            }
        ),

        // ---------------- 共享任务 DAG ----------------
        Tool(
            name = AgentTeamToolNames.TEAM_TASK,
            description = """
                Read and change the team's shared task board through one tool; pick action=
                create (add a task, title required, blocked_by must reference existing tasks and keep the
                graph acyclic), list (all tasks with status/owner/dependencies/revision), get (one task in
                full) or update (compare-and-set on revision: a stale revision is REJECTED and you get the
                current snapshot back, so re-read and retry instead of overwriting someone's work).
                Statuses: pending -> claimed (or failed); claimed -> failed; done only via accept_task; done/failed can reopen to pending.
                A task can never be set to done here: use accept_task, because only an accepted result
                marks a task done. owner="" unassigns. The board is validated inside one transaction.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("create"); add("list"); add("get"); add("update")
                            })
                        })
                        put("task_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Task id (required for get/update)")
                        })
                        put("revision", buildJsonObject {
                            put("type", "integer")
                            put("description", "Revision you last read (required for update, compare-and-set)")
                        })
                        put("title", buildJsonObject { put("type", "string") })
                        put("description", buildJsonObject { put("type", "string") })
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("pending"); add("claimed"); add("failed")
                            })
                        })
                        put("owner", buildJsonObject {
                            put("type", "string")
                            put("description", "Teammate name, or empty string to unassign")
                        })
                        put("blocked_by", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = { args ->
                val action = args.obj().str("action")?.trim()?.lowercase()?.ifBlank { null }
                    ?: return@Tool toolError("action is required")
                val readOnly = action == "list" || action == "get"
                val team: Uuid? = if (readOnly) existingTeam()?.id else teamId()
                when (action) {
                    "create" -> {
                        val teamId = team ?: return@Tool toolError("no team yet")
                        val title = args.obj().str("title")
                            ?: return@Tool toolError("title is required")
                        val owner = args.obj().str("owner")?.trim()?.ifBlank { null }
                        if (owner != null && teamRepository.getMemberByName(teamId, owner) == null) {
                            return@Tool toolError("no teammate named '$owner'; use team_status to see the roster")
                        }
                        try {
                            val task = teamRepository.createTask(
                                teamId = teamId,
                                title = title,
                                description = args.obj().str("description").orEmpty(),
                                blockedBy = args.obj().uuidList("blocked_by").orEmpty(),
                                ownerName = owner,
                            )
                            toolJson { putTask(task) }
                        } catch (e: IllegalArgumentException) {
                            toolError(e.message ?: "create task failed")
                        }
                    }

                    "list" -> {
                        val allTasks = team?.let { teamRepository.listTasks(it) }.orEmpty()
                        val tasks = allTasks.take(MAX_TASK_LIST)
                        toolJson {
                            // 截断要显式标出，别让模型以为看到的就是全部
                            put("truncated", allTasks.size > tasks.size)
                            put("total", allTasks.size)
                            put("tasks", buildJsonArray {
                                tasks.forEach { task ->
                                    add(buildJsonObject {
                                        put("id", task.id.toString())
                                        put("title", task.title)
                                        put("status", task.status.name)
                                        put("owner", task.ownerName ?: "")
                                        put("blocked_by", buildJsonArray {
                                            task.blockedBy.forEach { add(it.toString()) }
                                        })
                                        put("revision", task.revision)
                                    })
                                }
                            })
                        }
                    }

                    "get" -> {
                        val teamId = team ?: return@Tool toolError("no team yet")
                        val id = args.obj().uuid("task_id")
                            ?: return@Tool toolError("task_id must be a valid uuid")
                        val task = teamRepository.getTask(id)
                            ?: return@Tool toolError("no task with id $id")
                        if (task.teamId != teamId) return@Tool toolError("task belongs to another team")
                        toolJson { putTask(task, includeDescription = true) }
                    }

                    "update" -> {
                        val teamId = team ?: return@Tool toolError("no team yet")
                        val id = args.obj().uuid("task_id")
                            ?: return@Tool toolError("task_id must be a valid uuid")
                        val revision = args.obj().int("revision")
                            ?: return@Tool toolError("revision is required")
                        val task = teamRepository.getTask(id)
                            ?: return@Tool toolError("no task with id $id")
                        if (task.teamId != teamId) return@Tool toolError("task belongs to another team")
                        val rawStatus = args.obj().str("status")
                        val status = rawStatus?.let { name ->
                            TeamTaskStatus.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                                ?: return@Tool toolError(
                                    "unknown status '$name'; expected one of " +
                                        TeamTaskStatus.entries.joinToString { it.name.lowercase() }
                                )
                        } ?: task.status
                        // 置 DONE 只能走接受凭据，防止执行者自报完成
                        if (rawStatus != null && status == TeamTaskStatus.DONE) {
                            return@Tool toolError(
                                "use accept_task to accept a task's result; a task becomes done only through acceptance"
                            )
                        }
                        val owner = args.obj().let { obj ->
                            if (obj.containsKey("owner")) obj.str("owner")?.ifBlank { null } else task.ownerName
                        }
                        if (owner != null && teamRepository.getMemberByName(teamId, owner) == null) {
                            return@Tool toolError("no teammate named '$owner'; use team_status to see the roster")
                        }
                        if (status == TeamTaskStatus.CLAIMED && owner == null) {
                            return@Tool toolError("status=claimed requires a valid owner")
                        }
                        try {
                            when (val result = teamRepository.updateTask(
                                task = task,
                                expectedRevision = revision.toLong(),
                                title = args.obj().str("title") ?: task.title,
                                description = args.obj().str("description") ?: task.description,
                                status = status,
                                ownerName = owner,
                                blockedBy = args.obj().uuidList("blocked_by") ?: task.blockedBy,
                            )) {
                                is TeamTaskUpdateResult.Applied -> toolJson {
                                    put("updated", true)
                                    putTask(result.task, includeDescription = true)
                                }

                                is TeamTaskUpdateResult.Conflict -> toolJson {
                                    put("updated", false)
                                    put("reason", "stale_revision")
                                    put("hint", "someone else changed this task; re-read and retry")
                                    putTask(result.current, includeDescription = true)
                                }

                                TeamTaskUpdateResult.NotFound -> toolError("no task with id $id")
                            }
                        } catch (e: IllegalArgumentException) {
                            // 状态机收窄后补一句顺序提示：PENDING 必须先 claim 才能 done/failed
                            val reason = e.message ?: "update task failed"
                            toolError(
                                if (reason.startsWith("illegal task transition")) {
                                    "$reason (pending can be claimed or failed; done only via accept_task)"
                                } else {
                                    reason
                                }
                            )
                        }
                    }

                    else -> toolError("unknown action '$action'; expected create|list|get|update")
                }
            }
        ),

        // ---------------- 自动唤醒（结果回填到这次调用） ----------------
        Tool(
            name = AgentTeamToolNames.EXPECT_REPORT,
            description = """
                This call is no longer needed. Teammate results are delivered later by a receive_events call and
                wake this conversation by themselves; nothing is written back into this output. After
                spawn_teammate/send_message just END your turn instead of waiting. There is no polling tool, so
                never busy-wait with sleep or repeated team_status calls.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("handle", buildJsonObject {
                            put("type", "string")
                            put("description", "Teammate name")
                        })
                    },
                    required = listOf("handle")
                )
            },
            execute = { args ->
                val handle = args.obj().str("handle")?.trim()?.lowercase()?.ifBlank { null }
                    ?: return@Tool toolError("handle is required")
                toolJson {
                    put("status", "noop")
                    put("final", true)
                    put("handle", handle)
                    put(
                        "hint",
                        "nothing to wait for; results are delivered later by receive_events and wake the conversation"
                    )
                }
            }
        ),

    ).map { tool ->
        // 执行期复核只看身份，不在这里建队：建队会把可嵌套成员变成空子队 lead
        guardTeamTool(tool) { teamService.authorizeTeamTool(leadConversationId) }
    }
}

// ---------------- JSON 小工具 ----------------

private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.uuid(key: String): Uuid? =
    str(key)?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

private fun JsonObject.uuidList(key: String): List<Uuid>? =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putTask(
    task: TeamTask,
    includeDescription: Boolean = false,
) {
    put("id", task.id.toString())
    put("title", task.title)
    put("status", task.status.name)
    put("owner", task.ownerName ?: "")
    put("blocked_by", buildJsonArray { task.blockedBy.forEach { add(it.toString()) } })
    put("revision", task.revision)
    if (includeDescription) put("description", task.description)
}

private fun toolJson(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject(build))))

private fun toolError(message: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", message)
    }.let { JsonInstantPretty.encodeToString(it) }))

/** 名册校验的入口，供 UI 侧复用同一套规则 */
fun isValidTeammateName(name: String): Boolean =
    AgentTeamRules.isValidMemberName(AgentTeamRules.normalizeMemberName(name))

/** 供 UI 展示用的状态文案 */
fun TeamMemberStatus.displayName(): String = when (this) {
    TeamMemberStatus.PROVISIONING -> "provisioning"
    TeamMemberStatus.ACTIVE -> "active"
    TeamMemberStatus.FAILED -> "failed"
}

/**
 * team_status：一次读全队（名册 / 任务板 / 未读事件计数 / 步数额度）。
 * 调用者身份由会话解析：领队看自己这支队，纯成员只看父队里自己那一份。
 */
internal fun teamStatusTool(
    teamService: AgentTeamService,
    teamRepository: AgentTeamRepository,
    conversationId: Uuid,
): Tool = Tool(
    name = AgentTeamToolNames.TEAM_STATUS,
    description = """
        One read-only snapshot of the team: roster (status, run state, running flag, latest message),
        task board with acceptance flags, unacked event counters (lead only) and the shared step budget.
        Your identity is resolved from the session, so never pass who you are.
        The roster snapshot truncates last_message to 2000 chars by default (preview_chars=0 disables it; agent=<name> returns that member's full text). Do NOT poll this tool
        to wait for a result: finished rounds are pushed back as a tool result and wake this conversation.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("include", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put(
                        "description",
                        "Any of agents|tasks|events|budget (default: all four); unknown values are ignored"
                    )
                })
                put("agent", buildJsonObject {
                    put("type", "string")
                    put("description", "Only this teammate in agents; its last_message is never truncated")
                })
                put("preview_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Truncate roster last_message (default 2000); 0 = no truncation")
                })
            }
        )
    },
    execute = { args ->
        val obj = args.obj()
        val include = if (obj.containsKey("include")) {
            obj.stringList("include").map { it.trim().lowercase() }
                .filter { it in TEAM_STATUS_SECTIONS }
                .distinct()
                .ifEmpty { emptyList() }
        } else {
            TEAM_STATUS_SECTIONS
        }
        val memberOf = teamRepository.getMemberByConversation(conversationId)
        val owned = teamRepository.ownedTeam(conversationId)
        val team = when {
            owned != null -> owned
            memberOf != null -> teamRepository.getTeam(memberOf.teamId)
                ?: return@Tool toolError("team not found for this teammate")
            // 普通会话只是查状态：不要为了读一次就落一条 team 行（否则会被当成领队记账）
            else -> return@Tool toolJson {
                put("team", buildJsonObject {
                    put("id", "")
                    put("depth", 0)
                    put("is_lead", true)
                })
                if ("agents" in include) put("agents", buildJsonArray { })
                if ("tasks" in include) put("tasks", buildJsonArray { })
                if ("events" in include) {
                    put("events", buildJsonObject {
                        put("unacked", 0)
                        put("pending_questions", 0)
                        put("by_kind", buildJsonObject { })
                    })
                }
                if ("budget" in include) {
                    put("budget", buildJsonObject {
                        put("root_team_id", "")
                        put("steps_used", 0)
                        put("step_limit", 0)
                    })
                }
                put("hint", "no team yet; spawn a teammate or create a task first")
            }
        }
        val isLead = team.leadConversationId == conversationId
        val agentFilter = obj.str("agent")?.trim()?.lowercase()?.ifBlank { null }
        // 整队快照默认截断；点名某个成员时才给全文（用户要求领队能读到完整正文）
        val previewChars = (obj.int("preview_chars")
            ?: if (agentFilter == null) DEFAULT_ROSTER_PREVIEW_CHARS else 0).coerceAtLeast(0)
        // 成员只看到自己；领队看全队，可用 agent 收窄到一个人
        val views = teamService.listAgents(team.id, conversationId)
            .filter { isLead || it.member.conversationId == conversationId }
            .filter { agentFilter == null || it.member.name == agentFilter }
        val allTasks = if (isLead) {
            teamRepository.listTasks(team.id)
        } else {
            teamRepository.listTasks(team.id).filter { it.ownerName == memberOf?.name }
        }
        val tasks = allTasks.take(MAX_STATUS_TASKS)
        val accepted = teamRepository.acceptedTaskIds(team.id)
        // 额度是根团队的共享账本：嵌套层看到的是同一个 root
        val root = teamRepository.getTeam(team.rootTeamId) ?: team
        // toolJson 的 builder 不是 suspend：事件计数必须先在这里取好
        val eventSummary = if (isLead && "events" in include) {
            teamService.unackedEventSummary(conversationId)
        } else {
            emptyMap<String, Int>()
        }
        val pendingQuestions = if (isLead && "events" in include) {
            teamService.pendingQuestionCount(conversationId)
        } else {
            0
        }
        toolJson {
            put("team", buildJsonObject {
                put("id", team.id.toString())
                put("depth", team.depth)
                put("is_lead", isLead)
            })
            if ("agents" in include) {
                put("agents", buildJsonArray {
                    views.forEach { view ->
                        add(buildJsonObject {
                            put("name", view.member.name)
                            put("role", view.member.role)
                            put("status", view.member.status.name)
                            view.state?.let { put("state", it.name) }
                            put("running", view.running)
                            put("conversation_id", view.member.conversationId.toString())
                            if (view.member.allowedTools.isNotEmpty()) {
                                put("allowed_tools", buildJsonArray {
                                    view.member.allowedTools.forEach { add(it) }
                                })
                            }
                            view.lastMessage?.let { text ->
                                val truncate = agentFilter == null && previewChars > 0 && text.length > previewChars
                                put("last_message", if (truncate) text.take(previewChars) + "…" else text)
                            }
                        })
                    }
                })
            }
            if ("tasks" in include) {
                put("tasks_total", allTasks.size)
                put("tasks_truncated", allTasks.size > tasks.size)
                put("tasks", buildJsonArray {
                    tasks.forEach { task ->
                        add(buildJsonObject {
                            put("id", task.id.toString())
                            put("title", task.title)
                            put("status", task.status.name)
                            put("owner", task.ownerName ?: "")
                            put("blocked_by", buildJsonArray {
                                task.blockedBy.forEach { add(it.toString()) }
                            })
                            put("revision", task.revision)
                            put("accepted", task.id in accepted)
                        })
                    }
                })
            }
            // 事件计数只对领队有意义：成员的事件是领队写给它的派活
            if (isLead && "events" in include) {
                put("events", buildJsonObject {
                    put("unacked", eventSummary.values.sum())
                    put("pending_questions", pendingQuestions)
                    put("by_kind", buildJsonObject {
                        eventSummary.toSortedMap().forEach { (kind, count) -> put(kind, count) }
                    })
                })
            }
            if ("budget" in include) {
                put("budget", buildJsonObject {
                    put("root_team_id", root.id.toString())
                    put("steps_used", root.stepsUsed)
                    put("step_limit", root.stepBudget)
                })
            }
        }
    }
)

/** 成员会话的 team_status：不带领队门禁，靠工具内部解析调用者身份 */
fun createTeamStatusTools(
    teamService: AgentTeamService,
    teamRepository: AgentTeamRepository,
    conversationId: Uuid,
): List<Tool> = listOf(teamStatusTool(teamService, teamRepository, conversationId))
