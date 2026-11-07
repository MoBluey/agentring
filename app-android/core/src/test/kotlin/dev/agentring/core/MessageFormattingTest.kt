package dev.agentring.core

import org.junit.Assert.*
import org.junit.Test

class MessageFormattingTest {
    @Test fun `formats only selected text and toggles without losing content`() {
        val raw = "alpha beta gamma"
        val bold = MessageFormatting.toggle(emptyList(), MessageFormat.BOLD, 6, 10)
        assertEquals("alpha **beta** gamma", MessageFormatting.markdown(raw, bold))
        val both = MessageFormatting.toggle(bold, MessageFormat.HIGHLIGHT, 6, 10)
        assertEquals("alpha **==beta==** gamma", MessageFormatting.markdown(raw, both))
        val parsed = MessageFormatting.inline(MessageFormatting.markdown(raw, both))
        assertEquals(raw, parsed.text)
        assertEquals(setOf(MessageFormat.BOLD, MessageFormat.HIGHLIGHT), parsed.formats.map { it.format }.toSet())
        assertEquals(raw, MessageFormatting.markdown(raw, MessageFormatting.toggle(bold, MessageFormat.BOLD, 6, 10)))
    }

    @Test fun `editing before and inside formatted selection keeps intended style range`() {
        val range = listOf(FormatRange(6, 10, MessageFormat.BOLD))
        val shifted = MessageFormatting.edited("alpha beta", "new alpha beta", range)
        assertEquals("new alpha **beta**", MessageFormatting.markdown("new alpha beta", shifted))
        val changed = MessageFormatting.edited("alpha beta", "alpha better", range)
        assertEquals("alpha **better**", MessageFormatting.markdown("alpha better", changed))
        assertEquals(emptyList<FormatRange>(), MessageFormatting.edited("alpha beta", "alpha ", range))
    }

    @Test fun `fenced code content preserves indentation unicode and trailing newline`() {
        val code = "  println(\"hi 🦘\")\n```nested fence\n"
        val markdown = MessageFormatting.markdown(code, listOf(FormatRange(0, code.length, MessageFormat.CODE)))
        val block = MessageFormatting.blocks(markdown).single() as MessageBlock.Code
        assertEquals(code, block.content)
        val language = MessageFormatting.blocks("```kotlin\nval a = 1\n```\n").single() as MessageBlock.Code
        assertEquals("kotlin", language.language)
        assertEquals("val a = 1", language.content)
    }

    @Test fun `unmatched markup and html remain literal safe text`() {
        val raw = "**unfinished <script>alert(1)</script>"
        assertEquals(raw, MessageFormatting.inline(raw).text)
        assertTrue(MessageFormatting.blocks(raw).single() is MessageBlock.Text)
    }
}
