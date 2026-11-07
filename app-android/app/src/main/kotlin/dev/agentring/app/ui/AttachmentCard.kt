package dev.agentring.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var active by remember(attachment.attachmentId) { mutableStateOf<AttachmentTransfer?>(null) }
    var bytes by remember(attachment.attachmentId) { mutableStateOf(0L) }
    var error by remember(attachment.attachmentId) { mutableStateOf<String?>(null) }
    var thumbnail by remember(attachment.attachmentId) { mutableStateOf<Bitmap?>(null) }
    var loadVersion by remember(attachment.attachmentId) { mutableIntStateOf(0) }
    var previewError by remember(attachment.attachmentId) { mutableStateOf<String?>(null) }
    var viewing by rememberSaveable(attachment.attachmentId) { mutableStateOf(false) }
    val isImage = attachment.mediaType.startsWith("image/", ignoreCase = true)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(attachment.mediaType)) { uri ->
        if (uri != null && file != null) scope.launch {
            try { withContext(Dispatchers.IO) { store.save(file!!, uri) }; error = null }
            catch (e: Exception) { error = "Could not save: ${e.message}" }
        }
    }
    fun download(retry: Boolean = false) {
        if (active != null) return
        val control = AttachmentTransfer(); active = control; bytes = 0; error = null; previewError = null
        scope.launch {
            try {
                file = withContext(Dispatchers.IO) {
                    if (retry) store.discardCached(attachment)
                    store.download(Graph.api() ?: error("Not paired"), attachment, control) { bytes = it }
                }
                loadVersion++
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Download failed" }
            finally { active = null }
        }
    }
    DisposableEffect(attachment.attachmentId) { onDispose { active?.cancel() } }
    LaunchedEffect(attachment.attachmentId) { if (isImage && file == null) download() }
    LaunchedEffect(file, loadVersion) {
        thumbnail = null; previewError = null
        if (file != null && isImage) {
            try { thumbnail = withContext(Dispatchers.IO) { store.image(attachment, file!!, 512) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { previewError = e.message ?: "Image preview unavailable" }
        }
    }
    if (viewing && file != null) ImageAttachmentViewer(attachment, file!!, store, onClose = { viewing = false }, onSave = { save.launch(attachment.name) })
    Column(Modifier.fillMaxWidth().testTag("attachment_${attachment.attachmentId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(attachment.name, style = MaterialTheme.typography.titleSmall)
        Text("${attachment.mediaType} · ${attachmentSize(attachment.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
        thumbnail?.let { Image(it.asImageBitmap(), attachment.name,
            Modifier.fillMaxWidth().heightIn(max = 200.dp).testTag("preview_${attachment.attachmentId}").clickable { viewing = true }, contentScale = ContentScale.Fit) }
        if (active != null) {
            LinearProgressIndicator(progress = { if (attachment.sizeBytes > 0) bytes.toFloat() / attachment.sizeBytes else 0f }, modifier = Modifier.fillMaxWidth())
            Row { Text("Downloading ${attachmentSize(bytes)}", Modifier.weight(1f)); TextButton(colors = buttonColors, onClick = { active?.cancel() }) { Text("Cancel") } }
        } else if (file == null) {
            TextButton(colors = buttonColors, onClick = { download() }, modifier = Modifier.testTag("download_${attachment.attachmentId}")) {
                Text(if (isImage) "Retry preview" else if (error == null) "Download" else "Retry download")
            }
        } else {
            Row {
                TextButton(colors = buttonColors, onClick = {
                    try { if (isImage) viewing = true else store.open(file!!, attachment); error = null }
                    catch (e: android.content.ActivityNotFoundException) { error = "No app can open this file. Save it to use elsewhere." }
                    catch (e: Exception) { error = "Could not open: ${e.message}" }
                }, modifier = Modifier.testTag("open_${attachment.attachmentId}")) { Text(if (isImage) "View image" else "Open") }
                TextButton(colors = buttonColors, onClick = { save.launch(attachment.name) }, modifier = Modifier.testTag("save_${attachment.attachmentId}")) { Text("Save as…") }
            }
        }
        previewError?.let { reason ->
            Text("Image preview unavailable: $reason", modifier = Modifier.testTag("preview_error_${attachment.attachmentId}"), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(colors = buttonColors, onClick = { file = null; download(retry = true) }, modifier = Modifier.testTag("retry_preview_${attachment.attachmentId}")) { Text("Retry preview") }
                TextButton(colors = buttonColors, onClick = { runCatching { store.open(file!!, attachment) }.onFailure { error = "No app can open this image. Use Save as instead." } }) { Text("Open externally") }
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
