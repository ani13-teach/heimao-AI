package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * The interactive workspace terminal.
 *
 * [maintenance] selects the shared-environment maintenance terminal: it never mounts a project,
 * runs as the only session on the shared rootfs and is expected to keep a recovery point first.
 * Both modes obtain a lease and a prepared shell context from the repository instead of building
 * paths or patching a rootfs here.
 */
@Composable
fun WorkspaceTerminalPage(id: String, maintenance: Boolean = false, onBack: (() -> Unit)? = null) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()

    RikkahubTheme(colorMode = ColorMode.DARK) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when {
                                maintenance -> stringResource(R.string.workspace_shared_maintenance_title)
                                else -> state.workspace?.name?.let {
                                    stringResource(R.string.workspace_terminal_title_with_name, it)
                                } ?: stringResource(R.string.workspace_terminal_title)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton(onClick = onBack) },
                )
            },
        ) { innerPadding ->
            WorkspaceTerminalContent(
                id = id,
                root = state.workspace?.root,
                shellStatus = state.workspace?.shellStatus,
                maintenance = maintenance,
                sharedEnvironment = state.sharedEnvironment.isShared,
                contentPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun WorkspaceTerminalContent(
    id: String,
    root: String?,
    shellStatus: String?,
    maintenance: Boolean,
    sharedEnvironment: Boolean,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val repository: WorkspaceRepository = koinInject()
    val terminalTextSizePx = with(LocalDensity.current) { 12.sp.roundToPx() }
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
    }
    // A status update (install progress, audit refresh, snapshot work) must never restart this
    // effect and kill a running terminal, so the rootfs-ready signal is latched: it flips at most
    // once per page instance and all later updates leave the running session alone.
    val everReady = remember(root, maintenance) { mutableStateOf(false) }
    val isReady = shellStatus == WorkspaceShellStatus.READY.name
    LaunchedEffect(isReady) {
        if (isReady) everReady.value = true
    }

    var retryToken by remember(root, maintenance) { mutableStateOf(0) }
    var finished by remember(root, maintenance, retryToken) { mutableStateOf(false) }
    var controlDown by remember(root, maintenance) { mutableStateOf(false) }
    var altDown by remember(root, maintenance) { mutableStateOf(false) }
    val leaseOwner = remember(root, maintenance, retryToken) { TerminalLeaseOwner() }
    val sessionClient = remember(root, maintenance, retryToken, leaseOwner) {
        WorkspaceTerminalSessionClient(context.applicationContext) {
            finished = true
            leaseOwner.onProcessFinished()
        }
    }
    val viewClient = remember(root, maintenance, retryToken) {
        WorkspaceTerminalViewClient(context)
    }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    // Register cleanup for the whole attempt, including preparation and the Ready-to-view gap.
    DisposableEffect(leaseOwner, sessionClient, viewClient) {
        onDispose {
            sessionClient.terminalView = null
            viewClient.terminalView = null
            leaseOwner.cleanup()
        }
    }

    val failureTitle = stringResource(R.string.workspace_terminal_unavailable)
    val sessionState by produceState<TerminalSessionUiState>(
        initialValue = TerminalSessionUiState.Loading,
        root,
        maintenance,
        everReady.value,
        retryToken,
        sessionClient,
    ) {
        val currentRoot = root
        if (currentRoot == null || !everReady.value) {
            value = TerminalSessionUiState.NotInstalled
            return@produceState
        }
        when (val preparation = prepareTerminal(repository, id, maintenance, leaseOwner)) {
            is TerminalPreparation.Failed -> {
                value = TerminalSessionUiState.Failed(preparation.message)
            }

            TerminalPreparation.RootfsMissing -> {
                value = TerminalSessionUiState.NotInstalled
            }

            is TerminalPreparation.Ready -> {
                if (!isActive) {
                    leaseOwner.cleanup()
                    return@produceState
                }
                val session = try {
                    // TerminalSession creates a Handler, so it must run on the main thread.
                    createWorkspaceTerminalSession(context, preparation.shellContext, sessionClient)
                } catch (error: Throwable) {
                    leaseOwner.releaseAfterPreparationFailure()
                    value = TerminalSessionUiState.Failed(error.message ?: failureTitle)
                    return@produceState
                }
                leaseOwner.attachProcess(session.asTerminalLeaseProcess())
                if (!isActive) {
                    // The attempt owner distinguishes an unstarted session from a running process.
                    leaseOwner.cleanup()
                    return@produceState
                }
                value = TerminalSessionUiState.Ready(session)
            }
        }
    }

    val currentState = sessionState
    if (currentState !is TerminalSessionUiState.Ready) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (currentState) {
                is TerminalSessionUiState.NotInstalled -> Text(
                    text = stringResource(R.string.workspace_terminal_not_installed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )

                is TerminalSessionUiState.Failed -> {
                    Text(
                        text = stringResource(R.string.workspace_terminal_unavailable),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = currentState.message,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.workspace_terminal_busy_hint),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                    Button(
                        onClick = { retryToken += 1 },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text(stringResource(R.string.workspace_terminal_retry))
                    }
                }

                else -> Text(
                    text = stringResource(R.string.workspace_terminal_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }
        return
    }
    val session = currentState.session

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding(),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (maintenance) {
                TerminalNotice(
                    text = stringResource(R.string.workspace_shared_maintenance_notice),
                )
            } else if (sharedEnvironment) {
                TerminalNotice(
                    text = stringResource(R.string.workspace_shared_terminal_hint),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        TerminalView(viewContext, null).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setTextSize(terminalTextSizePx)
                            setTypeface(terminalTypeface)
                            setTerminalViewClient(viewClient)
                            attachSession(session)
                            sessionClient.terminalView = this
                            viewClient.terminalView = this
                            setOnTouchListener { _, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    viewClient.focusAndShowKeyboard()
                                }
                                false
                            }
                            post {
                                viewClient.focusAndShowKeyboard()
                            }
                        }
                    },
                    update = { terminalView ->
                        terminalView.isFocusable = true
                        terminalView.isFocusableInTouchMode = true
                        terminalView.setTextSize(terminalTextSizePx)
                        terminalView.setTypeface(terminalTypeface)
                        terminalView.setTerminalViewClient(viewClient)
                        sessionClient.terminalView = terminalView
                        viewClient.terminalView = terminalView
                        terminalView.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_UP) {
                                viewClient.focusAndShowKeyboard()
                            }
                            false
                        }
                        terminalView.attachSession(session)
                        terminalView.onScreenUpdated()
                    },
                )
                if (finished) {
                    Text(
                        text = stringResource(R.string.workspace_terminal_exited),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }
            }
            TerminalExtraKeysBar(
                controlDown = controlDown,
                altDown = altDown,
                onControlToggle = { controlDown = !controlDown },
                onAltToggle = { altDown = !altDown },
                onSendText = { session.writeText(it) },
            )
        }
    }
}

@Composable
private fun TerminalNotice(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Acquires the lease and prepares the shell context off the main thread.
 *
 * The lease is taken before preparation and kept until the terminal process exits. Every failure
 * path before handing the lease to the UI releases it again, and a cancellation is never converted
 * into a user-visible error.
 */
private suspend fun prepareTerminal(
    repository: WorkspaceRepository,
    id: String,
    maintenance: Boolean,
    leaseOwner: TerminalLeaseOwner,
): TerminalPreparation {
    return try {
        withContext(Dispatchers.IO) {
            val lease = if (maintenance) {
                repository.acquireMaintenanceTerminal(id)
            } else {
                repository.acquireTerminalLease(id)
            }
            // Ownership is stable before this dispatcher can hand the result back or be cancelled.
            leaseOwner.attachLease(lease)
            val shellContext = if (maintenance) {
                repository.prepareMaintenanceContext(id, lease)
            } else {
                repository.prepareTerminalContext(id)
            }
            if (workspaceContextReady(shellContext)) {
                leaseOwner.preparationSucceeded()
                TerminalPreparation.Ready(shellContext)
            } else {
                leaseOwner.releaseAfterPreparationFailure()
                TerminalPreparation.RootfsMissing
            }
        }
    } catch (error: CancellationException) {
        leaseOwner.releaseAfterPreparationFailure()
        throw error
    } catch (error: Throwable) {
        leaseOwner.releaseAfterPreparationFailure()
        TerminalPreparation.Failed(error.message ?: "Unable to prepare the terminal")
    }
}

private sealed interface TerminalPreparation {
    data class Ready(val shellContext: WorkspaceShellContext) : TerminalPreparation
    data object RootfsMissing : TerminalPreparation
    data class Failed(val message: String) : TerminalPreparation
}

@Composable
private fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        },
    )
}

private fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray()
    write(bytes, 0, bytes.size)
}

private sealed interface TerminalSessionUiState {
    data object Loading : TerminalSessionUiState
    data object NotInstalled : TerminalSessionUiState
    data class Failed(val message: String) : TerminalSessionUiState
    data class Ready(val session: TerminalSession) : TerminalSessionUiState
}
