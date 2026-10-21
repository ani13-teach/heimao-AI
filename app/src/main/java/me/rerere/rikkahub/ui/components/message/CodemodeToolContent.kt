package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.codemodeExecution
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun CodemodeStatus.displayName(): String = when (this) {
    CodemodeStatus.RUNNING -> "运行中"
    CodemodeStatus.SUCCEEDED -> "成功"
    CodemodeStatus.FAILED -> "失败"
    CodemodeStatus.CANCELLED -> "已取消"
    CodemodeStatus.INTERRUPTED -> "已中断"
}

internal fun CodemodeExecution.summary(): String = "${status.displayName()} · ${operations.size} 项操作"

private fun JsonElement.pretty(): String = JsonInstantPretty.encodeToString(this)

@Composable
internal fun ChainOfThoughtScope.CodemodeToolContent(
    tool: UIMessagePart.Tool,
    execution: CodemodeExecution,
) {
    ChainOfThoughtStep(
        icon = {
            if (execution.status == CodemodeStatus.RUNNING) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        label = {
            Column {
                Text("Codemode", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary)
                CodemodeStatusText(execution.status, execution.summary())
            }
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                execution.error?.let { CodemodeError(it) }
                execution.operations.forEachIndexed { index, operation ->
                    key(operation.id) {
                        ChainOfThoughtStep(
                            label = {
                                Column {
                                    Text(
                                        text = "${index + 1}. ${operation.toolName}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    CodemodeStatusText(operation.status, operation.status.displayName())
                                }
                            },
                            content = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CodemodeField("参数", operation.arguments.pretty())
                                    operation.result?.let { CodemodeField("结果", it.pretty()) }
                                    operation.error?.let { CodemodeError(it) }
                                    if (operation.truncated) {
                                        Text("结果已截断", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                        )
                    }
                }
                ChainOfThoughtStep(
                    label = { Text("原始输入 / 输出", style = MaterialTheme.typography.bodySmall) },
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CodemodeField("输入", tool.input)
                            CodemodeField("输出", tool.output.filterIsInstance<UIMessagePart.Text>()
                                .joinToString("\n") { it.text })
                        }
                    },
                )
            }
        },
    )
}

@Composable
private fun CodemodeStatusText(status: CodemodeStatus, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (status == CodemodeStatus.FAILED || status == CodemodeStatus.INTERRUPTED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun CodemodeError(error: String) {
    SelectionContainer {
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun CodemodeField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text = value.ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** Formats local trace details only for user-requested exports. */
internal fun formatCodemodeToolMarkdown(tool: UIMessagePart.Tool): String? {
    if (tool.toolName != "codemode") return null
    val execution = tool.codemodeExecution ?: return null
    return buildString {
        appendLine("**Codemode** · ${execution.summary()}")
        execution.error?.let { appendCode("错误", it) }
        execution.operations.forEachIndexed { index, operation ->
            appendLine()
            appendLine("${index + 1}. ${operation.toolName} · ${operation.status.displayName()}")
            appendCode("参数", operation.arguments.pretty(), "json")
            operation.result?.let { appendCode("结果", it.pretty(), "json") }
            operation.error?.let { appendCode("错误", it) }
            if (operation.truncated) appendLine("结果已截断")
        }
        appendLine()
        appendCode("原始输入", tool.input, "json")
        appendCode("原始输出", tool.output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text })
    }
}

private fun StringBuilder.appendCode(label: String, value: String, language: String = "text") {
    val longestFence = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestFence + 1))
    appendLine()
    appendLine("$label:")
    appendLine("$fence$language")
    appendLine(value)
    appendLine(fence)
}
