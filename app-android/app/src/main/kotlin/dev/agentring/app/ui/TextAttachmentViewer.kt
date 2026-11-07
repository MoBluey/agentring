package dev.agentring.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.agentring.core.Attachment
import dev.agentring.core.TextAttachmentPreview
import dev.agentring.app.data.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Native selectable text, never HTML/Markdown execution or remote resource loading. */
@Composable
internal fun TextAttachmentViewer(attachment: Attachment, file: File, store: AttachmentStore, onClose: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        AppVisibility.imagePreviewOpen.value = true
        AppVisibility.visibleChatId = null
        onDispose { AppVisibility.imagePreviewOpen.value = false }
    }
    var preview by remember(file) { mutableStateOf<TextAttachmentPreview?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(file, retry) {
        preview = null; error = null; copied = false
        try { preview = withContext(Dispatchers.IO) { store.text(attachment, file) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Text preview unavailable" }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("text_viewer")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("close_text_viewer")) { Text("Close") }
                    Text(attachment.name, Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = onSave, modifier = Modifier.testTag("save_text_original")) { Text("Save original") }
                }
                val shown = preview
                when {
                    shown != null -> {
                        if (shown.truncated) Text("Preview truncated to the first 64 KiB of UTF-8 text. Save original keeps the complete file.", Modifier.padding(horizontal = 16.dp).testTag("text_preview_truncated"), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(attachment.name, shown.text))
                            copied = true
                        }, modifier = Modifier.testTag("copy_text_attachment")) { Text(if (copied) "Copied" else if (shown.truncated) "Copy preview" else "Copy text") }
                        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text(shown.text.ifEmpty { "" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().testTag("text_attachment_content"))
                        }
                    }
                    error != null -> Column(Modifier.padding(24.dp)) {
                        Text("Text preview unavailable: $error", Modifier.testTag("text_preview_error"))
                        TextButton(onClick = { retry++ }) { Text("Retry") }
                        Text("Save original keeps the complete file.")
                    }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}
