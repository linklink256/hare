# 移除手动分段翻页（源码，未打包）

## 来源
用户圈出的“显示片段… / 上一片段 / 下一片段”来自此前长内容显示内存优化，不是原生RikkaHub或用户误开设置。代码/工具分页与CodeRenderWindow在03257cb8一并提交进入首个Tablet Release194，195保留；Markdown手动翻页由0c0c33e5加入。提交时间仅是入库时间，不能当作最初改动/安装时间。

## 移除与保留
- HighlightCodeBlock不再取窗口或显示翻页提示/按钮，展开时直接使用完整code。
- Markdown(content)直接解析完整content，不再截取MarkdownRenderWindow。
- 修复下层CodeHighlightText自身的preview截断，防止只删外层按钮却仍少正文；长代码只跳过昂贵语法高亮，纯文本显示全文。
- 原有代码块自动折叠/展开设置不动；展开显示完整内容。复制/导出依旧使用原始code，不变更历史或模型请求。
- Shell折叠摘要本来就是少量行预览，继续保留其有界摘要；详情/展开路径显示全文，不需要上一片段/下一片段。
- 增量持久化、流式请求与delta批量、Coil缓存等其他优化保持不变。

## 验证与边界
- CodeDisplayContent独立Kotlin/JUnit8项通过：长单行、多行、尾部标记、Unicode/CRLF、展开原对象复用、原有折叠、长代码只不高亮、空内容。8.0秒，最低MemAvailable2668784KiB。
- 静态检查确认主要展示链路无window、Markdown完整参数、Highlighter完整参数、复制/导出原文、git diff --check通过。
- 未跑App/Compose全量编译、未做真机显示验收；不把纯逻辑测试当UI布局验收。
- 全文排版较分片可能增加内存，保留>4096字符跳过语法高亮以减少附加开销；不承诺超巨量历史滚动已无ANR。
- 用户仍要求先不要生成APK，本轮未打包/安装/重启当前Tablet。结果在results/remove-display-pagination-20261028。
