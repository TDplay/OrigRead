package me.ash.reader.domain.model.article

/** 网站范围清理只需要身份、链接和收藏保护，不加载历史正文。 */
data class ArticleCleanupMetadata(val id: String, val link: String, val isStarred: Boolean)
