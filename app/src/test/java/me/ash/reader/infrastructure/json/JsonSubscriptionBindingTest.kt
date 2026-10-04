package me.ash.reader.infrastructure.json

import android.content.Context
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** 验证真实规则文件的绑定、映射恢复与精确补偿，避免只测试内存 mock。 */
class JsonSubscriptionBindingTest {
    private val directory = Files.createTempDirectory("json-binding-test").toFile()
    private lateinit var repository: JsonRuleRepository
    private val rule = JsonRule(
        id = "second-rule", name = "Successful rule", hosts = listOf("page.example.com"),
        endpoint = "https://api.example.com/posts", itemsPath = "$[*]", titlePath = "$.title", linkPath = "$.url",
    )

    /** 仅替换 Android 文件目录，规则序列化、原子写入和身份选择均实际执行。 */
    @Before fun setUp() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(directory)
        repository = JsonRuleRepository(context)
        repository.saveRule(rule)
    }

    /** 释放本例创建的独立规则文件。 */
    @After fun tearDown() { directory.deleteRecursively() }

    /** API 跨域时沿用探测规则；导入规则删除必须报错。 */
    @Test fun crossDomainBindingPreservesRuleIdentityAndExposesDeletion() {
        bind("feed-a")
        assertEquals(rule.id, repository.subscriptionRule("feed-a", rule.endpoint)?.id)
        repository.deleteRule(rule.id)
        assertThrows(IllegalStateException::class.java) {
            repository.subscriptionRule("feed-a", rule.endpoint)
        }
    }

    /** 正常合并保留其他账户绑定，失败补偿必须移除新增项并精确恢复全部原绑定。 */
    @Test fun exactSnapshotRestoreRemovesFailedBindingsAndPreservesOtherAccounts() {
        bind("other-account-feed")
        val snapshot = repository.exportBackup()
        bind("old-feed-id")
        val incoming = repository.exportBackup(setOf("old-feed-id"))
        repository.restoreBackup(incoming, mapOf("old-feed-id" to "new-feed-id"))
        assertEquals(rule.id, repository.subscriptionRule("new-feed-id", rule.endpoint)?.id)
        assertEquals(rule.id, repository.subscriptionRule("other-account-feed", rule.endpoint)?.id)

        repository.restoreBackup(snapshot)
        assertEquals(null, repository.subscriptionRule("new-feed-id", rule.endpoint))
        assertEquals(null, repository.subscriptionRule("old-feed-id", rule.endpoint))
        assertEquals(rule.id, repository.subscriptionRule("other-account-feed", rule.endpoint)?.id)
    }

    /** 规则停用或请求地址改变时，不选择域名下另一条规则。 */
    @Test fun disabledAndChangedEndpointsAreExplicitFailures() {
        bind("feed-a")
        repository.setEnabled(rule.id, false)
        assertThrows(IllegalArgumentException::class.java) {
            repository.subscriptionRule("feed-a", rule.endpoint)
        }
        repository.saveRule(rule.copy(endpoint = "https://api.example.com/changed"))
        assertThrows(IllegalArgumentException::class.java) {
            repository.subscriptionRule("feed-a", rule.endpoint)
        }
    }

    /** 保存与真实预览相同的规则和请求 endpoint。 */
    private fun bind(feedId: String) {
        repository.bindSubscription(feedId, "https://page.example.com/news", JsonSourceProbeResult(
            rule, rule.endpoint, SyndFeedImpl(), importedRule = true,
        ))
    }
}
