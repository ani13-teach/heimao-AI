package me.rerere.rikkahub.ui.pages.chatlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.ChatLogEntry
import me.rerere.rikkahub.data.log.ChatLogLevel
import me.rerere.rikkahub.data.log.CacheLogSummary
import me.rerere.rikkahub.data.log.summarizeCacheLogs
import me.rerere.ai.provider.CacheBoundary
import me.rerere.ai.provider.PromptCacheApi
import me.rerere.ai.provider.PromptCacheChange
import me.rerere.ai.provider.PromptCacheSection
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

@Composable
fun ChatLogPage(vm: ChatLogVM = koinViewModel()) {
    val level by vm.level.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var showSummaries by remember { mutableStateOf(false) }
    val summaries = remember(entries) { summarizeCacheLogs(entries) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.chat_log_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, stringResource(R.string.chat_log_refresh))
                    }
                    IconButton(onClick = { vm.clear() }) {
                        Icon(HugeIcons.Delete01, stringResource(R.string.chat_log_clear))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                LevelCard(
                    level = level,
                    onLevelChange = vm::setLevel,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    listOf(false to "请求明细", true to "缓存汇总").forEachIndexed { index, (summary, label) ->
                        SegmentedButton(
                            selected = showSummaries == summary,
                            onClick = { showSummaries = summary },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
            }
            if (showSummaries) {
                if (summaries.isEmpty()) {
                    item { Text("暂无缓存用量记录", modifier = Modifier.padding(16.dp)) }
                }
                items(summaries) { summary ->
                    CacheSummaryCard(summary, Modifier.padding(horizontal = 8.dp))
                }
            } else if (entries.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        colors = CustomColors.cardColorsOnSurfaceContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_log_empty),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(entries) { entry ->
                    LogEntryCard(entry = entry, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun LevelCard(
    level: ChatLogLevel,
    onLevelChange: (ChatLogLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_log_level_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val options = listOf(
                ChatLogLevel.OFF to R.string.chat_log_level_off,
                ChatLogLevel.ERRORS_ONLY to R.string.chat_log_level_errors,
                ChatLogLevel.ALL to R.string.chat_log_level_all,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = level == value,
                        onClick = { onLevelChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(stringResource(label))
                    }
                }
            }
            Text(
                text = stringResource(R.string.chat_log_retention_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogEntryCard(entry: ChatLogEntry, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (entry.isError) HugeIcons.Alert01 else HugeIcons.ChartColumn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (entry.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    text = when (entry) {
                        is ChatLogEntry.RequestUsage -> stringResource(R.string.chat_log_type_usage)
                        is ChatLogEntry.ToolFailure -> stringResource(R.string.chat_log_type_tool_failure)
                        is ChatLogEntry.MemoryFailure -> stringResource(R.string.chat_log_type_memory_failure)
                        is ChatLogEntry.Crash -> stringResource(R.string.chat_log_type_crash)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = TIME_FORMATTER.format(
                        Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault())
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (entry) {
                is ChatLogEntry.RequestUsage -> RequestUsageBody(entry)
                is ChatLogEntry.ToolFailure -> ToolFailureBody(entry)
                is ChatLogEntry.MemoryFailure -> MemoryFailureBody(entry)
                is ChatLogEntry.Crash -> CrashBody(entry)
            }
        }
    }
}

@Composable
private fun RequestUsageBody(entry: ChatLogEntry.RequestUsage) {
    DetailRow(stringResource(R.string.chat_log_model), entry.modelId)
    DetailRow(stringResource(R.string.chat_log_provider), "${entry.providerName} · ${entry.providerId}")
    entry.sessionKey?.let { DetailRow("会话", it.takeLast(10)) }
    when (entry.usageReported) {
        false -> DetailRow("用量", "服务商未报告")
        null -> DetailRow("用量", "历史记录")
        true -> Unit
    }
    if (entry.usageReported != false) {
        DetailRow(stringResource(R.string.chat_log_prompt_tokens), entry.promptTokens.formatNumber())
        DetailRow(stringResource(R.string.chat_log_completion_tokens), entry.completionTokens.formatNumber())
        val missingCache = entry.cacheUsageReported == false
        DetailRow(stringResource(R.string.chat_log_cache_read), if (missingCache) "未报告" else entry.cacheReadTokens.formatNumber())
        DetailRow(stringResource(R.string.chat_log_cache_write), if (missingCache) "未报告" else entry.cacheWriteTokens.formatNumber())
        DetailRow(stringResource(R.string.chat_log_total_tokens), entry.totalTokens.formatNumber())
        DetailRow(stringResource(R.string.chat_log_cache_hit_rate), entry.cacheHitRate.toPercentText())
    }
    entry.cacheDiagnostic?.let { diagnostic ->
        val changes = if (diagnostic.baseline) "首次记录" else diagnostic.changes.entries.joinToString("、") { (section, change) ->
            "${section.displayName()}（${if (change == PromptCacheChange.APPENDED) "追加" else "变化"}）"
        }.ifEmpty { "未变化" }
        DetailRow("逻辑提示词分区", changes)
        if (diagnostic.api == PromptCacheApi.CLAUDE) {
            val boundaries = when {
                diagnostic.layoutSkipped -> "自定义结构，已跳过"
                diagnostic.appliedBoundaries.isEmpty() -> "未添加"
                else -> diagnostic.appliedBoundaries.joinToString("、") {
                    when (it) {
                        CacheBoundary.SYSTEM_END -> "系统"
                        CacheBoundary.MEMORY_END -> "记忆"
                        CacheBoundary.HISTORY_END -> "历史"
                    }
                }
            }
            DetailRow("请求缓存断点", boundaries)
        }
        if (diagnostic.api == PromptCacheApi.CHAT_COMPLETIONS || diagnostic.api == PromptCacheApi.RESPONSES) {
            DetailRow("会话缓存键", if (diagnostic.cacheKeySent) "已发送" else "未发送")
        }
    }
}

private fun PromptCacheSection.displayName(): String = when (this) {
    PromptCacheSection.TOOLS -> "工具"
    PromptCacheSection.SYSTEM -> "系统"
    PromptCacheSection.MEMORY -> "记忆"
    PromptCacheSection.HISTORY -> "历史"
    PromptCacheSection.TRANSIENT -> "临时材料"
    PromptCacheSection.MEMORY_DELTA -> "记忆补丁"
    PromptCacheSection.CURRENT -> "当前轮"
    PromptCacheSection.CUSTOM_PROMPT -> "自定义请求"
}

@Composable
private fun CacheSummaryCard(summary: CacheLogSummary, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(summary.modelId, style = MaterialTheme.typography.titleSmall)
            DetailRow("渠道", "${summary.providerName} · ${summary.providerId}")
            DetailRow("会话", summary.sessionKey?.takeLast(10) ?: "历史记录")
            DetailRow("生成步骤", summary.steps.toString())
            DetailRow("报告用量", "${summary.usageReports}/${summary.steps}")
            DetailRow("报告缓存用量", "${summary.cacheReports}/${summary.steps}")
            DetailRow("有缓存读取", "${summary.hitSteps}/${summary.cacheReports}")
            DetailRow("已报缓存请求的输入", summary.inputTokens.toString())
            DetailRow("缓存读取", summary.cacheReadTokens.toString())
            DetailRow("缓存写入", summary.cacheWriteTokens.toString())
            DetailRow("缓存命中率", summary.cacheHitRate.toPercentText())
        }
    }
}

@Composable
private fun ToolFailureBody(entry: ChatLogEntry.ToolFailure) {
    DetailRow(stringResource(R.string.chat_log_tool_name), entry.toolName)
    DetailRow(stringResource(R.string.chat_log_error_type), entry.errorType)
    if (entry.errorMessage.isNotBlank()) {
        DetailRow(stringResource(R.string.chat_log_reason), entry.errorMessage)
    }
    if (entry.upstreamCode != null) {
        DetailRow(stringResource(R.string.chat_log_upstream_code), entry.upstreamCode)
    }
}

@Composable
private fun MemoryFailureBody(entry: ChatLogEntry.MemoryFailure) {
    DetailRow(stringResource(R.string.chat_log_tool_name), entry.toolName)
    entry.action?.let { DetailRow(stringResource(R.string.chat_log_memory_action), it) }
    entry.recordId?.let { DetailRow(stringResource(R.string.chat_log_memory_record_id), it.toString()) }
    DetailRow(stringResource(R.string.chat_log_error_type), entry.errorType)
    if (entry.errorMessage.isNotBlank()) {
        DetailRow(stringResource(R.string.chat_log_reason), entry.errorMessage)
    }
}

@Composable
private fun CrashBody(entry: ChatLogEntry.Crash) {
    DetailRow(stringResource(R.string.chat_log_crash_thread), entry.threadName)
    DetailRow(stringResource(R.string.chat_log_error_type), entry.errorType)
    Text(
        text = entry.stackTrace,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = JetbrainsMono,
        maxLines = 12,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
        )
    }
}

private fun Double?.toPercentText(): String =
    if (this == null) "—" else String.format(Locale.US, "%.1f%%", this * 100)
