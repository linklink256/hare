package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.OutlinedNumberInput
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAutoCompressionPage() {
    val vm: SettingVM = koinViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("自动上下文压缩") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(innerPadding).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                switchItem(
                    checked = settings.autoCompressionEnabled,
                    onCheckedChange = { enabled -> vm.updateSettings { it.copy(autoCompressionEnabled = enabled) } },
                    headlineContent = { Text("自动压缩") },
                    supportingContent = { Text("默认开启，适用于领队与队友。在发送消息及工具循环的下一次模型请求前检查。") },
                )
                formItem(
                    headlineContent = { Text("触发比例（%）") },
                    supportingContent = { Text("10–95%，默认 75%。按当前会话模型的上下文窗口计算，切换模型自动适配。为回复预留空间时可能提前压缩。结合服务端用量与本地估算，并非精确计数；请正确填写模型上下文窗口。") },
                ) {
                    OutlinedNumberInput(
                        value = settings.autoCompressionThresholdPercent,
                        onValueChange = { value ->
                            vm.updateSettings { it.copy(autoCompressionThresholdPercent = value.coerceIn(10, 95)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                formItem(
                    headlineContent = { Text("保留最近消息数") },
                    supportingContent = { Text("1–100，默认 6；不拆分同一条消息中的工具调用。历史不足时适当减少保留数，正在生成的消息始终保留。") },
                ) {
                    OutlinedNumberInput(
                        value = settings.autoCompressionKeepRecent,
                        onValueChange = { value ->
                            vm.updateSettings { it.copy(autoCompressionKeepRecent = value.coerceIn(1, 100)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text("使用当前会话模型生成摘要，会产生一次额外请求与费用。原始记录仍可查看、复制及导出；只有发给模型的历史改用摘要。压缩失败会停止本次生成并提示，可重试或手动压缩。单条过大的消息、附件或尚未完成的工具调用无法靠历史压缩解决。")
        }
    }
}
