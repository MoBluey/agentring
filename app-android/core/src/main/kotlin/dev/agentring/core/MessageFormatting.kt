package dev.agentring.core

/** Formatting stays in plain Markdown on the wire; no HTML is executed. */
enum class MessageFormat { BOLD, HIGHLIGHT, CODE }
data class FormatRange(val start: Int, val end: Int, val format: MessageFormat)
sealed interface MessageBlock {
    data class Text(val text: String) : MessageBlock
    data class Code(val language: String, val content: String) : MessageBlock
}
data class InlineText(val text: String, val formats: List<FormatRange>)

object MessageFormatting {
    fun blocks(raw: String): List<MessageBlock> {
        val opening = Regex("(?m)^(`{3,})([^`\\r\\n]*)\\r?\\n")
        val result = mutableListOf<MessageBlock>()
        var position = 0
        while (position < raw.length) {
            val open = opening.find(raw, position)
            val close = open?.let { Regex("(?m)^" + Regex.escape(it.groupValues[1]) + "`*[ \t]*(?:\\r?\\n|$)").find(raw, it.range.last + 1) }
            if (open == null || close == null) { result += MessageBlock.Text(raw.substring(position)); break }
            if (open.range.first > position) result += MessageBlock.Text(raw.substring(position, open.range.first))
            val content = raw.substring(open.range.last + 1, close.range.first).removeSuffix("\n").removeSuffix("\r")
            result += MessageBlock.Code(open.groupValues[2].trim(), content)
            position = close.range.last + 1
        }
        return result
    }

    fun inline(raw: String): InlineText {
        val hidden = BooleanArray(raw.length)
        val ranges = mutableListOf<FormatRange>()
        for ((marker, format) in listOf("**" to MessageFormat.BOLD, "==" to MessageFormat.HIGHLIGHT)) {
            var position = 0
            while (position < raw.length) {
                val start = raw.indexOf(marker, position)
                val end = if (start >= 0) raw.indexOf(marker, start + marker.length) else -1
                if (end < 0) break
                for (i in start until start + marker.length) hidden[i] = true
                for (i in end until end + marker.length) hidden[i] = true
                ranges += FormatRange(start + marker.length, end, format)
                position = end + marker.length
            }
        }
        val offsets = IntArray(raw.length + 1)
        val text = buildString {
            raw.forEachIndexed { i, char -> offsets[i] = length; if (!hidden[i]) append(char) }
            offsets[raw.length] = length
        }
        return InlineText(text, ranges.map { it.copy(start = offsets[it.start], end = offsets[it.end]) }.filter { it.start < it.end })
    }

    fun toggle(ranges: List<FormatRange>, format: MessageFormat, start: Int, end: Int): List<FormatRange> {
        if (start >= end) return ranges
        if (format != MessageFormat.CODE && ranges.any { it.format == MessageFormat.CODE && it.start < end && it.end > start }) return ranges
        fun subtract(range: FormatRange): List<FormatRange> = if (range.end <= start || range.start >= end) listOf(range)
            else buildList {
                if (range.start < start) add(range.copy(end = start))
                if (range.end > end) add(range.copy(start = end))
            }
        val covered = (start until end).all { i -> ranges.any { it.format == format && i >= it.start && i < it.end } }
        if (covered) return ranges.flatMap { if (it.format == format) subtract(it) else listOf(it) }
        val next = if (format == MessageFormat.CODE) ranges.flatMap { if (it.format != MessageFormat.CODE) subtract(it) else listOf(it) } else ranges
        return MessageFormat.entries.flatMap { kind ->
            val merged = mutableListOf<FormatRange>()
            (next + FormatRange(start, end, format)).filter { it.format == kind }.sortedBy { it.start }.forEach { range ->
                val last = merged.lastOrNull()
                if (last != null && range.start <= last.end) merged[merged.lastIndex] = last.copy(end = maxOf(last.end, range.end))
                else merged += range
            }
            merged
        }
    }

    fun edited(old: String, next: String, ranges: List<FormatRange>): List<FormatRange> {
        var start = 0
        while (start < old.length && start < next.length && old[start] == next[start]) start++
        var suffix = 0
        while (suffix < old.length - start && suffix < next.length - start && old[old.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        val oldEnd = old.length - suffix
        val inserted = next.length - suffix - start
        val delta = next.length - old.length
        return ranges.mapNotNull { range ->
            val adjusted = when {
                range.end <= start -> range
                range.start >= oldEnd -> range.copy(start = range.start + delta, end = range.end + delta)
                else -> range.copy(start = minOf(range.start, start), end = if (range.end > oldEnd) range.end + delta else start + inserted)
            }
            adjusted.takeIf { it.start >= 0 && it.end <= next.length && it.start < it.end }
        }
    }

    fun markdown(text: String, ranges: List<FormatRange>): String {
        val out = StringBuilder()
        var active = emptyList<MessageFormat>()
        fun transition(next: List<MessageFormat>) {
            if (next == active) return
            active.asReversed().forEach { out.append(if (it == MessageFormat.BOLD) "**" else "==") }
            next.forEach { out.append(if (it == MessageFormat.BOLD) "**" else "==") }
            active = next
        }
        var position = 0
        while (position < text.length) {
            val code = ranges.filter { it.format == MessageFormat.CODE && position >= it.start && position < it.end }.maxByOrNull { it.end }
            if (code != null) {
                transition(emptyList())
                if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                val content = text.substring(position, code.end)
                val longest = Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0
                val fence = "`".repeat(maxOf(3, longest + 1))
                out.append(fence).append('\n').append(content).append('\n').append(fence)
                position = code.end
                if (position < text.length && text[position] != '\n') out.append('\n')
                continue
            }
            transition(listOf(MessageFormat.BOLD, MessageFormat.HIGHLIGHT).filter { format -> ranges.any { it.format == format && position >= it.start && position < it.end } })
            out.append(text[position++])
        }
        transition(emptyList())
        return out.toString()
    }
}
