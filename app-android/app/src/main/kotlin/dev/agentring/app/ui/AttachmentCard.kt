package dev.agentring.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agentring.app.Graph
import dev.agentring.app.data.AttachmentStore
import dev.agentring.core.Attachment
import dev.agentring.core.AttachmentTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AttachmentCard(attachment: Attachment) {
    val context = LocalContext.current
    val store = remember(context) { AttachmentStore(context) }
    val scope = rememberCoroutineScope()
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
    var file by remember(attachment.attachmentId) { mutableStateOf(store.cached(attachment)) }
    var active by remember { mutableStateOf<AttachmentTransfer?>(null) }
    var bytes by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(attachment.mediaType)) { uri ->
        if (uri != null && file != null) scope.launch {
            try { withContext(Dispatchers.IO) { store.save(file!!, uri) }; error = null }
            catch (e: Exception) { error = "Could not save: ${e.message}" }
        }
    }
    DisposableEffect(attachment.attachmentId) { onDispose { active?.cancel() } }
    LaunchedEffect(file) {
        thumbnail = if (file != null && attachment.mediaType.startsWith("image/")) {
            withContext(Dispatchers.IO) { runCatching { store.thumbnail(file!!) }.getOrNull() }
        } else null
    }
    Column(Modifier.fillMaxWidth().testTag("attachment_${attachment.attachmentId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(attachment.name, style = MaterialTheme.typography.titleSmall)
        Text("${attachment.mediaType} · ${attachmentSize(attachment.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
        thumbnail?.let { Image(it.asImageBitmap(), attachment.name, Modifier.fillMaxWidth().heightIn(max = 200.dp)) }
        if (active != null) {
            LinearProgressIndicator(progress = { if (attachment.sizeBytes > 0) bytes.toFloat() / attachment.sizeBytes else 0f }, modifier = Modifier.fillMaxWidth())
            Row { Text("Downloading ${attachmentSize(bytes)}", Modifier.weight(1f)); TextButton(colors = buttonColors, onClick = { active?.cancel() }) { Text("Cancel") } }
        } else if (file == null) {
            TextButton(colors = buttonColors, onClick = {
                val transfer = AttachmentTransfer(); active = transfer; bytes = 0; error = null
                scope.launch {
                    try { file = withContext(Dispatchers.IO) { store.download(Graph.api() ?: error("Not paired"), attachment, transfer) { bytes = it } } }
                    catch (e: Exception) { error = e.message ?: "Download failed" }
                    finally { active = null }
                }
            }, modifier = Modifier.testTag("download_${attachment.attachmentId}")) { Text(if (error == null) "Download" else "Retry download") }
        } else {
            Row {
                TextButton(colors = buttonColors, onClick = {
                    try { store.open(file!!, attachment); error = null }
                    catch (e: android.content.ActivityNotFoundException) { error = "No app can open this file. Save it to use elsewhere." }
                    catch (e: Exception) { error = "Could not open: ${e.message}" }
                }, modifier = Modifier.testTag("open_${attachment.attachmentId}")) { Text("Open") }
                TextButton(colors = buttonColors, onClick = { save.launch(attachment.name) }, modifier = Modifier.testTag("save_${attachment.attachmentId}")) { Text("Save as…") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun attachmentSize(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MiB".format(size.toDouble() / (1024 * 1024))
    size >= 1024 -> "%.1f KiB".format(size.toDouble() / 1024)
    else -> "$size B"
}
