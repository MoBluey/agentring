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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
    val requestedChat = AppVisibility.requestedChat.value
    LaunchedEffect(requestedChat, chats) {
        if (requestedChat != null && chats.any { it.chatId == requestedChat }) { selected = requestedChat; AppVisibility.requestedChat.value = null }
    }
    val chat = chats.firstOrNull { it.chatId == selected }
    if (chat != null) {
        androidx.activity.compose.BackHandler { selected = null }
        androidx.compose.runtime.key(chat.chatId) { ConversationScreen(pairing, chat) { selected = null } }
    } else {
        Column(Modifier.fillMaxSize()) {
            Text("Chats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize().testTag("chat_list")) {
                items(chats, key = { it.chatId }) { item ->
                    Surface(onClick = { selected = item.chatId }, modifier = Modifier.fillMaxWidth().testTag("chat_${item.chatId}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(item.caller.label, style = MaterialTheme.typography.bodySmall)
                            if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall)
                            val latest = allMessages.lastOrNull { it.chatId == item.chatId }
                            latest?.let { Text(it.text.ifBlank { it.attachments.firstOrNull()?.name ?: "Attachment" }, maxLines = 1, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    androidx.compose.material3.HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ConversationScreen(pairing: Pairing, chat: Chat, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allMessages by Graph.messages.messages.collectAsStateWithLifecycle()
    val messages = allMessages.filter { it.chatId == chat.chatId }
    var input by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val store = remember(ctx) { AttachmentStore(ctx) }
    var selected by remember { mutableStateOf<SelectedAttachment?>(null) }
    var uploaded by remember { mutableStateOf<dev.agentring.core.Attachment?>(null) }
    var transfer by remember { mutableStateOf<AttachmentTransfer?>(null) }
    var transferred by remember { mutableStateOf(0L) }
    var preparing by remember { mutableStateOf(false) }
    val sendAttempt = remember { dev.agentring.core.MessageSendAttempt() }
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
    DisposableEffect(chat.chatId) { onDispose { transfer?.cancel(); selected?.file?.delete() } }

    DisposableEffect(Unit) {
        AppVisibility.visibleChatId = chat.chatId
        onDispose { AppVisibility.visibleChatId = null }
    }
    LaunchedEffect(chat.chatId) {
        withContext(Dispatchers.IO) { Graph.api()?.let { runCatching { Graph.messages.refreshChat(it, chat.chatId) } } }
    }
    // Opening the thread (or a new message arriving while it's open) marks the agent's messages read.
    LaunchedEffect(messages.size) {
        Notifications.cancelMessages(ctx, chat.chatId)
        if (messages.any { it.fromAgent && it.readAt == null }) {
            withContext(Dispatchers.IO) { Graph.api()?.let { runCatching { Graph.messages.markAgentMessagesRead(it, chat.chatId) } } }
        }
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val online by Graph.connection.agentOnline.collectAsStateWithLifecycle()
            TextButton(onClick = onBack, modifier = Modifier.testTag("back_chats")) { Text("Back") }
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium)
                Text(chat.caller.label, style = MaterialTheme.typography.bodySmall)
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            val lastReadByAgent = messages.lastOrNull { !it.fromAgent && it.readAt != null }?.msgId
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("message_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(messages.asReversed(), key = { it.msgId }) { m ->
                    Bubble(m, showRead = m.msgId == lastReadByAgent)
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
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !sending && !preparing, modifier = Modifier.testTag("attach_button")) { Text("Attach") }
            OutlinedTextField(
                value = input,
                enabled = !sending && !preparing,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.message_hint, pairing.agentName)) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).testTag("message_input"),
            )
            FilledIconButton(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty() && selected == null || sending || preparing) return@FilledIconButton
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
                            input = ""
                            selected?.file?.delete(); selected = null; uploaded = null; sendAttempt.reset()
                        } catch (e: Exception) {
                            sendError = e.message ?: e.javaClass.simpleName
                        } finally {
                            transfer = null
                            sending = false
                        }
                    }
                },
                enabled = (input.isNotBlank() || selected != null) && !sending && !preparing,
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
private fun Bubble(m: Message, showRead: Boolean) {
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
                if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyLarge)
                m.attachments.forEach { AttachmentCard(it) }
            }
        }
        val time = DateUtils.getRelativeTimeSpanString(m.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        Text(
            if (showRead) "$time · ${stringResource(R.string.read)}" else time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
