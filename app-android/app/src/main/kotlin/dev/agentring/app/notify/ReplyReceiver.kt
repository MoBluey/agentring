package dev.agentring.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dev.agentring.app.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/** Inline reply from the message notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val chatId = intent.getStringExtra("chat_id") ?: "chat_default"
        val appContext = context.applicationContext
        val pending = goAsync()
        Graph.appScope.launch(Dispatchers.IO) {
            try {
                val api = Graph.api() ?: error("not paired")
                val agentName = Graph.pairing.current?.agentName ?: ""
                val shown = Graph.messages.unreadFromAgent().filter { it.chatId == chatId }
                Graph.messages.upsert(api.postMessage(text, clientMsgId = UUID.randomUUID().toString(), chatId = chatId))
                // Replying implies the user read what the agent said.
                Graph.messages.markAgentMessagesRead(api, chatId)
                Notifications.showMessages(appContext, agentName, shown, sentReply = text, chatId = chatId)
            } catch (e: Exception) {
                Log.w("ReplyReceiver", "reply failed", e)
                Notifications.cancelMessages(appContext, chatId)
                Notifications.showReplyFailed(appContext, text, e.message ?: e.javaClass.simpleName, chatId)
            } finally {
                pending.finish()
            }
        }
    }
}
