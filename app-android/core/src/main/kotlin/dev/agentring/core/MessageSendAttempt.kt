package dev.agentring.core

import java.util.UUID

/** A lost response may be retried, but edited content is a separate message. */
class MessageSendAttempt {
    data class Attempt(val clientMsgId: String, val payloadChanged: Boolean)
    private var payload: Pair<String, String?>? = null
    private var id: String? = null

    fun forPayload(text: String, attachmentKey: String?): Attempt {
        val next = text to attachmentKey
        val changed = next != payload || id == null
        if (changed) { payload = next; id = UUID.randomUUID().toString() }
        return Attempt(id!!, changed)
    }

    fun reset() { payload = null; id = null }

    fun snapshot(): List<String?> = listOf(payload?.first, payload?.second, id)

    companion object {
        fun restore(snapshot: List<String?>): MessageSendAttempt = MessageSendAttempt().apply {
            require(snapshot.size == 3)
            id = snapshot[2]
            payload = if (id == null) null else snapshot[0].orEmpty() to snapshot[1]
        }
    }
}
