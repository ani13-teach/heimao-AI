package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.data.ai.MAX_MODEL_FALLBACKS
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlin.uuid.Uuid

/** Stores only an explicit save; inherited defaults and legacy entries remain untouched on open. */
@Composable
fun ModelChainSettingItem(
    title: String,
    description: String,
    primaryModelId: Uuid?,
    fallbackModelIds: List<Uuid>,
    providers: List<ProviderSetting>,
    onSave: (Uuid?, List<Uuid>) -> Unit,
    accept: (Model) -> Boolean = { it.type == ModelType.CHAT },
    defaultModelLabel: String? = null,
    defaultModelId: Uuid? = null,
    inheritedModelId: Uuid? = null,
    inheritedFallbackIds: List<Uuid> = emptyList(),
    inheritFallbacks: Boolean = false,
    singleModel: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    val primaryText = chainPrimaryLabel(
        primaryModelId, providers, defaultModelLabel, defaultModelId, inheritedModelId,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 4.dp))
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "编辑$title") { editing = true }
                    .heightIn(min = 56.dp)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(primaryText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(HugeIcons.ArrowRight01, contentDescription = null)
            }
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (editing) {
        ModelChainEditor(
            title = title,
            primaryModelId = primaryModelId,
            fallbackModelIds = fallbackModelIds,
            providers = providers,
            accept = accept,
            defaultModelLabel = defaultModelLabel,
            defaultModelId = defaultModelId,
            inheritedModelId = inheritedModelId,
            inheritedFallbackIds = inheritedFallbackIds,
            inheritFallbacks = inheritFallbacks,
            singleModel = singleModel,
            onDismiss = { editing = false },
            onSave = { primary, fallbacks ->
                onSave(primary, fallbacks)
                editing = false
            },
        )
    }
}

private fun chainModelLabel(id: Uuid?, providers: List<ProviderSetting>): String {
    val provider = providers.firstOrNull { p -> p.models.any { it.id == id } }
    val model = provider?.models?.firstOrNull { it.id == id }
    return if (provider != null && model != null) {
        "${provider.name} / ${model.modelId}" + if (!provider.enabled) "（供应商已禁用）" else ""
    } else {
        id?.let { "模型已失效：$it" } ?: "未选择模型"
    }
}

private fun chainPrimaryLabel(
    id: Uuid?,
    providers: List<ProviderSetting>,
    defaultLabel: String?,
    defaultId: Uuid?,
    inheritedId: Uuid?,
): String = if (defaultLabel != null && id == defaultId) {
    defaultLabel + if (inheritedId != null) "\n${chainModelLabel(inheritedId, providers)}" else ""
} else {
    chainModelLabel(id, providers)
}

@Composable
private fun ModelChainEditor(
    title: String,
    primaryModelId: Uuid?,
    fallbackModelIds: List<Uuid>,
    providers: List<ProviderSetting>,
    accept: (Model) -> Boolean,
    defaultModelLabel: String?,
    defaultModelId: Uuid?,
    inheritedModelId: Uuid?,
    inheritedFallbackIds: List<Uuid>,
    inheritFallbacks: Boolean,
    singleModel: Boolean,
    onDismiss: () -> Unit,
    onSave: (Uuid?, List<Uuid>) -> Unit,
) {
    var primary by remember { mutableStateOf(primaryModelId) }
    var fallbacks by remember { mutableStateOf(fallbackModelIds.toList()) }
    // -1 selects the primary; size selects a new fallback. Null keeps the editor visible.
    var selection by remember { mutableStateOf<Int?>(null) }
    var search by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val candidates = providers.filter { it.enabled }.flatMap { provider ->
        provider.models.filter(accept).map { provider to it }
    }
    val candidateIds = candidates.map { it.second.id }.toSet()
    val usingDefault = defaultModelLabel != null && primary == defaultModelId
    val effectivePrimary = if (usingDefault) inheritedModelId else primary
    val inheriting = inheritFallbacks && usingDefault && fallbacks.isEmpty()
    val displayedFallbacks = if (inheriting) inheritedFallbackIds else fallbacks
    fun updateFallbacks(next: List<Uuid>) {
        // Null primary plus an empty list means inheritance, not an explicitly empty chain.
        if (inheritFallbacks && usingDefault && next.isEmpty()) {
            primary = inheritedModelId
            notice = "备用已清空；主模型已固定为当前快速模型，不再继承快速模型的备用顺序。"
        } else {
            notice = if (inheriting) "已改为此用途的专用备用顺序。" else null
        }
        fallbacks = next
    }
    val effectiveFallbacks = displayedFallbacks.distinct()
        .filter { it != effectivePrimary && it in candidateIds }
        .take(MAX_MODEL_FALLBACKS)
    val primaryError = when {
        usingDefault && !inheritFallbacks -> null // Current chat model is resolved at request time.
        effectivePrimary !in candidateIds -> "主模型不可用，请重新选择模型或启用对应供应商。"
        else -> null
    }
    val fallbackError = when {
        fallbacks.size > MAX_MODEL_FALLBACKS -> "最多保存 $MAX_MODEL_FALLBACKS 个备用模型，请删除多出的 ${fallbacks.size - MAX_MODEL_FALLBACKS} 个。"
        fallbacks.any { it !in candidateIds } -> "备用列表有不可用模型，请替换或删除后保存。"
        fallbacks.distinct().size != fallbacks.size || fallbacks.any { it == effectivePrimary } -> "备用列表有重复模型，请删除重复项后保存。"
        else -> null
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            val selecting = selection
            if (selecting != null) {
                Text(if (selecting < 0) "选择主模型" else "选择备用模型 ${selecting + 1}")
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索供应商 / model id / 模型名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                val excluded = if (selecting < 0) emptySet() else {
                    displayedFallbacks.filterIndexed { index, _ -> index != selecting }.toSet() + listOfNotNull(effectivePrimary)
                }
                val filtered = candidates.filter { (provider, model) ->
                    model.id !in excluded &&
                        !(selecting < 0 && defaultModelLabel != null && model.id == defaultModelId) &&
                        "${provider.name} ${model.modelId} ${model.displayName}".contains(search.trim(), ignoreCase = true)
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selecting < 0 && defaultModelLabel != null) {
                        ChainChoice(
                            label = chainPrimaryLabel(defaultModelId, providers, defaultModelLabel, defaultModelId, inheritedModelId),
                            onClick = {
                                primary = defaultModelId
                                fallbacks = fallbacks.filterNot { it == inheritedModelId }
                                notice = null
                                selection = null
                            },
                        )
                    }
                    filtered.forEach { (provider, model) ->
                        ChainChoice(
                            label = "${provider.name} / ${model.modelId}" +
                                if (model.displayName.isNotBlank() && model.displayName != model.modelId) "\n${model.displayName}" else "",
                            onClick = {
                                if (selecting < 0) {
                                    primary = model.id
                                    fallbacks = fallbacks.filterNot { it == model.id }
                                    notice = null
                                } else {
                                    updateFallbacks(displayedFallbacks.toMutableList().apply {
                                        if (selecting < size) set(selecting, model.id) else add(model.id)
                                    })
                                }
                                selection = null
                            },
                        )
                    }
                    if (filtered.isEmpty()) Text("没有符合条件的可用模型")
                }
                TextButton(onClick = { selection = null; search = "" }) { Text("返回编辑") }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("主模型", style = MaterialTheme.typography.titleMedium)
                    ChainChoice(
                        label = chainPrimaryLabel(primary, providers, defaultModelLabel, defaultModelId, inheritedModelId),
                        actionLabel = "替换主模型",
                        onClick = { selection = -1; search = "" },
                    )
                    if (!singleModel) {
                        Text("备用模型（${displayedFallbacks.size} / $MAX_MODEL_FALLBACKS）", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "主模型失败后按编号依次尝试，可跨供应商；每个模型使用各自的自动重试次数。最多 1 个主模型和 $MAX_MODEL_FALLBACKS 个备用模型。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (inheriting) {
                            Text("当前继承快速模型的备用顺序；修改下面列表后将保存为此用途的专用备用。专用主模型和备用均为空时，始终继承快速模型。")
                        }
                        displayedFallbacks.forEachIndexed { index, id ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("备用 ${index + 1}", style = MaterialTheme.typography.labelLarge)
                                    ChainChoice(
                                        label = chainModelLabel(id, providers),
                                        actionLabel = "替换备用 ${index + 1}",
                                        onClick = { selection = index; search = "" },
                                    )
                                    val issue = when {
                                        id !in candidateIds -> "模型不可用，不会参与执行"
                                        id == effectivePrimary || displayedFallbacks.take(index).contains(id) -> "重复模型，不会重复执行"
                                        index >= MAX_MODEL_FALLBACKS -> "旧备用超出 $MAX_MODEL_FALLBACKS 个上限，请删除多余项后保存"
                                        else -> null
                                    }
                                    issue?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = {
                                                updateFallbacks(displayedFallbacks.toMutableList().apply { add(index - 1, removeAt(index)) })
                                            },
                                            enabled = index > 0,
                                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "上移备用 ${index + 1}" },
                                        ) { Text("上移") }
                                        TextButton(
                                            onClick = {
                                                updateFallbacks(displayedFallbacks.toMutableList().apply { add(index + 1, removeAt(index)) })
                                            },
                                            enabled = index < displayedFallbacks.lastIndex,
                                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "下移备用 ${index + 1}" },
                                        ) { Text("下移") }
                                        TextButton(
                                            onClick = { updateFallbacks(displayedFallbacks.filterIndexed { i, _ -> i != index }) },
                                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "删除备用 ${index + 1}" },
                                        ) { Text("删除") }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { selection = displayedFallbacks.size; search = "" },
                            enabled = displayedFallbacks.size < MAX_MODEL_FALLBACKS,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("添加备用模型") }
                        if (displayedFallbacks.size >= MAX_MODEL_FALLBACKS) {
                            Text("已达到 $MAX_MODEL_FALLBACKS 个备用上限；可以替换、删除或调整顺序。", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("实际尝试顺序", style = MaterialTheme.typography.titleSmall)
                        if (usingDefault && !inheritFallbacks) {
                            Text("主模型随当前聊天确定；与当前聊天模型重复的备用会在执行时跳过。", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("1. ${chainPrimaryLabel(primary, providers, defaultModelLabel, defaultModelId, inheritedModelId)}")
                        effectiveFallbacks.forEachIndexed { index, id -> Text("${index + 2}. ${chainModelLabel(id, providers)}") }
                        if (effectiveFallbacks.isEmpty()) Text("无可用备用模型", style = MaterialTheme.typography.bodySmall)
                    }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    primaryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!singleModel) fallbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { onSave(primary, fallbacks) },
                        enabled = primaryError == null && (singleModel || fallbackError == null),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ChainChoice(label: String, actionLabel: String = "选择模型", onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = actionLabel, onClick = onClick)
                .heightIn(min = 56.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(HugeIcons.ArrowRight01, contentDescription = null)
        }
    }
}
