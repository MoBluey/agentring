package dev.agentring.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dev.agentring.core.Attachment
import dev.agentring.core.AttachmentTransfer
import dev.agentring.core.RelayApi
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class SelectedAttachment(val file: File, val name: String, val mediaType: String) {
    val size: Long get() = file.length()
}

/** Only user-selected downloads are stored. Every downloaded byte is checksum verified. */
class AttachmentStore(private val context: Context) {
    private val downloads = File(context.filesDir, "attachments").apply { mkdirs() }
    private val drafts = File(context.cacheDir, "attachment-drafts").apply { mkdirs() }

    fun stage(uri: Uri, transfer: AttachmentTransfer, progress: (Long) -> Unit): SelectedAttachment {
        var name = "attachment"
        var size: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        require(size == null || size!! <= AttachmentTransfer.MAX_BYTES) { "Files must be 100 MiB or smaller" }
        val target = File(drafts, UUID.randomUUID().toString())
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        transfer.check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= AttachmentTransfer.MAX_BYTES) { "Files must be 100 MiB or smaller" }
                        output.write(buffer, 0, n)
                        progress(count)
                    }
                }
            }
            return SelectedAttachment(target, name.take(128), context.contentResolver.getType(uri) ?: "application/octet-stream")
        } catch (e: Exception) { target.delete(); throw e }
    }

    private fun target(attachment: Attachment): File {
        val key = MessageDigest.getInstance("SHA-256").digest(attachment.attachmentId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(downloads, key)
    }

    fun cached(attachment: Attachment): File? = target(attachment).takeIf { it.isFile && it.length() == attachment.sizeBytes }

    fun download(api: RelayApi, attachment: Attachment, transfer: AttachmentTransfer, progress: (Long) -> Unit): File {
        cached(attachment)?.let { return it }
        val target = target(attachment)
        val temporary = File(downloads, target.name + "." + UUID.randomUUID() + ".part")
        try {
            temporary.outputStream().use { api.downloadAttachment(attachment, it, transfer, progress) }
            check(temporary.renameTo(target)) { "Cannot store downloaded file" }
            return target
        } finally { temporary.delete() }
    }

    fun save(file: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination, "wt")!!.use { output -> file.inputStream().use { it.copyTo(output) } }
    }

    fun open(file: File, attachment: Attachment) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".attachments", file, attachment.name)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mediaType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun thumbnail(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
