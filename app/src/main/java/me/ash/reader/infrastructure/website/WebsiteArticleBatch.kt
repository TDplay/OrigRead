package me.ash.reader.infrastructure.website

import me.ash.reader.domain.model.article.Article

/** 一次抓取的文章和实际规则一起返回，保留既有只读列表调用契约。 */
class WebsiteArticleBatch(
    private val articles: List<Article>,
    val selectedRule: WebsiteRule,
) : AbstractList<Article>() {
    override val size: Int get() = articles.size

    override fun get(index: Int): Article = articles[index]

    /** 自动和非范围规则不触发历史查询；范围清理始终使用同一批次的规则快照。 */
    val cleanupRule: WebsiteRule?
        get() = selectedRule.takeIf {
            !it.id.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX) &&
                it.cleanupMode == WebsiteCleanupMode.URL_ID_RANGE
        }
}
