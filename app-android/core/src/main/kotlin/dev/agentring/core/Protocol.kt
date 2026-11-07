package dev.agentring.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin mirror of protocol/relay.openapi.yaml. Kept honest by
 * ProtocolFixturesTest, which round-trips every file in protocol/examples.
 */
val ProtocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class QrPayload(
    val v: Int,
    @SerialName("relay_url") val relayUrl: String,
    @SerialName("pairing_token") val pairingToken: String,
) {
    companion object {
        /** Parses the QR / pasted pairing code. Throws [IllegalArgumentException] with a user-facing reason. */
        fun parse(raw: String): QrPayload {
            val p = try {
                ProtocolJson.decodeFromString<QrPayload>(raw.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("This is not an agentring pairing code")
            }
            require(p.v == 1) { "Unsupported pairing code version ${p.v}; update the app" }
            require(p.pairingToken.startsWith("pt_")) { "Invalid pairing token" }
            RelayUrlPolicy.check(p.relayUrl)
            return p
        }
    }
}

@Serializable
data class PairRequest(
    @SerialName("pairing_token") val pairingToken: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("fcm_token") val fcmToken: String? = null,
)

@Serializable
data class FcmConfig(
    @SerialName("project_id") val projectId: String,
    @SerialName("application_id") val applicationId: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("sender_id") val senderId: String,
)

@Serializable
data class PushConfig(val provider: String, val fcm: FcmConfig)

@Serializable
data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_jwt") val deviceJwt: String,
    @SerialName("push_secret") val pushSecret: String,
    @SerialName("relay_id") val relayId: String,
    @SerialName("agent_name") val agentName: String,
    @SerialName("push_config") val pushConfig: PushConfig? = null,
)

@Serializable
data class CallerContext(val provider: String? = null, val model: String? = null, val project: String? = null) {
    fun title(legacyName: String): String = provider?.takeIf { it.isNotBlank() }
        ?: legacyName.takeIf { it.isNotBlank() } ?: "Unknown provider"
    val modelLine: String get() = "Model: ${model?.takeIf { it.isNotBlank() } ?: "Unknown model"}"
    val projectLine: String get() = "Project: ${project?.takeIf { it.isNotBlank() } ?: "Unknown project"}"
    val label: String get() = "$modelLine\n$projectLine"
}

@Serializable
data class Chat(
    @SerialName("chat_id") val chatId: String,
    @SerialName("client_session_id") val clientSessionId: String,
    val title: String,
    val description: String = "",
    val caller: CallerContext = CallerContext(),
    val callee: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("archived_at") val archivedAt: Long? = null,
) {
    /** A pending new call has no verified caller yet; it must not inherit the legacy relay name. */
    fun displayTitle(legacyName: String): String = caller.title(if (clientSessionId.startsWith("pending:")) "" else legacyName)

    /** Short composer wording only; call routing still uses the explicit relay session target. */
    fun composerTarget(): String = when (callee) {
        "claude" -> "Claude"
        "codex" -> "Codex"
        else -> when (val provider = caller.provider?.trim()?.takeIf { it.isNotEmpty() }) {
            null -> "this session"
            else -> when (provider.lowercase().replace(Regex("\\s*/\\s*"), "/")) {
                "claude", "claude code", "anthropic" -> "Claude"
                "codex", "openai codex", "openai/codex" -> "Codex"
                else -> provider
            }
        }
    }

}

@Serializable
data class ChatPage(val chats: List<Chat>)

@Serializable
data class ChatSearchMatch(
    val kind: String,
    val snippet: String,
    @SerialName("msg_id") val msgId: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
data class ChatSearchResult(val chat: Chat, val match: ChatSearchMatch)

@Serializable
data class ChatSearchPage(val results: List<ChatSearchResult>)

@Serializable
data class Attachment(
    @SerialName("attachment_id") val attachmentId: String,
    val name: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("chat_id") val chatId: String = "chat_default",
)

@Serializable
data class QuestionOption(val id: String, val label: String)

@Serializable
data class Question(
    val options: List<QuestionOption>,
    @SerialName("allow_free_text") val allowFreeText: Boolean = false,
    @SerialName("answered_by_msg_id") val answeredByMsgId: String? = null,
    @SerialName("answered_at") val answeredAt: Long? = null,
)

@Serializable
data class QuestionAnswer(
    @SerialName("question_id") val questionId: String,
    @SerialName("option_id") val optionId: String? = null,
    @SerialName("free_text") val freeText: String? = null,
)

@Serializable
data class Message(
    @SerialName("msg_id") val msgId: String,
    val from: String,
    val text: String,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("read_at") val readAt: Long? = null,
    val attachments: List<Attachment> = emptyList(),
    val question: Question? = null,
    val answer: QuestionAnswer? = null,
    @SerialName("delivered_at") val deliveredAt: Long? = null,
    @SerialName("chat_id") val chatId: String = "chat_default",
) {
    val fromAgent: Boolean get() = from == FROM_AGENT

    companion object {
        const val FROM_AGENT = "agent"
        const val FROM_USER = "user"
    }
}

@Serializable
data class MessagePage(val messages: List<Message>, @SerialName("has_more") val hasMore: Boolean)

@Serializable
data class Status(
    @SerialName("relay_id") val relayId: String,
    val version: String,
    @SerialName("agent_name") val agentName: String,
    @SerialName("agent_online") val agentOnline: Boolean,
    @SerialName("push_provider") val pushProvider: String,
    /** The call currently ringing or active, if any. */
    @SerialName("active_call") val activeCall: Call? = null,
)

@Serializable
data class Call(
    @SerialName("call_id") val callId: String,
    val direction: String,
    val state: String,
    val reason: String? = null,
    val priority: String,
    @SerialName("agent_name") val agentName: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("answered_at") val answeredAt: Long? = null,
    @SerialName("ended_at") val endedAt: Long? = null,
    @SerialName("ended_by") val endedBy: String? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("chat_id") val chatId: String = "chat_default",
    val chat: Chat? = null,
) {
    val isRinging: Boolean get() = state == "ringing"
    val isActive: Boolean get() = state == "active"

    /** A group call from the agent that is in progress: a phone not on it may join. */
    val isJoinable: Boolean get() = isActive && direction == "agent_to_user"
}

@Serializable
data class JoinInfo(
    @SerialName("livekit_url") val livekitUrl: String,
    @SerialName("livekit_token") val livekitToken: String,
    val room: String,
    val identity: String,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
data class CallWithJoin(val call: Call, val join: JoinInfo)

@Serializable
data class ApiErrorBody(val error: Detail) {
    @Serializable
    data class Detail(val code: String, val message: String)
}

/** Relay events. Unknown types decode to [Event.Unknown] so old apps keep working. */
sealed interface Event {
    val type: String
    val id: String
    val ts: Long
    val seq: Long?

    @Serializable
    data class CallInvite(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
        val direction: String,
        @SerialName("agent_name") val agentName: String,
        val reason: String,
        val priority: String,
        @SerialName("expires_at") val expiresAt: Long,
        @SerialName("chat_id") val chatId: String = "chat_default",
        val chat: Chat? = null,
    ) : Event

    @Serializable
    data class CallRinging(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
        @SerialName("device_id") val deviceId: String,
    ) : Event

    /** call.accept and call.decline share a shape. */
    @Serializable
    data class CallAnswer(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
        val by: String,
        val reason: String? = null,
        val chat: Chat? = null,
        @SerialName("chat_id") val chatId: String? = null,
        @SerialName("device_id") val deviceId: String? = null,
    ) : Event

    @Serializable
    data class CallEnd(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
        val by: String,
        val reason: String,
        @SerialName("duration_ms") val durationMs: Long? = null,
    ) : Event

    @Serializable
    data class CallMissed(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("call_id") val callId: String,
    ) : Event

    @Serializable
    data class MsgNew(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("msg_id") val msgId: String,
        val from: String,
        val text: String,
        @SerialName("reply_to") val replyTo: String? = null,
        val truncated: Boolean? = null,
        val question: Question? = null,
        val answer: QuestionAnswer? = null,
        val attachments: List<Attachment> = emptyList(),
        @SerialName("chat_id") val chatId: String = "chat_default",
    ) : Event {
        fun toMessage() = Message(msgId = msgId, from = from, text = text, replyTo = replyTo, createdAt = ts, attachments = attachments, chatId = chatId, question = question, answer = answer)
    }

    @Serializable
    data class ChatUpdated(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        val chat: Chat,
    ) : Event

    @Serializable
    data class MsgRead(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("msg_id") val msgId: String,
        val by: String,
    ) : Event

    @Serializable
    data class MsgDelivered(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("msg_id") val msgId: String,
        val by: String,
    ) : Event

    @Serializable
    data class QuestionAnswered(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("chat_id") val chatId: String,
        @SerialName("question_id") val questionId: String,
        @SerialName("answer_msg_id") val answerMsgId: String,
        @SerialName("answered_at") val answeredAt: Long,
    ) : Event

    @Serializable
    data class AgentPresence(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        val online: Boolean,
    ) : Event

    @Serializable
    data class WsHello(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long? = null,
        @SerialName("relay_id") val relayId: String,
        @SerialName("last_seq") val lastSeq: Long,
        @SerialName("server_ts") val serverTs: Long,
    ) : Event

    data class Unknown(
        override val type: String, override val id: String, override val ts: Long, override val seq: Long?,
        val raw: JsonObject,
    ) : Event

    companion object {
        fun parse(json: String): Event = parse(ProtocolJson.parseToJsonElement(json).jsonObject)

        fun parse(obj: JsonObject): Event {
            val type = obj["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("event without type")
            val j = ProtocolJson
            return when (type) {
                "call.invite" -> j.decodeFromJsonElement(CallInvite.serializer(), obj)
                "call.ringing" -> j.decodeFromJsonElement(CallRinging.serializer(), obj)
                "call.accept", "call.decline" -> j.decodeFromJsonElement(CallAnswer.serializer(), obj)
                "call.end" -> j.decodeFromJsonElement(CallEnd.serializer(), obj)
                "call.missed" -> j.decodeFromJsonElement(CallMissed.serializer(), obj)
                "msg.new" -> j.decodeFromJsonElement(MsgNew.serializer(), obj)
                "chat.updated" -> j.decodeFromJsonElement(ChatUpdated.serializer(), obj)
                "msg.delivered" -> j.decodeFromJsonElement(MsgDelivered.serializer(), obj)
                "question.answered" -> j.decodeFromJsonElement(QuestionAnswered.serializer(), obj)
                "msg.read" -> j.decodeFromJsonElement(MsgRead.serializer(), obj)
                "agent.presence" -> j.decodeFromJsonElement(AgentPresence.serializer(), obj)
                "ws.hello" -> j.decodeFromJsonElement(WsHello.serializer(), obj)
                else -> Unknown(
                    type = type,
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    ts = obj["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    seq = obj["seq"]?.jsonPrimitive?.content?.toLongOrNull(),
                    raw = obj,
                )
            }
        }
    }
}
