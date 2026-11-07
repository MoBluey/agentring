package dev.agentring.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SessionSearchTest {
    private val chat = Chat("chat_1", "session_1", "Build notes", caller = CallerContext(provider = "OpenAI / Codex"), createdAt = 1, updatedAt = 2)

    @Test fun `archive is an authenticated reversible patch and legacy chat defaults active`() {
        MockWebServer().use { server ->
            val api = RelayApi(server.url("/").toString(), "jwt")
            for (archived in listOf(true, false)) {
                val response = chat.copy(archivedAt = if (archived) 3 else null)
                server.enqueue(MockResponse().setBody(ProtocolJson.encodeToString(Chat.serializer(), response)))
                assertEquals(response, api.setChatArchived(chat.chatId, archived))
                val request = server.takeRequest()
                assertEquals("PATCH", request.method)
                assertEquals("/v1/chats/chat_1", request.path)
                assertEquals("Bearer jwt", request.getHeader("Authorization"))
                assertEquals("{\"archived\":$archived}", request.body.readUtf8())
            }
            val legacy = ProtocolJson.decodeFromString(Chat.serializer(), """{"chat_id":"chat_old","client_session_id":"s","title":"Legacy","created_at":1,"updated_at":1}""")
            assertNull(legacy.archivedAt)
        }
    }

    @Test fun `combined status provider and literal query survive URL encoding and decode target`() {
        MockWebServer().use { server ->
            val query = "100%_ [x] + C++ & café?"
            val page = ChatSearchPage(listOf(ChatSearchResult(chat, ChatSearchMatch("message", "Log: $query", "msg_old", 1))))
            server.enqueue(MockResponse().setBody(ProtocolJson.encodeToString(ChatSearchPage.serializer(), page)))
            val api = RelayApi(server.url("/").toString(), "jwt")
            assertEquals(page, api.searchChats(query, "archived", "OpenAI / Codex", 30))
            val request = server.takeRequest()
            assertEquals("/v1/chats/search", request.requestUrl!!.encodedPath)
            assertEquals(query, request.requestUrl!!.queryParameter("q"))
            assertEquals("archived", request.requestUrl!!.queryParameter("status"))
            assertEquals("OpenAI / Codex", request.requestUrl!!.queryParameter("provider"))
            assertEquals("30", request.requestUrl!!.queryParameter("limit"))
            assertEquals("Bearer jwt", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody(ProtocolJson.encodeToString(ChatPage.serializer(), ChatPage(listOf(chat)))))
            assertEquals(listOf(chat), api.listChats("active", "Claude Code").chats)
            val list = server.takeRequest().requestUrl!!
            assertEquals("active", list.queryParameter("status")); assertEquals("Claude Code", list.queryParameter("provider"))
        }
    }
}
