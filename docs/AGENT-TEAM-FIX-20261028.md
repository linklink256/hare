# Agent team 双漏洞修复 — 2026-10-28

## 修复
- AgentTeamService的名册/创建/派活决策在Mutex内串行，挂起期间也不能竞争抢占同一名额或名字。
- 已有队友sendToMember也检查容量；lazy/queued/cancelling jobs保守计入预留；同一已运行会话继续收件不额外占名额。满额闲置队友返回明确错误，不伪装投递成功。
- ChatService.runTurn统一运行时准入TeamExecutionGate：聊天页面、Web、重新生成、审批续跑都必须取得按teamId分组的名额。领队不计入，失败成员不运行；取消/出错释放名额，等待可取消，动态设置在下一次准入重读。不强杀已有运行来满足调低后的上限。
- list_agents.running只计已准入且有active job的成员，直接入口的等待任务仍保持isGenerating job状态但显示「等待团队并发名额…」。
- ChatToolFactory依据数据库member身份而非仅enableAgentTeam：队友不获得9种领队工具。所有9种工具execute重新requireLeader；getOrCreateTeam在查询旧team前也拒绝队友，避免旧缓存/旧嵌套team绕过。旧压测造出的嵌套team不删除、不再赋予权限。

## 验证
86项JUnit通过：TeamExecutionGate8/TeamToolAccess4/AgentTeamRules17/Session26/Manager6/Compression20/NodeChanges5。
编译110.1s。首轮assemble内存保护中止，初次误拿旧APK的短测试已停并单独归档为old-apk-attempt，不算修复验收。
最终assemble148.1s成功，任务私有较小堆，最低MemAvailable2004016KiB。新APK SHA256
23f46366dc17e96bf4860ecdb1f9e04123cf9710fb1116c9bd841d0da3fec3c5，安装base匹配、同签名原地更新。

真实commandcode/deepseek-v4.1-flash独立新团队：
- 创建5成员但最多4运行；5个已有成员连续send，第5明确4/4拒绝，实际list_agents最多4。
- 直接向5个成员Web聊天入口投递，同一runtime gate最多4running，随后interrupt全部，running归零。
- alpha的请求不包含领队工具，能力探测没有工具调用；数据库新团队5成员、无任何子成员名下nested team。
- 本轮末段触发资源保护，停止并关闭debug后补单独短请求：alpha保留secret且RESUMED_OK，重启后名额可用。
- 不将资源中断计为完整无中断内存稳定通过；memory guard停止模型不一定立即回收内存，实际需停止debug（未杀正式聊天/系统、未reboot）。

测试原设置逐项恢复：40%/keep1/team8/providers及原助手选择/web关；仅保留一个验收助手和launch_count变化。
旧debug回滚APK：/root/backups/rikkahub-debug-before-teamfix-20261028/base.apk。
证据含密钥/数据库，仅本地results/agent-team-fix-20261028（700目录600文件），禁止原样回流或上传。
