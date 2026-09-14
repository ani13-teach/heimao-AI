package me.rerere.rikkahub.ui.pages.chatlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.log.ChatLogEntry
import me.rerere.rikkahub.data.log.ChatLogLevel
import me.rerere.rikkahub.data.log.ChatLogStore

class ChatLogVM(
    private val chatLogStore: ChatLogStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val level: StateFlow<ChatLogLevel> = settingsStore.settingsFlow
        .map { it.chatLogLevel }
        .distinctUntilChanged()
        // 初值直接取当前设置，免得进页面先闪一下默认值。
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            settingsStore.settingsFlow.value.chatLogLevel,
        )

    private val _entries = MutableStateFlow<List<ChatLogEntry>>(emptyList())
    val entries: StateFlow<List<ChatLogEntry>> = _entries.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _entries.value = chatLogStore.recent()
        }
    }

    fun setLevel(level: ChatLogLevel) {
        viewModelScope.launch {
            settingsStore.transformLatest { it.copy(chatLogLevel = level) }
        }
    }

    fun clear() {
        viewModelScope.launch {
            chatLogStore.clear()
            refresh()
        }
    }
}
