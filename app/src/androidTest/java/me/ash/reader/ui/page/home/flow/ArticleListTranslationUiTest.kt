package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import org.junit.Rule
import org.junit.Test

/** 只渲染卡片，不访问账号、数据库、网络或真实翻译服务。 */
class ArticleListTranslationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun shortProgressBackgroundWrapsItsContent() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(360.dp).testTag("translation_status_container")) {
                    ListTranslationStatus(ListTranslationState(isLoading = true, completed = 8, total = 20))
                }
            }
        }
        val container = compose.onNodeWithTag("translation_status_container").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("list_translation_status").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("短进度背景不应铺满底栏", status.width < container.width * 0.85f)
    }


    @Test fun statusOnlyAppearsWhileWorkingAndHasItsOwnSurface() {
        val state = mutableStateOf(ListTranslationState())
        compose.setContent { MaterialTheme { ListTranslationStatus(state.value) } }
        compose.onNodeWithTag("list_translation_status").assertDoesNotExist()
        compose.runOnIdle { state.value = ListTranslationState(isLoading = true, total = 20) }
        compose.onNodeWithTag("list_translation_status").assertIsDisplayed()
        compose.runOnIdle { state.value = ListTranslationState(completed = 20, total = 20) }
        compose.onNodeWithTag("list_translation_status").assertDoesNotExist()
        compose.runOnIdle { state.value = ListTranslationState(stopped = true, completed = 8, total = 20) }
        compose.onNodeWithTag("list_translation_status").assertDoesNotExist()
    }

    @Test fun failedTranslationCanBeDismissedWithoutRemovingResults() {
        val state = ListTranslationState(completed = 8, total = 20, errorMessage = "Service unavailable")
        compose.setContent { MaterialTheme { ListTranslationStatus(state) } }
        compose.onNodeWithTag("list_translation_status").assertIsDisplayed()
        val dismiss = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cancel)
        compose.onNodeWithContentDescription(dismiss).performClick()
        compose.onNodeWithTag("list_translation_status").assertDoesNotExist()
        org.junit.Assert.assertEquals(8, state.completed)
    }

    @Test fun bilingualHasSeparateOriginalAndTranslatedText() {
        show(bilingual = true)
        listOf("Original title", "Original preview", "译文标题", "译文摘要").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun replacementOnlyChangesDisplayedCopy() {
        show(bilingual = false)
        compose.onNodeWithText("译文标题").assertIsDisplayed()
        compose.onNodeWithText("译文摘要").assertIsDisplayed()
        compose.onNodeWithText("Original title").assertDoesNotExist()
        compose.onNodeWithText("Original preview").assertDoesNotExist()
    }

    private fun show(bilingual: Boolean) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFlowArticleListDesc provides FlowArticleListDescPreference.SHORT) {
                    ArticleItem(title = "Original title", shortDescription = "Original preview",
                        translatedTitle = "译文标题", translatedDescription = "译文摘要", bilingual = bilingual)
                }
            }
        }
    }
}
