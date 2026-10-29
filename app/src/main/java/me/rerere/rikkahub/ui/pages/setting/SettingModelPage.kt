package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelType
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.validateContextCompressionSettings
import me.rerere.rikkahub.ui.components.ai.ModelChainSettingItem
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelChainSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                primaryModelId = settings.chatModelId,
                fallbackModelIds = emptyList(),
                providers = settings.providers,
                singleModel = true,
                onSave = { primary, _ ->
                    if (primary != null) vm.updateSettings(settings.copy(chatModelId = primary))
                },
            )
        }
        item {
            ModelChainSettingItem(
                title = stringResource(R.string.setting_model_page_fast_model),
                description = stringResource(R.string.setting_model_page_fast_model_desc),
                primaryModelId = settings.fastModelId,
                fallbackModelIds = settings.fastFallbackModelIds,
                providers = settings.providers,
                onSave = { primary, fallbacks ->
                    if (primary != null) vm.updateSettings(settings.copy(fastModelId = primary, fastFallbackModelIds = fallbacks))
                },
            )
        }
        item {
            ModelChainSettingItem(
                title = stringResource(R.string.setting_model_page_title_model),
                description = stringResource(R.string.setting_model_page_title_model_desc),
                primaryModelId = settings.titleModelId,
                fallbackModelIds = settings.titleFallbackModelIds,
                providers = settings.providers,
                accept = { it.type == ModelType.CHAT && !ModelRegistry.QWEN_MT.match(it.modelId) },
                defaultModelLabel = "使用快速模型",
                inheritedModelId = settings.fastModelId,
                inheritedFallbackIds = settings.fastFallbackModelIds,
                inheritFallbacks = true,
                onSave = { primary, fallbacks ->
                    vm.updateSettings(settings.copy(titleModelId = primary, titleFallbackModelIds = fallbacks))
                },
            )
        }
        item { SuggestionModelSettingItem(settings = settings, vm = vm) }
        item {
            ModelChainSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                primaryModelId = settings.translateModeId,
                fallbackModelIds = settings.translateFallbackModelIds,
                providers = settings.providers,
                onSave = { primary, fallbacks ->
                    if (primary != null) vm.updateSettings(settings.copy(translateModeId = primary, translateFallbackModelIds = fallbacks))
                },
            )
        }
        item {
            ModelChainSettingItem(
                title = stringResource(R.string.setting_model_page_ocr_model),
                description = stringResource(R.string.setting_model_page_ocr_model_desc),
                primaryModelId = settings.ocrModelId,
                fallbackModelIds = settings.ocrFallbackModelIds,
                providers = settings.providers,
                accept = { it.type == ModelType.CHAT && Modality.IMAGE in it.inputModalities },
                onSave = { primary, fallbacks ->
                    if (primary != null) vm.updateSettings(settings.copy(ocrModelId = primary, ocrFallbackModelIds = fallbacks))
                },
            )
        }
        item {
            ModelChainSettingItem(
                title = stringResource(R.string.setting_model_page_compress_model),
                description = stringResource(R.string.setting_model_page_compress_model_desc),
                primaryModelId = settings.compressModelId,
                fallbackModelIds = settings.compressFallbackModelIds,
                providers = settings.providers,
                defaultModelLabel = "使用当前聊天模型",
                defaultModelId = DEFAULT_AUTO_MODEL_ID,
                accept = { it.type == ModelType.CHAT },
                onSave = { primary, fallbacks ->
                    if (primary != null) vm.updateSettings(settings.copy(compressModelId = primary, compressFallbackModelIds = fallbacks))
                },
            )
        }
        item { ContextCompressionSettingItem(settings = settings, vm = vm) }
    }
}

@Composable
private fun ContextCompressionSettingItem(settings: Settings, vm: SettingVM) {
    var triggerInput by rememberSaveable(settings.autoCompressionThresholdTokens) {
        mutableStateOf(settings.autoCompressionThresholdTokens.toString())
    }
    var targetInput by rememberSaveable(settings.autoCompressionTargetTokens) {
        mutableStateOf(settings.autoCompressionTargetTokens.toString())
    }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val triggerTokens = triggerInput.toIntOrNull()
    val targetTokens = targetInput.toIntOrNull()
    val validationError = when {
        triggerTokens == null -> "触发阈值不能为空，请输入 0～9999999；0 表示关闭自动压缩"
        targetTokens == null -> "压缩目标不能为空，请输入 1～9999999"
        else -> validateContextCompressionSettings(triggerTokens, targetTokens)
    }

    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("自动压缩", style = MaterialTheme.typography.titleMedium)
            Text(
                "达到触发长度后，将旧摘要与较早历史合并为一份摘要，保留当前轮和最近一个完整轮；未触发时沿用原摘要。所有助手和供应商共用，两项一起保存。0 停止更新摘要，已有摘要继续使用。压缩后上限须小于触发长度；token 为本地估算，上限包含摘要、系统提示词、工具、记忆和当前问题等全部请求内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = triggerInput,
                onValueChange = { value ->
                    if (value.length <= 7 && value.all { it in '0'..'9' }) {
                        triggerInput = value
                        notice = null
                    } else {
                        notice = "只能输入最多 7 位 ASCII 数字（0～9）"
                    }
                },
                label = { Text("自动压缩触发长度（token）") },
                supportingText = { Text("0～9999999；0 关闭，默认 100000；不能留空") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !settings.init && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = targetInput,
                onValueChange = { value ->
                    if (value.length <= 7 && value.all { it in '0'..'9' }) {
                        targetInput = value
                        notice = null
                    } else {
                        notice = "只能输入最多 7 位 ASCII 数字（0～9）"
                    }
                },
                label = { Text("压缩后上下文上限（token）") },
                supportingText = { Text("1～9999999，默认 10000；不能为 0 或留空") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !settings.init && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            validationError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    if (validationError != null || triggerTokens == null || targetTokens == null) {
                        notice = "未保存：$validationError"
                    } else {
                        saving = true
                        scope.launch {
                            try {
                                vm.updateContextCompressionSettings(triggerTokens, targetTokens)
                                notice = "已保存自动压缩设置"
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                notice = "保存失败：${error.message ?: "请重试"}"
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
                enabled = !settings.init && !saving,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (saving) "保存中…" else "保存自动压缩设置")
            }
        }
    }
}

@Composable
private fun SuggestionModelSettingItem(settings: Settings, vm: SettingVM) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.setting_model_page_enable_suggestion),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.enableSuggestion,
                    onCheckedChange = { vm.updateSettings(settings.copy(enableSuggestion = it)) },
                    modifier = Modifier.semantics { contentDescription = "启用聊天建议" },
                )
            }
        }
        if (settings.enableSuggestion) {
            ModelChainSettingItem(
                title = stringResource(R.string.setting_model_page_suggestion_model),
                description = stringResource(R.string.setting_model_page_suggestion_model_desc),
                primaryModelId = settings.suggestionModelId,
                fallbackModelIds = settings.suggestionFallbackModelIds,
                providers = settings.providers,
                accept = { it.type == ModelType.CHAT && !ModelRegistry.QWEN_MT.match(it.modelId) },
                defaultModelLabel = "使用快速模型",
                inheritedModelId = settings.fastModelId,
                inheritedFallbackIds = settings.fastFallbackModelIds,
                inheritFallbacks = true,
                onSave = { primary, fallbacks ->
                    vm.updateSettings(settings.copy(suggestionModelId = primary, suggestionFallbackModelIds = fallbacks))
                },
            )
        }
    }
}
