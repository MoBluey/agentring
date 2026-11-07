package dev.agentring.core

import org.junit.Assert.*
import org.junit.Test

class MessageSendAttemptRestorationTest {
    @Test fun `restored draft retries its original key and edited draft gets a new key`() {
        val attempt = MessageSendAttempt()
        val key = attempt.forPayload("draft", "private-file").clientMsgId
        val restored = MessageSendAttempt.restore(attempt.snapshot())
        assertEquals(key, restored.forPayload("draft", "private-file").clientMsgId)
        assertNotEquals(key, restored.forPayload("edited draft", "private-file").clientMsgId)
    }
}
