package dev.agentring.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import android.content.ClipboardManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
            if (args.getString("preservePairing") != "true") Graph.unpair()
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    private val notificationPermission: TestRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
        else GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

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
    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // The shell user can write to /data/local/tmp; CI pulls these files.
        shell("screencap -p /data/local/tmp/agentring-$name.png")
    }

    private fun waitForTag(tag: String, text: String? = null, timeoutMs: Long = 15_000) {
        if (tag.startsWith("chat_chat_")) {
            compose.waitUntil(timeoutMs) { Graph.messages.chats.value.any { "chat_${it.chatId}" == tag } }
            compose.waitUntil(timeoutMs) { runCatching { compose.onNodeWithTag("chat_list").performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false) }
        }
        val matcher = if (text == null) hasTestTag(tag) else hasTestTag(tag).and(hasText(text, substring = true))
        try { compose.waitUntil(timeoutMs) { runCatching { compose.onAllNodes(matcher, useUnmergedTree = tag.startsWith("search_snippet_")).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
        catch (e: Throwable) { println(compose.onRoot(useUnmergedTree = true).printToString()); println("WAIT_DIAGNOSTIC tag=$tag chats=${Graph.messages.chats.value.map { it.chatId }} messages=${Graph.messages.messages.value.size} seq=${Graph.messages.lastSeq} foreground=${dev.agentring.app.ui.AppVisibility.isForeground} socket=${Graph.connection.relayState.value}"); screenshot("session-failure-$tag"); throw e }
    }

    private fun openChat(chatId: String) {
        if (!runCatching { compose.onNodeWithTag("chat_list").isDisplayed() }.getOrDefault(false)) {
            val tag = if (compose.onAllNodes(hasTestTag("back_chats")).fetchSemanticsNodes().isNotEmpty()) "back_chats" else "open_sidebar"
            compose.onNodeWithTag(tag).performClick()
        }
        waitForTag("chat_$chatId")
        compose.onNodeWithTag("chat_$chatId").performClick()
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

    private fun pairNormally() {
        val qr = agent("POST", "/v1/pairing-tokens", "{}")["qr_payload"]!!.jsonPrimitive.content
        compose.onNodeWithTag("get_started").performClick()
        waitForTag("show_paste")
        compose.onNodeWithTag("show_paste").performClick()
        waitForTag("pairing_code")
        compose.waitForIdle()
        compose.onNodeWithTag("pairing_code").performScrollTo().performTextInput(qr)
        compose.waitForIdle()
        compose.onNodeWithTag("pair_button").performScrollTo().performClick()
        waitForTag("calls_next", timeoutMs = 20_000)
        compose.onNodeWithTag("calls_next").performClick()
        waitForTag("continue")
        compose.onNodeWithTag("continue").performClick()
        waitForTag("relay_status", context.getString(R.string.connected), timeoutMs = 20_000)

    }

    @Test
    fun twoSessionsSendReceiveFilesAndLabelIncomingCall() {
        pairNormally()

        val sessionStamp = System.currentTimeMillis().toString()
        val first = agent("POST", "/v1/chats", """{"client_session_id":"android-session-claude-$sessionStamp","title":"Claude · claude-sonnet-4-5 · Orchard","description":"Coding session for Orchard","caller":{"provider":"Anthropic","model":"claude-sonnet-4-5","project":"Orchard"}}""")
        val second = agent("POST", "/v1/chats", """{"client_session_id":"android-session-codex-$sessionStamp","title":"Codex · gpt-5.1-codex · Harbour","description":"Coding session for Harbour","caller":{"provider":"OpenAI","model":"gpt-5.1-codex","project":"Harbour"}}""")
        val firstId = first["chat_id"]!!.jsonPrimitive.content
        val secondId = second["chat_id"]!!.jsonPrimitive.content
        compose.onNodeWithTag("tab_messages").performClick()
        waitForTag("chat_$firstId")
        waitForTag("chat_$secondId")
        screenshot("sessions")

        // Real composer selection formats visible text; Markdown remains the transport value.
        openChat(firstId)
        waitForTag("message_input")
        val editor = compose.onNodeWithTag("message_input")
        val richStamp = System.currentTimeMillis().toString()
        editor.performTextInput("alpha beta gamma $richStamp")
        editor.performTextInputSelection(TextRange(6, 10))
        compose.onNodeWithTag("format_bold").performClick()
        editor.performTextInputSelection(TextRange(0, 5))
        compose.onNodeWithTag("format_highlight").performClick()
        waitForTag("message_input", "alpha beta gamma")
        screenshot("rich-composer")
        compose.onNodeWithTag("send_button").performClick()
        val formattedRaw = "==alpha== **beta** gamma $richStamp"
        var formattedMessage: JsonObject? = null
        waitFor(what = "formatted user message on real relay") {
            formattedMessage = agent("GET", "/v1/messages?chat_id=$firstId&from=user")["messages"]!!.jsonArray.map { it.jsonObject }.lastOrNull { it["text"]!!.jsonPrimitive.content == formattedRaw }
            formattedMessage != null
        }
        val userId = formattedMessage!!["msg_id"]!!.jsonPrimitive.content
        waitForTag("message_text_${userId}_0", "alpha beta gamma")
        compose.onNodeWithTag("copy_message_$userId").performScrollTo().performClick()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals(formattedRaw, clipboard.primaryClip!!.getItemAt(0).text.toString())

        val code = "  println(\"agent code\")\nprintln(\"done\")"
        val rawRich = "Select alpha beta\n\n**Bold agent** and ==highlight==\n\n```kotlin\n$code\n```"
        val rich = agent("POST", "/v1/messages", org.json.JSONObject().put("chat_id", firstId).put("text", rawRich).toString())
        val richId = rich["msg_id"]!!.jsonPrimitive.content
        waitForTag("copy_code_${richId}_1")
        compose.onNodeWithTag("copy_message_$richId").performScrollTo().performClick()
        assertEquals(rawRich, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithTag("copy_code_${richId}_1").performScrollTo().performClick()
        assertEquals(code, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithTag("message_text_${richId}_0").performScrollTo().performTouchInput { longClick(Offset(20f, 20f)) }
        nativeClick("Copy")
        val selectedText = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue("Selected actual message text", selectedText.isNotBlank() && selectedText.length < rawRich.length && rawRich.contains(selectedText))
        compose.onNodeWithTag("message_input").performClick()
        screenshot("rich-agent-message")

        val userCode = "println(\"user code $richStamp\")"
        editor.performTextInput(userCode)
        editor.performTextInputSelection(TextRange(0, userCode.length))
        compose.onNodeWithTag("format_code").performClick()
        compose.onNodeWithTag("send_button").performClick()
        val userCodeRaw = "```\n$userCode\n```"
        var codeMessage: JsonObject? = null
        waitFor(what = "fenced user code on relay") {
            codeMessage = agent("GET", "/v1/messages?chat_id=$firstId&from=user")["messages"]!!.jsonArray.map { it.jsonObject }.lastOrNull { it["text"]!!.jsonPrimitive.content == userCodeRaw }
            codeMessage != null
        }
        val codeId = codeMessage!!["msg_id"]!!.jsonPrimitive.content
        waitForTag("copy_code_${codeId}_0")
        compose.onNodeWithTag("copy_code_${codeId}_0").performScrollTo().performClick()
        assertEquals(userCode, clipboard.primaryClip!!.getItemAt(0).text.toString())
        // Each receipt proves the recipient's explicit acknowledgement, not a history fetch.
        waitForTag("timestamp_$codeId", "✓ Sent")
        agent("GET", "/v1/messages/$codeId")
        assertTrue(agent("GET", "/v1/messages/$codeId")["read_at"] == null)
        agent("POST", "/v1/messages/$codeId/delivered", "{}")
        waitForTag("timestamp_$codeId", "✓✓ Delivered")
        agent("POST", "/v1/messages/$codeId/read", "{}")
        waitForTag("timestamp_$codeId", "✓✓ Read")
        val questionIds = mutableListOf<String>()
        for (writtenAnswer in listOf(false, true)) {
            val questionJson = org.json.JSONObject().put("chat_id", firstId).put("client_msg_id", "question-$richStamp-$writtenAnswer")
                .put("text", if (writtenAnswer) "**What should change?**" else "**Deploy this build?**")
                .put("question", org.json.JSONObject().put("allow_free_text", true).put("options", org.json.JSONArray()
                    .put(org.json.JSONObject().put("id", "ship").put("label", "Ship it"))
                    .put(org.json.JSONObject().put("id", "hold").put("label", "Hold"))))
            val q = agent("POST", "/v1/messages", questionJson.toString())
            val qid = q["msg_id"]!!.jsonPrimitive.content
            questionIds += qid
            waitForTag("question_$qid")
            if (writtenAnswer) compose.onNodeWithTag("answer_text_$qid").performScrollTo().performTextInput("Change the button label $richStamp")
            else compose.onNodeWithTag("option_${qid}_hold").performScrollTo().performClick()
            assertTrue("Selecting a choice never submits", agent("GET", "/v1/messages/$qid")["question"]!!.jsonObject["answered_by_msg_id"] == null)
            screenshot(if (writtenAnswer) "written-question" else "choice-question")
            compose.onNodeWithTag("answer_send_$qid").performScrollTo().performClick()
            waitForTag("answered_$qid")
            val restored = agent("GET", "/v1/messages/$qid")
            val answerId = restored["question"]!!.jsonObject["answered_by_msg_id"]!!.jsonPrimitive.content
            val answer = agent("GET", "/v1/messages/$answerId")
            assertEquals(firstId, answer["chat_id"]!!.jsonPrimitive.content)
            assertEquals(qid, answer["reply_to"]!!.jsonPrimitive.content)
            assertEquals(qid, answer["answer"]!!.jsonObject["question_id"]!!.jsonPrimitive.content)
            assertEquals(if (writtenAnswer) "Change the button label $richStamp" else "Hold", answer["text"]!!.jsonPrimitive.content)
            assertTrue(agent("GET", "/v1/messages?chat_id=$secondId")["messages"]!!.jsonArray.none { it.jsonObject["msg_id"]!!.jsonPrimitive.content == answerId })
            waitFor(what = "phone delivery/read acknowledgement") {
                val saved = agent("GET", "/v1/messages/$qid")
                saved["delivered_at"] != null && saved["read_at"] != null
            }
        }
        compose.activityRule.scenario.recreate()
        waitForTag("tab_messages")
        Notifications.openMessagesIntent(context, firstId).send()
        waitForTag("answered_${questionIds.last()}")
        screenshot("answered-question-recreated")
        compose.onNodeWithTag("back_chats").performClick()

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
            openChat(chatId)
            waitForTag("message_input")
            waitForTag("chat_provider", if (index % 2 == 0) "Anthropic" else "OpenAI")
            waitForTag("chat_model", if (index % 2 == 0) "Model: claude-sonnet-4-5" else "Model: gpt-5.1-codex")
            waitForTag("chat_project", if (index % 2 == 0) "Project: Orchard" else "Project: Harbour")
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
                if (index != 1) compose.onNodeWithTag("message_input").performTextInput("Reply in session $index ($stamp)")
                compose.onNodeWithTag("send_button").performClick()
                var sent: JsonObject? = null
                waitFor(what = "scoped attachment reply") {
                    sent = agent("GET", "/v1/messages?chat_id=$chatId&from=user")["messages"]!!.jsonArray.map { it.jsonObject }.firstOrNull { if (index == 1) it["attachments"]?.jsonArray?.any { a -> a.jsonObject["name"]!!.jsonPrimitive.content == fixture.first } == true else it["text"]?.jsonPrimitive?.content == "Reply in session $index ($stamp)" }
                    sent != null
                }
                val sentId = sent!!["msg_id"]!!.jsonPrimitive.content
                waitForTag("timestamp_$sentId", "✓ Sent")
                agent("POST", "/v1/messages/$sentId/delivered", "{}")
                waitForTag("timestamp_$sentId", "✓✓ Delivered")
                agent("POST", "/v1/messages/$sentId/read", "{}")
                waitForTag("timestamp_$sentId", "✓✓ Read")
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
        openChat(firstId)
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
        // A truncated push cannot acknowledge an absent body after REST hydration fails.
        val originalPairing = Graph.pairing.current!!
        Graph.pairing.save(originalPairing.copy(relayUrl = originalPairing.relayUrl + "/unavailable")); Graph.pairing.setSetupComplete(true)
        val partial = try { agent("POST", "/v1/messages", org.json.JSONObject().put("chat_id", backgroundId).put("client_msg_id", "partial-$stamp")
            .put("text", "Complete question after retry\n" + "x".repeat(3900)).put("question", org.json.JSONObject().put("allow_free_text", true)
                .put("options", org.json.JSONArray().put(org.json.JSONObject().put("id", "yes").put("label", "Yes"))
                    .put(org.json.JSONObject().put("id", "no").put("label", "No")))).toString()) }
        catch (e: Exception) { Graph.pairing.save(originalPairing); Graph.pairing.setSetupComplete(true); throw e }
        val partialId = partial["msg_id"]!!.jsonPrimitive.content
        val partialEvent = org.json.JSONObject().put("type", "msg.new").put("id", "partial-$stamp").put("ts", partial["created_at"]!!.jsonPrimitive.content.toLong())
            .put("msg_id", partialId).put("from", "agent").put("text", "Complete…").put("chat_id", backgroundId).put("truncated", true).toString()
        val partialPush = mapOf("v" to "1", "relay_id" to originalPairing.relayId, "event" to partialEvent, "sig" to PushVerifier.sign(originalPairing.pushSecret, partialEvent))
        try {
            Graph.pairing.save(originalPairing.copy(relayUrl = originalPairing.relayUrl + "/unavailable")); Graph.pairing.setSetupComplete(true)
            assertTrue(PushHandler.handle(context, partialPush) is PushHandler.Outcome.Notified)
            waitFor(what = "safe pending notification after failed full hydration") {
                context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.tag == backgroundId && it.id == Notifications.ID_MESSAGES &&
                        it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("Waiting for connection")
                }
            }
            shell("cmd statusbar expand-notifications")
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
            nativeWait("Waiting for", substring = true)
            shell("screencap -p /data/local/tmp/pending-message-notification.png")
            shell("cmd statusbar collapse")
            assertTrue(Graph.messages.messages.value.none { it.msgId == partialId })
            val untouched = agent("GET", "/v1/messages/$partialId")
            assertTrue(untouched["delivered_at"] == null && untouched["read_at"] == null)
        } finally { Graph.pairing.save(originalPairing); Graph.pairing.setSetupComplete(true) }
        // The complete response is received but a failed atomic cache write must still block delivery.
        val temporaryCache = java.io.File(context.filesDir, "messages.json.tmp")
        assertTrue(temporaryCache.mkdir())
        try {
            PushHandler.handle(context, partialPush)
            Graph.messages.markAgentMessagesRead(Graph.api()!!, backgroundId)
            val untouched = agent("GET", "/v1/messages/$partialId")
            assertTrue("Failed cache write cannot report delivered/read", untouched["delivered_at"] == null && untouched["read_at"] == null)
        } finally { assertTrue(temporaryCache.delete()) }
        assertTrue(PushHandler.handle(context, partialPush) is PushHandler.Outcome.Notified)
        val completeQuestion = Graph.messages.messages.value.single { it.msgId == partialId }
        assertEquals(2, completeQuestion.question!!.options.size)
        val deliveredQuestion = agent("GET", "/v1/messages/$partialId")
        assertTrue(deliveredQuestion["delivered_at"] != null && deliveredQuestion["read_at"] == null)

        // Separate forced failure/retry pushes from the normal notification scenario (Android limits enqueue rate).
        Thread.sleep(1_500)
        val backgroundMessage = agent("POST", "/v1/messages", """{"chat_id":"$backgroundId","text":"First background message"}""")
        val event = agent("GET", "/v1/events?after_seq=${Graph.messages.lastSeq}&limit=500")["events"]!!.jsonArray.map { it.jsonObject }
            .first { it["msg_id"]?.jsonPrimitive?.content == backgroundMessage["msg_id"]!!.jsonPrimitive.content }.toString()
        val pairing = Graph.pairing.current!!
        assertTrue(PushHandler.handle(context, mapOf("v" to "1", "relay_id" to pairing.relayId, "event" to event, "sig" to PushVerifier.sign(pairing.pushSecret, event))) is PushHandler.Outcome.Notified)
        assertEquals("new-session-model", Graph.messages.chats.value.first { it.chatId == backgroundId }.caller.model)
        val nm = context.getSystemService(NotificationManager::class.java)
        waitFor(what = "labelled new-session notification") { nm.activeNotifications.any { it.tag == backgroundId && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() == "OpenAI" && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("Model: new-session-model\nProject: Background project") } }
        // A known backgrounded session can switch models before its next message.
        agent("POST", "/v1/chats", """{"client_session_id":"background-$stamp","caller":{"provider":"OpenAI","model":"updated-session-model","project":"Background project"}}""")
        val updatedMessage = agent("POST", "/v1/messages", """{"chat_id":"$backgroundId","text":"Message after model update"}""")
        val updatedEvent = agent("GET", "/v1/events?after_seq=${Graph.messages.lastSeq}&limit=500")["events"]!!.jsonArray.map { it.jsonObject }
            .first { it["msg_id"]?.jsonPrimitive?.content == updatedMessage["msg_id"]!!.jsonPrimitive.content }.toString()
        assertTrue(PushHandler.handle(context, mapOf("v" to "1", "relay_id" to pairing.relayId, "event" to updatedEvent, "sig" to PushVerifier.sign(pairing.pushSecret, updatedEvent))) is PushHandler.Outcome.Notified)
        assertEquals("updated-session-model", Graph.messages.chats.value.first { it.chatId == backgroundId }.caller.model)
        waitFor(what = "updated known-session notification label") { nm.activeNotifications.any { it.tag == backgroundId && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() == "OpenAI" && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("Model: updated-session-model\nProject: Background project") } }
        shell("cmd statusbar expand-notifications")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        if (nativeNode("Project: Background project", substring = true) == null && nativeNode("Expand notification") != null) nativeClick("Expand notification")
        nativeWait("Model: updated-session-model", substring = true)
        nativeWait("Project: Background project", substring = true)
        shell("screencap -p /data/local/tmp/presentation-message-notification.png")
        shell("cmd statusbar collapse")
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
        waitFor(what = "scoped inline confirmation") { nm.activeNotifications.any { it.tag == backgroundId && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().endsWith(firstReply) } }
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

        // Activity recreation preserves session navigation and reloads selected history from the relay.
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
        assertEquals("Anthropic", callerPerson.name.toString())
        assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().startsWith("Model: claude-sonnet-4-5\nProject: Orchard"))
        shell("cmd statusbar expand-notifications")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        println("CALL_NOTIFICATION_MODEL_VISIBLE=" + (nativeNode("Model: claude-sonnet-4-5", substring = true) != null))
        println("CALL_NOTIFICATION_PROJECT_VISIBLE=" + (nativeNode("Project: Orchard", substring = true) != null))
        shell("screencap -p /data/local/tmp/presentation-call-notification.png")
        shell("cmd statusbar collapse")
        notification.contentIntent.send()
        waitFor(what = "CallActivity resumed from notification") {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys activity activities")
            val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
            java.util.regex.Pattern.compile("(?m)^.*(?:topResumedActivity|mResumedActivity)[^\\n]*CallActivity[^\\n]*$").matcher(dump).find()
        }
        waitForTag("call_agent", "Anthropic")
        waitForTag("call_model", "Model: claude-sonnet-4-5")
        waitForTag("call_project", "Project: Orchard")
        nativeWait("Review Orchard build")
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        compose.waitForIdle()
        shell("screencap -p /data/local/tmp/session-labelled-call.png")
        agent("POST", "/v1/calls/$callId/end", "{}")
        waitFor(what = "call ended") { Graph.calls.state.value == null }
        verifyCallSelection()
    }

    /** One real unowned existing-session call must be unavailable, not labelled expired. */
    @Test
    fun unavailableSessionOwnerShowsHonestCallReason() {
        waitForTag("relay_status", context.getString(R.string.connected), timeoutMs = 30_000)
        val session = agent("POST", "/v1/chats", """{"client_session_id":"android-unowned-session-fixture-${System.currentTimeMillis()}","title":"Unavailable owner fixture","callee":"codex","caller":{"provider":"Codex","model":"unowned-session-fixture","project":"Call reason check"}}""")
        val chatId = session["chat_id"]!!.jsonPrimitive.content
        compose.onNodeWithTag("tab_messages").performClick()
        openChat(chatId)
        waitForTag("call_session")
        try {
            compose.onNodeWithTag("call_session").performClick()
            waitForTag("call_status", context.getString(R.string.call_callee_unavailable), timeoutMs = 30_000)
            val callId = Graph.calls.state.value!!.info.callId
            assertEquals("callee_unavailable", agent("GET", "/v1/calls/$callId")["end_reason"]!!.jsonPrimitive.content)
            assertTrue(context.getString(R.string.call_callee_unavailable) != context.getString(R.string.call_unavailable))
            screenshot("session-call-owner-unavailable")
            waitFor(what = "unavailable existing-session call cleaned up") { Graph.calls.state.value == null }
        } finally {
            (agent("GET", "/v1/status")["active_call"] as? JsonObject)?.let { agent("POST", "/v1/calls/${it["call_id"]!!.jsonPrimitive.content}/end", "{}") }
        }
    }

    /** Phase one leaves an actually archived chat and a cache that has evicted its old read message. */
    @Test
    fun sessionArchiveFiltersAndDrafts() {
        val firstId = args.getString("searchChatId")!!
        val secondId = args.getString("searchOtherChatId")!!
        val oldId = args.getString("searchMessageId")!!
        val literal = args.getString("searchLiteral")!!
        val titleQuery = args.getString("searchTitle")!!
        val otherMessageId = args.getString("searchOtherMessageId")!!
        val size = Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1) ?: "reset"
        val density = Regex("Override density: ([0-9]+)").find(shell("wm density"))?.groupValues?.get(1) ?: "reset"
        pairNormally()
        waitForTag("agent_name", "AgentRing")
        compose.onNodeWithTag("tab_messages").performClick()
        openChat(firstId)
        waitForTag("message_input")
        waitForTag("session_voice_unavailable", "Voice calls to this session are not connected yet")
        assertTrue(compose.onNodeWithTag("call_session").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled))
        waitFor(what = "old message read by actual visible conversation") { agent("GET", "/v1/messages/$oldId")["read_at"] != null }
        val draft = "Codex draft survives archive and filters"
        compose.onNodeWithTag("message_input").performTextInput(draft)
        try {
            compose.onNodeWithTag("back_chats").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("sessions_provider").performClick()
            waitForTag("provider_OpenAI / Codex")
            compose.onNodeWithTag("provider_OpenAI / Codex").performClick()
            compose.onNodeWithTag("session_search").performTextReplacement(titleQuery)
            waitForTag("search_snippet_$firstId", titleQuery)
            val title = compose.onNodeWithTag("search_snippet_$firstId", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            assertTrue("Matching suggestion visibly styled", title.spanStyles.isNotEmpty())
            screenshot("session-search-title")
            // Rapid edits plus a provider change must finish on the latest filter, without stale results.
            compose.onNodeWithTag("session_search").performTextReplacement("no-match-before-latest")
            compose.onNodeWithTag("session_search").performTextReplacement(literal)
            compose.onNodeWithTag("sessions_provider").performClick()
            compose.onNodeWithTag("provider_Claude Code").performClick()
            waitForTag("search_snippet_$secondId", literal)
            compose.onNodeWithTag("sessions_provider").performClick()
            compose.onNodeWithTag("provider_OpenAI / Codex").performClick()
            waitForTag("search_snippet_$firstId", literal)
            assertTrue(compose.onAllNodes(hasTestTag("chat_$secondId")).fetchSemanticsNodes().isEmpty())
            assertTrue("Search does not read the other matching chat", agent("GET", "/v1/messages/$otherMessageId")["read_at"] == null)
            screenshot("session-search-literal-provider")
            compose.onNodeWithTag("chat_$firstId").performClick()
            waitForTag("message_input", draft)
            waitForTag("message_text_${oldId}_0", literal)
            shell("wm size 1500x1800"); shell("wm density 320")
            waitForTag("message_input", draft, timeoutMs = 30_000)
            waitForTag("sessions_sidebar")
            waitForTag("search_snippet_$firstId", literal)
            val side = compose.onNodeWithTag("sessions_sidebar").fetchSemanticsNode().boundsInRoot
            val pane = compose.onNodeWithTag("conversation_pane").fetchSemanticsNode().boundsInRoot
            assertTrue(pane.left >= side.right - 2)
            compose.onNodeWithTag("archive_$firstId").performScrollTo().performClick()
            waitFor(what = "server archive persisted") { agent("GET", "/v1/chats/$firstId")["archived_at"] != null }
            waitForTag("session_search_empty")
            waitForTag("message_input", draft)
            compose.onNodeWithTag("sessions_status_archived").performClick()
            waitForTag("chat_$firstId")
            waitForTag("archive_$firstId", "Unarchive")
            screenshot("session-archived-wide-draft")
            compose.onNodeWithTag("archive_$firstId").performScrollTo().performClick()
            waitFor(what = "server unarchive reversible") { agent("GET", "/v1/chats/$firstId")["archived_at"] == null }
            waitForTag("session_search_empty")
            waitForTag("message_input", draft)
            compose.onNodeWithTag("sessions_status_active").performClick()
            waitForTag("chat_$firstId")
            compose.onNodeWithTag("archive_$firstId").performScrollTo().performClick()
            waitFor(what = "archive kept for actual process restart") { agent("GET", "/v1/chats/$firstId")["archived_at"] != null }
            compose.onNodeWithTag("sessions_status_all").performClick()
            compose.onNodeWithTag("clear_session_search").performClick()
            compose.onNodeWithTag("sessions_provider").performClick()
            compose.onNodeWithTag("provider_all").performClick()
            openChat(secondId)
            val otherDraft = "Claude draft stays separate"
            compose.onNodeWithTag("message_input").performTextInput(otherDraft)
            openChat(firstId)
            waitForTag("message_input", draft)
            openChat(secondId)
            waitForTag("message_input", otherDraft)
            openChat(firstId)
            shell("wm size $size"); shell("wm density $density")
            waitForTag("message_input", draft, timeoutMs = 30_000)
            screenshot("session-search-restored-narrow-draft")
            var latestInFirst = ""
            repeat(30) { index -> latestInFirst = Graph.api()!!.postMessage("Recent session text $index", clientMsgId = java.util.UUID.randomUUID().toString(), chatId = firstId).msgId }
            val filler = agent("POST", "/v1/chats", """{"client_session_id":"search-history-${System.currentTimeMillis()}","title":"Unread history fixture","caller":{"provider":"Claude Code","model":"search-history-fixture","project":"Unread fixture"}}""")["chat_id"]!!.jsonPrimitive.content
            val unread = agent("POST", "/v1/messages", """{"chat_id":"$filler","text":"Keep this unrelated chat unread"}""")["msg_id"]!!.jsonPrimitive.content
            var last = ""
            // Real device REST -> relay -> normal WS consumer, never seeding the repository/cache.
            repeat(505) { index -> last = Graph.api()!!.postMessage("History filler $index", clientMsgId = java.util.UUID.randomUUID().toString(), chatId = filler).msgId }
            waitFor(timeoutMs = 60_000, what = "WS persisted messages beyond the 500-message cache") {
                Graph.messages.messages.value.any { it.msgId == last } && !java.io.File(context.filesDir, "messages.json").readText().contains(oldId)
            }
            assertTrue(agent("GET", "/v1/messages/$unread")["read_at"] == null)
            println("SEARCH_PHASE_PROOF=" + org.json.JSONObject().put("unread_message_id", unread).put("filler_chat_id", filler).put("old_message_id", oldId).put("old_message_evicted_from_disk", true).put("later_real_messages", 505).put("latest_in_first", latestInFirst))
        } finally { shell("wm size $size"); shell("wm density $density") }
    }

    /** Run after an actual force-stop/cold launch with preservePairing=true, not ActivityScenario recreation. */
    @Test
    fun archivedSessionSurvivesColdLaunchAndOldHitLoads() {
        val firstId = args.getString("searchChatId")!!
        val oldId = args.getString("searchMessageId")!!
        val unreadId = args.getString("searchUnreadMessageId")!!
        val latestInFirst = args.getString("searchLatestMessageId")!!
        val literal = args.getString("searchLiteral")!!
        waitForTag("relay_status", context.getString(R.string.connected), timeoutMs = 30_000)
        assertTrue("Real process cache reload excludes old message", Graph.messages.messages.value.none { it.msgId == oldId })
        assertTrue(agent("GET", "/v1/chats/$firstId")["archived_at"] != null)
        compose.onNodeWithTag("tab_messages").performClick()
        waitForTag("session_search")
        compose.onNodeWithTag("sessions_provider").performClick()
        waitForTag("provider_OpenAI / Codex")
        compose.onNodeWithTag("provider_OpenAI / Codex").performClick()
        compose.onNodeWithTag("session_search").performTextReplacement(literal)
        waitForTag("session_search_empty") // Default Active excludes the archived result.
        compose.onNodeWithTag("sessions_status_archived").performClick()
        waitForTag("search_snippet_$firstId", literal)
        assertTrue(agent("GET", "/v1/messages/$unreadId")["read_at"] == null)
        screenshot("session-archived-after-cold-launch")
        compose.onNodeWithTag("chat_$firstId").performClick()
        waitForTag("message_text_${oldId}_0", literal, timeoutMs = 30_000)
        compose.waitForIdle()
        assertTrue("Search opened the actual old matching bubble", compose.onNodeWithTag("message_text_${oldId}_0").isDisplayed())
        assertTrue(agent("GET", "/v1/messages/$unreadId")["read_at"] == null)
        val draft = "Draft retained when unarchiving the selected session"
        compose.onNodeWithTag("message_input").performTextInput(draft)
        screenshot("session-old-message-target")
        compose.onNodeWithTag("message_list").performScrollToNode(hasTestTag("message_text_${latestInFirst}_0"))
        assertTrue(compose.onNodeWithTag("message_text_${latestInFirst}_0").isDisplayed())
        compose.onNodeWithTag("back_chats").performClick(); compose.waitForIdle()
        waitForTag("chat_$firstId")
        compose.onNodeWithTag("chat_$firstId").performClick()
        waitForTag("message_text_${oldId}_0", literal)
        compose.waitForIdle()
        assertTrue("Selecting identical search result re-anchors", compose.onNodeWithTag("message_text_${oldId}_0").isDisplayed())
        waitForTag("message_input", draft)
        compose.onNodeWithTag("back_chats").performClick(); compose.waitForIdle()
        waitForTag("archive_$firstId", "Unarchive")
        compose.onNodeWithTag("archive_$firstId").performScrollTo().performClick()
        waitFor(what = "unarchive after actual cold launch") { agent("GET", "/v1/chats/$firstId")["archived_at"] == null }
        waitForTag("session_search_empty")
        compose.onNodeWithTag("close_sidebar").performClick(); compose.waitForIdle()
        waitForTag("message_input", draft)
        compose.onNodeWithTag("back_chats").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("sessions_status_active").performClick()
        waitForTag("search_snippet_$firstId", literal)
        screenshot("session-unarchived-active")
        compose.onNodeWithTag("chat_$firstId").performClick()
        waitForTag("message_input", draft)
        assertTrue(agent("GET", "/v1/messages/$unreadId")["read_at"] == null)
        val wifi = shell("settings get global wifi_on").trim() == "1"
        val mobile = shell("settings get global mobile_data").trim() == "1"
        try {
            shell("svc wifi disable"); shell("svc data disable")
            compose.onNodeWithTag("back_chats").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("session_search").performTextReplacement("offline network search")
            waitForTag("session_search_error", timeoutMs = 35_000)
            waitForTag("retry_session_search")
            screenshot("session-search-offline-error")
            compose.onNodeWithTag("clear_session_search").performClick()
            waitForTag("chat_$firstId")
            screenshot("session-cached-sidebar-offline")
            compose.onNodeWithTag("chat_$firstId").performClick()
            waitForTag("message_input", draft)
            screenshot("session-cached-conversation-offline")
        } finally {
            shell("svc wifi " + if (wifi) "enable" else "disable")
            shell("svc data " + if (mobile) "enable" else "disable")
        }
        waitFor(timeoutMs = 30_000, what = "relay reconnects after restored emulator network") { Graph.connection.relayState.value == dev.agentring.core.RelaySocket.State.CONNECTED }
        compose.onNodeWithTag("back_chats").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("session_search").performTextReplacement(literal)
        waitForTag("search_snippet_$firstId", literal)
        compose.onNodeWithTag("chat_$firstId").performClick()
        waitForTag("message_input", draft)
        assertTrue(agent("GET", "/v1/messages/$unreadId")["read_at"] == null)
    }

    @Test
    fun textAttachmentsOpenSelectCopyAndSave() {
        val chatId = args.getString("textChatId") ?: error("textChatId required")
        val messageIds = args.getString("textMessageIds")!!.split(',')
        pairNormally()
        compose.onNodeWithTag("tab_messages").performClick()
        openChat(chatId)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        for ((index, messageId) in messageIds.withIndex()) {
            val attachment = ProtocolJson.decodeFromString(Attachment.serializer(), agent("GET", "/v1/messages/$messageId")["attachments"]!!.jsonArray.single().toString())
            val id = attachment.attachmentId
            waitForTag("download_$id")
            assertTrue(AttachmentStore(context).cached(attachment) == null)
            compose.waitForIdle()
            compose.onNodeWithTag("message_list").performScrollToNode(hasTestTag("download_$id"))
            compose.onNodeWithTag("download_$id").performClick()
            waitForTag("open_$id", "View text")
            compose.onNodeWithTag("open_$id").performScrollTo().performClick()
            waitForTag("text_attachment_content")
            val bounds = compose.onNodeWithTag("text_viewer").fetchSemanticsNode().boundsInRoot
            val window = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
            assertTrue(bounds.width >= window.width() - 2 && bounds.height >= window.height() - 2)
            val expected = agentBytes("/v1/attachments/$id/content").toString(Charsets.UTF_8)
            waitForTag("text_attachment_content", expected)
            compose.onNodeWithTag("copy_text_attachment").performClick()
            assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
            compose.onNodeWithTag("text_attachment_content").performTouchInput { longClick(Offset(30f, 20f)) }
            nativeClick("Copy")
            val selected = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue("Native selected text", selected.isNotBlank() && selected.length < expected.length && expected.contains(selected))
            screenshot("text-viewer-" + attachment.name.substringAfterLast('.'))
            // Dismiss native selection before exercising the viewer navigation/save actions.
            compose.onNodeWithTag("copy_text_attachment").performClick()
            if (index == 0) {
                val hidden = agent("POST", "/v1/messages", """{"chat_id":"$chatId","text":"Arrived behind the full-screen text"}""")["msg_id"]!!.jsonPrimitive.content
                waitFor(what = "delivered behind text viewer") { agent("GET", "/v1/messages/$hidden")["delivered_at"] != null }
                assertTrue(agent("GET", "/v1/messages/$hidden")["read_at"] == null)
                compose.onNodeWithTag("save_text_original").performClick()
                nativeClick("Save", ignoreCase = true)
                waitForTag("text_viewer")
                waitFor(what = "saved original text bytes") { shell("sha256sum /sdcard/Download/${attachment.name} /sdcard/Download/${attachment.name}.txt").lineSequence().any { it.substringBefore(' ') == attachment.sha256 } }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                waitForTag("message_input")
                waitFor(what = "read after returning from text viewer") { agent("GET", "/v1/messages/$hidden")["read_at"] != null }
            } else {
                compose.onNodeWithTag("close_text_viewer").performClick(); compose.waitForIdle()
                waitForTag("message_input")
            }
        }
        val stamp = System.currentTimeMillis()
        val large = ("Preview line with bounded bytes\n".repeat(10000)).toByteArray()
        val largeMetadata = agentUpload(chatId, "large-$stamp.log", "text/plain", large)
        val largeId = largeMetadata["attachment_id"]!!.jsonPrimitive.content
        agent("POST", "/v1/messages", """{"chat_id":"$chatId","text":"","attachment_ids":["$largeId"]}""")
        waitForTag("download_$largeId")
        compose.waitForIdle()
        compose.onNodeWithTag("message_list").performScrollToNode(hasTestTag("download_$largeId"))
        compose.onNodeWithTag("download_$largeId").performClick()
        waitForTag("open_$largeId")
        compose.onNodeWithTag("open_$largeId").performScrollTo().performClick()
        waitForTag("text_preview_truncated")
        waitForTag("copy_text_attachment", "Copy preview")
        compose.onNodeWithTag("copy_text_attachment").performClick()
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertEquals(large.copyOf(64 * 1024).toString(Charsets.UTF_8), copied)
        screenshot("text-viewer-truncated")
        compose.onNodeWithTag("save_text_original").performClick()
        nativeClick("Save", ignoreCase = true)
        waitForTag("text_viewer")
        waitFor(what = "saved complete large original") { shell("sha256sum /sdcard/Download/large-$stamp.log /sdcard/Download/large-$stamp.log.txt").lineSequence().any { it.substringBefore(' ') == largeMetadata["sha256"]!!.jsonPrimitive.content } }
        compose.onNodeWithTag("close_text_viewer").performClick(); compose.waitForIdle()
        val binary = agentUpload(chatId, "binary-$stamp.txt", "text/plain", byteArrayOf(65, 0, 66))
        val binaryId = binary["attachment_id"]!!.jsonPrimitive.content
        agent("POST", "/v1/messages", """{"chat_id":"$chatId","text":"","attachment_ids":["$binaryId"]}""")
        waitForTag("download_$binaryId")
        compose.waitForIdle()
        compose.onNodeWithTag("message_list").performScrollToNode(hasTestTag("download_$binaryId"))
        compose.onNodeWithTag("download_$binaryId").performClick()
        waitForTag("open_$binaryId")
        compose.onNodeWithTag("open_$binaryId").performScrollTo().performClick()
        waitForTag("text_preview_error", "binary data")
        waitForTag("save_text_original")
        screenshot("text-viewer-binary-fallback")
        compose.onNodeWithTag("close_text_viewer").performClick(); compose.waitForIdle()
        // An unsupported binary keeps normal download/save/external-open behaviour.
        val pdf = agentUpload(chatId, "unsupported-$stamp.pdf", "application/pdf", "%PDF-1.4\n%%EOF".toByteArray())
        val pdfId = pdf["attachment_id"]!!.jsonPrimitive.content
        agent("POST", "/v1/messages", """{"chat_id":"$chatId","text":"","attachment_ids":["$pdfId"]}""")
        waitForTag("download_$pdfId")
        compose.waitForIdle()
        compose.onNodeWithTag("message_list").performScrollToNode(hasTestTag("download_$pdfId"))
        compose.onNodeWithTag("download_$pdfId").performClick()
        waitForTag("open_$pdfId", "Open")
        waitForTag("save_$pdfId")
        // Shared verified-cache image path remains intact after extracting verification.
        val image = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        val imageBytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val metadata = agentUpload(chatId, "image-boundary-$stamp.png", "image/png", imageBytes)
        val imageAttachment = ProtocolJson.decodeFromString(Attachment.serializer(), metadata.toString())
        val store = AttachmentStore(context)
        agent("POST", "/v1/messages", """{"chat_id":"$chatId","text":"Image cache boundary","attachment_ids":["${imageAttachment.attachmentId}"]}""")
        waitForTag("preview_${imageAttachment.attachmentId}")
        val file = store.cached(imageAttachment)!!
        val decoded = store.image(imageAttachment, file, 512)
        assertEquals(4, decoded.width); decoded.recycle()
        val textAttachment = ProtocolJson.decodeFromString(Attachment.serializer(), agent("GET", "/v1/messages/${messageIds.first()}")["attachments"]!!.jsonArray.single().toString())
        val cached = store.cached(textAttachment)!!
        val original = cached.readBytes()
        try {
            cached.writeBytes(ByteArray(original.size) { 65 })
            try { store.text(textAttachment, cached); org.junit.Assert.fail("Changed cache checksum must fail") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("checksum")) }
        } finally { cached.writeBytes(original) }
    }

    @Test
    fun inlineImagesFullScreenAndResponsiveSessions() {
        val firstId = args.getString("imageChatId") ?: error("imageChatId required")
        val secondId = args.getString("otherChatId") ?: error("otherChatId required")
        val imageId = args.getString("imageAttachmentId") ?: error("imageAttachmentId required")
        val incomingMessageId = args.getString("imageMessageId") ?: error("imageMessageId required")
        val incoming = agent("GET", "/v1/messages/$incomingMessageId")["attachments"]!!.jsonArray.single().jsonObject
        val attachment = ProtocolJson.decodeFromString(Attachment.serializer(), incoming.toString())
        assertEquals(args.getString("imageExpectedSha"), attachment.sha256)
        val size = Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1) ?: "reset"
        val density = Regex("Override density: ([0-9]+)").find(shell("wm density"))?.groupValues?.get(1) ?: "reset"
        pairNormally()
        assertEquals("Agentring", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals("Agentring", context.packageManager.getActivityInfo(android.content.ComponentName(context, MainActivity::class.java), 0).loadLabel(context.packageManager).toString())
        try {
            compose.onNodeWithTag("tab_messages").performClick()
            openChat(firstId)
            waitForTag("preview_$imageId", timeoutMs = 30_000)
            val cached = AttachmentStore(context).cached(attachment)!!
            assertEquals(attachment.sha256, sha(cached.readBytes()))
            val thumb = AttachmentStore(context).image(attachment, cached, 512)
            assertTrue(thumb.width <= 512 && thumb.height <= 512)
            thumb.recycle()
            screenshot("inline-received-image")
            compose.onNodeWithTag("preview_$imageId").performScrollTo().performClick()
            waitForTag("full_image_$imageId")
            val bounds = compose.onNodeWithTag("image_viewer").fetchSemanticsNode().boundsInRoot
            val window = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
            assertTrue("Full screen width", bounds.width >= window.width() - 2)
            assertTrue("Full screen height", bounds.height >= window.height() - 2)
            val full = AttachmentStore(context).image(attachment, cached, 2048)
            assertTrue(full.width <= 2048 && full.height <= 2048)
            full.recycle()
            assertTrue(shell("dumpsys activity activities").lineSequence().any { (it.contains("topResumedActivity") || it.contains("mResumedActivity")) && it.contains("MainActivity") })
            val hidden = agent("POST", "/v1/messages", """{"chat_id":"$firstId","text":"Arrived behind the full-screen image"}""")["msg_id"]!!.jsonPrimitive.content
            waitFor(what = "stored message behind full-screen viewer") { agent("GET", "/v1/messages/$hidden")["delivered_at"] != null }
            assertTrue(agent("GET", "/v1/messages/$hidden")["read_at"] == null)
            screenshot("full-screen-received-image")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitForTag("message_input")
            waitFor(what = "read after returning from image") { agent("GET", "/v1/messages/$hidden")["read_at"] != null }
            compose.onNodeWithTag("save_$imageId").performScrollTo().performClick()
            nativeClick("Save", ignoreCase = true)
            waitForTag("message_input")
            val saved = shell("sha256sum /sdcard/Download/${attachment.name}").substringBefore(' ')
            assertEquals(attachment.sha256, saved)

            val stamp = System.currentTimeMillis().toString()
            val bitmap = Bitmap.createBitmap(3000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val filename = "sent-inline-$stamp.png"
            val uri = createDownload(filename, "image/png", bytes)
            try {
                compose.onNodeWithTag("attach_button").performClick()
                pickDocument(filename)
                waitForText(filename)
                val firstDraft = "Claude draft $stamp"
                val secondDraft = "Codex draft $stamp"
                compose.onNodeWithTag("message_input").performTextInput(firstDraft)
                compose.onNodeWithTag("back_chats").performClick()
                waitForTag("sessions_sidebar")
                assertTrue(dev.agentring.app.ui.AppVisibility.visibleChatId == null)
                screenshot("sidebar-narrow-open")
                openChat(secondId)
                waitForText("Reply to Codex")
                assertTrue(compose.onAllNodes(hasTestTag("message_input").and(hasText(firstDraft))).fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("message_input").performTextInput(secondDraft)
                openChat(firstId)
                waitForTag("message_input", firstDraft)
                waitForText(filename)
                compose.onNodeWithTag("back_chats").performClick()
                compose.waitForIdle()
                waitFor(what = "sidebar fully covers the selected conversation") { dev.agentring.app.ui.AppVisibility.visibleChatId == null }
                val obscured = agent("POST", "/v1/messages", """{"chat_id":"$firstId","text":"Arrived behind the sessions sidebar"}""")["msg_id"]!!.jsonPrimitive.content
                waitFor(what = "delivered behind sidebar") { agent("GET", "/v1/messages/$obscured")["delivered_at"] != null }
                assertTrue(agent("GET", "/v1/messages/$obscured")["read_at"] == null)
                compose.onNodeWithTag("close_sidebar").performClick(); compose.waitForIdle()
                waitFor(what = "read after closing sidebar") { agent("GET", "/v1/messages/$obscured")["read_at"] != null }
                shell("wm size 1500x1800"); shell("wm density 320")
                waitForTag("message_input", firstDraft, timeoutMs = 30_000)
                waitForText(filename)
                waitForTag("sessions_sidebar")
                val side = compose.onNodeWithTag("sessions_sidebar").fetchSemanticsNode().boundsInRoot
                val pane = compose.onNodeWithTag("conversation_pane").fetchSemanticsNode().boundsInRoot
                assertTrue("Sidebar beside the conversation", pane.left >= side.right - 2)
                screenshot("sidebar-wide-open")
                compose.onNodeWithTag("close_sidebar").performClick(); compose.waitForIdle()
                assertTrue(!runCatching { compose.onNodeWithTag("sessions_sidebar").isDisplayed() }.getOrDefault(false))
                waitForTag("message_input", firstDraft)
                screenshot("sidebar-wide-collapsed")
                openChat(secondId)
                waitForTag("message_input", secondDraft)
                openChat(firstId)
                waitForTag("message_input", firstDraft)
                waitForText(filename)
                shell("wm size $size"); shell("wm density $density")
                waitForTag("message_input", firstDraft, timeoutMs = 30_000)
                waitForText(filename)
                screenshot("sidebar-restored-narrow")
                compose.onNodeWithTag("send_button").performClick()
                var outgoing: JsonObject? = null
                waitFor(what = "normal picker upload through restored chat draft") {
                    outgoing = agent("GET", "/v1/messages?chat_id=$firstId&from=user")["messages"]!!.jsonArray.map { it.jsonObject }.lastOrNull { it["text"]!!.jsonPrimitive.content == firstDraft }
                    outgoing != null
                }
                val sentAttachment = outgoing!!["attachments"]!!.jsonArray.single().jsonObject
                val sentId = sentAttachment["attachment_id"]!!.jsonPrimitive.content
                assertEquals(sha(bytes), sentAttachment["sha256"]!!.jsonPrimitive.content)
                assertArrayEquals(bytes, agentBytes("/v1/attachments/$sentId/content"))
                waitForTag("preview_$sentId", timeoutMs = 30_000)
                compose.onNodeWithTag("preview_$sentId").performScrollTo().performClick()
                waitForTag("full_image_$sentId")
                screenshot("full-screen-sent-image")
                compose.onNodeWithTag("close_image_viewer").performClick()
                waitForTag("message_input")
                waitForText("Reply to Claude")
            } finally { context.contentResolver.delete(uri, null, null) }

            val corrupt = agentUpload(firstId, "unsupported-$stamp.png", "image/png", "not-an-image".toByteArray())
            val corruptId = corrupt["attachment_id"]!!.jsonPrimitive.content
            agent("POST", "/v1/messages", """{"chat_id":"$firstId","text":"","attachment_ids":["$corruptId"]}""")
            waitForTag("preview_error_$corruptId")
            compose.onNodeWithTag("retry_preview_$corruptId").performScrollTo().performClick()
            waitForTag("preview_error_$corruptId")
            waitForTag("save_$corruptId")
            screenshot("corrupt-image-fallback")
            val pdf = agentUpload(firstId, "preview-regression-$stamp.pdf", "application/pdf", "%PDF-1.4\n%%EOF".toByteArray())
            val pdfId = pdf["attachment_id"]!!.jsonPrimitive.content
            agent("POST", "/v1/messages", """{"chat_id":"$firstId","text":"Document still uses explicit download","attachment_ids":["$pdfId"]}""")
            waitForTag("download_$pdfId")
            assertTrue(AttachmentStore(context).cached(ProtocolJson.decodeFromString(Attachment.serializer(), pdf.toString())) == null)
            compose.onNodeWithTag("download_$pdfId").performScrollTo().performClick()
            waitForTag("open_$pdfId")
            assertArrayEquals("%PDF-1.4\n%%EOF".toByteArray(), AttachmentStore(context).cached(ProtocolJson.decodeFromString(Attachment.serializer(), pdf.toString()))!!.readBytes())
        } finally { shell("wm size $size"); shell("wm density $density") }
    }

    /** Real installed-provider acceptance; run against the isolated development serve daemon. */
    @Test
    fun realProviderSessionsRetainMemory() {
        pairNormally()
        waitFor(timeoutMs = 30_000, what = "actual development daemon online") { Graph.connection.agentOnline.value == true }
        val proofs = org.json.JSONArray()
        try {
        for (callee in listOf("claude", "codex")) {
            compose.onNodeWithTag("tab_home").performClick()
            waitForTag("call_$callee")
            screenshot("real-provider-buttons")
            compose.onNodeWithTag("call_$callee").performScrollTo().performClick()
            var call: JsonObject? = null
            waitFor(timeoutMs = 120_000, what = "$callee actual provider preparation and acceptance") {
                call = agent("GET", "/v1/status")["active_call"] as? JsonObject
                call?.get("state")?.jsonPrimitive?.content == "active" &&
                    call?.get("chat")?.jsonObject?.get("client_session_id")?.jsonPrimitive?.content?.startsWith("pending:") == false
            }
            val original = call!!
            val chat = original["chat"]!!.jsonObject
            val chatId = chat["chat_id"]!!.jsonPrimitive.content
            val sessionId = chat["client_session_id"]!!.jsonPrimitive.content
            val caller = chat["caller"]!!.jsonObject
            val provider = caller["provider"]!!.jsonPrimitive.content
            val model = caller["model"]?.jsonPrimitive?.content?.takeUnless { it == "null" || it.isBlank() } ?: "Unknown model"
            assertEquals(callee, original["callee"]!!.jsonPrimitive.content)
            assertEquals("new", original["session_mode"]!!.jsonPrimitive.content)
            assertTrue(caller["project"] == null)
            waitForTag("call_agent", provider, timeoutMs = 30_000)
            waitForTag("call_model", "Model: $model")
            waitForTag("call_project", "Project: Unknown project")
            waitForTag("call_status", "0:", timeoutMs = 30_000)
            screenshot("real-new-$callee-call")
            compose.onNodeWithTag("call_hang_up").performClick()
            waitFor(what = "new $callee call ended") { Graph.calls.state.value == null }
            waitForTag("tab_messages")
            compose.onNodeWithTag("tab_messages").performClick()
            openChat(chatId)
            val token = "amber-${System.currentTimeMillis()}-$callee"
            fun exchange(text: String, expected: String): JsonObject {
                compose.onNodeWithTag("message_input").performTextInput(text)
                compose.onNodeWithTag("send_button").performClick()
                var sent: JsonObject? = null
                var reply: JsonObject? = null
                waitFor(timeoutMs = 120_000, what = "$callee actual same-session model text response and explicit read acknowledgement") {
                    val messages = agent("GET", "/v1/messages?chat_id=$chatId")["messages"]!!.jsonArray.map { it.jsonObject }
                    sent = messages.lastOrNull { it["from"]!!.jsonPrimitive.content == "user" && it["text"]!!.jsonPrimitive.content == text }
                    val id = sent?.get("msg_id")?.jsonPrimitive?.content
                    reply = messages.lastOrNull { it["from"]!!.jsonPrimitive.content == "agent" && it["text"]!!.jsonPrimitive.content.contains(expected) && it["reply_to"]?.jsonPrimitive?.content == id }
                    reply != null && sent?.get("read_at") != null
                }
                val id = sent!!["msg_id"]!!.jsonPrimitive.content
                compose.onNodeWithTag("message_list").performScrollToNode(hasTestTag("timestamp_$id"))
                waitForTag("timestamp_$id", "✓✓ Read")
                screenshot("real-$callee-text-" + if (proofs.length() == 0) "claude" else "codex")
                return reply!!
            }
            val remembered = exchange("Remember this verification token: $token. Reply with exactly Stored $token.", "Stored $token")
            compose.onNodeWithTag("call_session").performClick()
            var existing: JsonObject? = null
            waitFor(timeoutMs = 120_000, what = "$callee existing session call accepted") {
                existing = agent("GET", "/v1/status")["active_call"] as? JsonObject
                existing?.get("state")?.jsonPrimitive?.content == "active"
            }
            assertEquals("existing", existing!!["session_mode"]!!.jsonPrimitive.content)
            assertEquals(chatId, existing!!["chat_id"]!!.jsonPrimitive.content)
            assertEquals(sessionId, existing!!["chat"]!!.jsonObject["client_session_id"]!!.jsonPrimitive.content)
            waitForTag("call_agent", provider)
            waitForTag("call_model", "Model: $model")
            waitForTag("call_status", "0:", timeoutMs = 30_000)
            screenshot("real-existing-$callee-call")
            compose.onNodeWithTag("call_hang_up").performClick()
            waitFor(what = "existing $callee call ended") { Graph.calls.state.value == null }
            waitForTag("message_input")
            val recalled = exchange("What verification token did I ask you to remember? Reply with only the token.", token)
            val proof = org.json.JSONObject().put("callee", callee).put("chat_id", chatId).put("client_session_id", sessionId)
                .put("caller", org.json.JSONObject(caller.toString())).put("new_call_id", original["call_id"]!!.jsonPrimitive.content)
                .put("existing_call_id", existing!!["call_id"]!!.jsonPrimitive.content).put("stored_reply_id", remembered["msg_id"]!!.jsonPrimitive.content)
                .put("recalled_reply_id", recalled["msg_id"]!!.jsonPrimitive.content).put("memory_token", token)
            proofs.put(proof)
            println("REAL_PROVIDER_PROOF=" + proof.toString())
            compose.onNodeWithTag("back_chats").performClick()
        }
        assertEquals(2, proofs.length())
        assertTrue(proofs.getJSONObject(0).getString("chat_id") != proofs.getJSONObject(1).getString("chat_id"))
        } finally {
            (agent("GET", "/v1/status")["active_call"] as? JsonObject)?.let { active -> agent("POST", "/v1/calls/${active["call_id"]!!.jsonPrimitive.content}/end", "{}") }
        }
    }

    /** Normal phone buttons -> REST call selection -> a real agent socket; no provider fallback. */
    private fun verifyCallSelection() {
        val socket = dev.agentring.core.RelaySocket(dev.agentring.core.RelayApi(relayUrl, apiKey), { Graph.messages.lastSeq }, object : dev.agentring.core.RelaySocket.Listener {
            override fun onEvent(event: dev.agentring.core.Event) = Unit
            override fun onState(state: dev.agentring.core.RelaySocket.State) = Unit
            override fun onUnauthorized() = Unit
        })
        socket.start()
        try {
            waitFor(what = "real agent socket online") { Graph.connection.agentOnline.value == true }
            val newChats = mutableSetOf<String>()
            for (callee in listOf("claude", "codex", "codex")) {
                waitForTag("tab_home")
                compose.onNodeWithTag("tab_home").performClick()
                waitForTag("call_$callee")
                screenshot("call-provider-buttons")
                compose.onNodeWithTag("call_$callee").performScrollTo().performClick()
                var created: JsonObject? = null
                waitFor(what = "normal $callee button created a call") {
                    created = agent("GET", "/v1/status")["active_call"] as? JsonObject
                    created?.get("direction")?.jsonPrimitive?.content == "user_to_agent"
                }
                val call = created!!
                val id = call["call_id"]!!.jsonPrimitive.content
                val chatId = call["chat_id"]!!.jsonPrimitive.content
                assertEquals(callee, call["callee"]!!.jsonPrimitive.content)
                assertEquals("new", call["session_mode"]!!.jsonPrimitive.content)
                assertTrue(call["client_call_id"]!!.jsonPrimitive.content.isNotBlank())
                assertTrue("Every main button call gets a distinct new chat", newChats.add(chatId))
                assertTrue(call["chat"]!!.jsonObject["client_session_id"]!!.jsonPrimitive.content.startsWith("pending:"))
                waitForTag("call_agent", "Unknown provider")
                waitForTag("call_status", context.getString(R.string.call_calling, if (callee == "codex") "Codex" else "Claude"))
                waitForTag("call_model", "Model: Unknown model")
                waitForTag("call_project", "Project: Unknown project")
                screenshot("new-$callee-call")
                agent("POST", "/v1/calls/$id/decline", """{"reason":"callee_unavailable"}""")
                waitForTag("call_status", context.getString(R.string.call_callee_unavailable))
                assertEquals("callee_unavailable", agent("GET", "/v1/calls/$id")["end_reason"]!!.jsonPrimitive.content)
                waitFor(what = "unavailable call closed without fallback") { Graph.calls.state.value == null }
            }
            val session = "call-routing-" + System.currentTimeMillis()
            val existing = agent("POST", "/v1/chats", """{"client_session_id":"$session","callee":"codex","caller":{"provider":"OpenAI","model":"emulator-call-fixture","project":"Existing call routing fixture"}}""")
            val chatId = existing["chat_id"]!!.jsonPrimitive.content
            waitForTag("tab_messages")
            compose.onNodeWithTag("tab_messages").performClick()
            openChat(chatId)
            waitForTag("message_input")
            waitForText("Reply to Codex")
            waitForTag("call_session")
            screenshot("existing-session-call-button")
            compose.onNodeWithTag("call_session").performClick()
            var created: JsonObject? = null
            waitFor(what = "existing chat Call button") {
                created = agent("GET", "/v1/status")["active_call"] as? JsonObject
                created != null
            }
            val call = created!!
            val id = call["call_id"]!!.jsonPrimitive.content
            assertEquals("existing", call["session_mode"]!!.jsonPrimitive.content)
            assertEquals("codex", call["callee"]!!.jsonPrimitive.content)
            assertEquals(chatId, call["chat_id"]!!.jsonPrimitive.content)
            assertEquals(session, call["chat"]!!.jsonObject["client_session_id"]!!.jsonPrimitive.content)
            waitForTag("call_agent", "OpenAI")
            waitForTag("call_model", "Model: emulator-call-fixture")
            waitForTag("call_project", "Project: Existing call routing fixture")
            screenshot("existing-codex-call")
            agent("POST", "/v1/calls/$id/decline", """{"reason":"callee_unavailable"}""")
            waitForTag("call_status", context.getString(R.string.call_callee_unavailable))
            waitFor(what = "existing unavailable session closed without replacement") { Graph.calls.state.value == null }
            assertEquals(session, agent("GET", "/v1/chats/$chatId")["client_session_id"]!!.jsonPrimitive.content)
        } finally {
            (agent("GET", "/v1/status")["active_call"] as? JsonObject)?.let { call -> agent("POST", "/v1/calls/${call["call_id"]!!.jsonPrimitive.content}/end", "{}") }
            socket.stop()
        }
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

    private fun nativeRoots(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        if (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
        }
        return automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
    }

    private fun nativeNode(text: String, substring: Boolean = false, ignoreCase: Boolean = false): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (listOf(node.text?.toString(), node.contentDescription?.toString()).any { value -> value != null && if (substring) value.contains(text, ignoreCase) else value.equals(text, ignoreCase) }) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return nativeRoots().firstNotNullOfOrNull { walk(it) }
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
        waitFor(what = "clickable native $text") { target = nativeRoots().firstNotNullOfOrNull { clickable(it) }; target != null }
        assertTrue("Clickable native $text", target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
