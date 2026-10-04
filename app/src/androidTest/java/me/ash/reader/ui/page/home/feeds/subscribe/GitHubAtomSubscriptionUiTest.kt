package me.ash.reader.ui.page.home.feeds.subscribe

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import kotlinx.coroutines.runBlocking
import me.ash.reader.R
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.android.AndroidApp
import me.ash.reader.infrastructure.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Issue #2：真实 MainActivity、GitHub Atom、数据库和首次刷新共同验证普通版及 X 版。 */
@RunWith(AndroidJUnit4::class)
class GitHubAtomSubscriptionUiTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 保留已有账户和订阅，仅清理本次新建的 ReadYou 测试来源。 */
    @Test fun reportedAtomCanBeConfirmedPersistedAndRefreshed() {
        grantNotificationPermission()
        val app = context.applicationContext as AndroidApp
        val account = app.accountService.getCurrentAccount()
        val accountId = requireNotNull(account.id)
        assertEquals("设备验收需要本地账户", AccountType.Local.id, account.type.id)
        runBlocking {
            check(app.androidDatabase.feedDao().queryByLink(accountId, REPORTED_URL).isEmpty()) {
                "设备已有该订阅，保留现有数据；请在未订阅该来源的测试账户运行此用例"
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            subscribeThroughUi()
            verifyPersistenceAndRefresh(app, accountId)
        } finally {
            // 入口已确认不存在同地址订阅，所以这里只删除本次用例创建的来源与文章。
            runBlocking {
                app.androidDatabase.feedDao().queryByLink(accountId, REPORTED_URL).forEach {
                    app.localRssService.deleteFeed(it, false)
                }
            }
            scenario.close()
        }
    }

    /** 不替换网络或解析依赖，搜索后应直接出现原生 RSS/Atom 的配置摘要。 */
    private fun subscribeThroughUi() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.subscribe)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement(REPORTED_URL)
        composeRule.onNodeWithText(context.getString(R.string.search)).performClick()
        val confirm = context.getString(R.string.confirm_subscribe)
        composeRule.waitUntil(timeoutMillis = DISCOVERY_WAIT_MS) {
            composeRule.onAllNodesWithText(confirm).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.subscription_native_rss_confirmed)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.subscription_feed_address, REPORTED_URL)).assertIsDisplayed()
        assertDialogOptionVisible(R.string.allow_notification)
        assertDialogOptionVisible(R.string.add_to_group)
        composeRule.onNodeWithText(context.getString(R.string.source_catalog_matches_title)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.subscription_other_catalog_unverified)).assertDoesNotExist()
        composeRule.onNodeWithText(confirm).performClick()
        composeRule.waitUntil(timeoutMillis = SUBMISSION_WAIT_MS) {
            composeRule.onAllNodesWithText(confirm).fetchSemanticsNodes().isEmpty()
        }
    }

    /** 同名设置也存在于底层页面，只检查本次确认订阅弹窗中的真实配置控件。 */
    private fun assertDialogOptionVisible(label: Int) {
        composeRule.onNode(hasText(context.getString(label)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    /** 检查保存地址、来源类型和文章，首次真实刷新不能把原生 Atom 改成其他仓库或 RSSHub。 */
    private fun verifyPersistenceAndRefresh(app: AndroidApp, accountId: Int) = runBlocking {
        val feedDao = app.androidDatabase.feedDao()
        val articleDao = app.androidDatabase.articleDao()
        val stored = feedDao.queryByLink(accountId, REPORTED_URL).single()
        assertEquals(SourceType.RSS, stored.sourceType)
        val before = articleDao.queryAllByFeedId(accountId, stored.id).map { it.id }.toSet()
        assertTrue("确认订阅后应保存已解析文章", before.isNotEmpty())
        val refresh = app.localRssService.sync(accountId = accountId, feedId = stored.id, groupId = null)
        // 条件请求未变更也是成功刷新，业务结果携带来源计数，不能要求 outputData 为空。
        assertTrue("首次刷新必须成功：$refresh", refresh is ListenableWorker.Result.Success)
        val refreshed = requireNotNull(feedDao.queryById(stored.id))
        assertEquals(REPORTED_URL, refreshed.url)
        assertEquals(SourceType.RSS, refreshed.sourceType)
        val after = articleDao.queryAllByFeedId(accountId, stored.id).map { it.id }.toSet()
        assertTrue("首次刷新应保留已解析文章", after.containsAll(before))
    }

    /** 通知权限由测试环境授予，避免系统弹窗干扰订阅配置的 UI 断言。 */
    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private companion object {
        /** Issue 作者反馈的原始 GitHub Atom 地址，禁止替换成目录来源。 */
        const val REPORTED_URL = "https://github.com/ReadYouApp/ReadYou/releases.atom"
        /** 覆盖既有真实探测阶段，超时明确失败，不重试或改用伪造来源。 */
        const val DISCOVERY_WAIT_MS = 60_000L
        /** 覆盖本地持久化完成后的配置关闭。 */
        const val SUBMISSION_WAIT_MS = 20_000L
    }
}
