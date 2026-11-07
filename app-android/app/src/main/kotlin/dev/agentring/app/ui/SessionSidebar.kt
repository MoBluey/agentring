package dev.agentring.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.agentring.app.data.Pairing
import dev.agentring.app.Graph
import dev.agentring.core.Chat
import dev.agentring.core.ChatSearchMatch
import dev.agentring.core.ChatSearchResult
import dev.agentring.core.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keep filters outside either responsive sidebar so folding does not reset a search. */
internal class SessionBrowserState(query: String = "", status: String = "active", provider: String? = null) {
    var query by mutableStateOf(query)
    var status by mutableStateOf(status)
    var provider by mutableStateOf(provider)
    var results by mutableStateOf<List<ChatSearchResult>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var revision by mutableIntStateOf(0)
    var archiving by mutableStateOf<String?>(null)
    var archiveError by mutableStateOf<String?>(null)

    fun archive(chat: Chat) {
        if (archiving != null) return
        archiving = chat.chatId; archiveError = null
        Graph.appScope.launch(Dispatchers.Main.immediate) {
            try {
                val updated = withContext(Dispatchers.IO) { (Graph.api() ?: error("Not paired")).setChatArchived(chat.chatId, chat.archivedAt == null) }
                Graph.messages.upsertChat(updated)
                revision++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { archiveError = e.message ?: "Could not update archive" }
            finally { archiving = null }
        }
    }

    companion object {
        val Saver = Saver<SessionBrowserState, List<String>>(
            save = { listOf(it.query, it.status, it.provider.orEmpty()) },
            restore = { SessionBrowserState(it[0], it[1], it[2].takeIf(String::isNotEmpty)) },
        )
    }
}

@Composable
internal fun rememberSessionBrowser(chats: List<Chat>): SessionBrowserState {
    val state = rememberSaveable(saver = SessionBrowserState.Saver) { SessionBrowserState() }
    LaunchedEffect(state.query, state.status, state.provider, state.revision, chats) {
        val query = state.query.trim()
        val status = state.status
        val provider = state.provider
        state.error = null
        if (query.isEmpty()) {
            // The synced chat cache remains browsable offline; full-text search still uses the server.
            state.results = chats.filter { chat ->
                (status == "all" || (chat.archivedAt != null) == (status == "archived")) &&
                    (provider == null || chat.caller.provider.equals(provider, ignoreCase = true))
            }.map { ChatSearchResult(it, ChatSearchMatch("metadata", "")) }
            state.loading = false
            return@LaunchedEffect
        }
        state.loading = true; state.results = emptyList()
        try {
            delay(250)
            val results = withContext(Dispatchers.IO) { (Graph.api() ?: error("Not paired")).searchChats(query, status, provider).results }
            // withContext checks cancellation before returning; an older response cannot replace a newer filter.
            state.results = results; state.loading = false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.error = e.message ?: "Could not load sessions"; state.loading = false }
    }
    return state
}

@Composable
internal fun SessionSidebar(pairing: Pairing, chats: List<Chat>, messages: List<Message>, selected: String?, browser: SessionBrowserState, onSelect: (ChatSearchResult) -> Unit, onClose: () -> Unit) {
    var providerMenu by remember { mutableStateOf(false) }
    val providers = chats.mapNotNull { it.caller.provider?.takeIf(String::isNotBlank) }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    Column(Modifier.fillMaxSize().testTag("sessions_sidebar")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose, modifier = Modifier.testTag("close_sidebar")) { Text("Close") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (status in listOf("active", "archived", "all")) TextButton(onClick = { browser.status = status }, modifier = Modifier.testTag("sessions_status_$status")) {
                Text(status.replaceFirstChar { it.uppercase() }, fontWeight = if (browser.status == status) FontWeight.Bold else FontWeight.Normal,
                    color = if (browser.status == status) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { providerMenu = true }, modifier = Modifier.testTag("sessions_provider")) { Text("Provider: " + (browser.provider ?: "All")) }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                DropdownMenuItem(text = { Text("All providers") }, onClick = { browser.provider = null; providerMenu = false }, modifier = Modifier.testTag("provider_all"))
                providers.forEach { provider -> DropdownMenuItem(text = { Text(provider) }, onClick = { browser.provider = provider; providerMenu = false }, modifier = Modifier.testTag("provider_$provider")) }
            }
        }
        OutlinedTextField(value = browser.query, onValueChange = { browser.query = it.take(200) }, singleLine = true,
            label = { Text("Search sessions and messages") },
            trailingIcon = { if (browser.query.isNotEmpty()) TextButton(onClick = { browser.query = "" }, modifier = Modifier.testTag("clear_session_search")) { Text("Clear") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("session_search"))
        browser.archiveError?.let { Text("Archive failed: $it", Modifier.padding(12.dp).testTag("archive_error"), color = MaterialTheme.colorScheme.error) }
        if (browser.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("session_search_loading"))
            Text(if (browser.query.isBlank()) "Loading sessions…" else "Searching…", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        } else if (browser.error != null) {
            Text("Could not load sessions: ${browser.error}", Modifier.padding(12.dp).testTag("session_search_error"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { browser.revision++ }, modifier = Modifier.testTag("retry_session_search")) { Text("Retry") }
        } else if (browser.results.isEmpty()) {
            val empty = if (browser.query.isBlank()) "No ${browser.status.takeUnless { it == "all" }?.plus(" ").orEmpty()}sessions" else when (browser.status) {
                "active" -> "No matches in active sessions. Archived sessions are excluded."
                "archived" -> "No matches in archived sessions. Active sessions are excluded."
                else -> "No matches in all sessions."
            }
            Text(empty, Modifier.padding(16.dp).testTag("session_search_empty"))
            if (browser.query.isNotBlank() && browser.status != "all") {
                TextButton(onClick = { browser.status = "all" }, modifier = Modifier.padding(horizontal = 8.dp).testTag("search_all_sessions")) { Text("Search all sessions") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_list")) {
            items(browser.results, key = { it.chat.chatId }) { result ->
                val item = result.chat
                Surface(onClick = { onSelect(result) },
                    color = if (item.chatId == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().testTag("chat_${item.chatId}")) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.displayTitle(pairing.agentName), style = MaterialTheme.typography.titleMedium)
                        Text(item.caller.modelLine, style = MaterialTheme.typography.bodySmall)
                        Text(item.caller.projectLine, style = MaterialTheme.typography.bodySmall)
                        if (browser.query.isNotBlank()) {
                            val snippet = result.match.snippet
                            val highlighted = AnnotatedString.Builder(snippet).apply {
                                val query = browser.query.trim()
                                var at = snippet.indexOf(query, ignoreCase = true)
                                while (query.isNotEmpty() && at >= 0) {
                                    addStyle(SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold), at, at + query.length)
                                    at = snippet.indexOf(query, at + query.length, ignoreCase = true)
                                }
                            }.toAnnotatedString()
                            Text(highlighted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("search_snippet_${item.chatId}"))
                            Text(if (result.match.kind == "message") "Message match" else "Session match", style = MaterialTheme.typography.labelSmall)
                        } else messages.lastOrNull { it.chatId == item.chatId }?.let { Text(it.text.ifBlank { it.attachments.firstOrNull()?.name ?: "Attachment" }, maxLines = 1, style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = { browser.archive(item) }, enabled = browser.archiving == null, modifier = Modifier.align(Alignment.End).testTag("archive_${item.chatId}")) {
                            Text(if (browser.archiving == item.chatId) "Saving…" else if (item.archivedAt == null) "Archive" else "Unarchive")
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
