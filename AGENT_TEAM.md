# Agent Team：把多 Agent 团队做进 RikkaHub

> 目标：在 RikkaHub（Android LLM 客户端）里实现「Lead + 常驻队友 + 共享任务板 + 对等消息」，
> 机制对齐 **DeepSeek Harness 官方 Agent Teams**（参考快照见工作区 `notes/refs/dsh-official-agent-team/`）。
> 设计原则：**队友就是一个普通会话**——不引入第二套消息/存储体系，用户能直接点进队友的聊天看它在干什么。

## 1. 概念映射（DSH 官方 → RikkaHub）

| DSH 官方 | RikkaHub 落地 | 依据 |
|---|---|---|
| Lead 会话 | 一个普通 `Conversation`，作为团队根 | `ConversationEntity` |
| teammate（独立会话+独立上下文） | **另一个普通 `Conversation`**，可指定自己的 Assistant/模型 | `ConversationRepository.insertConversation` |
| 对等信箱 / `send_message` | 向队友会话注入一条用户消息 | `ChatService.sendMessage(conversationId, parts, answer)` |
| `wait_agent` | 订阅 `ChatService.generationDoneFlow` + 查 `ConversationSession.isGenerating` | `ChatService.kt:181`, `ConversationSession.kt:235` |
| `interrupt_agent` | 取消该会话的生成 Job | `ConversationSession.cancelJobs()` / `ChatService.regenerateAtMessage` 同族 |
| 共享任务 DAG（revision CAS） | 新 Room 表 `team_task`，`blockedBy` + `revision` | 新增 |
| 名册（不可变、名字不复用） | 新 Room 表 `team_member`，`name` 唯一 | 新增 |
| Web 投影 | Compose 团队面板（RikkaHub 已有 Web server，可后续加路由） | `ui/pages/chat/` |
| 9 个工具 | 新增 `AgentTeamTools.kt`，在 `ChatToolFactory` 装配 | `data/ai/tools/ChatToolFactory.kt:48` |

## 2. 已核实的接线点（读源码确认，非猜测）

1. **工具工厂**：`ChatToolFactory.createTools(settings, assistant, model, workspaceCwd)` 是唯一装配入口，
   由 `ChatRequestFactory.createContext()` 调用（`ChatRequestFactory.kt:113`）。
   **缺口**：`createTools` 拿不到 `conversationId`，而 `ChatTurnContext` 里其实已经有（`ChatRequestFactory.kt` 内 `conversationId = conversation.id`）。
   → 只需给 `createTools` 加一个 `conversationId: Uuid` 参数，团队工具才知道"我属于哪个 Lead"。
2. **工具形态**：`Tool(name, description, parameters = { InputSchema.Obj(...) }, needsApproval = {...}, execute = { args -> List<UIMessagePart> })`。
   需要用户确认的工具走 `needsApproval = { true }` + `ChatService.handleToolApproval`（`ask_user` 就是这么做的）。
3. **可复用 agent 循环**：`me.rerere.ai.agent.runAgent(AgentRequest, AgentHooks)`（`ai/src/main/java/me/rerere/ai/agent/Agent.kt`）。
   若不想经 `ChatService` 的会话/通知链路，也可用它直接跑一个队友（自己实现 hooks）。
4. **会话状态**：`ConversationSession`（`isGenerating` / `setJob` / `cancelJobs` / `processingStatus`）由
   `ConversationSessionManager` 按 conversationId 管理（`getOrCreate` / `withSession` / `launchWithSession`）。
5. **助手开关**：`Assistant` 是 `@Serializable`，已有 `enableMemory` / `enableWebSearch` / `localTools` / `enabledSkills` 等布尔/列表开关，
   新增 `enableAgentTeam: Boolean = false` 与既有风格一致（`data/model/Assistant.kt:30-52`）。
6. **DB**：Room `AppDatabase` 当前 `version = 32`，实体在 `@Database(entities = [...])` 列表里，迁移用 `AutoMigration` + `migrations/` 下的 `Migration_x_y`。

## 3. 改动清单（文件级）

| # | 文件 | 内容 |
|---|---|---|
| 1 | `data/model/AgentTeam.kt` | 领域模型：`Team` / `TeamMember` / `TeamTask` / `TeamTaskStatus`；**任务图环检测**（纯 Kotlin，可单测） |
| 2 | `data/db/entity/AgentTeamEntity.kt` | `TeamEntity` / `TeamMemberEntity` / `TeamTaskEntity` |
| 3 | `data/db/dao/AgentTeamDAO.kt` | 增删改查 + revision CAS 更新 |
| 4 | `data/db/AppDatabase.kt` | `version 32 → 33`，注册实体与 `AutoMigration(32, 33)` |
| 5 | `data/repository/AgentTeamRepository.kt` | 领域模型 ↔ 实体转换 |
| 6 | `data/ai/tools/AgentTeamTools.kt` | **9 个工具**：`spawn_teammate` / `send_message` / `list_agents` / `wait_agent` / `interrupt_agent` / `team_task_create` / `team_task_list` / `team_task_get` / `team_task_update` |
| 7 | `data/ai/tools/ChatToolFactory.kt` | 加 `conversationId` 参数；`assistant.enableAgentTeam` 时装配团队工具 |
| 8 | `data/ai/ChatRequestFactory.kt` | 把 `conversation.id` 传进 `createTools` |
| 9 | `data/model/Assistant.kt` | 新增 `enableAgentTeam` |
| 10 | `service/AgentTeamService.kt` | 编排：建队/加成员/派活/等待/打断；与 `ChatService`、`ConversationSessionManager` 协作 |
| 11 | `ui/pages/chat/AgentTeamPanel.kt` | 团队面板：成员状态 + 任务板 + 跳转成员会话 |
| 12 | `di/AppModule.kt` | 注册 repo / service |
| 13 | `app/src/test/.../AgentTeam*Test.kt` | 任务图环检测、revision CAS、名册不可变性等单测 |

## 4. 与 DSH 官方的刻意对齐 / 刻意不同

**对齐**（这些是被验证过的正确取舍，不重复踩坑）：
- 队友是**独立上下文**的持久身份，不是同进程 subagent；
- 名册**扁平且不可变**：只有 Lead 能建直属队友，名字不复用、不能删（避免引用失效）；
- 任务**每次变更写整份快照 + revision**，用 compare-and-set，过期编辑**被拒绝而非覆盖**；
- 只有 Lead 能 `spawn_teammate` / `interrupt_agent`；任何成员都能发消息、都能用任务板。

**不同**（RikkaHub 是手机端单进程 App）：
- 不做多进程/多 checkout；队友共享同一 App 进程与工作区；
- `wait_agent` 用**协程挂起 + flow** 实现，不用轮询文件；
- UI 用 Compose 面板而不是 Web 投影（Web server 路由留作后续）。

## 5. 分阶段计划

| 阶段 | 内容 | 可验证性 |
|---|---|---|
| **P0** | 构建链打通：`apk add openjdk21` + 国内镜像 + Android SDK，**先编出未改动 APK 作基线** | ✅ 真出 APK |
| **P1** | 领域模型 + DB + 任务图（改动 1–5） | ✅ 单测（JVM） |
| **P2** | 9 个工具 + 工厂接线（改动 6–9） | ✅ 单测 + 编译 + **真机启动** |
| **P3** | 团队面板 UI + 跳转（改动 10–12） | ✅ 编译 + **装机实测** |
| **P4** | 端到端：一句话拉起团队干活 | ⚠️ 需真实 API Key |

## 5.5 实测记录（2026-10-26）
- `:app:assembleDebug` 成功，504 个单测全绿；APK 56.7 M，装到平板（TB373FU / Android 16 / arm64-v8a）可正常启动。
- **踩到的坑：Koin 依赖注册是「编译期查不出、只有真机跑才暴露」**。首次装机即崩：
  ```
  NoDefinitionFoundException: No definition found for type 'AgentTeamDAO'
  ```
  原因：注册了 `AgentTeamRepository(agentTeamDao = get())` 却没注册 DAO 本身。
  **本项目的注册分层**（照这个来）：
  | 类型 | 模块 |
  |---|---|
  | DAO | `di/DataSourceModule.kt` → `single { get<AppDatabase>().xxxDao() }` |
  | Repository | `di/RepositoryModule.kt` |
  | Service | `di/AppModule.kt` |
  教训：**新增 Room 表后必须同时补 DAO 的 Koin 定义**，否则编译通过、启动即崩。

## 5.6 P3 UI 实测记录（2026-10-26）
装机后逐屏验证（截图见工作区 `shots/p3_*.jpg`）：
1. 助手 → 本地工具页顶部出现「**作为领队组建团队**」开关（中英文案均已加，`values/` + `values-zh/`）；
2. 打开开关 → 回到聊天页，顶栏**多出第三个图标**（团队）；
3. 点该图标 → 弹出「智能体团队」面板，空状态显示「还没有队友。让助手组建一支队伍。」

这条链路同时验证了：`switchItem` → `assistant.copy(enableAgentTeam = true)` → DataStore 持久化 → `ChatPage` 顶栏按 `assistant.enableAgentTeam` 显示入口 → 面板拉取 `getTeamByLeadConversation`。

**仍未验证**：模型真的去调这 9 个工具（需要在 debug 版里配一个 provider/API Key 才能跑一轮生成）。

### UI 落地位置
| 改动 | 文件 |
|---|---|
| 开关 | `ui/pages/assistant/detail/AssistantLocalToolPage.kt` |
| 顶栏入口 + 面板挂载 | `ui/pages/chat/ChatPage.kt`（`TopBar` 加 `onOpenAgentTeam` 参数；面板挂在 `ChatPageContent` 末尾） |
| 面板本体 | `ui/pages/chat/AgentTeamPanel.kt`（`AgentTeamSheet` + `AgentTeamPanelState`） |
| 状态与刷新 | `ui/pages/chat/ChatVM.kt`（`agentTeamPanel` + `refreshAgentTeam()`，订阅 `generationDoneFlow` 自动刷新） |
| 字符串 | `res/values/strings.xml`、`res/values-zh/strings.xml` |

> 踩坑：面板一开始挂到了 `ChatFilesPickerSheet` 末尾（`ChatPage.kt` 里 `ChatPageContent` 与 `TopBar` 之间还有这个函数），
> 报 `Unresolved reference 'navController'`。**Compose 里插代码要用带函数签名的锚点，别用 `} else null, ) } }` 这种通用结尾。**

## 6. 已知风险
1. **验证成本**：没有 JDK/Android SDK 时只能"盲写"Kotlin；P0 就是为消除这个风险。
2. **Firebase**：原版 `app/build.gradle.kts` 应用了 `google.services` + `firebase.crashlytics` 插件，代码里
   `di/AppModule.kt`、`ui/pages/chat/ChatVM.kt` 有引用。fork 需剥离或塞 stub `google-services.json`。
3. **并发生成**：RikkaHub 的生成链路带前台服务/通知（`ChatGenerationForegroundService`）。
   多个队友同时跑会同时占用生成链路，需要明确并发上限（DSH 默认 `maxMembers: 16`，bundle 给 8）。
4. **成本**：多 agent 是"用钱买广度"。参考工作区 `notes/research/multi-agent-teams.md`：多 agent 约 **15× chat** 的 token；
   10 个 95% 可靠的 agent 串成链成功率 < 60%。UI 上要给出成本提示。
5. **上游策略**：RikkaHub 明确拒绝"AI 生成的大规模重构/新功能"PR。**本 fork 不打算合并回上游**，只作自用。
