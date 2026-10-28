package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.model.TeamLeaderListItem
import me.rerere.rikkahub.data.model.TeamMemberListItem
import me.rerere.rikkahub.data.repository.AgentTeamConversationIndex
import me.rerere.rikkahub.data.repository.AgentTeamRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.service.AgentTeamService
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.toLocalString

/** 抽屉里的团队视图：Lead 会话 + 它的队友（点箭头进入） */
data class DrawerTeamView(
    val leadConversationId: Uuid,
    val title: String,
    val leader: TeamLeaderListItem,
    val members: List<TeamMemberListItem>,
)

/** 一次会话列表查询的输入 */
private data class ConversationListQuery(
    val assistantId: Uuid,
    val folderId: Uuid?,
    val sortOrder: ConversationSortOrder,
    val hiddenConversationIds: Set<Uuid>,
)

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val savedStateHandle: SavedStateHandle,
    private val agentTeamRepository: AgentTeamRepository,
    private val agentTeamService: AgentTeamService,
) : ViewModel() {

    private val assistantIdFlow = settingsStore.settingsFlow
        .map { it.assistantId }
        .distinctUntilChanged()

    private val sortOrderFlow = settingsStore.settingsFlow
        .map { it.displaySetting.conversationSortOrder }
        .distinctUntilChanged()

    // 当前选中的文件夹筛选，null 表示「未归类」视图
    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 当前助手的记忆库是否正在后台整理；没开记忆的助手即使共用的全局记忆库在整理也不显示
    val memoryDreaming: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { settings -> settings.getCurrentAssistant().takeIf { it.enableMemory }?.memoryId }
        .distinctUntilChanged()
        .flatMapLatest { memoryId ->
            if (memoryId == null) flowOf(false) else memoryConsolidationScheduler.statusOf(memoryId).map { it.running }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Lead / 队友会话索引：主列表据此隐藏队友会话，并给 Lead 行加团队入口 */
    val teamIndex: StateFlow<AgentTeamConversationIndex> =
        agentTeamRepository.observeConversationIndex()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentTeamConversationIndex.EMPTY)

    // 点箭头进入的团队视图；null 表示普通会话列表
    private val _teamView = MutableStateFlow<DrawerTeamView?>(null)
    private var teamViewObservation: Job? = null
    val teamView: StateFlow<DrawerTeamView?> = _teamView.asStateFlow()

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(assistantIdFlow, _selectedFolderId, sortOrderFlow, teamIndex) { assistantId, folderId, sortOrder, index ->
            ConversationListQuery(assistantId, folderId, sortOrder, index.memberConversationIds)
        }
            .flatMapLatest { (assistantId, folderId, sortOrder, hiddenConversationIds) ->
                val paging = if (folderId == null) {
                    conversationRepo.getUnfiledConversationsOfAssistantPaging(assistantId, sortOrder)
                } else {
                    conversationRepo.getConversationsOfFolderPaging(folderId, sortOrder)
                }
                paging.map { pagingData ->
                    pagingData
                        // 队友会话不混进主对话列表，只从 Lead 的团队视图进入
                        .filter { conversation -> conversation.id !in hiddenConversationIds }
                        .map { ConversationListItem.Item(it) }
                        .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                            when {
                                after == null -> null
                                after.conversation.isPinned -> {
                                    if (before == null) ConversationListItem.PinnedHeader else null
                                }

                                else -> {
                                    // 日期分组跟随排序依据：按创建时间排序时以创建日期分组
                                    val afterDate = after.conversation.sortDate(sortOrder)
                                    val sameGroup = before != null &&
                                        !before.conversation.isPinned &&
                                        before.conversation.sortDate(sortOrder) == afterDate
                                    if (sameGroup) {
                                        null
                                    } else {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate),
                                            sortOrder = sortOrder,
                                        )
                                    }
                                }
                            }
                        }
                }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        // 助手切换时重置文件夹筛选，回到「聊天」视图，
        // 避免继续显示上一个助手文件夹内的会话（文件夹是助手内分组）
        viewModelScope.launch {
            assistantIdFlow.collect {
                _selectedFolderId.value = null
                closeTeam()
            }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(folderId: Uuid?) {
        _selectedFolderId.value = folderId
        closeTeam()
    }

    /** 点 Lead 会话行右侧的箭头：进入该团队的会话列表 */
    fun openTeam(leadConversationId: Uuid) {
        teamViewObservation?.cancel()
        _teamView.value = null
        teamViewObservation = viewModelScope.launch {
            loadTeamView(leadConversationId)
        }
    }

    fun closeTeam() {
        teamViewObservation?.cancel()
        teamViewObservation = null
        _teamView.value = null
    }

    private suspend fun loadTeamView(leadConversationId: Uuid) {
        val team = agentTeamRepository.getTeamForConversation(leadConversationId)
        if (team == null) {
            _teamView.value = null
            return
        }
        combine(
            agentTeamService.observeLeaderConversation(team),
            agentTeamService.observeMemberList(team.id),
        ) { leader, members ->
            DrawerTeamView(
                leadConversationId = team.leadConversationId,
                title = leader.title.ifBlank { context.getString(R.string.agent_team_title) },
                leader = leader,
                members = members,
            )
        }.collect { _teamView.value = it }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed)
        }
    }

    fun renameFolder(folderId: Uuid, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     * 删除文件夹。若文件夹内有正在生成回复的会话，拒绝删除并返回 false（UI 层据此提示用户）。
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
            }
        }
        return true
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    private fun Conversation.sortDate(sortOrder: ConversationSortOrder): LocalDate {
        val instant = when (sortOrder) {
            ConversationSortOrder.UPDATE_TIME -> updateAt
            ConversationSortOrder.CREATE_TIME -> createAt
        }
        return instant.atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
