package dev.agentring.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.ui.MainActivity
import dev.agentring.core.Message

object Notifications {
    const val CHANNEL_MESSAGES = "messages"
    const val ID_MESSAGES = 1001
    const val ID_TEST = 1002
    const val ID_REPLY_FAILED = 1003
    const val KEY_REPLY = "reply_text"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun mutableFlag() = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    fun openMessagesIntent(context: Context, chatId: String = "chat_default"): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MESSAGES)
            .putExtra("chat_id", chatId)
            .setData(android.net.Uri.Builder().scheme("agentring-mo").authority("messages").appendPath(chatId).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun replyAction(context: Context, chatId: String): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java).putExtra("chat_id", chatId)
            .setData(android.net.Uri.Builder().scheme("agentring-mo").authority("reply").appendPath(chatId).build())
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag())
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.reply)).build()
        return NotificationCompat.Action.Builder(R.drawable.ic_send, context.getString(R.string.reply), pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun agentPerson(name: String) = Person.Builder().setName(name).setKey("agent").build()

    /**
     * Shows (or updates) the conversation notification with the agent's unread
     * messages, plus an optional reply the user just sent from the notification.
     */
    @SuppressLint("MissingPermission")
    fun showMessages(
        context: Context,
        agentName: String,
        agentMessages: List<Message> = Graph.messages.unreadFromAgent(),
        sentReply: String? = null,
        chatId: String? = null,
    ) {
        if (!canNotify(context)) return
        val groups = agentMessages.filter { chatId == null || it.chatId == chatId }.groupBy { it.chatId }
        if (groups.size > 1) { groups.forEach { (id, messages) -> showMessages(context, agentName, messages, chatId = id) }; return }
        val destinationChatId = chatId ?: agentMessages.firstOrNull()?.chatId ?: "chat_default"
        val chat = Graph.messages.chats.value.firstOrNull { it.chatId == destinationChatId }
        val unread = groups[destinationChatId].orEmpty().takeLast(6)
        if (unread.isEmpty() && sentReply == null) return
        val me = Person.Builder().setName(context.getString(R.string.you)).build()
        val agent = agentPerson(chat?.let { "${it.title} · ${it.caller.label}" } ?: agentName)
        val style = NotificationCompat.MessagingStyle(me)
        unread.forEach { style.addMessage(it.text.ifBlank { it.attachments.joinToString { attachment -> attachment.name } }, it.createdAt, agent) }
        if (sentReply != null) style.addMessage(sentReply, System.currentTimeMillis(), null as Person?)

        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openMessagesIntent(context, destinationChatId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(sentReply != null)
            .addAction(replyAction(context, destinationChatId))
            .build()
        NotificationManagerCompat.from(context).notify(destinationChatId, ID_MESSAGES, n)
    }

    @SuppressLint("MissingPermission")
    fun showReplyFailed(context: Context, text: String, error: String, chatId: String = "chat_default") {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reply_failed_title))
            .setContentText("$text — $error")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$error"))
            .setContentIntent(openMessagesIntent(context, chatId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(chatId, ID_REPLY_FAILED, n)
    }

    @SuppressLint("MissingPermission")
    fun showTest(context: Context) {
        if (!canNotify(context)) return
        val name = Graph.pairing.current?.agentName ?: context.getString(R.string.app_name)
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.you)).build())
            .addMessage(context.getString(R.string.test_notification_text), System.currentTimeMillis(), agentPerson(name))
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_TEST, n)
    }

    fun cancelMessages(context: Context, chatId: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (chatId != null) manager.cancel(chatId, ID_MESSAGES)
        else manager.activeNotifications.filter { it.id == ID_MESSAGES }.forEach { manager.cancel(it.tag, it.id) }
    }
}
