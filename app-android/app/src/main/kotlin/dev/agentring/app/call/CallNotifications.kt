package dev.agentring.app.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dev.agentring.app.R
import dev.agentring.app.notify.Notifications
import dev.agentring.app.ui.CallActivity
import dev.agentring.app.ui.MainActivity

object CallNotifications {
    const val CHANNEL_CALLS = "calls"
    const val CHANNEL_CALLS_URGENT = "calls_urgent"
    const val CHANNEL_MISSED = "missed_calls"
    const val CHANNEL_IN_PROGRESS = "calls_in_progress"
    const val ID_CALL = 2001
    private const val ID_MISSED = 2002
    private const val ID_JOIN = 2003

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The app plays the ringtone itself (it must honour the ringer mode and use the alarm
        // stream for urgent calls), so the channels are silent.
        fun silent(id: String, nameRes: Int, importance: Int) = NotificationChannel(id, context.getString(nameRes), importance).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(silent(CHANNEL_CALLS, R.string.channel_calls, NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(
            silent(CHANNEL_CALLS_URGENT, R.string.channel_calls_urgent, NotificationManager.IMPORTANCE_HIGH).apply {
                // Only takes effect when the user granted Do Not Disturb access.
                setBypassDnd(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MISSED, context.getString(R.string.channel_missed_calls), NotificationManager.IMPORTANCE_DEFAULT),
        )
        // "Call in progress — Join": visible in the status bar, but never rings or pops up.
        nm.createNotificationChannel(silent(CHANNEL_IN_PROGRESS, R.string.channel_calls_in_progress, NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun person(info: CallInfo) = Person.Builder().setName(info.displayName + " · " + info.callerLabel).setKey("agent").setImportant(true).build()

    private fun activityIntent(context: Context, action: String?, requestCode: Int): PendingIntent {
        val i = Intent(context, CallActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun receiverIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun incoming(context: Context, info: CallInfo): Notification {
        // Answer opens the activity directly (no trampoline): that also gives us microphone access.
        val answer = activityIntent(context, CallActivity.ACTION_ANSWER, 1)
        val decline = receiverIntent(context, CallActionReceiver.ACTION_DECLINE, 2)
        val fullScreen = activityIntent(context, null, 3)
        return NotificationCompat.Builder(context, if (info.urgent) CHANNEL_CALLS_URGENT else CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(info.displayName)
            .setContentText(info.notificationText)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(info), decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setTimeoutAfter((info.expiresAt - System.currentTimeMillis() + 5_000).coerceAtLeast(5_000))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun ongoing(context: Context, call: CallUi): Notification {
        val hangUp = receiverIntent(context, CallActionReceiver.ACTION_HANG_UP, 4)
        val b = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(call.info.displayName)
            .setContentText(call.info.callerLabel)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(call.info), hangUp))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(activityIntent(context, null, 5))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (call is CallUi.Active) {
            // Convert the elapsed-realtime start into wall-clock time for the chronometer.
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - call.since))
        }
        return b.build()
    }

    /** Shown briefly when the service was started for a call that has already ended. */
    fun placeholder(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.call_ended))
            .setSilent(true)
            .build()

    @SuppressLint("MissingPermission")
    fun post(context: Context, n: Notification) {
        if (Notifications.canNotify(context)) NotificationManagerCompat.from(context).notify(ID_CALL, n)
    }

    /**
     * Ongoing "Call in progress with <agent> — Join" while a group call is active and
     * this phone is not on it. Join opens the call screen directly (no trampoline), so
     * the microphone foreground service may start.
     */
    @SuppressLint("MissingPermission")
    fun showJoin(context: Context, offer: JoinOffer) {
        if (!Notifications.canNotify(context)) return
        val info = offer.info
        val join = PendingIntent.getActivity(
            context,
            7,
            CallActivity.joinIntent(context, info),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context,
            8,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_IN_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.call_in_progress_with, info.agentName))
            .setContentText(info.notificationText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_call, context.getString(R.string.call_join), join)
        offer.answeredAt?.let { b.setUsesChronometer(true).setWhen(it) }
        NotificationManagerCompat.from(context).notify(ID_JOIN, b.build())
    }

    fun cancelJoin(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_JOIN)
    }

    @SuppressLint("MissingPermission")
    fun showMissed(context: Context, info: CallInfo) {
        if (!Notifications.canNotify(context)) return
        val open = PendingIntent.getActivity(
            context,
            6,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.call_missed_from, info.agentName))
            .setContentText(info.notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.notificationText))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_MISSED, n)
    }
}
