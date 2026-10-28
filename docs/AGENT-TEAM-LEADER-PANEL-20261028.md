# 团队面板的成员视角与领队入口（Tablet196）

## 修复
- 原ChatVM只按leadConversationId找团队，子代理打开面板会被误判未建队。新getTeamForConversation先按成员归属找原团队，再兼容领队会话；历史嵌套记录不能替代实际归属，孤儿成员不回退到无关团队。
- 面板和抽屉在成员上方增加“领队”，显示领队会话标题及运行点，点击打开领队会话。领队标题用Room轻量单列Flow实时更新，不读消息历史。
- 从领队或任一子代理看的是同一队成员和任务板。成员仍只显示标题，没有角色或消息预览。
- 面板点当前会话仅关闭，不创建重复导航；观测仍在面板/抽屉关闭时取消。
- 仅UI导航使用新lookup；requireLeader、requireRootTeam、ChatToolFactory、guardTeamTool与权限测试保持原样。可以看团队不代表子代理可创建/管理团队。

## 验证计划与状态
- 新增8项AgentTeamConversationLookupTest：真实repository配只读fake DAO，覆盖lead/member/sibling/跨队隔离/普通会话/孤儿/历史嵌套/failed记录。
- 本地未启动Gradle：MemAvailable不满足3GiB启动线。由GitHub Actions跑App全量单测和Release/R8，具体结果见results/team-leader-panel-20261028/，未完成前不称通过。
- UI编译不等于真机视觉验收；不会自动安装/重启当前Tablet，不改用户配置/数据。
