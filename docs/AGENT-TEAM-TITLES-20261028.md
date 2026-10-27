# 团队成员只显示标题与自动标题修复

## 行为
- 截图所示成员面板和抽屉团队列表只显示单行会话标题（及运行点），不显示角色、消息预览或空闲文字；整行可打开队友会话。
- UI 改用 TeamMemberListItem，与模型工具 list_agents 的 TeamMemberView 分离；工具仍保留角色及消息预览，领队能力不变。
- 标题为空时临时显示不可变成员名；成员名不随标题变化，团队工具仍使用原名寻址。
- 新队友不再用成员名预填 Conversation.title，一轮完成后走现有 ConversationMetaGenerator（fastModelId / titlePrompt / TITLE usage）。遵循 enableAutoTitle 开关，不增加独立标题模型配置。
- 兼容旧版 title == member.name 的占位标题：下一轮完成时可自动生成；手动重新生成标题同样可用。不遍历已有队友批量调用模型、不改运行中的数据库。
- Room 只查询会话标题并与名册、jobs、gate admissions 合并；不为 UI 读取消息节点。异步标题完成即响应更新，不依赖 generationDoneFlow 的时序。
- 成员面板关闭取消订阅；模态抽屉关闭、切换助手或文件夹取消团队订阅。永久抽屉保留可见订阅。

## 验证
- 相关四类单测：38 项，0 failure / error；包括新增标题规则8项及gate StateFlow用例1项。
- 最终 :app:testGithubDebugUnitTest --offline：78 suites，571 tests，0 failure / error；含 App Kotlin/Compose 编译与 Room KSP 查询生成成功。
- 最终运行72.0秒，保护器采样最低 MemAvailable 2,403,240 KiB；任务私有1536MiB堆、单worker、SerialGC；未改系统参数。日志开头的旧打印“1.375GiB”不准确，实际 JVM_ARGS=-Xmx1536m。
- SQLite内存fixture核对团队隔离、空/缺失标题、标题更新和空团队；四个UI文件布线断言通过；git diff --check通过。
- 完整日志及本地guard在 results/team-titles-20261028/（gitignore，不上传）。

## 部署状态与边界
- 本轮只修改并验证本地源码；未构建新Release、未上传、未安装或重启当前Tablet，不代表截图界面已更新或真机视觉验收。
- 旧队友不会仅因打开列表就批量生成标题，需要下一轮完成或手动重新生成。现有手动标题保留（恰好等于成员名时无法区分历史占位，兼容逻辑视为占位）。
- 未改变压缩功能及设置，未进行模型付费压测或设备内存审查。
