package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩指令模板契约：[DEFAULT_COMPRESSION_PROMPT] 与 buildCompressPrompt(additionalPrompt, locale, template)。
 *
 * 占位符说明：源码里模板用的是 `{locale}` 槽位，`中文 (简体中文,中国大陆)` 是 zh-CN 下
 * Locale.getDefault().displayName 的运行时值（调用点就以它作 locale 传入），默认模板里没有该字面量，
 * 因此「替换」的期望按 `{locale}` 手写，「中文 (简体中文,中国大陆)」只在渲染结果里断言。
 *
 * 未覆盖（本文件不涉及）：
 * 1. 「模型设置 → 提示词」编辑入口 SettingModelPromptPage 的默认值/重置行为（需要 Compose 运行时）。
 * 2. Settings.compressionPrompt 经 DataStore 的持久化读写（由 SettingsFieldsTest 覆盖键映射）。
 * 3. 真实调用点的 locale 取值（ChatRequestFactory / ChatService 传 Locale.getDefault().displayName）。
 *
 * 状态：仅编写，未运行 Gradle/JUnit（executed:false）。
 */
class CompressPromptTest {

    private val headings = listOf(
        "User Goals",
        "Key Context",
        "Decisions and Conclusions",
        "Tool Results and Artifacts",
        "Errors and Resolutions",
        "Open Items",
        "Current State",
    )

    @Test
    fun `default template carries the seven section headings and the locale slot`() {
        headings.forEach { heading ->
            assertTrue("missing heading: ## $heading", DEFAULT_COMPRESSION_PROMPT.contains("## $heading"))
        }
        assertTrue(DEFAULT_COMPRESSION_PROMPT.contains("{locale}"))
        assertTrue(DEFAULT_COMPRESSION_PROMPT.contains("Stop the current task"))
    }

    @Test
    fun `rendered default carries the headings and substitutes the device locale name`() {
        val rendered = buildCompressPrompt(additionalPrompt = "", locale = "中文 (简体中文,中国大陆)")
        headings.forEach { heading ->
            assertTrue("missing heading after render: ## $heading", rendered.contains("## $heading"))
        }
        assertTrue(rendered.contains("中文 (简体中文,中国大陆)"))
        assertFalse(rendered.contains("{locale}"))
        assertFalse(rendered.contains("Additional instructions"))
    }

    @Test
    fun `locale slot is replaced wherever it appears`() {
        val template = "summarize in {locale}; answer in {locale}"
        assertEquals("summarize in xx-XX; answer in xx-XX", buildCompressPrompt("", "xx-XX", template))
    }

    @Test
    fun `template without the locale slot is kept as is`() {
        val template = "Custom template without any placeholder"
        assertEquals(template, buildCompressPrompt("", "xx-XX", template))
        // 尾部空白仍被裁掉，但正文一个字都不改
        assertEquals("Custom template", buildCompressPrompt("", "xx-XX", "Custom template  \n"))
    }

    @Test
    fun `blank template falls back to the default rendering`() {
        val expected = buildCompressPrompt("", "xx-XX")
        assertEquals(expected, buildCompressPrompt("", "xx-XX", template = ""))
        assertEquals(expected, buildCompressPrompt("", "xx-XX", template = "   \n\t  "))
        assertTrue(expected.startsWith("Stop the current task"))
        assertTrue(expected.contains("use xx-XX"))
    }

    @Test
    fun `additional prompt is appended after a blank line`() {
        assertEquals(
            "BASE\n\nAdditional instructions from user: keep file paths",
            buildCompressPrompt("keep file paths", "xx-XX", "BASE"),
        )
        // 默认模板同样适用
        val appended = buildCompressPrompt("保留路径", "xx-XX")
        assertTrue(appended.startsWith("Stop the current task"))
        assertTrue(appended.endsWith("Additional instructions from user: 保留路径"))
    }

    @Test
    fun `blank additional prompt appends nothing`() {
        val out = buildCompressPrompt("   \n\t ", "xx-XX", "BASE")
        assertEquals("BASE", out)
        assertFalse(out.contains("Additional instructions"))
    }

    @Test
    fun `custom template replaces the default entirely`() {
        val out = buildCompressPrompt("", "xx-XX", "summarize {locale} only")
        assertEquals("summarize xx-XX only", out)
        assertFalse(out.contains("## User Goals"))
        assertFalse(out.contains("Stop the current task"))
    }
}
