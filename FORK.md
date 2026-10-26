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
| 移除 Firebase Crashlytics / Analytics | 原版构建需要 `app/google-services.json`；fork 不接 Firebase，去掉后才能独立构建（已提交 `a92bdc66`） |

## 构建（本机环境）
容器是 **aarch64**，Android SDK 宿主工具是 x86-64 → 用 qemu + glibc sysroot 跑。
完整配方、坑与实测数据见工作区 `notes/rikkahub-build-notes.md`。命令：
```sh
/root/bin/gradle-low-memory :app:assembleGithubDebug
```

**不要**再手写 `./gradlew -Dorg.gradle.jvmargs=... -Dkotlin.daemon.jvmargs=...`：那套双守护进程配方
（2.5 G + 1.5 G）会把平板可用内存压到 ~1 G 并大量用 swap，本入口会直接拒绝这类覆盖。
入口固定 1.75 GiB 共享堆 / Kotlin in-process / 单 worker / 用完即退，实测峰值约 0.8–2.5 GiB。
详见 `notes/gradle-low-memory.md`。
已实测：**BUILD SUCCESSFUL**，产出 6 个 APK（各 56 M），
`app/build/outputs/apk/{github,play}/debug/`。

## 许可
上游为 **AGPL-3.0**。本 fork 沿用同一许可；若通过网络对外提供服务，须公开源码。
