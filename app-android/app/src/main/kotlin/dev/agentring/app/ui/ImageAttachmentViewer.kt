package dev.agentring.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.agentring.app.data.AttachmentStore
import dev.agentring.core.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Native full-screen preview; the existing verified private file never leaves the app. */
@Composable
internal fun ImageAttachmentViewer(attachment: Attachment, file: File, store: AttachmentStore, onClose: () -> Unit, onSave: () -> Unit) {
    DisposableEffect(Unit) {
        AppVisibility.imagePreviewOpen.value = true
        AppVisibility.visibleChatId = null
        onDispose { AppVisibility.imagePreviewOpen.value = false }
    }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(file, retry) {
        error = null
        try { bitmap = withContext(Dispatchers.IO) { store.image(attachment, file, 2048) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Image preview unavailable" }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("image_viewer"), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("close_image_viewer")) { Text("Close", color = Color.White) }
                    Text(attachment.name, Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = onSave) { Text("Save as…", color = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val image = bitmap
                    when {
                        image != null -> Image(image.asImageBitmap(), attachment.name, Modifier.fillMaxSize().testTag("full_image_${attachment.attachmentId}"), contentScale = ContentScale.Fit)
                        error != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Image preview unavailable: $error")
                            TextButton(onClick = { retry++ }) { Text("Retry", color = Color.White) }
                            Text("You can save the original file to view it elsewhere.")
                        }
                        else -> CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }
    }
}
