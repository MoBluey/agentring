package dev.agentring.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class AttachmentTest {
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun metadata(hashValue: String = hash) = Attachment("att_1", "report.pdf", "application/pdf", bytes.size.toLong(), hashValue, 1)

    @Test fun `upload and download stream identical bytes and authenticate`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(ProtocolJson.encodeToString(Attachment.serializer(), metadata())))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val api = RelayApi(server.url("/").toString(), "jwt")
            var progress = 0L
            val attachment = api.uploadAttachment("report.pdf", "application/pdf", bytes.size.toLong(), { bytes.inputStream() }) { progress = it }
            assertEquals(bytes.size.toLong(), progress)
            val request = server.takeRequest()
            assertEquals("Bearer jwt", request.getHeader("Authorization"))
            assertEquals("report.pdf", request.requestUrl!!.queryParameter("name"))
            assertArrayEquals(bytes, request.body.readByteArray())
            val result = ByteArrayOutputStream()
            api.downloadAttachment(attachment, result)
            assertArrayEquals(bytes, result.toByteArray())
            assertEquals("/v1/attachments/att_1/content", server.takeRequest().path)
        }
    }

    @Test fun `corruption and truncation never produce a successful download`() {
        MockWebServer().use { server ->
            val api = RelayApi(server.url("/").toString(), "jwt")
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            assertThrows(IllegalArgumentException::class.java) { api.downloadAttachment(metadata("0".repeat(64)), ByteArrayOutputStream()) }
            server.enqueue(MockResponse().setBody("truncated"))
            assertThrows(IllegalArgumentException::class.java) { api.downloadAttachment(metadata(), ByteArrayOutputStream()) }
        }
    }

    @Test fun `cancel and size limit reject before opening content`() {
        val api = RelayApi("http://localhost:1", "jwt")
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            api.uploadAttachment("big", "application/octet-stream", AttachmentTransfer.MAX_BYTES + 1, { opened = true; bytes.inputStream() })
        }
        assertFalse(opened)
        val transfer = AttachmentTransfer().apply { cancel() }
        assertThrows(java.io.InterruptedIOException::class.java) {
            api.uploadAttachment("cancel", "application/octet-stream", bytes.size.toLong(), { opened = true; bytes.inputStream() }, transfer)
        }
        assertFalse(opened)
    }

    @Test fun `attachment message permits empty text and metadata survives event`() {
        MockWebServer().use { server ->
            val message = Message("msg_1", "user", "", createdAt = 1, attachments = listOf(metadata()))
            server.enqueue(MockResponse().setBody(ProtocolJson.encodeToString(Message.serializer(), message)))
            val sent = RelayApi(server.url("/").toString(), "jwt").postMessage("", attachmentIds = listOf("att_1"))
            assertEquals(hash, sent.attachments.single().sha256)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"attachment_ids\":[\"att_1\"]"))
            val event = Event.parse("""{"type":"msg.new","id":"evt_1","ts":1,"msg_id":"msg_1","from":"agent","text":"","attachments":[${ProtocolJson.encodeToString(Attachment.serializer(), metadata())}]}""") as Event.MsgNew
            assertEquals(metadata(), event.toMessage().attachments.single())
        }
    }
    @Test fun `failed upload can be retried with fresh stream`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"unavailable","message":"Try again"}}"""))
            server.enqueue(MockResponse().setBody(ProtocolJson.encodeToString(Attachment.serializer(), metadata())))
            val api = RelayApi(server.url("/").toString(), "jwt")
            val failure = assertThrows(RelayException::class.java) { api.uploadAttachment("report.pdf", "application/pdf", bytes.size.toLong(), { bytes.inputStream() }) }
            assertEquals(503, failure.status)
            assertEquals("Try again", failure.message)
            assertEquals(hash, api.uploadAttachment("report.pdf", "application/pdf", bytes.size.toLong(), { bytes.inputStream() }).sha256)
        }
    }

    @Test fun `caller context is honest when model was not supplied`() {
        assertTrue(CallerContext(provider = "OpenAI", project = "Orchard").label.contains("Unknown model"))
        val caller = CallerContext("OpenAI", "verified-model", "Orchard")
        assertEquals("OpenAI", caller.title("Legacy agent"))
        assertEquals("Model: verified-model\nProject: Orchard", caller.label)
        assertEquals("Legacy agent", CallerContext().title("Legacy agent"))
        assertEquals("Unknown provider", CallerContext().title(""))
        assertEquals("Model: Unknown model", CallerContext(model = " ").modelLine)
        assertEquals("Project: Unknown project", CallerContext(project = " ").projectLine)
        val chat = Chat("chat_1", "session_1", "Session", caller = CallerContext("Anthropic", "claude-model", "Orchard"), createdAt = 1, updatedAt = 2)
        val event = Event.parse("""{"type":"chat.updated","id":"evt_2","ts":2,"chat":${ProtocolJson.encodeToString(Chat.serializer(), chat)}}""") as Event.ChatUpdated
        assertEquals("claude-model", event.chat.caller.model)
    }

    @Test fun `retry key is retained only for identical payload and resets after success`() {
        val attempts = MessageSendAttempt()
        val original = attempts.forPayload("Original", "file-A")
        assertTrue(original.payloadChanged)
        val retry = attempts.forPayload("Original", "file-A")
        assertEquals(original.clientMsgId, retry.clientMsgId)
        assertFalse(retry.payloadChanged)
        val edited = attempts.forPayload("Edited", "file-A")
        assertNotEquals(original.clientMsgId, edited.clientMsgId)
        assertTrue(edited.payloadChanged)
        val replaced = attempts.forPayload("Edited", "file-B")
        assertNotEquals(edited.clientMsgId, replaced.clientMsgId)
        assertTrue(replaced.payloadChanged)
        attempts.reset()
        assertNotEquals(replaced.clientMsgId, attempts.forPayload("Edited", "file-B").clientMsgId)
    }

}
