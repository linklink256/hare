# 耗时仅显示在智能体团队面板

用户要求：会话抽屉中点击团队箭头进入的成员列表不显示耗时；仅智能体团队面板显示耗时。

## 修改

- `AgentTeamDrawerList.kt` 移除成员行的 `showElapsed`、`elapsedMillis` 参数和 `TeamTaskElapsed` 调用，耗时与未知值的 `—` 占位一起去掉。
- 抽屉保留会话标题、运行状态小圆点、当前选中高亮、领队跳转和原行高，不恢复消息预览。
- `AgentTeamService.observeMemberList` 只订阅名册、标题、生成状态和并发准入，不订阅 `runTimings` 或启动每秒 ticker。
- 新的 `observeTimedMemberList` 仅由面板对应 `ChatVM` 订阅，保留实时耗时、完成缓存优先于落库旧值、持久化耗时回退与取消订阅停止 ticker。
- `ChatDrawerVM` 继续使用不带计时的成员流；计时统计与数据库不改，面板关闭时停止观察的既有规则保留。

## 验证与状态

- 7 项源码接线/显示范围检查通过。
- 独立 Kotlin/JUnit 14 项通过：6 项对当前 Service 两个方法体原样提取后的 Flow 逻辑测试（仓库/聊天服务为测试替身），加 8 项真实计时/格式测试。验证抽屉无计时订阅、无每秒刷新、标题与准入状态更新、面板实时/持久化/缓存耗时和关闭取消订阅。
- 10.0 秒，最低采样 MemAvailable 2,761,404 KiB，没有触发 1.5 GiB 停止线。
- 开工采样 MemAvailable 低于完整 Gradle 的 3 GiB 启动门槛，未启动完整 App/Compose 编译；轻量测试不能替代它。
- 本轮只改源码，未重新打包/安装 debug 或 Tablet，未停止任何 App，也未修改用户设置/聊天。
- 测试证据 `results/team-panel-only-elapsed-20261028/`，未纳入提交。

此修改覆盖早期任务耗时文档中“团队面板和抽屉均显示”的 UI 范围描述；统计和持久化口径不变。
