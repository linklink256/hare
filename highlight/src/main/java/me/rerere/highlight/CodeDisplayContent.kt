package me.rerere.highlight

/** The user's existing collapse setting may create a preview; expanded content is never paginated. */
object CodeDisplayContent {
    const val MAX_HIGHLIGHT_CHARS = 4096

    fun visibleCode(source: String, collapsed: Boolean, collapsedLines: Int = 10): String {
        require(collapsedLines > 0)
        return if (collapsed) source.lineSequence().take(collapsedLines).joinToString("\n") else source
    }

    /** Expensive tokenization is optional; plain-text rendering must still receive the full source. */
    fun shouldHighlight(source: String): Boolean = source.length <= MAX_HIGHLIGHT_CHARS
}
