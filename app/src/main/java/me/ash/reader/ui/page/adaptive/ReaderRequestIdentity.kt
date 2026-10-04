package me.ash.reader.ui.page.adaptive

import me.ash.reader.domain.model.article.Article

/** 全文结果同时绑定文章、账户和请求代次，包含同一文章的重复请求。 */
internal data class ReaderRequestIdentity(val articleId: String, val accountId: Int, val serial: Long)

/** 所有正文模式切换均使先前任务失效，取消后的网络结果也无法发布。 */
internal fun ArticleListReaderViewModel.invalidateFullContentRequest() {
    readerRequestSerial++
    fullContentJob?.cancel()
    fullContentJob = null
}

/** 导航或退出时取消初始化和全文任务，避免旧 DAO 结果重新打开已离开的文章。 */
internal fun ArticleListReaderViewModel.cancelReaderRequests() {
    initializationJob?.cancel()
    initializationJob = null
    invalidateFullContentRequest()
}

/** 每次正文重取创建新代次，不允许同一文章的旧请求覆盖新请求。 */
internal fun ArticleListReaderViewModel.beginFullContentRequest(article: Article): ReaderRequestIdentity {
    invalidateFullContentRequest()
    return ReaderRequestIdentity(article.id, article.accountId, readerRequestSerial)
}

/** 身份核对覆盖成功、失败、loading 及相关 AI 状态重置。 */
internal fun ArticleListReaderViewModel.isCurrentRequest(identity: ReaderRequestIdentity): Boolean =
    readerRequestSerial == identity.serial && currentArticle?.id == identity.articleId &&
        currentArticle?.accountId == identity.accountId && _readerState.value.articleId == identity.articleId
