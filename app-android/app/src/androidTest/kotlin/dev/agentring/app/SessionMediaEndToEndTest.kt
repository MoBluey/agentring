package dev.agentring.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.agentring.app.notify.Notifications
import dev.agentring.app.push.PushHandler
import dev.agentring.app.ui.MainActivity
import dev.agentring.core.ProtocolJson
import dev.agentring.core.PushVerifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentValues
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import dev.agentring.app.data.AttachmentStore
import dev.agentring.core.Attachment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * M1 acceptance on a real emulator against a real relay (started by CI).
 * Instrumentation args: relayUrl (as seen from the emulator) and apiKey (agent key).
 */
@RunWith(AndroidJUnit4::class)
class SessionMediaEndToEndTest {
    companion object {
        private val args = InstrumentationRegistry.getArguments()
        private val relayUrl: String = args.getString("relayUrl") ?: "http://10.0.2.2:8080"
        private val apiKey: String = args.getString("apiKey") ?: "ak_ci_test_key"

        @BeforeClass
        @JvmStatic
        fun resetApp() {
            // Start from a fresh install state.
            Graph.unpair()
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    private val notificationPermission: TestRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else TestRule { base, _ -> base }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(notificationPermission).around(compose)

    private val http = OkHttpClient()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun agent(method: String, path: String, json: String? = null): JsonObject {
        val req = Request.Builder().url(relayUrl + path)
            .header("Authorization", "Bearer $apiKey")
            .method(method, json?.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body!!.string()
            assertTrue("$method $path -> ${res.code}: $body", res.isSuccessful)
            return if (body.isEmpty()) JsonObject(emptyMap()) else ProtocolJson.parseToJsonElement(body).jsonObject
        }
    }

    /** Runs a shell command as the shell user and waits for it to finish. */
    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // The shell user can write to /data/local/tmp; CI pulls these files.
        shell("screencap -p /data/local/tmp/agentring-$name.png")
    }

    private fun waitForTag(tag: String, text: String? = null, timeoutMs: Long = 15_000) {
        val matcher = if (text == null) hasTestTag(tag) else hasTestTag(tag).and(hasText(text, substring = true))
        try { compose.waitUntil(timeoutMs) { runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
        catch (e: Exception) { screenshot("session-failure-$tag"); throw e }
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) { runCatching { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }

    private fun userMessages(): List<String> =
        agent("GET", "/v1/messages?from=user")["messages"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }

    private fun waitFor(timeoutMs: Long = 15_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) { screenshot("condition-failure"); throw AssertionError("timed out waiting for $what") }
            Thread.sleep(250)
        }
    }

    @Test
    fun twoSessionsSendReceiveFilesAndLabelIncomingCall() {
        val qr = agent("POST", "/v1/pairing-tokens", "{}")["qr_payload"]!!.jsonPrimitive.content
        compose.onNodeWithTag("get_started").performClick()
        waitForTag("show_paste")
        compose.onNodeWithTag("show_paste").performClick()
        compose.onNodeWithTag("pairing_code").performScrollTo().performTextInput(qr)
        compose.onNodeWithTag("pair_button").performScrollTo().performClick()
        waitForTag("calls_next", timeoutMs = 20_000)
        compose.onNodeWithTag("calls_next").performClick()
        waitForTag("continue")
        compose.onNodeWithTag("continue").performClick()
        waitForTag("relay_status", context.getString(R.string.connected), timeoutMs = 20_000)

        val first = agent("POST", "/v1/chats", """{"client_session_id":"android-session-claude","title":"Claude · claude-sonnet-4-5 · Orchard","description":"Coding session for Orchard","caller":{"provider":"Anthropic","model":"claude-sonnet-4-5","project":"Orchard"}}""")
        val second = agent("POST", "/v1/chats", """{"client_session_id":"android-session-codex","title":"Codex · gpt-5.1-codex · Harbour","description":"Coding session for Harbour","caller":{"provider":"OpenAI","model":"gpt-5.1-codex","project":"Harbour"}}""")
        val firstId = first["chat_id"]!!.jsonPrimitive.content
        val secondId = second["chat_id"]!!.jsonPrimitive.content
        compose.onNodeWithTag("tab_messages").performClick()
        waitForTag("chat_$firstId")
        waitForTag("chat_$secondId")
        screenshot("sessions")

        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF".toByteArray()
        val stamp = System.currentTimeMillis().toString().takeLast(6)
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val fixtures = listOf(
            Triple("session-$stamp-image.png", "image/png", png),
            Triple("session-$stamp-report.pdf", "application/pdf", pdf),
            Triple("session-$stamp-audio.wav", "audio/wav", testContext.assets.open("sample.wav").use { it.readBytes() }),
            Triple("session-$stamp-video.mp4", "video/mp4", testContext.assets.open("sample.mp4").use { it.readBytes() }),
        )
        for ((index, fixture) in fixtures.withIndex()) {
            val chatId = if (index % 2 == 0) firstId else secondId
            compose.onNodeWithTag("chat_$chatId").performClick()
            waitForTag("message_input")
            if (index == 0) {
                compose.onNodeWithTag("attach_button").performClick()
                Thread.sleep(500)
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                waitForTag("message_input")
                val oversized = createDownload("oversize-$stamp.bin", "application/octet-stream", byteArrayOf())
                try {
                    context.contentResolver.openFileDescriptor(oversized, "w")!!.use { android.system.Os.ftruncate(it.fileDescriptor, 100L * 1024 * 1024 + 1) }
                    compose.onNodeWithTag("attach_button").performClick()
                    pickDocument("oversize-$stamp.bin")
                    waitForText("Files must be 100 MiB or smaller")
                } finally { context.contentResolver.delete(oversized, null, null) }
            }
            val fixtureUri = createDownload(fixture.first, fixture.second, fixture.third)
            try {
                compose.onNodeWithTag("attach_button").performClick()
                pickDocument(fixture.first)
                waitForText(fixture.first)
                compose.onNodeWithTag("message_input").performTextInput("Reply in session $index ($stamp)")
                compose.onNodeWithTag("send_button").performClick()
                var sent: JsonObject? = null
                waitFor(what = "scoped attachment reply") {
                    sent = agent("GET", "/v1/messages?chat_id=$chatId&from=user")["messages"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["text"]?.jsonPrimitive?.content == "Reply in session $index ($stamp)" }
                    sent != null
                }
                val attachment = sent!!["attachments"]!!.jsonArray.single().jsonObject
                assertEquals(chatId, sent!!["chat_id"]!!.jsonPrimitive.content)
                val uploadedBytes = agentBytes("/v1/attachments/${attachment["attachment_id"]!!.jsonPrimitive.content}/content")
                assertArrayEquals(fixture.third, uploadedBytes)
                assertEquals(sha(fixture.third), attachment["sha256"]!!.jsonPrimitive.content)
                assertTrue(agent("GET", "/v1/messages?chat_id=${if (index % 2 == 0) secondId else firstId}&from=user")["messages"]!!.jsonArray.none { it.jsonObject["text"]?.jsonPrimitive?.content == "Reply in session $index ($stamp)" })

                // Agent -> phone uses a separate upload and a normal msg.new, then the UI download.
                val incoming = agentUpload(chatId, "agent-" + fixture.first, fixture.second, fixture.third)
                val incomingId = incoming["attachment_id"]!!.jsonPrimitive.content
                agent("POST", "/v1/messages", """{"chat_id":"$chatId","text":"From this coding session","attachment_ids":["$incomingId"]}""")
                waitForTag("download_$incomingId")
                compose.onNodeWithTag("download_$incomingId").performScrollTo().performClick()
                waitForTag("open_$incomingId")
                val decoded = ProtocolJson.decodeFromString(Attachment.serializer(), incoming.toString())
                val downloaded = AttachmentStore(context).cached(decoded)!!
                assertArrayEquals(fixture.third, downloaded.readBytes())
                assertEquals(sha(fixture.third), sha(downloaded.readBytes()))
                screenshot("media-$index")
                // Save through Android's document destination picker, then verify its bytes.
                compose.onNodeWithTag("save_$incomingId").performScrollTo().performClick()
                nativeClick("Save", ignoreCase = true)
                waitForTag("message_input")
                val saved = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/agent-${fixture.first}")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(saved).use { assertArrayEquals(fixture.third, it.readBytes()) }
            } finally { context.contentResolver.delete(fixtureUri, null, null) }
            compose.onNodeWithTag("back_chats").performClick()
        }
        // Switch through a notification while the old conversation has a draft.
        compose.onNodeWithTag("chat_$firstId").performClick()
        compose.onNodeWithTag("message_input").performTextInput("Draft belongs to Orchard")
        Notifications.openMessagesIntent(context, secondId).send()
        compose.waitUntil(15_000) { dev.agentring.app.ui.AppVisibility.visibleChatId == secondId }
        assertTrue(compose.onAllNodes(hasTestTag("message_input").and(hasText("Draft belongs to Orchard"))).fetchSemanticsNodes().isEmpty())

        // A first message in a new session arrives while the app is backgrounded.
        compose.activity.moveTaskToBack(true)
        waitFor(what = "foreground socket stopped") { Graph.connection.relayState.value == dev.agentring.core.RelaySocket.State.DISCONNECTED }
        val backgroundChat = agent("POST", "/v1/chats", """{"client_session_id":"background-$stamp","title":"Background session","caller":{"provider":"OpenAI","model":"new-session-model","project":"Background project"}}""")
        val backgroundId = backgroundChat["chat_id"]!!.jsonPrimitive.content
        assertTrue(Graph.messages.chats.value.none { it.chatId == backgroundId })
        val backgroundMessage = agent("POST", "/v1/messages", """{"chat_id":"$backgroundId","text":"First background message"}""")
        val event = agent("GET", "/v1/events?after_seq=${Graph.messages.lastSeq}&limit=500")["events"]!!.jsonArray.map { it.jsonObject }
            .first { it["msg_id"]?.jsonPrimitive?.content == backgroundMessage["msg_id"]!!.jsonPrimitive.content }.toString()
        val pairing = Graph.pairing.current!!
        assertTrue(PushHandler.handle(context, mapOf("v" to "1", "relay_id" to pairing.relayId, "event" to event, "sig" to PushVerifier.sign(pairing.pushSecret, event))) is PushHandler.Outcome.Notified)
        assertEquals("new-session-model", Graph.messages.chats.value.first { it.chatId == backgroundId }.caller.model)
        val nm = context.getSystemService(NotificationManager::class.java)
        waitFor(what = "labelled new-session notification") { nm.activeNotifications.any { it.tag == backgroundId && androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)?.messages?.lastOrNull()?.person?.name.toString().contains("new-session-model") } }
        // A known backgrounded session can switch models before its next message.
        agent("POST", "/v1/chats", """{"client_session_id":"background-$stamp","caller":{"provider":"OpenAI","model":"updated-session-model","project":"Background project"}}""")
        val updatedMessage = agent("POST", "/v1/messages", """{"chat_id":"$backgroundId","text":"Message after model update"}""")
        val updatedEvent = agent("GET", "/v1/events?after_seq=${Graph.messages.lastSeq}&limit=500")["events"]!!.jsonArray.map { it.jsonObject }
            .first { it["msg_id"]?.jsonPrimitive?.content == updatedMessage["msg_id"]!!.jsonPrimitive.content }.toString()
        assertTrue(PushHandler.handle(context, mapOf("v" to "1", "relay_id" to pairing.relayId, "event" to updatedEvent, "sig" to PushVerifier.sign(pairing.pushSecret, updatedEvent))) is PushHandler.Outcome.Notified)
        assertEquals("updated-session-model", Graph.messages.chats.value.first { it.chatId == backgroundId }.caller.model)
        waitFor(what = "updated known-session notification label") { nm.activeNotifications.any { it.tag == backgroundId && androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)?.messages?.lastOrNull()?.person?.name.toString().contains("updated-session-model") } }
        val initialNotification = nm.activeNotifications.first { it.tag == backgroundId && it.id == Notifications.ID_MESSAGES }.notification
        Notifications.openMessagesIntent(context, backgroundId).send()
        waitForTag("message_input")
        waitForText("First background message")

        // The original notification may be replied to after its unread messages were cleared.
        waitFor(what = "background message read") { Graph.messages.unreadFromAgent().none { it.chatId == backgroundId } }
        fun reply(notification: android.app.Notification, text: String) {
            val action = notification.actions.first { it.remoteInputs?.isNotEmpty() == true }
            val fill = Intent()
            RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(Notifications.KEY_REPLY).build()), fill, Bundle().apply { putCharSequence(Notifications.KEY_REPLY, text) })
            action.actionIntent.send(context, 0, fill)
        }
        val firstReply = "Inline after read $stamp"
        reply(initialNotification, firstReply)
        waitFor(what = "first scoped inline reply") { agent("GET", "/v1/messages?chat_id=$backgroundId&from=user")["messages"]!!.jsonArray.any { it.jsonObject["text"]!!.jsonPrimitive.content == firstReply } }
        waitFor(what = "scoped inline confirmation") { nm.activeNotifications.any { it.tag == backgroundId && androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)?.messages?.lastOrNull()?.text.toString() == firstReply } }
        val confirmation = nm.activeNotifications.first { it.tag == backgroundId && it.id == Notifications.ID_MESSAGES }.notification
        val secondReply = "Second inline reply $stamp"
        reply(confirmation, secondReply)
        waitFor(what = "second reply retained originating session") { agent("GET", "/v1/messages?chat_id=$backgroundId&from=user")["messages"]!!.jsonArray.any { it.jsonObject["text"]!!.jsonPrimitive.content == secondReply } }
        Notifications.openMessagesIntent(context, firstId).send()
        compose.waitUntil(15_000) { dev.agentring.app.ui.AppVisibility.visibleChatId == firstId }
        Notifications.showReplyFailed(context, "Pending reply", "Offline", backgroundId)
        waitFor(what = "scoped reply failure") { nm.activeNotifications.any { it.id == Notifications.ID_REPLY_FAILED && it.tag == backgroundId } }
        nm.activeNotifications.first { it.id == Notifications.ID_REPLY_FAILED && it.tag == backgroundId }.notification.contentIntent.send()
        compose.waitUntil(15_000) { dev.agentring.app.ui.AppVisibility.visibleChatId == backgroundId }

        // Recreate through the normal Activity lifecycle; files and session selection survive repository cache reload.
        compose.activityRule.scenario.recreate()
        waitForTag("tab_messages")
        Notifications.openMessagesIntent(context, secondId).send()
        compose.waitUntil(15_000) { dev.agentring.app.ui.AppVisibility.visibleChatId == secondId }
        waitForText("agent-" + fixtures[3].first)
        // A normal relay call is identified by the originating coding session, not voice model.
        val call = agent("POST", "/v1/calls", """{"chat_id":"$firstId","reason":"Review Orchard build"}""")["call"]!!.jsonObject
        val callId = call["call_id"]!!.jsonPrimitive.content
        waitFor(what = "incoming call state") { Graph.calls.state.value?.info?.callId == callId }
        assertEquals("claude-sonnet-4-5", Graph.calls.state.value!!.info.chat!!.caller.model)
        waitFor(what = "posted incoming call notification") { nm.activeNotifications.any { it.id == dev.agentring.app.call.CallNotifications.ID_CALL } }
        val notification = nm.activeNotifications.first { it.id == dev.agentring.app.call.CallNotifications.ID_CALL }.notification
        val callerPerson = notification.extras.getParcelable("android.callPerson", android.app.Person::class.java)!!
        assertTrue(callerPerson.name.toString().contains("Orchard"))
        assertTrue(callerPerson.name.toString().contains("claude-sonnet-4-5"))
        notification.contentIntent.send()
        waitFor(what = "CallActivity resumed from notification") {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys activity activities")
            val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
            java.util.regex.Pattern.compile("(?m)^.*(?:topResumedActivity|mResumedActivity)[^\\n]*CallActivity[^\\n]*$").matcher(dump).find()
        }
        waitForTag("call_caller", "claude-sonnet-4-5")
        nativeWait("Review Orchard build")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        compose.waitForIdle()
        shell("screencap -p /data/local/tmp/session-labelled-call.png")
        agent("POST", "/v1/calls/$callId/end", "{}")
        waitFor(what = "call ended") { Graph.calls.state.value == null }

    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun agentBytes(path: String): ByteArray {
        http.newCall(Request.Builder().url(relayUrl + path).header("Authorization", "Bearer $apiKey").build()).execute().use { response ->
            assertTrue(response.isSuccessful)
            return response.body!!.bytes()
        }
    }

    private fun agentUpload(chatId: String, name: String, type: String, bytes: ByteArray): JsonObject {
        val url = (relayUrl + "/v1/attachments").toHttpUrl().newBuilder()
            .addQueryParameter("chat_id", chatId).addQueryParameter("name", name).addQueryParameter("media_type", type).build()
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $apiKey").post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { response ->
            val body = response.body!!.string(); assertTrue(body, response.isSuccessful); return ProtocolJson.parseToJsonElement(body).jsonObject
        }
    }

    private fun createDownload(name: String, type: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    private fun nativeNode(text: String, substring: Boolean = false, ignoreCase: Boolean = false): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (listOf(node.text?.toString(), node.contentDescription?.toString()).any { value -> value != null && if (substring) value.contains(text, ignoreCase) else value.equals(text, ignoreCase) }) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    private fun nativeWait(text: String, substring: Boolean = false, ignoreCase: Boolean = false): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        try { waitFor(what = "native text $text") { found = nativeNode(text, substring, ignoreCase); found != null } }
        catch (e: AssertionError) { screenshot("native-failure"); throw e }
        return found!!
    }

    private fun pickDocument(name: String) {
        nativeClick("Show roots")
        nativeClick("Downloads")
        nativeClick(name)
    }

    private fun nativeClick(text: String, ignoreCase: Boolean = false) {
        fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (listOf(node.text?.toString(), node.contentDescription?.toString()).any { it?.equals(text, ignoreCase) == true }) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.isEnabled) return target
            }
            for (index in 0 until node.childCount) clickable(node.getChild(index))?.let { return it }
            return null
        }
        var target: AccessibilityNodeInfo? = null
        waitFor(what = "clickable native $text") { target = clickable(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow); target != null }
        assertTrue("Clickable native $text", target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
