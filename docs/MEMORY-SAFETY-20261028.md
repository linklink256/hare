# RikkaHub 内存异常：调查与修复（2026-10-28）

## 已证实
- 霜存测试期间换出尝试为0；root观察服务RSS约16–19MiB，自建实验室数据16MiB，不是系统数GiB增长的主要占用者。
- RikkaHub正式包同PID21340曾RSS4,009,844KiB，停止大段输出后自然回落到数百MiB；未杀聊天/系统进程或强制GC。
- 历史退出记录含LOW_MEMORY/OOM，23:32 RSS7.9GB为系统退出报告值，不与平时RSS统计直接等同。
- 旧Java OOM为512MiB堆耗尽，在MTK ActivityInfo.recordLastFrameCapacity申请24字节时失败。该ROM代码正常容量20；有size==20而非>=20的脆弱条件，但本次未证明越界或竞争，不能仅凭最终栈归咎MTK，也未全局修改ROM。
- **实际安装APK** SHA256 9037a038620a6ca3ebcd…，离线DEX确认SSE回调把完整data拼接后Log.d；工具可读取logcat，存在把对话/工具内容反复回送的反馈风险。此链能增加瞬时字符串和上下文，但未做危险复现来量化全部峰值。
- 同一安装DEX存在对原始code调用lines；工作区对应代码显示默认/展开状态没有字符/单行/总行数上限。布局、语法高亮、JSON解析/pretty复制、动态图形是明确的放大路径。采样包含native heap约519MiB与Graphics约413MiB，但未抓heap/图形分配追踪，不能声称某一项独占全部峰值。

## 已完成源码修复（工作区fork，非当前正式包）
1. CodeRenderWindow：每片段最多8Ki字符/80行/单行512字符，UTF16代理对不被拆开。先限量再lines/排版；上/下片段可遍历全文。原始数据、复制、导出不截断。
2. CodeHighlightText直接调用同样有显示上限；大型Mermaid/HTML不绕过限制自动渲染。
3. 大于16Ki字符的工具输出不再完整JSON pretty；大于64Ki字符的自定义摘要不额外解析完整JSON树，改为分段原文。
4. 移除6类provider完整SSE/request-body日志，错误只保留类型/长度，不把工具/聊天正文送进logcat。
5. Shell摘要既限制行数也限制单行宽度，避免一行巨型输出绕过maxLines。

## 验证
- 新增6个JUnit回归场景全部通过：短文本无变化、超长单行、多行、总字符上限、所有片段含Unicode/CRLF重组完全一致、非法offset。
- 8条源码集成断言通过；git diff --check通过；:app:compileGithubDebugKotlin、:app:assembleGithubDebug通过。
- 使用既有gradle-low-memory单worker/1.75GiB堆，外围监控1.5GiB整机可用内存阈值。最终构建最低1,923,088KiB，未触发停止。
- 没有进行大量消息/长日志/内存压力复现，未声称真机长期峰值已修复。

## 已生效的轻量缓解
- 当前正式包代码行号从ON改为OFF；只读UI设置核验showLineNumbers=false。
- 代码自动折叠原本已ON，保持ON；autoCloseThinking=true、showThinkingContent=true，**未关闭思考内容、未声称关闭“自动展开”**。
- 六个provider log.tag设为W（临时非persist属性，可回滚）；输出探针未读到记录，过滤效果未实证，而且不消除调用前字符串拼接，所以不能代替源码修复。
- 减少工具输出、完整日志留工作区，仅返回小摘要；霜存/实验室不再运行。

## 无法无损在线替换的边界
- 正在聊天：me.rerere.rikkahub /194/3.1.1，正式签名SHA256 2ab3ecd515c24a19eb6c317dd99c0b94ac15f5822228ab9cf520a8ab444ea3f1。
- 新候选：me.rerere.rikkahub.debug，签名f44f36d62d8681b446c6bd03a62ce5c62ea467c62977f5f7cdd667a64d106688，非同一安装包。
- 工作区没有正式签名私钥配置；不卸载正式App、不覆盖DB/清数据、不绕过签名、不往运行中APK/so写入。因此当前正式包**没有加载新UI补丁**。
- 后续应先在debug包验证，再讨论正式源/签名构建或使用应用原生备份迁移；不能把候选APK当“点一下就覆盖正式包”的更新。

## 证据
/root/dev/frostvault/results/memory-audit-20261027/：memory-before/followup/after-build、退出历史、原装APK/ROM离线DEX、构建receipt、回归XML、签名证据、源码ZIP/patch、rollback-log-tags.sh。
