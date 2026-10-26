# RikkaHub · Agent Team Fork

本仓库是 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 的 fork，目标：把**多 Agent 团队协作**做进 Android 客户端。

- 基线：upstream `master` @ `a7d1e6ef`（versionName 3.1.1 / versionCode 194 / targetSdk 37）
- 分支：`feat/agent-team`
- 上游远端名为 `upstream`，本 fork 远端为 `origin`
- 设计、映射与落地清单见 **[AGENT_TEAM.md](AGENT_TEAM.md)**

```sh
git fetch upstream && git rebase upstream/master    # 跟进上游
```

## 与原版的差异（截至当前）
| 差异 | 原因 |
|---|---|
| `AGENT_TEAM.md` / `FORK.md` | fork 说明与设计文档 |
| （待做）移除 Firebase Crashlytics / Analytics | 原版构建需要 `app/google-services.json`；fork 不接 Firebase，去掉后才能独立构建 |
| （待做）`gradle/libs.versions.toml` 增加国内镜像 | 本机网络下 `dl.google.com` / `maven.google.com` 不可达 |

## 许可
上游为 **AGPL-3.0**。本 fork 沿用同一许可；若通过网络对外提供服务，须公开源码。
