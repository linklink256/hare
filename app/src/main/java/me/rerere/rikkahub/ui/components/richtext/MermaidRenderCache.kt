package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 已渲染 Mermaid 的缓存：SVG + 适配后的高度。
 *
 * 图是纯函数：同一段源码 + 同一套配色渲染结果相同，滚动进出视口时没必要每次重跑 mermaid.js；
 * 高度也一起缓存，重新进入会话时首帧就按最终高度排版，不会先占位 200dp 再长高把列表顶一下。
 * 内存里留最近若干张，磁盘按内容哈希存文件（重启后仍可用），超出上限按修改时间淘汰。
 */
internal object MermaidRenderCache {
    private const val MEMORY_ENTRIES = 24
    private const val DISK_ENTRIES = 128
    private const val DISK_DIR = "mermaid"

    internal data class Entry(val svg: String, val heightDp: Int)

    private val memory = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MEMORY_ENTRIES
    }

    fun key(code: String, themeSignature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((themeSignature + "\u0000" + code).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(context: Context, key: String): Entry? {
        synchronized(memory) { memory[key] }?.let { return it }
        val svgFile = fileFor(context, key, "svg")
        if (!svgFile.isFile) return null
        val svg = runCatching { svgFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val height = runCatching { fileFor(context, key, "h").readText().trim().toInt() }.getOrDefault(0)
        val entry = Entry(svg, height)
        synchronized(memory) { memory[key] = entry }
        return entry
    }

    fun putSvg(context: Context, key: String, svg: String) {
        if (svg.isBlank()) return
        val height = synchronized(memory) { memory[key]?.heightDp } ?: 0
        synchronized(memory) { memory[key] = Entry(svg, height) }
        runCatching {
            fileFor(context, key, "svg").writeText(svg)
            trim(dir(context))
        }
    }

    fun putHeight(context: Context, key: String, heightDp: Int) {
        if (heightDp <= 0) return
        synchronized(memory) {
            val svg = memory[key]?.svg ?: runCatching { fileFor(context, key, "svg").readText() }.getOrNull()
            if (svg != null) memory[key] = Entry(svg, heightDp)
        }
        runCatching { fileFor(context, key, "h").writeText(heightDp.toString()) }
    }

    private fun dir(context: Context): File = File(context.cacheDir, DISK_DIR).apply { mkdirs() }

    private fun fileFor(context: Context, key: String, extension: String): File =
        File(dir(context), "$key.$extension")

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".svg") } ?: return
        if (files.size <= DISK_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - DISK_ENTRIES)
            .forEach { svg ->
                runCatching { svg.delete() }
                runCatching { File(svg.parentFile, svg.name.removeSuffix(".svg") + ".h").delete() }
            }
    }
}
