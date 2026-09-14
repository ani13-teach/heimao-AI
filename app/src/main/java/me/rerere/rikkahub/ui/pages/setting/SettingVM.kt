package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.transformLatest { current ->
                current.copy(
                    dynamicColor = settings.dynamicColor,
                    themeId = settings.themeId,
                    customThemes = settings.customThemes,
                    displaySetting = settings.displaySetting,
                    promptCacheEnabled = settings.promptCacheEnabled,
                    autoRetryEnabled = settings.autoRetryEnabled,
                    autoRetryIntervalSeconds = settings.autoRetryIntervalSeconds,
                    autoRetryMaxDurationMinutes = settings.autoRetryMaxDurationMinutes,
                    autoRetryMaxAttempts = settings.autoRetryMaxAttempts,
                    favoriteModels = settings.favoriteModels,
                    chatModelId = settings.chatModelId,
                    fastModelId = settings.fastModelId,
                    titleModelId = settings.titleModelId,
                    imageGenerationModelId = settings.imageGenerationModelId,
                    titlePrompt = settings.titlePrompt,
                    translateModeId = settings.translateModeId,
                    translatePrompt = settings.translatePrompt,
                    translateThinkingBudget = settings.translateThinkingBudget,
                    enableSuggestion = settings.enableSuggestion,
                    suggestionModelId = settings.suggestionModelId,
                    suggestionPrompt = settings.suggestionPrompt,
                    ocrModelId = settings.ocrModelId,
                    ocrPrompt = settings.ocrPrompt,
                    compressModelId = settings.compressModelId,
                    compressPrompt = settings.compressPrompt,
                    providers = settings.providers,
                    searchServices = settings.searchServices,
                    searchCommonOptions = settings.searchCommonOptions,
                    searchServiceSelected = settings.searchServiceSelected,
                    mcpServers = settings.mcpServers,
                )
            }
        }
    }
}
