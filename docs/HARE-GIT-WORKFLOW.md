# Hare 仓库提交与标签规范

Hare 是 RikkaHub 的分支实现（包名 `me.rerere.rikkahub.fork.hare`），每轮发布都以
`versionCode` 递增的 APK 交付。本仓库用 git 固化每一轮的源码状态，保证「交付的包」与
「可回查的提交」一一对应。

## 分支

- `feat/agent-team`：Hare 的主开发分支，所有轮次提交都落在这里。
- `master`：跟随上游的基线分支。
- `backup/pre-rollback`：回滚用的历史快照。

不新建、不移动、不删除上述分支；需要实验时另开工作区，不要在本仓库里改分支拓扑。

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
