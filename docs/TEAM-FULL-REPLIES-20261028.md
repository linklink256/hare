# 团队工具返回完整队友正文（2026-10-28）

## 用户要求

不要短预览；领队直接读取子代理最新消息的完整正文，不增加另一个按需读取工具或分页交互。本轮不生成 APK，不安装、不重启运行中的 Tablet。

## 改动

- `AgentTeamService.listAgents` 的 `lastMessage` 不再 `.take(400)`。
- 用纯函数 `fullTeamReplyText` 按原顺序拼接所有 Text parts，返回全文；不裁剪开头缩进/尾部换行；仅无 Text 或全空白时返回 null。
- 不展开 Reasoning 和工具执行日志，保持原有文本内容边界。
- `list_agents` 的 `last_message` 字段名和 JSON 结构不变。工具描述改为完整正文，说明运行中的消息可能还没生成完。
- 成员 UI 依旧只显示会话标题/耗时，不重新放回消息预览。
- 权限隔离、成员查询、wait_agent 完成事件语义没有修改；wait_agent 仍只等待，领队再用 list_agents 读取。
- 没有对完整正文设置新的字符/字节上限；代价是领队上下文会随完整回报增加。

## 验证

`results/team-full-replies-20261028/pure-tests.py` 独立编译真实 helper + JUnit 测试：

- 7 项通过：空、全空白、12,000+ 中文字符及结论尾部、旧 400 边界的 emoji、多个长 Text parts、缩进和尾换行、空 parts 分隔符。
- Kotlin 编译堆 384MiB，测试堆 128MiB；8.0 秒，最低 MemAvailable 3,608,840KiB，没有触及 1.5GiB 保护线。
- 静态确认 listAgents 使用完整正文 helper，400 字符常量删除，last_message JSON 仍直接放入全文，没有增加工具权限或 UI 消息预览。
- 这不是 App/Room/Compose 全量编译或真机安装验证。当前已安装版本没有因源码改动自动生效。

实际提前压缩核查另见 `EARLY-TEAM-COMPRESSION-20261028.md`；该策略本轮未修改。
