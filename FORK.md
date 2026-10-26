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
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/opt/android-sdk/build-tools/36.0.0/aapt2 \
  -Dorg.gradle.jvmargs="-Xmx2560m -XX:MaxMetaspaceSize=768m" \
  -Dkotlin.daemon.jvmargs="-Xmx1536m" --no-parallel
```
已实测：**BUILD SUCCESSFUL**，产出 6 个 APK（各 56 M），
`app/build/outputs/apk/{github,play}/debug/`。

## 许可
上游为 **AGPL-3.0**。本 fork 沿用同一许可；若通过网络对外提供服务，须公开源码。
