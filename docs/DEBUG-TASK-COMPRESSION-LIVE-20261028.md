# Debug 真机：任务后压缩、完整队友回报与 Room 迁移验收

## 范围和环境

- 被测源码 `af96b3b5`，分支 `feat/agent-team`；没有为测试修改产品代码。
- TB373FU，独立包 `me.rerere.rikkahub.debug` 从 194 更新到 196；签名 SHA-256 与原 debug 包一致。
- 仅构建、更新 debug，不生成或安装新的 Tablet Release。当前聊天所在 `me.rerere.rikkahub.tablet` 未安装覆盖、未停止；开始与结束读取的 PID 均为 30381（只描述本次观测，不作为后续脚本的固定 PID）。
- debug 原来未运行。更新前保存其原 APK、设置及数据库诊断副本；没有清数据或卸载，没有用新建空库绕过迁移。
- 在容器 loopback 上临时启动确定性 OpenAI Chat Completions 接口，debug 的临时配置仅包含该接口，付费外部 provider 不可用；关闭自动标题、建议、重试和测试助手的记忆整理。
- **是真实已安装 debug App、真实 ChatService/AgentLoop/Room/团队工具的运行验证，但不是外部 LLM 在线验收。** 模型回复、工具调用指令和 usage 由本地接口控制。工具实际在 App 内执行，未模拟工具结果或直接写入测试消息。
- 通过 debug 自带 Web API 创建全新验收会话，没有触碰旧会话；没有切换前台 UI，也没有使用 Operator/uiautomator。

## 构建

通过已有 guard 调用：

```sh
python3 results/compress-after-task-20261028/build-guard.py \
  debug-assemble :app:assembleGithubDebug --offline
```

成功，118.1 秒；最低采样 MemAvailable 2,281,072 KiB。保留启动前 3 GiB、运行中 1.5 GiB 保护线，没有放宽。

ARM64 APK：`app/build/outputs/apk/github/debug/app-github-arm64-v8a-debug.apk`

SHA-256：`80aae4a3b07dd456e2c2d4acca5bcbb6456fe785d9b0d5e035e2510f2e371690`

更新只使用 `adb install -r`，未使用清数据、卸载或覆盖 Tablet 的路径。

## 真实运行：9 项断言全部通过

| 场景 | 实际观测 |
|---|---|
| 达阈值的多步任务中暂不压缩 | 完成两次真实 `get_time_info` 调用、第三次模型请求被测试闸门阻挡时，检查点仍为 0，生成状态仍运行；工具输出为真实时间 JSON |
| 最终回复先保存 | 放行模型最终回复、阻挡摘要请求时，最终 `FINAL_OK` 已可由 API 读取，检查点仍为 0 |
| 正常完成后生成摘要 | 放行摘要后检查点为 1；最终回复 ID 不变，种子历史的 ID 和 parts 均仍保留 |
| 任务失败不压缩 | 完成一次工具调用后，模型接口返回明确 HTTP 400；任务结束后无摘要请求、无检查点 |
| 任务取消不压缩 | 工具调用后阻挡后续模型请求，调用该新会话的 stop；任务结束后无摘要请求、无检查点 |
| 后置摘要失败不损坏回复/暂停队列 | 摘要接口故意 HTTP 400，成功任务回复保留、无伪检查点；同会话下一条消息可正常完成 |
| 仅最后输出达到阈值 | 使用 1M 测试模型，在低于阈值的任务输入后控制最后一步 usage 跨过阈值；正常回复后仍生成检查点 |
| 领队读取完整回报 | 通过真实 `spawn_teammate → wait_agent → list_agents`，领队的工具结果含 1,930 字符正文及结论尾部，不被旧 400 单位截断 |
| 子代理完成后压缩且回报完整 | 真实 `send_message` 派活，子代理完成两次时间工具调用期间无检查点；回复先保存、摘要后产生 1 个检查点；领队等到完成后通过 `list_agents` 取得 2,270 字符完整正文和结论尾部 |

运行时最低采样 MemAvailable 2,815,304 KiB，没有触发停止线。有限窗口内 debug 进程日志未见 `FATAL EXCEPTION` / `Fatal signal`，不能据此声称长期稳定。

## Room 33 → 34：实际更新迁移通过

运行前数据库 `user_version=33`，上述 debug 启动和工具运行后为 34；Room 能实际打开数据库并持久化新的 `last_run_elapsed_millis`。

停止 debug 后对诊断副本逐行比较迁移前的全部原字段，而不只是比较数量：

| 表 | 原行数 | 原行全部字段仍保留 |
|---|---:|---|
| ConversationEntity | 39 | 是 |
| message_node | 273 | 是 |
| agent_team_members | 30 | 是 |
| agent_teams | 9 | 是 |
| agent_team_tasks | 7 | 是 |

新增测试成员的耗时值已持久化为非空（本轮为 430 ms）；不是仅在内存缓存显示。本次没有做 Compose 耗时 UI 的视觉验收。

## 收尾

- 所有测试任务结束后，仅停止 debug，逐字节恢复其原 `settings.preferences_pb`；恢复副本与原文件字节完全一致。
- debug 留在新版 196，且处于停止状态。旧数据和新增测试记录均未删除；测试助手为临时配置，恢复原设置后不会出现在正常助手名册中。
- 临时 loopback 模型服务已退出，debug Web 服务已停止，两个验收端口均不再监听。
- 仅删除本轮自己创建、且 SHA-256 匹配的两份设备侧临时设置文件；未删除用户下载或聊天数据。
- 私有证据在 `results/debug-after-task-live-20261028/`（目录 700、敏感文件 600），不纳入提交、不整目录上传。含原设置/数据库等敏感内容，不能直接打印或发布。

## 仍未验证 / 未修复

- 没有外部模型真实在线请求；没有 Responses 协议的真机接口验证。
- 审批暂停、步数上限的真机负例尚未新增，本轮只对失败与取消做了真实运行负例；此前 JVM 调度测试仍有效。
- 未做团队面板行高、领队入口、完整长代码显示等 Compose 真机视觉验收，也未做长时/满 8 并发压力测试。
- **本次不改变计数策略。`maxOf(全量粗估, usage)` 导致实际上下文约 10%/15% 就提前判达阈值的问题仍未修复；只是把真正压缩推迟到任务正常完成后。**
- 原巨型 keepRecent 消息导致摘要无收益/重复压缩的边界仍未修复；任务中若先越过模型硬窗口，仍可能请求失败。

此前已完成的 83 suites / 616 项 App JVM 测试与本次真机断言是不同验证层次，不能重复累计为新的 JVM 测试数。
