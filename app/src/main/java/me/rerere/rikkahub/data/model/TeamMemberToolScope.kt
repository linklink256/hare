package me.rerere.rikkahub.data.model

/**
 * 队友工具白名单（纯逻辑，可脱离 Android 单测）。
 * 空白名单表示不限制，等价于旧行为；report_back / ask_lead 永远保留。
 */
object TeamMemberToolScope {
    /** 白名单上限（条数） */
    const val MAX_TOOLS = 32

    /** 单个工具名最大长度 */
    const val MAX_NAME_CHARS = 64

    /** 成员永远保留、不受白名单限制的工具 */
    val ALWAYS_ALLOWED = setOf("report_back", "ask_lead")

    /** 规范化：trim、去空、去重、保序 */
    fun normalize(raw: List<String>): List<String> =
        raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 校验白名单；返回 null 表示通过，否则是给人看的一句话 */
    fun validate(raw: List<String>): String? {
        if (raw.size > MAX_TOOLS) return "too many allowed tools (${raw.size} > $MAX_TOOLS)"
        if (raw.any { it.isBlank() }) return "tool name must not be blank"
        val tooLong = raw.map { it.trim().length }.firstOrNull { it > MAX_NAME_CHARS }
        if (tooLong != null) return "tool name too long ($tooLong > $MAX_NAME_CHARS)"
        return null
    }

    /** 白名单为空 → true（不限制）；ALWAYS_ALLOWED 恒真；否则必须精确命中 */
    fun allows(allowed: List<String>, toolName: String): Boolean {
        if (allowed.isEmpty()) return true
        if (toolName in ALWAYS_ALLOWED) return true
        return toolName in allowed
    }

    /** 按白名单过滤一组工具名（保持原顺序） */
    fun filter(allowed: List<String>, toolNames: List<String>): List<String> =
        toolNames.filter { allows(allowed, it) }
}
