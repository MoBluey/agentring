package dev.agentring.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class TextAttachmentPreviewTest {
    @Test fun `common text and code extensions support octet stream but binary MIME wins`() {
        for (name in listOf("app.log", "notes.md", "report.TXT", "main.kt", "run.py", "Dockerfile")) assertTrue(name, TextAttachmentPreview.supports(name, "application/octet-stream"))
        assertTrue(TextAttachmentPreview.supports("unknown", "text/plain; charset=utf-8"))
        assertTrue(TextAttachmentPreview.supports("unknown", "application/problem+json"))
        assertFalse(TextAttachmentPreview.supports("document.pdf", "application/pdf"))
        assertFalse(TextAttachmentPreview.supports("binary.txt", "image/png"))
        assertFalse(TextAttachmentPreview.supports("archive.zip", "application/octet-stream"))
    }

    @Test fun `UTF8 text preserves literal markdown unicode and newlines`() {
        val raw = "# Heading\n**literal** <script>ignored</script>\nCafé 世界\t✓\r\n"
        val preview = TextAttachmentPreview.read(ByteArrayInputStream(("\uFEFF" + raw).toByteArray()))
        assertEquals(raw, preview.text)
        assertFalse(preview.truncated)
        assertEquals("", TextAttachmentPreview.read(ByteArrayInputStream(byteArrayOf())).text)
    }

    @Test fun `large preview stops at byte cap without splitting UTF8 character`() {
        val raw = "a".repeat(TextAttachmentPreview.MAX_BYTES - 1) + "世界" + "z".repeat(200000)
        val input = ByteArrayInputStream(raw.toByteArray())
        val preview = TextAttachmentPreview.read(input)
        assertTrue(preview.truncated)
        assertEquals("a".repeat(TextAttachmentPreview.MAX_BYTES - 1), preview.text)
        assertEquals(raw.toByteArray().size - TextAttachmentPreview.MAX_BYTES - 1, input.available())
        val exact = TextAttachmentPreview.read(ByteArrayInputStream(ByteArray(TextAttachmentPreview.MAX_BYTES) { 65 }))
        assertFalse(exact.truncated)
    }

    @Test fun `binary and malformed UTF8 are rejected rather than displayed as text`() {
        for (bytes in listOf(byteArrayOf(65, 0, 66), byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0xFF.toByte(), 0xFE.toByte()), byteArrayOf(65, 0x7f))) {
            try { TextAttachmentPreview.read(ByteArrayInputStream(bytes)); fail("Expected text rejection") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
