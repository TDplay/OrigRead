package me.ash.reader.infrastructure.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub 的域名属于多个仓库，目录建议必须保留完整 owner/repo 范围。 */
class FeedCatalogGithubMatchTest {
    private val readYou = entry("ReadYouApp/ReadYou", "commits/main.atom")
    private val bun = entry("oven-sh/bun", "releases.atom")
    private val biome = entry("biomejs/biome", "releases.atom")
    private val similarName = entry("ReadYouApp/ReadYou-Other", "releases.atom")
    private val index = FeedCatalogIndex(listOf(bun, biome, similarName, readYou))

    /** 同仓库的其他 Feed 只是建议，不能自动替换用户输入的 releases.atom。 */
    @Test fun suggestionsStayWithinTheExactRepository() {
        val input = "https://github.com/ReadYouApp/ReadYou/releases.atom"
        val match = index.matchUrl(input)
        assertEquals(listOf(readYou), match.suggestions)
        assertEquals(1, match.totalSuggestions)
        assertNull(match.preferred)
        assertNull(match.preferredProbeUrl(input))
    }

    /** GitHub 仓库标识不区分大小写，查询和 fragment 不属于仓库身份。 */
    @Test fun repositoryIdentityIgnoresCaseAndQuery() {
        val match = index.matchUrl("https://www.github.com/READYOUAPP/READYOU/releases.atom?x=1#latest")
        assertEquals(listOf(readYou), match.suggestions)
    }

    /** 缺少仓库或仓库未收录时，不能把整个 GitHub 目录作为相关建议。 */
    @Test fun incompleteOrUnknownRepositoriesHaveNoHostSuggestions() {
        listOf("https://github.com", "https://github.com/ReadYouApp/", "https://github.com/unknown/repository")
            .forEach { input ->
                val match = index.matchUrl(input)
                assertTrue(input, match.suggestions.isEmpty())
                assertEquals(input, 0, match.totalSuggestions)
                assertNull(match.preferred)
            }
    }

    /** 前面的 Feed 精确命中及唯一站点命中仍保持原来的验证入口。 */
    @Test fun exactFeedAndSiteMatchesRemainAvailable() {
        val feedMatch = index.matchUrl(readYou.feedUrl)
        assertEquals(readYou, feedMatch.preferred)
        assertNull(feedMatch.preferredProbeUrl(readYou.feedUrl))
        val siteMatch = index.matchUrl(requireNotNull(readYou.siteUrl))
        assertEquals(readYou, siteMatch.preferred)
        assertEquals(readYou.feedUrl, siteMatch.preferredProbeUrl(requireNotNull(readYou.siteUrl)))
    }

    /** 仓库级 URL 建议不限制“发现来源”页面的全目录文本搜索。 */
    @Test fun catalogSearchStillIncludesAllGithubRepositories() {
        assertEquals(4, index.search("github.com").size)
    }

    /** 目录项的站点和 Feed 指向同一仓库，便于独立检验 URL 范围。 */
    private fun entry(repository: String, feedPath: String) = FeedCatalogEntry(
        id = repository, name = repository, feedUrl = "https://github.com/$repository/$feedPath",
        siteUrl = "https://github.com/$repository",
    )
}
