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
}
