# Hare 仓库提交与标签规范

Hare 是 RikkaHub 的分支实现（包名 `me.rerere.rikkahub.fork.hare`），每轮发布都以
`versionCode` 递增的 APK 交付。本仓库用 git 固化每一轮的源码状态，保证「交付的包」与
「可回查的提交」一一对应。

## 分支

- `hare/main`：集成与交付线，也是当前开发分支；所有 `hare-r<versionCode>` 标签只打在它上面。
- `hare/<主题>`：12 个内容分支（见下方「分支策略」），各自只提交本主题的文件，`--no-ff` 合并回 `hare/main`。
- `feat/agent-team`、`backup/pre-branch-split`、`hare-r225-pre-split`：2026-11-03 按内容拆分分支前的线性历史快照（同一提交 `9d19ff82`），只读保留、不再向其提交。
- `master`：跟随上游的基线分支。
- `backup/pre-rollback`：旧的回滚点。

日常开发从 `hare/main` 开 `hare/<主题>` 分支，合并一律 `--no-ff`；历史快照分支与标签只用于回查，不要移动或删除。

## 每轮交付流程

1. 源码冻结后，按文件分组提交（一组一个主题，禁止把不相关改动混进同一提交）。
2. 提交信息用 conventional 前缀 + 中文描述，正文只写事实（改了什么、为什么合并、限制），不超过 10 行。
3. 出包并验收通过后打 annotated tag：`hare-r<versionCode>`，正文记录 versionCode、versionName、
   成品文件名、字节数、SHA256。
4. 交付 receipt 里必须写本轮 commit 与 tag，便于从包反查到源码。

## 提交信息的写法

```
feat(hscreen): 内置隐幕 CLI 与 native 引擎

- 打包 assets/hscreen 下的 CLI 与 native 引擎
- 本地工具层暴露 hscreen 调用
```

- 前缀用 `feat` / `fix` / `chore` / `test` / `docs` / `refactor` / `perf`。
- 一个提交只做一件事；确实无法拆分时才写清「为何合并」。
- 不要写「一堆改动」「杂项」这类无法回查的描述。

## 暂存规则

- 只用显式路径暂存：`git add -- <path> [<path>...]`。
- 禁止 `git add -A`、`git add .`、`git commit -a`：它们会把构建产物、本地证据、私钥一起带进来。
- 禁止 `git reset --hard`、`git clean`、批量 `git restore`/`git checkout`、`git rebase`、
  `git commit --amend`，也不改写已有提交。

## 入库与不入库

- 不入库：`artifacts/`（发布 APK、idsig、对齐中间产物）、`results/`（本机验证证据与截图）、
  `local.properties`、`*/build/`、签名私钥（`*.jks` / `*.keystore` / `*.p12`）。
- 入库：源码、资源、`docs/` 下的设计与实施记录、`app/src/test` 与 `ai/src/test` 下的单测。

## pre-commit 钩子

`scripts/git-hooks/pre-commit` 会拒绝以下暂存内容：`artifacts/`、`results/`、任意 `*/build/`、
`local.properties`、`*.keystore` / `*.jks` / `*.p12`、单文件大于 20MB。

启用（只写仓库内配置，不动全局 git config）：

```
git config core.hooksPath scripts/git-hooks
```

钩子只是最后一道闸门，真正的约束仍是上面的显式路径规则。

## 标签

- 命名：`hare-r<versionCode>`（例如 `hare-r225`）。
- 必须用 annotated tag：`git tag -a hare-r225 -m "..."`，正文写清 versionCode、versionName、
  成品文件名、字节数、SHA256。
- 标签一旦推送就代表交付基线，不要移动或重打；需要修正时递增 versionCode 出新包。

## 提交前自检

```
git status --porcelain          # 应为空
git log --oneline <base>..HEAD  # 本轮提交条数与顺序
git ls-files | grep -E '^(artifacts|results)/'   # 应无输出
git fsck --no-dangling           # 应无错误
```

## 分支策略

`hare/main` 是集成与交付线：所有 `hare-r<versionCode>` 标签只打在它上面，能编译、能交付的只有这条线上的交付点。

12 个内容分支各自负责一类改动，名字固定：

- `hare/git-infra`：`.gitignore`、`docs/HARE-GIT-WORKFLOW.md`、`scripts/git-hooks/`（仓库与提交流程本身）。
- `hare/brand`：应用身份与发布元数据（包名、versionCode/versionName、各语言 strings、关于页、OAuth 回环、NSD 服务名）。
- `hare/hscreen`：内置隐幕 CLI 与 native 引擎、本地工具注册。
- `hare/team`：队友工具、成员提示词、团队侧栏与团队数据层。
- `hare/multiagent`：AgentRun/邮箱持久层、Run 生命周期、结构化 report_back。
- `hare/recovery`：stream_chunk 断点恢复与生成中断后的续接。
- `hare/residency`：分层压缩与 PackedMessageList 按需驻留。
- `hare/settings`：数据存储字段、提供商配置与解码容错。
- `hare/conversation`：会话数据层、FTS 与列表项。
- `hare/ui`：聊天渲染、抽屉、输入区与图片解码上限。
- `hare/tests`：单元测试（新增与更新）。
- `hare/docs`：设计与实施记录。

流程：从上一个交付点开 `hare/<主题>` → 只提交该主题的文件 → `--no-ff` 合并回 `hare/main`，让每个主题在图上有独立合并点。

共享文件（`ChatService.kt`、`Settings.kt`、`AppDatabase.kt` 等同时被多个主题改过）**归属单一分支，不按 hunk 拆**：宁可某个分支带一点别主题的改动，也不要为了「纯净」把同一文件切碎。因此内容分支单独 checkout 不保证可编译；可编译、可交付的只有 `hare/main` 的交付点。
