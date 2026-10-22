package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val terminal by vm.terminalState.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    val ready = state.workspace?.shellStatus == WorkspaceShellStatus.READY.name
    DisposableEffect(vm) { onDispose { vm.cancelTerminalCommand() } }
    LaunchedEffect(terminal.history.size) {
        if (terminal.history.isNotEmpty()) scroll.animateScrollToItem(terminal.history.lastIndex)
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(state.workspace?.name ?: "终端") },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = vm::clearTerminal, enabled = !terminal.running) { Text("清空") }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(
                state = scroll,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (terminal.history.isEmpty()) item { Text("输入命令", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(terminal.history) { entry ->
                    when (entry) {
                        is WorkspaceTerminalEntry.Command -> Text("$ " + entry.command, fontFamily = FontFamily.Monospace)
                        is WorkspaceTerminalEntry.Error -> Text(entry.message, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace)
                        is WorkspaceTerminalEntry.Result -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (entry.result.stdout.isNotEmpty()) Text(entry.result.stdout, fontFamily = FontFamily.Monospace)
                            if (entry.result.stderr.isNotEmpty()) Text(entry.result.stderr, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace)
                            Text(
                                "退出码：${entry.result.exitCode}" + if (entry.result.timedOut) " · 超时" else "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (!ready) Text("工作区未就绪", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(
                value = terminal.input,
                onValueChange = vm::updateTerminalInput,
                enabled = ready && !terminal.running,
                label = { Text("命令") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (terminal.running) {
                    Button(onClick = vm::cancelTerminalCommand) { Text("取消") }
                } else {
                    Button(
                        enabled = ready && terminal.input.isNotBlank(),
                        onClick = { vm.executeTerminalCommand(terminal.input) },
                    ) { Text("执行") }
                }
            }
        }
    }
}
