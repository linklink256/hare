package me.rerere.ui.markdown

/** Display-only window. Never used for provider requests, storage, copying, or export. */
data class MarkdownRenderWindow(val start: Int, val end: Int, val text: String, val total: Int) {
    val hasPrevious get() = start > 0
    val hasNext get() = end < total

    companion object {
        const val MAX_CHARS = 12 * 1024
        const val MAX_LINES = 120
        const val MAX_LINE_CHARS = 2048

        fun slice(source: String, offset: Int = 0): MarkdownRenderWindow {
            require(offset in 0..source.length)
            val start = if (offset > 0 && offset < source.length &&
                Character.isLowSurrogate(source[offset]) && Character.isHighSurrogate(source[offset - 1])) offset - 1 else offset
            var end = start
            var lines = 1
            var width = 0
            while (end < source.length) {
                val c = source[end]
                val units = if (Character.isHighSurrogate(c) && end + 1 < source.length &&
                    Character.isLowSurrogate(source[end + 1])) 2 else 1
                if (end - start + units > MAX_CHARS || width + units > MAX_LINE_CHARS) break
                if ((c == '\n' || c == '\r') && lines >= MAX_LINES) break
                end += units
                if (c == '\n' || c == '\r') {
                    if (c == '\r' && end < source.length && source[end] == '\n' && end - start < MAX_CHARS) end++
                    lines++
                    width = 0
                } else width += units
            }
            check(end > start || start == source.length)
            return MarkdownRenderWindow(start, end, source.substring(start, end), source.length)
        }
    }
}
