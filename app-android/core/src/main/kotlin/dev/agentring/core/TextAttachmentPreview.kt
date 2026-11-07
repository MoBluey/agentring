package dev.agentring.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Plain UTF-8 only. At most 64 KiB is decoded/layouted, even for a 100 MiB attachment. */
data class TextAttachmentPreview(val text: String, val truncated: Boolean) {
    companion object {
        const val MAX_BYTES = 64 * 1024
        private val extensions = setOf("txt", "text", "log", "md", "markdown", "rst", "json", "jsonl", "ndjson", "csv", "tsv", "yaml", "yml", "toml", "xml", "html", "htm", "css", "scss", "js", "mjs", "cjs", "jsx", "ts", "tsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "sh", "bash", "zsh", "fish", "sql", "ini", "conf", "cfg", "properties", "gradle", "diff", "patch", "graphql", "env")
        private val mimeTypes = setOf("application/json", "application/ndjson", "application/x-ndjson", "application/xml", "application/javascript", "application/x-javascript", "application/yaml", "application/x-yaml", "application/toml", "application/sql", "application/x-sh")

        fun supports(name: String, mediaType: String): Boolean {
            val mime = mediaType.substringBefore(';').trim().lowercase()
            if (mime.startsWith("text/") || mime in mimeTypes || mime.endsWith("+json") || mime.endsWith("+xml")) return true
            if (mime.isNotEmpty() && mime != "application/octet-stream") return false
            return name.substringAfterLast('.', "").lowercase() in extensions || name.lowercase() in setOf("dockerfile", "makefile", "license", "readme", ".gitignore", ".env")
        }

        fun read(input: InputStream): TextAttachmentPreview {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            val truncated = bytes.size > MAX_BYTES
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val chars = CharBuffer.allocate(minOf(bytes.size, MAX_BYTES))
            val result = decoder.decode(ByteBuffer.wrap(bytes, 0, minOf(bytes.size, MAX_BYTES)), chars, !truncated)
            require(!result.isError) { "This file is not supported UTF-8 text. Save the original to open it elsewhere." }
            chars.flip()
            val text = chars.toString().removePrefix("\uFEFF")
            require(text.none { it < ' ' && it != '\n' && it != '\r' && it != '\t' || it == '\u007f' }) { "This file contains binary data. Save the original to open it elsewhere." }
            return TextAttachmentPreview(text, truncated)
        }
    }
}
