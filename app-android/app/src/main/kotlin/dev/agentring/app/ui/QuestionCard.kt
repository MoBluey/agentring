package dev.agentring.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agentring.core.Message
import dev.agentring.core.Question
import dev.agentring.core.QuestionAnswer
import dev.agentring.core.MessageSendAttempt
import dev.agentring.core.RelayException
import dev.agentring.app.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun QuestionCard(message: Message, question: Question) {
    var option by rememberSaveable(message.msgId) { mutableStateOf<String?>(null) }
    var written by rememberSaveable(message.msgId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val attempt = remember { MessageSendAttempt() }
    val scope = rememberCoroutineScope()
    val answered = question.answeredByMsgId != null
    Column(Modifier.fillMaxWidth().testTag("question_${message.msgId}")) {
        question.options.forEach { choice ->
            Surface(
                onClick = { option = choice.id; written = ""; error = null },
                enabled = !sending && !answered,
                modifier = Modifier.fillMaxWidth().testTag("option_${message.msgId}_${choice.id}"),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Text((if (option == choice.id) "◉ " else "○ ") + choice.label, Modifier.padding(10.dp)) }
        }
        if (question.allowFreeText && !answered) {
            OutlinedTextField(value = written, onValueChange = { written = it; option = null; error = null },
                enabled = !sending, label = { Text("Written answer") }, maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("answer_text_${message.msgId}"))
        }
        if (answered) Text("Answered", modifier = Modifier.testTag("answered_${message.msgId}"))
        else TextButton(
            enabled = !sending && (option != null || written.isNotBlank()) && written.length <= 4000,
            modifier = Modifier.testTag("answer_send_${message.msgId}"),
            onClick = {
                val answer = QuestionAnswer(message.msgId, option, written.takeIf { option == null })
                val key = attempt.forPayload(answer.toString(), null).clientMsgId
                sending = true; error = null
                scope.launch {
                    try {
                        val api = Graph.api() ?: error("not paired")
                        val sent = withContext(Dispatchers.IO) { api.postMessage("", clientMsgId = key, chatId = message.chatId, answer = answer) }
                        Graph.messages.upsert(sent)
                        withContext(Dispatchers.IO) { Graph.messages.upsert(api.getMessage(message.msgId)) }
                    } catch (e: Exception) {
                        error = e.message ?: "Cannot send answer"
                        if (e is RelayException && e.code == "question_answered") withContext(Dispatchers.IO) {
                            Graph.api()?.let { api -> runCatching { Graph.messages.upsert(api.getMessage(message.msgId)) } }
                        }
                    } finally { sending = false }
                }
            },
        ) { Text(if (sending) "Sending…" else "Send answer") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
