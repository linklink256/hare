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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AgentTeamRules
import me.rerere.rikkahub.data.model.TeamMemberStatus
import me.rerere.rikkahub.data.model.TeamTask
import me.rerere.rikkahub.data.model.TeamTaskStatus
import me.rerere.rikkahub.data.model.TeamTaskUpdateResult
import me.rerere.rikkahub.data.repository.AgentTeamRepository
import me.rerere.rikkahub.service.AgentTeamException
import me.rerere.rikkahub.service.AgentTeamService
import me.rerere.rikkahub.service.TeamInterruptOutcome
import me.rerere.rikkahub.service.TeamSendOutcome
import me.rerere.rikkahub.service.TeamWaitOutcome
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

object AgentTeamToolNames {
    const val SPAWN_TEAMMATE = "spawn_teammate"
    const val SEND_MESSAGE = "send_message"
    const val LIST_AGENTS = "list_agents"
    const val WAIT_AGENT = "wait_agent"
    const val INTERRUPT_AGENT = "interrupt_agent"
    const val TEAM_TASK_CREATE = "team_task_create"
    const val TEAM_TASK_LIST = "team_task_list"
    const val TEAM_TASK_GET = "team_task_get"
    const val TEAM_TASK_UPDATE = "team_task_update"

    val ALL = listOf(
        SPAWN_TEAMMATE, SEND_MESSAGE, LIST_AGENTS, WAIT_AGENT, INTERRUPT_AGENT,
        TEAM_TASK_CREATE, TEAM_TASK_LIST, TEAM_TASK_GET, TEAM_TASK_UPDATE,
    )
}

private const val DEFAULT_WAIT_SECONDS = 120
private const val MAX_WAIT_SECONDS = 600

/**
 * 团队工具（对齐 DeepSeek Harness 官方 Agent Teams 的 9 个工具）。
 *
 * 每个队友都是一个独立会话：`spawn_teammate` 建会话并登记名册，
 * `send_message` 往对方收件箱投递（非阻塞），`wait_agent` 等任意队友跑完一轮，
 * `interrupt_agent` 打断当前这一轮。任务板走共享 DAG + revision compare-and-set。
 *
 * 只有 Lead（[leadConversationId] 所属会话）会拿到这些工具。
 */
fun createAgentTeamTools(
    teamService: AgentTeamService,
    teamRepository: AgentTeamRepository,
    leadConversationId: Uuid,
    /** 设置里「子代理团队」页配置的默认等待秒数 */
    defaultWaitSeconds: Int = DEFAULT_WAIT_SECONDS,
): List<Tool> {
    suspend fun teamId(): Uuid = teamService.getOrCreateTeam(leadConversationId).id

    return listOf(
        // ---------------- 组建名册 ----------------
        Tool(
            name = AgentTeamToolNames.SPAWN_TEAMMATE,
            description = """
                Create a new teammate that works in its own conversation with its own context.
                Teammates are long-lived: you can message them again later without re-explaining background.
                The name is permanent and never reused (even if creation fails), so pick a clear lowercase name.
                Optionally pass an initial task, which is sent to the teammate immediately (non-blocking).
                Only the Lead can create teammates; there is no nested team.
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
                        name = name,
                        role = args.obj().str("role").orEmpty(),
                        initialTask = args.obj().str("task"),
                    )
                    toolJson {
                        put("created", true)
                        put("name", member.name)
                        put("conversation_id", member.conversationId.toString())
                        put("status", member.status.name)
                    }
                } catch (e: AgentTeamException) {
                    toolError(e.message ?: "spawn failed")
                } catch (e: IllegalArgumentException) {
                    toolError(e.message ?: "spawn failed")
                }
            }
        ),

        Tool(
            name = AgentTeamToolNames.LIST_AGENTS,
            description = """
                List every teammate on this team with its status, whether it is currently generating,
                and a short preview of its latest message. Use it to see who is doing what.
            """.trimIndent().replace("\n", " "),
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val team = teamId()
                val agents = teamService.listAgents(team)
                toolJson {
                    put("agents", buildJsonArray {
                        agents.forEach { view ->
                            add(buildJsonObject {
                                put("name", view.member.name)
                                put("role", view.member.role)
                                put("status", view.member.status.name)
                                put("running", view.running)
                                put("conversation_id", view.member.conversationId.toString())
                                view.lastMessage?.let { put("last_message", it) }
                            })
                        }
                    })
                }
            }
        ),

        // ---------------- 与名册对话 ----------------
        Tool(
            name = AgentTeamToolNames.SEND_MESSAGE,
            description = """
                Send a message to a teammate's inbox. This is non-blocking: it returns as soon as the
                message is queued, which does NOT mean the teammate has processed it. Use wait_agent to
                wait for a round to finish, then list_agents to read the result.
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
                            put("description", "Message body")
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
                try {
                    when (val outcome = teamService.sendToMember(team, target, text)) {
                        is TeamSendOutcome.Sent -> toolJson {
                            put("sent", true)
                            put("target", outcome.member.name)
                            put("running", outcome.running)
                        }

                        TeamSendOutcome.MemberNotFound ->
                            toolError("no teammate named '$target'; use list_agents to see the roster")
                    }
                } catch (e: AgentTeamException) {
                    toolError(e.message ?: "send failed")
                }
            }
        ),

        Tool(
            name = AgentTeamToolNames.WAIT_AGENT,
            description = """
                Wait until any teammate finishes its current round, then return which one finished.
                Returns "no_progress" immediately when no teammate is currently generating — in that case
                wake one with send_message first. Returns "timeout" if nothing finished in time.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("timeout_seconds", buildJsonObject {
                            put("type", "integer")
                            put("description", "How long to wait in seconds (max $MAX_WAIT_SECONDS); omit to use the configured default")
                        })
                    }
                )
            },
            execute = { args ->
                val team = teamId()
                val seconds = (args.obj().int("timeout_seconds") ?: defaultWaitSeconds)
                    .coerceIn(1, MAX_WAIT_SECONDS)
                when (val outcome = teamService.waitForChange(team, seconds * 1000L)) {
                    is TeamWaitOutcome.MemberFinished -> toolJson {
                        put("finished", true)
                        put("name", outcome.memberName ?: "")
                        put("conversation_id", outcome.conversationId.toString())
                    }

                    TeamWaitOutcome.NoProgress -> toolJson {
                        put("finished", false)
                        put("reason", "no_progress")
                        put("hint", "no teammate is generating; send_message to one first")
                    }

                    TeamWaitOutcome.Timeout -> toolJson {
                        put("finished", false)
                        put("reason", "timeout")
                    }
                }
            }
        ),

        Tool(
            name = AgentTeamToolNames.INTERRUPT_AGENT,
            description = """
                Stop the round a teammate is currently running. Only the Lead can interrupt.
                The teammate stays on the roster and can be messaged again.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("target", buildJsonObject {
                            put("type", "string")
                            put("description", "Teammate name")
                        })
                    },
                    required = listOf("target")
                )
            },
            execute = { args ->
                val team = teamId()
                val target = args.obj().str("target")
                    ?: return@Tool toolError("target is required")
                when (val outcome = teamService.interrupt(team, target)) {
                    is TeamInterruptOutcome.Interrupted -> toolJson {
                        put("interrupted", true)
                        put("target", outcome.member.name)
                        put("was_running", outcome.wasRunning)
                    }

                    TeamInterruptOutcome.MemberNotFound ->
                        toolError("no teammate named '$target'; use list_agents to see the roster")
                }
            }
        ),

        // ---------------- 共享任务 DAG ----------------
        Tool(
            name = AgentTeamToolNames.TEAM_TASK_CREATE,
            description = """
                Add a task to the team's shared board. Tasks may declare dependencies on other tasks
                (blocked_by); the graph is validated to stay acyclic. Every change is written as a full
                snapshot with a revision number, so concurrent edits are rejected instead of overwriting.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "Short task title")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional details, acceptance criteria, context")
                        })
                        put("blocked_by", buildJsonObject {
                            put("type", "array")
                            put("description", "Task ids this task depends on (must already exist)")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                        put("owner", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional teammate name to assign")
                        })
                    },
                    required = listOf("title")
                )
            },
            execute = { args ->
                val team = teamId()
                val title = args.obj().str("title")
                    ?: return@Tool toolError("title is required")
                try {
                    val task = teamRepository.createTask(
                        teamId = team,
                        title = title,
                        description = args.obj().str("description").orEmpty(),
                        blockedBy = args.obj().uuidList("blocked_by").orEmpty(),
                        ownerName = args.obj().str("owner"),
                    )
                    toolJson { putTask(task) }
                } catch (e: IllegalArgumentException) {
                    toolError(e.message ?: "create task failed")
                }
            }
        ),

        Tool(
            name = AgentTeamToolNames.TEAM_TASK_LIST,
            description = """
                List the team's tasks with status, owner, dependencies and revision.
                Use team_task_get for the full description of one task.
            """.trimIndent().replace("\n", " "),
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val team = teamId()
                val tasks = teamRepository.listTasks(team)
                toolJson {
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
        ),

        Tool(
            name = AgentTeamToolNames.TEAM_TASK_GET,
            description = "Read one team task in full, including its description and dependency ids.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("task_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Task id from team_task_list")
                        })
                    },
                    required = listOf("task_id")
                )
            },
            execute = { args ->
                val id = args.obj().uuid("task_id")
                    ?: return@Tool toolError("task_id must be a valid uuid")
                val task = teamRepository.getTask(id)
                    ?: return@Tool toolError("no task with id $id")
                toolJson { putTask(task, includeDescription = true) }
            }
        ),

        Tool(
            name = AgentTeamToolNames.TEAM_TASK_UPDATE,
            description = """
                Update a team task. You must pass the revision you last read: if someone else changed the
                task in the meantime the update is REJECTED and you get the current snapshot back — re-read
                and retry instead of overwriting their work. Statuses move pending -> claimed -> done/failed.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("task_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Task id")
                        })
                        put("revision", buildJsonObject {
                            put("type", "integer")
                            put("description", "Revision you last read (required for compare-and-set)")
                        })
                        put("title", buildJsonObject { put("type", "string") })
                        put("description", buildJsonObject { put("type", "string") })
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("pending"); add("claimed"); add("done"); add("failed")
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
                    required = listOf("task_id", "revision")
                )
            },
            execute = { args ->
                val id = args.obj().uuid("task_id")
                    ?: return@Tool toolError("task_id must be a valid uuid")
                val revision = args.obj().int("revision")
                    ?: return@Tool toolError("revision is required")
                val task = teamRepository.getTask(id)
                    ?: return@Tool toolError("no task with id $id")
                val rawStatus = args.obj().str("status")
                val status = rawStatus?.let { name ->
                    TeamTaskStatus.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: return@Tool toolError(
                            "unknown status '$name'; expected one of " +
                                TeamTaskStatus.entries.joinToString { it.name.lowercase() }
                        )
                } ?: task.status
                val owner = args.obj().let { obj ->
                    if (obj.containsKey("owner")) obj.str("owner")?.ifBlank { null } else task.ownerName
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
                    toolError(e.message ?: "update task failed")
                }
            }
        ),
    ).map { tool -> guardTeamTool(tool) { teamService.requireLeader(leadConversationId) } }
}

// ---------------- JSON 小工具 ----------------

private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.uuid(key: String): Uuid? =
    str(key)?.let { runCatching { Uuid.parse(it) }.getOrNull() }

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
