package me.ash.reader.ui.page.settings.search

import android.content.Context
import me.ash.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class SettingsSearchRegistryTest {

    @Test
    fun `all registered setting items have unique IDs`() {
        val ids = SettingsSearchRegistry.allItems.map { it.id }
        assertEquals("All search item IDs must be unique", ids.distinct().size, ids.size)
    }

    @Test
    fun `all registered setting items have non-empty valid breadcrumbs and title`() {
        SettingsSearchRegistry.allItems.forEach { item ->
            assertTrue("Title res must be valid for ${item.id}", item.titleRes != 0)
            assertTrue("Breadcrumbs must not be empty for ${item.id}", item.breadcrumbsRes.isNotEmpty())
            assertTrue("Breadcrumbs res must be valid for ${item.id}", item.breadcrumbsRes.all { it != 0 })
        }
    }

    @Test
    fun `empty or blank queries return no results`() {
        val context = mock<Context>()
        assertTrue(SettingsSearchRegistry.search(context, "").isEmpty())
        assertTrue(SettingsSearchRegistry.search(context, "   ").isEmpty())
    }

    @Test
    fun `search matches by title and keywords and ranks exact match highest`() {
        val stringMap = mapOf(
            R.string.settings to "Settings",
            R.string.ai_settings to "AI Reading",
            R.string.ai_settings_desc to "Configure AI models and API keys",
            R.string.translation_settings to "Translation",
            R.string.translation_settings_desc to "Configure translation engine",
            R.string.dark_theme to "Dark Theme",
        )
        val context = mock<Context> {
            on { getString(any()) } doAnswer { invocation ->
                val resId = invocation.getArgument<Int>(0)
                stringMap[resId] ?: "Res_$resId"
            }
        }

        // 标题完全匹配获得最高得分 100
        val exactResults = SettingsSearchRegistry.search(context, "AI Reading")
        assertFalse(exactResults.isEmpty())
        assertEquals("ai_settings", exactResults.first().item.id)
        assertEquals(100, exactResults.first().score)

        // 关键词匹配（如 DeepSeek）可检索出 ai_settings
        val keywordResults = SettingsSearchRegistry.search(context, "DeepSeek")
        assertFalse(keywordResults.isEmpty())
        assertTrue(keywordResults.any { it.item.id == "ai_settings" })

        // 描述内容匹配获得分值 30
        val descResults = SettingsSearchRegistry.search(context, "API keys")
        assertFalse(descResults.isEmpty())
        assertEquals("ai_settings", descResults.first().item.id)
        assertEquals(30, descResults.first().score)
    }

    @Test
    fun `search results only contain available items`() {
        val context = mock<Context> {
            on { getString(any()) } doAnswer { invocation ->
                "Res_${invocation.getArgument<Int>(0)}"
            }
        }
        val results = SettingsSearchRegistry.search(context, "Res_")
        assertTrue(results.all { it.item.isAvailable() })
    }
}
