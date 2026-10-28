# 会话列表有/无团队箭头的等高修复（源码，未打包）

## 原因与改动
- 普通行只包含Text/状态点，外层上下padding6dp；团队行含IconButton。原按钮size28dp不能消除Material3最小交互布局，可能使团队行变高。
- ConversationItem和团队抽屉行统一heightIn(min=48.dp)，大号辅助文字仍允许撑高，不使用固定高度裁切。
- 团队箭头视觉布局36dp，局部LocalMinimumInteractiveComponentSize=Dp.Unspecified消除重复最小布局；36+上下padding12=48dp。指针最小触控扩展仍由Compose处理，需真实触控验证，不把视觉高度当触控实测。
- 标题weight(1f)并单行省略，保证长标题不挤掉运行点和箭头；箭头保留独立onOpenTeam，父行仍打开会话。

## 验证与状态
- 补充ConversationRowHeightTest三个设备Compose测试：普通/团队48dp基线、大字体同高、长标题箭头独立点击。
- 已通过静态布线检查/git diff --check；这些不等于真实布局测量。
- 本地首次离线验证缺androidTest依赖，普通联网补依赖后，在KSP阶段触1.5GiB保护退出；改较低任务堆再次验证也触保护，未继续重试。无成功编译/单测结果，不沿用上一版571项宣称本轮通过。
- androidTest未在用户当前Tablet上运行，不安装测试APK、不打断聊天。
- 用户明确要求先不生成安装包，云端原196构建40609968032已cancelled；40610242425仅停止操作成功，不是APK构建成功。一次性构建/取消workflow均已删，无后台打包任务。
- 完整日志results/conversation-row-height-20261028（gitignore）。当前源码修复留待下次安全验证，不代表已安装App行高变化。
