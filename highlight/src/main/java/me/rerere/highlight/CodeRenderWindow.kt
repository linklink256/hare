package me.rerere.highlight

/** A bounded UI window only. Stored messages and copy/export must keep the original source. */
data class CodeRenderWindow(val start: Int, val end: Int, val text: String, val total: Int) {
    val hasPrevious: Boolean get() = start > 0
    val hasNext: Boolean get() = end < total

    companion object {
        const val MAX_CHARS = 8 * 1024
        const val MAX_LINES = 80
        const val MAX_LINE_CHARS = 512

        fun slice(source: String, start: Int = 0): CodeRenderWindow {
            require(start in 0..source.length)
            var offset = start
            var lines = 1
            var lineChars = 0
            while (offset < source.length) {
                val c = source[offset]
                val units = if (Character.isHighSurrogate(c) &&
                    offset + 1 < source.length && Character.isLowSurrogate(source[offset + 1])) 2 else 1
                if (offset - start + units > MAX_CHARS || lineChars + units > MAX_LINE_CHARS) break
                if ((c == '\n' || c == '\r') && lines >= MAX_LINES) break
                offset += units
                if (c == '\n' || c == '\r') {
                    if (c == '\r' && offset < source.length && source[offset] == '\n') {
                        if (offset - start < MAX_CHARS) offset++
                    }
                    lines++
                    lineChars = 0
                } else {
                    lineChars += units
                }
            }
            // At least one code point always fits an empty window.
            check(offset > start || start == source.length)
            return CodeRenderWindow(start, offset, source.substring(start, offset), source.length)
        }

        fun preview(source: String): String {
            val window = slice(source)
            return if (window.hasNext) window.text + "\n… [显示已限长，原始内容保留]" else window.text
        }
    }
}
