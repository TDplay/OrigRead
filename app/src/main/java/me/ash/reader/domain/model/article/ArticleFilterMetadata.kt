package me.ash.reader.domain.model.article

import java.util.Date

/** 过滤记录页面匹配与展示所需的文章字段，不加载正文或改变存储结构。 */
data class ArticleFilterMetadata(
    val id: String,
    val feedId: String,
    val title: String,
    val date: Date,
)
