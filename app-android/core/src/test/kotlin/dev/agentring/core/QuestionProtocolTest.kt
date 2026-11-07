package dev.agentring.core

import org.junit.Assert.*
import org.junit.Test

class QuestionProtocolTest {
    @Test fun `live question retains choices and completed history retains answer and receipts`() {
        val event = Event.parse("""{"type":"msg.new","id":"evt_q","ts":10,"msg_id":"msg_q","from":"agent","text":"**Deploy?**","chat_id":"chat_one","question":{"options":[{"id":"ship","label":"Ship"},{"id":"hold","label":"Hold"}],"allow_free_text":true}}""") as Event.MsgNew
        assertEquals(listOf("ship", "hold"), event.toMessage().question!!.options.map { it.id })
        assertTrue(event.toMessage().question!!.allowFreeText)
        val message = ProtocolJson.decodeFromString<Message>("""{"msg_id":"msg_a","from":"user","text":"Hold","created_at":20,"chat_id":"chat_one","reply_to":"msg_q","answer":{"question_id":"msg_q","option_id":"hold"},"delivered_at":30,"read_at":40}""")
        assertEquals("hold", message.answer!!.optionId)
        assertEquals(30L, message.deliveredAt)
        assertEquals(40L, message.readAt)
        assertEquals(message, ProtocolJson.decodeFromString<Message>(ProtocolJson.encodeToString(Message.serializer(), message)))
    }

    @Test fun `recipient receipts and question completion are known reconnect events`() {
        val delivered = Event.parse("""{"type":"msg.delivered","id":"evt_d","ts":30,"seq":4,"msg_id":"msg_a","by":"agent"}""") as Event.MsgDelivered
        assertEquals("agent", delivered.by)
        val answered = Event.parse("""{"type":"question.answered","id":"evt_a","ts":20,"chat_id":"chat_one","question_id":"msg_q","answer_msg_id":"msg_a","answered_at":20}""") as Event.QuestionAnswered
        assertEquals("chat_one", answered.chatId)
        assertEquals("msg_a", answered.answerMsgId)
        assertEquals(20L, answered.answeredAt)
    }

    @Test fun `legacy text messages never manufacture delivery or read`() {
        val message = ProtocolJson.decodeFromString<Message>("""{"msg_id":"msg_old","from":"agent","text":"Legacy","created_at":1}""")
        assertNull(message.deliveredAt)
        assertNull(message.readAt)
        assertNull(message.question)
        assertNull(message.answer)
    }
    @Test fun `pending new call never borrows a legacy caller name`() {
        val pending = Chat("chat_pending", "pending:call_1", "New Codex call", callee = "codex", createdAt = 1, updatedAt = 1)
        assertEquals("Unknown provider", pending.displayTitle("Claude Code"))
        assertEquals("Claude Code", pending.copy(clientSessionId = "legacy-session").displayTitle("Claude Code"))
        assertEquals("OpenAI", pending.copy(caller = CallerContext(provider = "OpenAI")).displayTitle("Claude Code"))
    }
    @Test fun `composer uses short verified targets and an honest unknown fallback`() {
        val chat = Chat("chat_one", "session", "Session", createdAt = 1, updatedAt = 1)
        assertEquals("Claude", chat.copy(callee = "claude").composerTarget())
        assertEquals("Codex", chat.copy(callee = "codex").composerTarget())
        assertEquals("Claude", chat.copy(caller = CallerContext(provider = "Claude Code")).composerTarget())
        assertEquals("Codex", chat.copy(caller = CallerContext(provider = "OpenAI Codex")).composerTarget())
        assertEquals("Codex", chat.copy(callee = null, caller = CallerContext(provider = "OpenAI / Codex")).composerTarget())
        assertEquals("Codex", chat.copy(callee = null, caller = CallerContext(provider = "OpenAI/Codex")).composerTarget())
        assertEquals("Other provider", chat.copy(caller = CallerContext(provider = "Other provider")).composerTarget())
        assertEquals("this session", chat.composerTarget())
    }
}
