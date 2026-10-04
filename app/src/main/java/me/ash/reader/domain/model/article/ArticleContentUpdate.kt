package me.ash.reader.domain.model.article

import java.util.Date

/** 抓取只拥有这些内容字段，部分更新不能写回阅读状态、首次入库时间或全文缓存。 */
data class ArticleContentUpdate(
    val id: String,
    val date: Date,
    val title: String,
    val author: String?,
    val rawDescription: String,
    val shortDescription: String,
    val img: String?,
    val link: String,
)

/** 将已合并的内容投影成 Room 部分实体，阻止旧快照覆盖用户并发操作。 */
fun Article.contentUpdate() = ArticleContentUpdate(
    id, date, title, author, rawDescription, shortDescription, img, link,
)
