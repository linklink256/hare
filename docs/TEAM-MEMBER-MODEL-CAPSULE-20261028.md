# 子代理（团队成员）会话输入区：只读模型胶囊

用户要求：成员会话底部输入区收成左下角一个只显示当前所用模型的小胶囊，防止误输入；
**只读**——不可点击展开，不提供输入/发送/附件/语音/模型选择/额外停止控件。
领队与普通会话输入区不变。

## 实现范围（UI 层 3 文件，产品源码已提交 b712e0f5）

基线 `HEAD 211a87a4`（分支 `feat/agent-team`）；产品改动已由源码作者提交为 **`b712e0f5`**（编译输入为该源码提交前的冻结 patch，与提交 diff 逐字节一致），合计 **+118 / -4**：

| 文件 | numstat | 要点 |
|---|---|---|
| `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt` | +75/-0 | 新增三态参数 `isTeamMemberConversation: Boolean? = false`；`when` 早返回置于队列面板与完整输入区**之前**（`true` → `TeamMemberInputCapsule(chatModel)` 后 `return@Column`；`null` → 直接 `return@Column`；`false` → 落原路径）。胶囊为无 `onClick` 的 `Surface` + `AutoAIIcon` + 模型名，无任何可点元素。 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt` | +21/-4 | 收集并透传标志（两个 `ChatPageContent` 调用点）；`onEdit`(:447) / `onClickSuggestion`(:470) 守卫由 `!= true` 收紧为 **`== false`**，仅非成员才写 `inputState`，保留原草稿语义、不清数据。 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt` | +22/-0 | `MutableStateFlow<Boolean?>(null)`（声明在 `init` 之前）；`init` 用 `agentTeamRepository.getMemberByConversation(_conversationId)`（与编排层同入口）查询归属；`CancellationException` 重抛，其他异常记 `Log.w` 后**保持 `null`**（fail-closed，不回落 false）。 |

- 模型来源：`vm.currentChatModel`，按会话 `ConversationConfig` 解析（`Settings.getChatModelOf`），非助手默认值。
- 未改动：后端派活 / 工具 / 审批 / DB / 压缩逻辑，均不在本轮范围。
- 归属未知或查询失败（`null`）时不渲染任何可输入界面（胶囊与完整输入区都不渲染）；异常保留未知、取消正常传播。
- 已知非阻断备注：`ChatInput.kt:240` 残留一句「生成中附带最小停止入口」的过时注释，与实际代码矛盾，待清理。

## 版本与 patch

- 产品提交：`b712e0f5`（`fix(chat): replace teammate composer with read-only model capsule`），其 `git diff HEAD~1 HEAD` 与冻结 patch 逐字节一致（`cmp` 相同）。
- 冻结 diff：`results/member-capsule-test-20261028/frozen-diff-v2.patch`
  SHA256 `a6cdb10df1ae2d7a47f04cadc1123ffef5771acc67410316925aef1a4ee89cb5`
  （= 当前工作树 `git diff`，= `results/member-capsule-win-20261028/member-capsule.patch`，四处逐字节一致）
- 相对 v1（`frozen-diff.patch` = `eb149eb4…`）仅 2 行代码变化，即上述两处守卫 `!= true` → `== false`。
- 编译源文件 SHA256（与 win 侧一致）：
  ChatInput `93b32146f82600503b1ec0550cee399001cdfc64b1e461530ea5a5ad54fd6c6f`、
  ChatPage `154c3ec49c1c7fc5f0b9a3b92fdad87a5fe3d4c330b4079f487e0261d536d7ce`、
  ChatVM `5043c797a986c11b0177cb5b40683e2fab996b67ba33ee7375f849f8561a93c6`。

## 验证与未验证

已验证：
- **L1 静态断言 34/34 通过、0 失败**（独立 tester，只读源码 + git 元数据，未跑 Gradle/JVM）：
  `results/member-capsule-test-20261028/REPORT-v2.md`、`l1-static-assertions-v2.json`。
- **L2 编译**：builder 在 llink-win 真跑 `:app:compileGithubDebugKotlin --no-daemon`，`BUILD SUCCESSFUL in 1m 53s`、
  `EXITCODE=0`、日志 `e:` 0 行、`FAILED` 0；KSP + Kotlin/Compose 编译器类型检查通过。
  receipt `results/member-capsule-win-20261028/receipt.json`；3 文件传输 hash 与容器一致。
  tester 已独立核验该 receipt（只读比对 hash / 日志 / 产物 class），见 `REPORT-v2.md` 追加节。
- **正式只读 review**：`results/member-capsule-review-20261028/REVIEW.md`，阻断数 0，2 项非阻断备注。

未验证（不得当成已通过）：
- 未跑 JVM 单元测试全套（本轮无 `./gradlew test` / JUnit runner 执行记录）。
- 未做 Compose 真机点击、未做视觉/截图验收、未做端到端行为验收（草稿不串、缺模型回退、模型来源当前会话配置、
  后端派活/审批在真机上的表现均未验）。
- 未 assemble / 未打包、未安装 APK；**当前设备上已安装的版本不包含本改动，App 尚未生效**。
- 34 项是**静态断言**，不等同于 34 项 UI 自动化测试；`compileGithubDebugKotlin` 成功也不等于运行时/视觉达标。

## 证据路径

- 静态验收：`results/member-capsule-test-20261028/`（`REPORT-v2.md`、`frozen-diff-v2.patch`、`l1-static-assertions-v2.json`）
- 构建：`results/member-capsule-win-20261028/`（`receipt.json`、`member-capsule.patch`、`BUILD-NOTES.md`）
- 复核：`results/member-capsule-review-20261028/REVIEW.md`
- 旧方案（已作废，勿再采信为最新）：`results/subagent-input-capsule-20261028/`

## 未执行 / 边界

上一阶段（本改动落源码时）未 push、未打包安装、未重启或停止 App、未改设备与 DB/设置；仅改 UI 源码 3 文件与本记录（本记录单独提交，产品源码提交见上）。

## 2026-10-28 追加：debug 198 打包与安装（本轮新授权）

- 版本提交 `03652ab4`（`build(debug): bump versionCode to 198`，仅 `app/build.gradle.kts` 197→198 一行，未碰产品源码）；
  产品胶囊实现仍在 `b712e0f5`。
- **已实际打包**：builder 在 llink-win 真跑 `:app:assembleGithubDebug`，`BUILD SUCCESSFUL in 1m 48s`、`EXITCODE=0`，
  日志 `e:` 0 行、`FAILED` 0；产物 `app-github-arm64-v8a-debug.apk`。
- **已实际安装**：容器原 debug key 本地重签（私钥不出容器），APK SHA256
  `0a2ad0a0b7ca636878b0fb3d3cc893a448fc150a2391dadbe778a88286cf4a53` / 58,150,970 B，
  证书 SHA-256 `f44f36d6…`（= 原 debug 证书）；ops 以 `adb install -r` 安装成功（EXIT=0，638 ms），
  `me.rerere.rikkahub.debug` 197 → **198**、label `debug`，未清数据、未启动前台。
- tester 独立实物核验（aapt2/apksigner/zipalign/zip 条目级/DEX 字符串/只读 ssh 核对 Win 日志）0 阻断，
  确认新胶囊实现（含 `chat_team_member_input_capsule`，不含 `chat_team_member_stop_button`/`collapseForTeamMember`）。
- **仍未验证**：未做真机 UI / 视觉验收（胶囊是否如期渲染、草稿是否不串、缺模型回退、后端派活/审批等均未验），
  未跑 JVM 全套测试；安装成功 ≠ UI 通过。安装期间**未中断 Tablet**：`me.rerere.rikkahub.tablet` 保持 vc195、
  PID 15363 三次重查一致、APK 路径与 lastUpdateTime 未变，release vc194 未运行。
- 数据完整性：以本轮 baseline 同口径对照，settings / DB / WAL / SHM 字节相同、uv34 / 27 表 / 行数一致；
  该 DB 副本是**只读诊断副本，不是一致性恢复备份**（无跨文件原子性）。细节以 tester 终报为准。
- 证据：`results/debug198-build-20261028/`、`results/debug198-install-20261028/`（`install-receipt.json`、`baseline.json`、`REPORT.md`）、
  `results/debug198-verify-20261028/REPORT.md`。
