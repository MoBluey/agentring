package dev.agentring.app.data

import android.content.Context
import android.util.Log
import dev.agentring.core.Chat
import dev.agentring.core.Event
import dev.agentring.core.Message
import dev.agentring.core.ProtocolJson
import dev.agentring.core.RelayApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The conversation with the agent. The relay is the source of truth; this is
 * an in-memory list plus a small JSON cache so the thread shows instantly.
 */
class MessageRepository(context: Context) {
    private val cache = File(context.filesDir, "messages.json")
    private val chatCache = File(context.filesDir, "chats.json")
    private val chatSerializer = ListSerializer(Chat.serializer())
    private val _chats = MutableStateFlow(runCatching { ProtocolJson.decodeFromString(chatSerializer, chatCache.readText()) }.getOrDefault(emptyList()))
    val chats: StateFlow<List<Chat>> = _chats
    private val prefs = context.getSharedPreferences("agentring_state", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(Message.serializer())

    private val _messages = MutableStateFlow(loadCache())
    val messages: StateFlow<List<Message>> = _messages

    /** Highest relay event `seq` applied from the WebSocket stream. */
    val lastSeq: Long get() = prefs.getLong(KEY_SEQ, 0L)

    private fun loadCache(): List<Message> = try {
        if (cache.exists()) ProtocolJson.decodeFromString(serializer, cache.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "dropping unreadable message cache", e)
        emptyList()
    }

    private fun persist(list: List<Message>) {
        try {
            val tmp = File(cache.parentFile, cache.name + ".tmp")
            tmp.writeText(ProtocolJson.encodeToString(serializer, list.takeLast(MAX_CACHED)))
            tmp.renameTo(cache)
        } catch (e: Exception) {
            Log.w(TAG, "cannot write message cache", e)
        }
    }

    private fun mutate(block: (List<Message>) -> List<Message>) {
        synchronized(this) {
            _messages.update { block(it) }
            persist(_messages.value)
        }
    }

    /** Inserts or updates by msg_id. A truncated push never overwrites a full text we already have. */
    fun upsert(m: Message, truncated: Boolean = false) = mutate { list ->
        val existing = list.firstOrNull { it.msgId == m.msgId }
        val merged = when {
            existing == null -> m
            truncated -> existing.copy(readAt = existing.readAt ?: m.readAt)
            else -> m.copy(readAt = m.readAt ?: existing.readAt)
        }
        (list.filter { it.msgId != m.msgId } + merged).sortedBy { it.msgId }
    }

    fun upsertChat(chat: Chat) = synchronized(this) {
        _chats.value = (_chats.value.filter { it.chatId != chat.chatId } + chat).sortedByDescending { it.updatedAt }
        runCatching { val temporary = File(chatCache.parentFile, "chats.json.tmp"); temporary.writeText(ProtocolJson.encodeToString(chatSerializer, _chats.value)); temporary.renameTo(chatCache) }
    }

    /** Applies a relay event. `advanceSeq` is false for push-delivered events (the stream may still be behind). */
    fun apply(event: Event, advanceSeq: Boolean) {
        when (event) {
            is Event.ChatUpdated -> upsertChat(event.chat)
            is Event.MsgNew -> upsert(event.toMessage(), truncated = event.truncated == true)
            is Event.MsgRead -> mutate { list ->
                list.map { if (it.msgId == event.msgId && it.readAt == null) it.copy(readAt = event.ts) else it }
            }
            else -> Unit
        }
        val seq = event.seq
        if (advanceSeq && seq != null && seq > lastSeq) prefs.edit().putLong(KEY_SEQ, seq).apply()
    }

    /** Pulls messages we don't have yet. Blocking; call from a background thread. */
    fun refresh(api: RelayApi) {
        api.listChats().chats.forEach(::upsertChat)
        var after = _messages.value.lastOrNull()?.msgId
        while (true) {
            val page = api.listMessages(after = after)
            page.messages.forEach { upsert(it) }
            if (!page.hasMore || page.messages.isEmpty()) return
            after = page.messages.last().msgId
        }
    }

    /** Restores a selected chat even when its older messages were evicted from the small cache. */
    fun refreshChat(api: RelayApi, chatId: String) {
        var after: String? = null
        while (true) {
            val page = api.listMessages(after = after, chatId = chatId)
            page.messages.forEach { upsert(it) }
            if (!page.hasMore || page.messages.isEmpty()) return
            after = page.messages.last().msgId
        }
    }

    /** Sends read receipts for the agent's messages. Blocking. */
    fun markAgentMessagesRead(api: RelayApi, chatId: String? = null) {
        val unread = _messages.value.filter { it.fromAgent && it.readAt == null && (chatId == null || it.chatId == chatId) }
        val now = System.currentTimeMillis()
        for (m in unread) {
            try {
                api.markRead(m.msgId)
                mutate { list -> list.map { if (it.msgId == m.msgId) it.copy(readAt = now) else it } }
            } catch (e: Exception) {
                Log.w(TAG, "markRead failed", e)
                return
            }
        }
    }

    fun unreadFromAgent(): List<Message> = _messages.value.filter { it.fromAgent && it.readAt == null }

    fun clear() = synchronized(this) {
        _chats.value = emptyList()
        chatCache.delete()
        _messages.value = emptyList()
        cache.delete()
        prefs.edit().remove(KEY_SEQ).apply()
    }

    private companion object {
        const val TAG = "MessageRepository"
        const val KEY_SEQ = "last_seq"
        const val MAX_CACHED = 500
    }
}
