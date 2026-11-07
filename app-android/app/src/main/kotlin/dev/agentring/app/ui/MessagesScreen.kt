package dev.agentring.app.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import dev.agentring.app.data.AttachmentStore
import dev.agentring.app.data.SelectedAttachment
import dev.agentring.core.AttachmentTransfer
import dev.agentring.core.Chat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.data.Pairing
import dev.agentring.app.notify.Notifications
import dev.agentring.core.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MessagesScreen(pairing: Pairing) {
    val chats by Graph.messages.chats.collectAsStateWithLifecycle()
    val allMessages by Graph.messages.messages.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var searchTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var searchSelection by rememberSaveable { mutableStateOf(0L) }
    val browser = rememberSessionBrowser(chats)
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val drafts = rememberSaveableStateHolder()
    val requestedChat = AppVisibility.requestedChat.value
    LaunchedEffect(requestedChat, chats) {
        if (requestedChat != null && chats.any { it.chatId == requestedChat }) {
            selected = requestedChat; searchTarget = null; searchSelection++; AppVisibility.requestedChat.value = null; drawer.close()
        }
    }
    val chat = chats.firstOrNull { it.chatId == selected }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val drawerClosed = drawer.currentValue == androidx.compose.material3.DrawerValue.Closed && drawer.targetValue == androidx.compose.material3.DrawerValue.Closed
        fun showSessions() { if (wide) expanded = !expanded else scope.launch { drawer.open() } }
        fun choose(result: dev.agentring.core.ChatSearchResult) { Graph.messages.upsertChat(result.chat); selected = result.chat.chatId; searchTarget = result.match.msgId; searchSelection++; scope.launch { drawer.close() } }
        LaunchedEffect(wide, chat == null) {
            if (wide) drawer.close() else if (chat == null) drawer.open()
        }
        androidx.activity.compose.BackHandler(enabled = !wide && chat != null && drawerClosed) { showSessions() }
        androidx.compose.material3.ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = !wide,
            drawerContent = {
                if (!wide) androidx.compose.material3.ModalDrawerSheet(Modifier.width(minOf(maxWidth * 0.85f, 320.dp))) {
                    SessionSidebar(pairing, chats, allMessages, selected, browser, ::choose) { scope.launch { drawer.close() } }
                }
            },
        ) {
            // Keep the conversation at one stable composition position across width/fold changes.
            Row(Modifier.fillMaxSize()) {
                if (wide && expanded) {
                    Box(Modifier.width(300.dp).fillMaxHeight()) { SessionSidebar(pairing, chats, allMessages, selected, browser, ::choose) { expanded = false } }
                    androidx.compose.material3.VerticalDivider()
                }
                Box(Modifier.weight(1f).fillMaxHeight().testTag("conversation_pane")) {
                    if (chat != null) drafts.SaveableStateProvider(chat.chatId) {
                        androidx.compose.runtime.key(chat.chatId) { ConversationScreen(pairing, chat, visible = wide || drawerClosed, focusMessageId = searchTarget, focusRequest = searchSelection, onBack = ::showSessions) }
                    } else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = ::showSessions, modifier = Modifier.testTag("open_sidebar")) { Text("Sessions") }
                        Text("Select a session", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationScreen(pairing: Pairing, chat: Chat, visible: Boolean, focusMessageId: String?, focusRequest: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allMessages by Graph.messages.messages.collectAsStateWithLifecycle()
    val messages = allMessages.filter { it.chatId == chat.chatId }
    val contentVisible = visible && !AppVisibility.imagePreviewOpen.value
    var focusReached by rememberSaveable(focusMessageId, focusRequest) { mutableStateOf(false) }
    var focusLoading by remember(focusMessageId, focusRequest) { mutableStateOf(focusMessageId != null) }
    var focusError by remember(focusMessageId, focusRequest) { mutableStateOf<String?>(null) }
    var focusRetry by remember { mutableStateOf(0) }
    val threadVisible = contentVisible && (focusMessageId == null || focusReached && !focusLoading && focusError == null)
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val store = remember(ctx) { AttachmentStore(ctx) }
    val selectedSaver = remember { Saver<SelectedAttachment?, List<String>>(
        save = { it?.let { file -> listOf(file.file.path, file.name, file.mediaType) } },
        restore = { value -> java.io.File(value[0]).takeIf { it.isFile }?.let { SelectedAttachment(it, value[1], value[2]) } },
    ) }
    var selected by rememberSaveable(stateSaver = selectedSaver) { mutableStateOf<SelectedAttachment?>(null) }
    val uploadedSaver = remember { Saver<dev.agentring.core.Attachment?, String>(
        save = { it?.let { attachment -> dev.agentring.core.ProtocolJson.encodeToString(dev.agentring.core.Attachment.serializer(), attachment) } },
        restore = { dev.agentring.core.ProtocolJson.decodeFromString(dev.agentring.core.Attachment.serializer(), it) },
    ) }
    var uploaded by rememberSaveable(stateSaver = uploadedSaver) { mutableStateOf<dev.agentring.core.Attachment?>(null) }
    var transfer by remember { mutableStateOf<AttachmentTransfer?>(null) }
    var transferred by remember { mutableStateOf(0L) }
    var preparing by remember { mutableStateOf(false) }
    val sendAttempt = rememberSaveable(saver = Saver(save = { attempt: dev.agentring.core.MessageSendAttempt -> attempt.snapshot() }, restore = { dev.agentring.core.MessageSendAttempt.restore(it) })) { dev.agentring.core.MessageSendAttempt() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val control = AttachmentTransfer(); transfer = control; preparing = true; transferred = 0; sendError = null
            scope.launch {
                try { selected?.file?.delete(); selected = null; uploaded = null; selected = withContext(Dispatchers.IO) { store.stage(uri, control) { transferred = it } } }
                catch (e: Exception) { sendError = e.message ?: "Cannot read file" }
                finally { transfer = null; preparing = false }
            }
        }
    }
    DisposableEffect(chat.chatId) { onDispose { transfer?.cancel() } }

    DisposableEffect(chat.chatId, threadVisible) {
        AppVisibility.visibleChatId = chat.chatId.takeIf { threadVisible }
        onDispose { AppVisibility.visibleChatId = null }
    }
    LaunchedEffect(chat.chatId, contentVisible, focusMessageId, focusRequest, focusRetry) {
        try {
            if (focusMessageId != null) { focusLoading = true; focusError = null }
            withContext(Dispatchers.IO) {
                val api = Graph.api() ?: error("Not paired")
                if (focusMessageId != null) {
                    val target = api.getMessage(focusMessageId)
                    require(target.chatId == chat.chatId) { "Search result belongs to another session" }
                    Graph.messages.upsert(target)
                }
                Graph.messages.refreshChat(api, chat.chatId)
            }
            focusLoading = false
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { focusLoading = false; if (focusMessageId != null) focusError = e.message ?: "Could not load matching message" }
    }
    LaunchedEffect(focusMessageId, focusRequest, messages.size, focusLoading, contentVisible) {
        if (focusMessageId != null && !focusReached && !focusLoading && focusError == null && contentVisible) {
            val index = messages.asReversed().indexOfFirst { it.msgId == focusMessageId }
            if (index >= 0) { listState.scrollToItem(index); focusReached = true }
        }
    }
    // Opening the thread (or a new message arriving while it's open) marks the agent's messages read.
    LaunchedEffect(messages.size, AppVisibility.foreground.value, threadVisible) {
        if (!threadVisible || !AppVisibility.isForeground) return@LaunchedEffect
        Notifications.cancelMessages(ctx, chat.chatId)
        if (messages.any { it.fromAgent && it.readAt == null }) {
            withContext(Dispatchers.IO) { Graph.api()?.let { runCatching { Graph.messages.markAgentMessagesRead(it, chat.chatId) } } }
        }
    }
    LaunchedEffect(messages.size) { if (threadVisible && focusMessageId == null && messages.isNotEmpty()) listState.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val online by Graph.connection.agentOnline.collectAsStateWithLifecycle()
            TextButton(onClick = onBack, modifier = Modifier.testTag("back_chats")) { Text("Sessions") }
            Column(Modifier.weight(1f)) {
                Text(chat.displayTitle(pairing.agentName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("chat_provider"))
                Text(chat.caller.modelLine, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chat_model"))
                Text(chat.caller.projectLine, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chat_project"))
            }
            val relay by Graph.connection.relayState.collectAsStateWithLifecycle()
            val revoked by Graph.pairing.revoked.collectAsStateWithLifecycle()
            CallAgentButton(pairing, relay == dev.agentring.core.RelaySocket.State.CONNECTED, online == true, revoked, chatId = chat.chatId, compact = true, sessionVoiceAvailable = chat.callee != null)
        }
        if (chat.callee == null) Text("Voice calls to this session are not connected yet", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("session_voice_unavailable"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (focusLoading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("search_target_loading"))
        focusError?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Could not open search match: $it", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { focusRetry++ }) { Text("Retry") }
            }
        }
        if (messages.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                HeroBadge(androidx.compose.ui.res.painterResource(R.drawable.ic_chat), size = 72.dp)
                Text(
                    stringResource(R.string.messages_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.messages_empty, pairing.agentName),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("messages_empty"),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("message_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(messages.asReversed(), key = { it.msgId }) { m ->
                    Bubble(m)
                }
            }
        }

        sendError?.let {
            Text(
                stringResource(R.string.send_failed, it) + " Tap Send to retry.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (selected != null || preparing || sending && transfer != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(selected?.let { "${it.name} · ${attachmentSize(it.size)}" } ?: "Preparing attachment…", style = MaterialTheme.typography.bodySmall)
                if (transfer != null) {
                    if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { if ((selected?.size ?: 0) > 0) transferred.toFloat() / selected!!.size else 0f }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { transfer?.cancel() }) { Text("Cancel transfer") }
                } else TextButton(onClick = { selected?.file?.delete(); selected = null; uploaded = null }) { Text("Remove attachment") }
            }
        }
        FormattingToolbar(input, enabled = !sending && !preparing) { input = formattedComposer(input, it) }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !sending && !preparing, modifier = Modifier.testTag("attach_button")) { Text("Attach") }
            OutlinedTextField(
                value = input,
                enabled = !sending && !preparing,
                onValueChange = { input = editedComposer(input, it) },
                placeholder = { Text(stringResource(R.string.message_hint, chat.composerTarget())) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).testTag("message_input"),
            )
            FilledIconButton(
                onClick = {
                    val text = composerMessage(input)
                    if (text.isBlank() && selected == null || sending || preparing) return@FilledIconButton
                    val attempt = sendAttempt.forPayload(text, selected?.file?.name)
                    if (attempt.payloadChanged) uploaded = null
                    scope.launch {
                        sending = true
                        sendError = null
                        try {
                            val api = Graph.api() ?: error("not paired")
                            val control = AttachmentTransfer(); transfer = control.takeIf { selected != null }; transferred = 0
                            val m = withContext(Dispatchers.IO) {
                                val draft = selected
                                if (draft != null && uploaded == null) {
                                    uploaded = api.uploadAttachment(draft.name, draft.mediaType, draft.size, { draft.file.inputStream() }, control, chat.chatId) { transferred = it }
                                }
                                control.check()
                                api.postMessage(text, clientMsgId = attempt.clientMsgId, attachmentIds = listOfNotNull(uploaded?.attachmentId), chatId = chat.chatId)
                            }
                            Graph.messages.upsert(m)
                            input = TextFieldValue()
                            selected?.file?.delete(); selected = null; uploaded = null; sendAttempt.reset()
                        } catch (e: Exception) {
                            sendError = e.message ?: e.javaClass.simpleName
                        } finally {
                            transfer = null
                            sending = false
                        }
                    }
                },
                enabled = (input.text.isNotBlank() || selected != null) && !sending && !preparing,
                modifier = Modifier.size(52.dp).testTag("send_button"),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.padding(10.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Message) {
    val mine = !m.fromAgent
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
            ),
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            border = if (mine) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.text.isNotBlank()) RichMessageBody(m.text, m.msgId)
                m.attachments.forEach { AttachmentCard(it) }
                m.question?.let { QuestionCard(m, it) }
            }
        }
        val time = DateUtils.formatDateTime(LocalContext.current, m.createdAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
        Text(
            "$time · " + when { m.readAt != null -> "✓✓ Read"; m.deliveredAt != null -> "✓✓ Delivered"; else -> "✓ Sent" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).testTag("timestamp_${m.msgId}"),
        )
    }
}
