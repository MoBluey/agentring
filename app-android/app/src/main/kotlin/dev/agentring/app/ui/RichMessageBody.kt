package dev.agentring.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.agentring.core.FormatRange
import dev.agentring.core.MessageBlock
import dev.agentring.core.MessageFormat
import dev.agentring.core.MessageFormatting

private const val FORMAT_TAG = "message-format"

internal fun editorRanges(value: TextFieldValue): List<FormatRange> =
    value.annotatedString.getStringAnnotations(FORMAT_TAG, 0, value.text.length).mapNotNull {
        runCatching { FormatRange(it.start, it.end, MessageFormat.valueOf(it.item)) }.getOrNull()
    }

private fun formatStyle(format: MessageFormat): SpanStyle = when (format) {
    MessageFormat.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    MessageFormat.HIGHLIGHT -> SpanStyle(background = Color(0xFFFFE082), color = Color.Black)
    MessageFormat.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFE0E0E0), color = Color.Black)
}

internal fun styledText(text: String, ranges: List<FormatRange>): AnnotatedString = AnnotatedString.Builder(text).apply {
    ranges.forEach { range ->
        addStyle(formatStyle(range.format), range.start, range.end)
        addStringAnnotation(FORMAT_TAG, range.format.name, range.start, range.end)
    }
}.toAnnotatedString()

internal fun editedComposer(old: TextFieldValue, next: TextFieldValue): TextFieldValue = next.copy(
    annotatedString = styledText(next.text, MessageFormatting.edited(old.text, next.text, editorRanges(old))),
)

internal fun formattedComposer(value: TextFieldValue, format: MessageFormat): TextFieldValue = value.copy(
    annotatedString = styledText(value.text, MessageFormatting.toggle(editorRanges(value), format, value.selection.min, value.selection.max)),
)

internal fun composerMessage(value: TextFieldValue): String = MessageFormatting.markdown(value.text, editorRanges(value))

@Composable
internal fun FormattingToolbar(value: TextFieldValue, enabled: Boolean, onFormat: (MessageFormat) -> Unit) {
    val includesCode = editorRanges(value).any { it.format == MessageFormat.CODE && it.start < value.selection.max && it.end > value.selection.min }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (value.text.isNotEmpty() && value.selection.collapsed) Text("Select text to format", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((format, label) in listOf(MessageFormat.BOLD to "Bold", MessageFormat.HIGHLIGHT to "Highlight", MessageFormat.CODE to "Code block")) {
                TextButton(onClick = { onFormat(format) }, enabled = enabled && !value.selection.collapsed && (format == MessageFormat.CODE || !includesCode),
                    modifier = Modifier.testTag("format_${format.name.lowercase()}")) { Text(label) }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Message", text))
}

@Composable
internal fun RichMessageBody(raw: String, messageId: String) {
    val context = LocalContext.current
    val blocks = remember(raw) { MessageFormatting.blocks(raw) }
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
    var copied by remember(raw) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                blocks.forEachIndexed { index, block ->
                    when (block) {
                        is MessageBlock.Text -> {
                            val inline = remember(block.text) { MessageFormatting.inline(block.text) }
                            Text(styledText(inline.text, inline.formats), style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag("message_text_${messageId}_$index"))
                        }
                        is MessageBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.padding(10.dp)) {
                                DisableSelection {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(block.language.ifBlank { "Code" }, style = MaterialTheme.typography.labelSmall)
                                        TextButton(onClick = { copy(context, block.content) }, modifier = Modifier.testTag("copy_code_${messageId}_$index")) { Text("Copy code") }
                                    }
                                }
                                Text(block.content, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("code_text_${messageId}_$index"), softWrap = false)
                            }
                        }
                    }
                }
            }
        }
        TextButton(colors = buttonColors, onClick = { copy(context, raw); copied = true }, modifier = Modifier.testTag("copy_message_$messageId")) {
            Text(if (copied) "Copied" else "Copy message")
        }
    }
}
