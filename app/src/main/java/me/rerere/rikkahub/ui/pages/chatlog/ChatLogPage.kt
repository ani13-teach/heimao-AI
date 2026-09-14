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
            if (entries.isEmpty()) {
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
                is ChatLogEntry.Crash -> CrashBody(entry)
            }
        }
    }
}

@Composable
private fun RequestUsageBody(entry: ChatLogEntry.RequestUsage) {
    DetailRow(stringResource(R.string.chat_log_model), entry.modelId)
    DetailRow(stringResource(R.string.chat_log_provider), "${entry.providerName} · ${entry.providerId}")
    DetailRow(stringResource(R.string.chat_log_prompt_tokens), entry.promptTokens.formatNumber())
    DetailRow(stringResource(R.string.chat_log_completion_tokens), entry.completionTokens.formatNumber())
    DetailRow(stringResource(R.string.chat_log_cache_read), entry.cacheReadTokens.formatNumber())
    DetailRow(stringResource(R.string.chat_log_cache_write), entry.cacheWriteTokens.formatNumber())
    DetailRow(stringResource(R.string.chat_log_total_tokens), entry.totalTokens.formatNumber())
    DetailRow(stringResource(R.string.chat_log_cache_hit_rate), entry.cacheHitRate.toPercentText())
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
