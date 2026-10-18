package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.validateContextCompressionSettings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

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
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                modelId = settings.chatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_fast_model),
                description = stringResource(R.string.setting_model_page_fast_model_desc),
                modelId = settings.fastModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(fastModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_title_model),
                description = stringResource(R.string.setting_model_page_title_model_desc),
                modelId = settings.titleModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(titleModelId = null)) },
            )
        }
        item {
            FallbackModelSettingItem(
                title = stringResource(R.string.setting_model_page_title_fallback),
                description = stringResource(R.string.setting_model_page_fallback_desc),
                primaryModelId = settings.titleModelId ?: settings.fastModelId,
                fallbackModelIds = settings.titleFallbackModelIds,
                providers = settings.providers,
                onChange = { vm.updateSettings(settings.copy(titleFallbackModelIds = it)) },
            )
        }
        item {
            SuggestionModelSettingItem(
                settings = settings,
                vm = vm,
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                modelId = settings.translateModeId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
            )
        }
        item {
            FallbackModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_fallback),
                description = stringResource(R.string.setting_model_page_fallback_desc),
                primaryModelId = settings.translateModeId,
                fallbackModelIds = settings.translateFallbackModelIds,
                providers = settings.providers,
                onChange = { vm.updateSettings(settings.copy(translateFallbackModelIds = it)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_ocr_model),
                description = stringResource(R.string.setting_model_page_ocr_model_desc),
                modelId = settings.ocrModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_compress_model),
                description = stringResource(R.string.setting_model_page_compress_model_desc),
                modelId = settings.compressModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(compressModelId = it.id)) },
            )
        }
        item {
            ContextCompressionSettingItem(settings = settings, vm = vm)
        }
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
            Text("自动上下文压缩", style = MaterialTheme.typography.titleMedium)
            Text(
                "所有助手和供应商共用。输入最多 7 位数字，两项一起保存。0 关闭自动压缩；开启时压缩后上限须小于触发长度。token 按本地统一规则估算，并非供应商的精确计数；上限包含摘要、系统提示词、工具、记忆和当前问题等全部请求内容。",
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
private fun SuggestionModelSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    val title = stringResource(R.string.setting_model_page_suggestion_model)
    val state = rememberModelListState(
        modelId = settings.suggestionModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                trailingContent = {
                    Switch(
                        checked = settings.enableSuggestion,
                        onCheckedChange = {
                            vm.updateSettings(settings.copy(enableSuggestion = it))
                        }
                    )
                },
            )
            if (settings.enableSuggestion) {
                item(
                    onClick = { state.open() },
                    headlineContent = { Text(title) },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = state.currentModel?.displayName
                                    ?: stringResource(R.string.model_list_select_model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.currentModel != null) {
                                IconButton(
                                    onClick = { vm.updateSettings(settings.copy(suggestionModelId = null)) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            } else {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.setting_model_page_suggestion_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = { vm.updateSettings(settings.copy(suggestionModelId = it.id)) })
}

@Composable
private fun FallbackModelSettingItem(
    title: String,
    description: String,
    primaryModelId: Uuid?,
    fallbackModelIds: List<Uuid>,
    providers: List<ProviderSetting>,
    onChange: (List<Uuid>) -> Unit,
) {
    // 备用只从主模型所属的那个供应商里选；换了主模型之后，旧备用可能已经不在同一家
    val candidates = remember(providers, primaryModelId) {
        val owner = primaryModelId?.let { id ->
            providers.firstOrNull { provider -> provider.models.any { it.id == id } }
        }
        owner?.models.orEmpty().filter { it.id != primaryModelId && it.type == ModelType.CHAT }
    }
    val effective = fallbackModelIds.filter { id -> candidates.any { it.id == id } }
    var visible by remember { mutableStateOf(false) }

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { visible = true },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = effective.joinToString(" → ") { id ->
                                candidates.first { it.id == id }.let { it.displayName.ifBlank { it.modelId } }
                            }.ifEmpty { stringResource(R.string.setting_model_page_fallback_empty) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    if (!visible) return

    // 点一下加进队列，再点一下移出；顺序就是尝试顺序
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text(title) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.setting_model_page_fallback_no_candidate))
            } else {
                LazyColumn(
                    // 对话框内容必须给上界，否则惰性列表量不到高度
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(candidates, key = { it.id }) { model ->
                        val order = effective.indexOf(model.id)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChange(
                                        if (order >= 0) {
                                            effective.filterNot { it == model.id }
                                        } else {
                                            effective + model.id
                                        }
                                    )
                                },
                        ) {
                            Checkbox(checked = order >= 0, onCheckedChange = null)
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(
                                    text = model.displayName.ifBlank { model.modelId },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (order >= 0) {
                                    Text(
                                        text = stringResource(
                                            R.string.setting_model_page_fallback_order,
                                            order + 1,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { visible = false }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = if (effective.isNotEmpty()) {
            {
                TextButton(onClick = { onChange(emptyList()) }) {
                    Text(stringResource(R.string.setting_model_page_fallback_clear))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onClear != null && state.currentModel != null) {
                            IconButton(onClick = onClear, modifier = Modifier.size(20.dp)) {
                                Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        } else {
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
}
