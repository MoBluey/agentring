package dev.agentring.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class RelayException(val status: Int, val code: String, message: String) : IOException(message) {
    /** Credentials are no longer valid (device revoked or relay reset): the app must re-pair. */
    val isUnauthorized: Boolean get() = status == 401
}

/** Blocking client for the relay REST API, as a device. Call from a background thread. */
class RelayApi(
    val baseUrl: String,
    private val deviceJwt: String?,
    private val http: OkHttpClient = defaultClient,
    private val userAgent: String = "agentring-android",
) {
    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        private val JSON = "application/json".toMediaType()
        private val EMPTY = "".toRequestBody(null)
    }

    @Serializable
    private data class FcmBody(@SerialName("fcm_token") val fcmToken: String)

    @Serializable
    private data class PostMessageBody(
        val text: String,
        val answer: QuestionAnswer? = null,
        @SerialName("reply_to") val replyTo: String? = null,
        @SerialName("client_msg_id") val clientMsgId: String? = null,
        @SerialName("attachment_ids") val attachmentIds: List<String>? = null,
        @SerialName("chat_id") val chatId: String? = null,
    )

    @Serializable
    private data class CreateCallBody(
        val reason: String? = null, @SerialName("chat_id") val chatId: String? = null,
        val callee: String? = null, @SerialName("session_mode") val sessionMode: String? = null,
        @SerialName("client_call_id") val clientCallId: String? = null,
    )

    fun pair(req: PairRequest): PairResponse =
        call("POST", "/v1/pair", body(PairRequest.serializer(), req), PairResponse.serializer(), auth = false)

    fun updateFcmToken(token: String) {
        call<Unit>("POST", "/v1/device/fcm", body(FcmBody.serializer(), FcmBody(token)), null)
    }

    fun status(): Status = call("GET", "/v1/status", null, Status.serializer())

    fun listMessages(after: String? = null, limit: Int = 200, chatId: String? = null): MessagePage {
        val q = buildString {
            append("/v1/messages?limit=").append(limit)
            if (after != null) append("&after=").append(after)
            if (chatId != null) append("&chat_id=").append(chatId)
        }
        return call("GET", q, null, MessagePage.serializer())
    }

    fun listChats(): ChatPage = call("GET", "/v1/chats", null, ChatPage.serializer())

    fun getChat(chatId: String): Chat = call("GET", "/v1/chats/$chatId", null, Chat.serializer())

    fun getMessage(msgId: String): Message = call("GET", "/v1/messages/$msgId", null, Message.serializer())

    fun postMessage(text: String, replyTo: String? = null, clientMsgId: String? = null, attachmentIds: List<String> = emptyList(), chatId: String? = null, answer: QuestionAnswer? = null): Message =
        call(
            "POST", "/v1/messages",
            body(PostMessageBody.serializer(), PostMessageBody(text, answer, replyTo, clientMsgId, attachmentIds.takeIf { it.isNotEmpty() }, chatId)),
            Message.serializer(),
        )

    fun markDelivered(msgId: String) {
        call<Unit>("POST", "/v1/messages/$msgId/delivered", "".toRequestBody(null), null)
    }

    fun markRead(msgId: String) {
        call<Unit>("POST", "/v1/messages/$msgId/read", "".toRequestBody(null), null)
    }

    /**
     * Calls the agent (M4, direction `user_to_agent`). The relay returns this
     * phone's LiveKit join info right away; the call rings until the agent accepts.
     * Fails with 503 `agent_offline` / `calls_unavailable` or 409 `busy`.
     */
    fun createCall(reason: String? = null, chatId: String? = null, callee: String? = null, sessionMode: String? = null, clientCallId: String? = null): CallWithJoin =
        call("POST", "/v1/calls", body(CreateCallBody.serializer(), CreateCallBody(reason, chatId, callee, sessionMode, clientCallId)), CallWithJoin.serializer())

    fun getCall(callId: String): Call = call("GET", "/v1/calls/$callId", null, Call.serializer())

    /** Tells the relay this device is showing the incoming call (measures ring latency). */
    fun reportRinging(callId: String) {
        call<Unit>("POST", "/v1/calls/$callId/ringing", EMPTY, null)
    }

    /** Answers a ringing call; after the ring window it fails with 409 `invalid_state` (use [joinCall]). */
    fun acceptCall(callId: String): CallWithJoin = call("POST", "/v1/calls/$callId/accept", EMPTY, CallWithJoin.serializer())

    /**
     * Joins an agent call that is ringing or active, also after this phone's ring
     * ended, it declined or it hung up. 409 `call_ended` once the call is over.
     */
    fun joinCall(callId: String): CallWithJoin = call("POST", "/v1/calls/$callId/join", EMPTY, CallWithJoin.serializer())

    fun declineCall(callId: String): Call = call("POST", "/v1/calls/$callId/decline", EMPTY, Call.serializer())

    fun endCall(callId: String): Call = call("POST", "/v1/calls/$callId/end", EMPTY, Call.serializer())

    fun webSocketRequest(afterSeq: Long): Request {
        val url = "${baseUrl.trimEnd('/')}/v1/ws".toHttpUrl().newBuilder()
            .addQueryParameter("after_seq", afterSeq.toString())
            .build()
        return Request.Builder().url(url)
            .header("Authorization", "Bearer ${requireNotNull(deviceJwt)}")
            .header("User-Agent", userAgent)
            .build()
    }

    fun uploadAttachment(
        name: String, mediaType: String, size: Long, input: () -> java.io.InputStream,
        transfer: AttachmentTransfer = AttachmentTransfer(), chatId: String = "chat_default", progress: (Long) -> Unit = {},
    ): Attachment {
        require(size in 0..AttachmentTransfer.MAX_BYTES) { "Files must be 100 MiB or smaller" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var written = 0L
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) {
                input().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        transfer.check()
                        val n = source.read(buffer)
                        if (n < 0) break
                        written += n
                        require(written <= size && written <= AttachmentTransfer.MAX_BYTES) { "File changed or exceeds 100 MiB" }
                        digest.update(buffer, 0, n)
                        sink.write(buffer, 0, n)
                        progress(written)
                    }
                }
                require(written == size) { "File size changed; choose it again" }
            }
        }
        val url = (baseUrl.trimEnd('/') + "/v1/attachments").toHttpUrl().newBuilder()
            .addQueryParameter("name", name).addQueryParameter("media_type", mediaType).addQueryParameter("chat_id", chatId).build()
        return attachmentCall(Request.Builder().url(url).post(body).build(), transfer) { response ->
            val result = ProtocolJson.decodeFromString(Attachment.serializer(), response.body!!.string())
            require(result.sizeBytes == written && result.sha256.equals(digest.digest().hex(), true)) { "Upload checksum mismatch" }
            result
        }
    }

    fun downloadAttachment(
        attachment: Attachment, output: java.io.OutputStream,
        transfer: AttachmentTransfer = AttachmentTransfer(), progress: (Long) -> Unit = {},
    ) {
        require(attachment.sizeBytes in 0..AttachmentTransfer.MAX_BYTES) { "Files must be 100 MiB or smaller" }
        val url = (baseUrl.trimEnd('/') + "/v1/attachments").toHttpUrl().newBuilder()
            .addPathSegment(attachment.attachmentId).addPathSegment("content").build()
        attachmentCall(Request.Builder().url(url).build(), transfer) { response ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var count = 0L
            response.body!!.byteStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    transfer.check()
                    val n = source.read(buffer)
                    if (n < 0) break
                    count += n
                    require(count <= attachment.sizeBytes && count <= AttachmentTransfer.MAX_BYTES) { "Download exceeds expected size" }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                    progress(count)
                }
            }
            require(count == attachment.sizeBytes && digest.digest().hex().equals(attachment.sha256, true)) { "Download checksum mismatch" }
        }
    }

    private fun <T> attachmentCall(request: Request, transfer: AttachmentTransfer, consume: (okhttp3.Response) -> T): T {
        val authenticated = request.newBuilder().header("Authorization", "Bearer ${requireNotNull(deviceJwt)}")
            .header("User-Agent", userAgent).build()
        val client = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
        val call = client.newCall(authenticated)
        transfer.attach(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val error = runCatching { ProtocolJson.decodeFromString(ApiErrorBody.serializer(), response.body!!.string()).error }.getOrNull()
                    throw RelayException(response.code, error?.code ?: "http_${response.code}", error?.message ?: "HTTP ${response.code}")
                }
                return consume(response)
            }
        } catch (e: IOException) { transfer.check(); throw e }
        finally { transfer.detach(call) }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun <T> body(serializer: KSerializer<T>, value: T): RequestBody =
        ProtocolJson.encodeToString(serializer, value).toRequestBody(JSON)

    private fun <T> call(method: String, path: String, body: RequestBody?, out: KSerializer<T>?, auth: Boolean = true): T {
        val rb = Request.Builder().url(baseUrl.trimEnd('/') + path).method(method, body).header("User-Agent", userAgent)
        if (auth) rb.header("Authorization", "Bearer ${requireNotNull(deviceJwt) { "not paired" }}")
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val err = runCatching { ProtocolJson.decodeFromString(ApiErrorBody.serializer(), text).error }.getOrNull()
                throw RelayException(res.code, err?.code ?: "http_${res.code}", err?.message ?: "HTTP ${res.code}")
            }
            @Suppress("UNCHECKED_CAST")
            return if (out == null) Unit as T else ProtocolJson.decodeFromString(out, text)
        }
    }
}
