package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.OutlinedNumberInput
import org.koin.androidx.compose.koinViewModel

/** 子代理（队友）参数：数量上限、任务板上限、等待超时。 */
private val MAX_MEMBERS_RANGE = 1..32
private val MAX_TASKS_RANGE = 1..1024
private val WAIT_TIMEOUT_RANGE = 10..600

@Composable
fun SettingAgentTeamPage() {
    val vm: SettingVM = koinViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // CardGroup 里放不了弹窗，选择状态在这里建好，弹窗统一放在列表后面
    val teamModel = rememberModelListState(
        modelId = settings.agentTeamModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_agent_team)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                formItem(
                    headlineContent = { Text(stringResource(R.string.setting_agent_team_model)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_team_model_desc)) },
                    leadingContent = {
                        CardGroupIcon(
                            HugeIcons.BubbleChat,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    },
                ) {
                    ModelField(
                        model = teamModel.currentModel,
                        onClick = { teamModel.open() },
                        placeholder = stringResource(R.string.setting_agent_team_model_follow),
                        onClear = { vm.updateSettings { it.copy(agentTeamModelId = null) } },
                    )
                }
            }

            CardGroup {
                formItem(
                    headlineContent = { Text(stringResource(R.string.setting_agent_team_max_members)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_team_max_members_desc)) },
                ) {
                    OutlinedNumberInput(
                        value = settings.agentTeamMaxMembers,
                        onValueChange = { value ->
                            vm.updateSettings {
                                it.copy(agentTeamMaxMembers = value.coerceIn(MAX_MEMBERS_RANGE))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                formItem(
                    headlineContent = { Text(stringResource(R.string.setting_agent_team_max_tasks)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_team_max_tasks_desc)) },
                ) {
                    OutlinedNumberInput(
                        value = settings.agentTeamMaxTasks,
                        onValueChange = { value ->
                            vm.updateSettings {
                                it.copy(agentTeamMaxTasks = value.coerceIn(MAX_TASKS_RANGE))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                formItem(
                    headlineContent = { Text(stringResource(R.string.setting_agent_team_wait_timeout)) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_team_wait_timeout_desc)) },
                ) {
                    OutlinedNumberInput(
                        value = settings.agentTeamWaitTimeoutSeconds,
                        onValueChange = { value ->
                            vm.updateSettings {
                                it.copy(agentTeamWaitTimeoutSeconds = value.coerceIn(WAIT_TIMEOUT_RANGE))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            CardGroup {
                item(
                    onClick = {
                        vm.updateSettings {
                            it.copy(
                                agentTeamMaxMembers = 8,
                                agentTeamMaxTasks = 256,
                                agentTeamWaitTimeoutSeconds = 120,
                            )
                        }
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Refresh01) },
                    supportingContent = { Text(stringResource(R.string.setting_agent_team_reset_desc)) },
                    headlineContent = { Text(stringResource(R.string.setting_agent_team_reset)) },
                )
            }

            Text(
                text = stringResource(R.string.setting_agent_team_applies_next_team),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    ModelListSheet(state = teamModel) { model ->
        vm.updateSettings { it.copy(agentTeamModelId = model.id) }
    }
}
