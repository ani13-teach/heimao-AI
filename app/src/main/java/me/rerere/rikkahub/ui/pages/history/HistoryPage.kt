package me.rerere.rikkahub.ui.pages.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkSquare02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Hashtag
import me.rerere.hugeicons.stroke.NoteEdit
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.summary.parseConversationSelection
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sourceAssistant by vm.assistant.collectAsStateWithLifecycle()
    val summaryConversationTitle = stringResource(R.string.history_page_summary_default_title)

    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var summaryMode by remember { mutableStateOf(false) }
    var selectedConversationIds by remember { mutableStateOf(emptySet<Uuid>()) }
    var showDateDialog by remember { mutableStateOf(false) }
    var showNumberDialog by remember { mutableStateOf(false) }
    var showAssistantSheet by remember { mutableStateOf(false) }
    var numberSelectionInput by remember { mutableStateOf("") }
    var numberSelectionError by remember { mutableStateOf<String?>(null) }
    var summarizerAssistantId by remember { mutableStateOf<Uuid?>(null) }
    var creatingSummary by remember { mutableStateOf(false) }

    val zoneId = remember { ZoneId.systemDefault() }
    val displayedConversations = remember(conversations, summaryMode) {
        if (summaryMode) conversations.sortedByDescending { it.createAt } else conversations
    }

    fun leaveSummaryMode() {
        summaryMode = false
        selectedConversationIds = emptySet()
        showDateDialog = false
        showNumberDialog = false
        showAssistantSheet = false
    }

    BackHandler(enabled = summaryMode) {
        leaveSummaryMode()
    }

    LaunchedEffect(conversations) {
        val validIds = conversations.mapTo(mutableSetOf()) { it.id }
        selectedConversationIds = selectedConversationIds.intersect(validIds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (summaryMode) {
                            stringResource(
                                R.string.history_page_summary_selected,
                                selectedConversationIds.size,
                            )
                        } else {
                            stringResource(R.string.history_page_title)
                        }
                    )
                },
                navigationIcon = {
                    if (summaryMode) {
                        IconButton(onClick = ::leaveSummaryMode) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.cancel),
                            )
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (summaryMode) {
                        IconButton(onClick = { showDateDialog = true }) {
                            Icon(
                                imageVector = HugeIcons.Calendar03,
                                contentDescription = stringResource(R.string.history_page_summary_by_date),
                            )
                        }
                        IconButton(
                            onClick = {
                                numberSelectionError = null
                                showNumberDialog = true
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Hashtag,
                                contentDescription = stringResource(R.string.history_page_summary_by_number),
                            )
                        }
                        IconButton(
                            onClick = {
                                selectedConversationIds = if (
                                    selectedConversationIds.size == conversations.size
                                ) {
                                    emptySet()
                                } else {
                                    conversations.mapTo(mutableSetOf()) { it.id }
                                }
                            },
                            enabled = conversations.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.CheckmarkSquare02,
                                contentDescription = stringResource(R.string.history_page_summary_select_all),
                            )
                        }
                    } else {
                        IconButton(onClick = { navController.navigate(Screen.MessageSearch) }) {
                            Icon(
                                HugeIcons.GlobalSearch,
                                contentDescription = stringResource(R.string.history_page_search_messages),
                            )
                        }
                        IconButton(
                            onClick = {
                                summaryMode = true
                                selectedConversationIds = emptySet()
                            },
                            enabled = conversations.isNotEmpty(),
                        ) {
                            Icon(
                                HugeIcons.NoteEdit,
                                contentDescription = stringResource(R.string.history_page_summary),
                            )
                        }
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.history_page_delete_all),
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (summaryMode) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.history_page_summary_selected,
                                selectedConversationIds.size,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                summarizerAssistantId = sourceAssistant?.id
                                    ?: settings.assistants.firstOrNull()?.id
                                showAssistantSheet = true
                            },
                            enabled = selectedConversationIds.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.NoteEdit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(
                                    R.string.history_page_summary_continue,
                                    selectedConversationIds.size,
                                )
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { contentPadding ->
        val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
        val snackMessageUndo = stringResource(R.string.history_page_undo)
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(displayedConversations, key = { _, conversation -> conversation.id }) { index, conversation ->
                if (summaryMode) {
                    val currentDate = conversation.createAt.atZone(zoneId).toLocalDate()
                    val previousDate = displayedConversations.getOrNull(index - 1)
                        ?.createAt
                        ?.atZone(zoneId)
                        ?.toLocalDate()
                    if (currentDate != previousDate) {
                        Text(
                            text = currentDate.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    ConversationItem(
                        conversation = conversation,
                        selectionMode = true,
                        selected = conversation.id in selectedConversationIds,
                        onClick = {
                            selectedConversationIds = if (conversation.id in selectedConversationIds) {
                                selectedConversationIds - conversation.id
                            } else {
                                selectedConversationIds + conversation.id
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                    )
                } else {
                    SwipeableConversationItem(
                        conversation = conversation,
                        onClick = { navigateToChatPage(navController, conversation.id) },
                        onDelete = {
                            scope.launch {
                                val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                                vm.deleteConversation(conversation)
                                val result = snackbarHostState.showSnackbar(
                                    message = snackMessageDeleted,
                                    actionLabel = snackMessageUndo,
                                    withDismissAction = true,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    vm.restoreConversation(fullConversation)
                                }
                            }
                        },
                        onTogglePin = { vm.togglePinStatus(conversation.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                    )
                }
            }
        }
    }

    if (showDateDialog) {
        val dateRangeState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDateDialog = false },
            confirmButton = {
                TextButton(
                    enabled = dateRangeState.selectedStartDateMillis != null,
                    onClick = {
                        val startMillis = dateRangeState.selectedStartDateMillis ?: return@TextButton
                        val endMillis = dateRangeState.selectedEndDateMillis ?: startMillis
                        val startDate = Instant.ofEpochMilli(startMillis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        val endDate = Instant.ofEpochMilli(endMillis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        val matchingIds = conversations
                            .filter { conversation ->
                                val date = conversation.createAt.atZone(zoneId).toLocalDate()
                                date >= startDate && date <= endDate
                            }
                            .mapTo(mutableSetOf()) { it.id }
                        selectedConversationIds = selectedConversationIds + matchingIds
                        showDateDialog = false
                        if (matchingIds.isEmpty()) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = resources.getString(R.string.history_page_summary_date_empty)
                                )
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DateRangePicker(
                state = dateRangeState,
                title = {
                    Text(
                        text = stringResource(R.string.history_page_summary_by_date),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                },
                modifier = Modifier.height(500.dp),
            )
        }
    }

    if (showNumberDialog) {
        AlertDialog(
            onDismissRequest = { showNumberDialog = false },
            title = { Text(stringResource(R.string.history_page_summary_by_number)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = numberSelectionInput,
                        onValueChange = {
                            numberSelectionInput = it
                            numberSelectionError = null
                        },
                        label = { Text(stringResource(R.string.history_page_summary_number_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        isError = numberSelectionError != null,
                        supportingText = {
                            numberSelectionError?.let { Text(it) }
                        },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = parseConversationSelection(numberSelectionInput)
                        val selection = parsed.getOrNull()
                        if (selection == null) {
                            numberSelectionError = resources.getString(
                                R.string.history_page_summary_number_invalid
                            )
                            return@TextButton
                        }
                        val byNumber = conversations.associateBy { it.conversationNumber }
                        val byId = conversations.associateBy { it.id }
                        val missingNumbers = selection.numbers.filterNot { it in byNumber }
                        val missingIds = selection.ids.filterNot { it in byId }
                        if (missingNumbers.isNotEmpty() || missingIds.isNotEmpty()) {
                            val missing = buildList {
                                addAll(missingNumbers.map { "#$it" })
                                addAll(missingIds.map { it.toString() })
                            }
                            numberSelectionError = resources.getString(
                                R.string.history_page_summary_number_missing,
                                missing.joinToString(", "),
                            )
                            return@TextButton
                        }
                        selectedConversationIds = selectedConversationIds + buildSet {
                            addAll(selection.numbers.mapNotNull { byNumber[it]?.id })
                            addAll(selection.ids.mapNotNull { byId[it]?.id })
                        }
                        showNumberDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNumberDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAssistantSheet) {
        SummaryAssistantSheet(
            settings = settings,
            selectedAssistantId = summarizerAssistantId,
            creating = creatingSummary,
            onAssistantSelected = { summarizerAssistantId = it.id },
            onDismiss = {
                if (!creatingSummary) showAssistantSheet = false
            },
            onConfirm = {
                val assistantId = summarizerAssistantId ?: return@SummaryAssistantSheet
                creatingSummary = true
                scope.launch {
                    runCatching {
                        vm.createSummaryConversation(
                            sourceConversationIds = displayedConversations
                                .filter { it.id in selectedConversationIds }
                                .map { it.id },
                            summarizerAssistantId = assistantId,
                            title = summaryConversationTitle,
                        )
                    }.onSuccess { summaryConversation ->
                        creatingSummary = false
                        showAssistantSheet = false
                        leaveSummaryMode()
                        navigateToChatPage(navController, summaryConversation.id)
                    }.onFailure {
                        creatingSummary = false
                        snackbarHostState.showSnackbar(
                            resources.getString(R.string.history_page_summary_create_failed)
                        )
                    }
                }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun SummaryAssistantSheet(
    settings: Settings,
    selectedAssistantId: Uuid?,
    creating: Boolean,
    onAssistantSelected: (Assistant) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.history_page_summary_ai_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(settings.assistants, key = { it.id }) { assistant ->
                    Surface(
                        onClick = { onAssistantSelected(assistant) },
                        color = if (assistant.id == selectedAssistantId) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    assistant.name.ifBlank { defaultAssistantName },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                UIAvatar(
                                    name = assistant.name.ifBlank { defaultAssistantName },
                                    value = assistant.avatar,
                                    modifier = Modifier.size(36.dp),
                                )
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = assistant.id == selectedAssistantId,
                                    onClick = { onAssistantSelected(assistant) },
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
            Button(
                onClick = onConfirm,
                enabled = selectedAssistantId != null && !creating,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.history_page_summary_create))
            }
        }
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(25.dp),
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick,
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = modifier,
    ) {
        ListItem(
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned && !selectionMode) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title
                            .ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(
                    text = "#${conversation.conversationNumber} · ${conversation.createAt.toLocalDateTime()}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onClick() },
                    )
                } else {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            contentDescription = if (conversation.isPinned) {
                                stringResource(R.string.history_page_unpin)
                            } else {
                                stringResource(R.string.history_page_pin)
                            },
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
