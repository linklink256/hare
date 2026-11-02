# Tablet Release 真机在线验收（2026-10-28 06:06后）

当前聊天已运行在me.rerere.rikkahub.tablet/PID27113；安装APK SHA256与GitHub发布产物0e66c594…一致、不可调试。
整个验收不重启、不强停、不替换当前聊天App，不写运行中设置文件，不复制live SQLite冒充一致备份。
用su启动应用已有WebServerService，仅绑定127.0.0.1:18770，最后用服务ACTION_STOP关闭。

## 核验
- 独立验收会话Tablet Release 实测 · 压缩与团队；第一条跟随当前默认codex/gpt-6.1-sol正常答复。
- 后续会话视角切换commandcode/deepseek-v4.1-flash、禁用该测试会话MCP和搜索，原助手/当前主聊天不改。
- 两队友spawn/wait/list/send真实工具成功；队友请求无团队工具，能力探测无工具调用；两个任务实际DONE/revision3。
- beta初始task的secret表述含歧义，模型拒称其“原始secret”但能回显所给值；不将其称为严谨独立记忆恢复验证，更不能归咎App丢上下文。
- 保持真实模型窗口100万/自动压缩75%/保留6不变，发送2583632字符合成ledger（非用户数据）。
- 首轮大请求prompt617486，下一轮prompt617541；两轮完成且原release_code/checksum/large_fact正确，原seed/长正文parts逐字核对未变。
- 14节点2检查点；**大ledger仍在最近6条保留区、两个摘要都位于它之前，因此没有缩小大上下文。**
  本地ASCII估算高于实际provider tokenizer，所以可早于实际75%触发。不能把此检查称为有效长上下文缩减通过。
- 未测满并发8名额、重启持久化、完整长时稳定，也未改任何全局设置来人为触发；没有强制GC/降系统保护。

## 内存与结论
起始PSS317727KiB/RSS462318KiB；基本阶段采样PSS528720/RSS617113。
大请求监测最低可用2324956KiB，未触发1800MiB停止线；之后额外dumpsys观察到PSS788902KiB/RSS881005KiB（约770/860MiB），不能只报告较低过程采样峰值。
当前进程还在承载用户的实时聊天，无法隔离所有占用归因，也不是与debug严格A/B；不宣称Release已低内存稳定通过。
原设置before/after全部相等，Web临时服务已停，PID仍27113，用户当前聊天继续；测试记录保留。
敏感证据results/tablet-release-live-20261028（700目录/600文件），未上传GitHub，无live DB副本。
