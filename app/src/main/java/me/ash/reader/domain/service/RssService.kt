package me.ash.reader.domain.service

import com.rometools.rome.feed.synd.SyndFeed
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.infrastructure.di.ApplicationScope

@Singleton
class RssService
@Inject
constructor(
    @ApplicationScope private val coroutineScope: CoroutineScope,
    private val accountService: AccountService,
    private val localRssService: LocalRssService,
    private val feverRssService: FeverRssService,
    private val googleReaderRssService: GoogleReaderRssService,
) {

    private val currentServiceFlow =
        accountService.currentAccountFlow
            .mapNotNull { it }
            .map { it.type.id }
            .distinctUntilChanged()
            .map { get(it) }
            .stateIn(coroutineScope, SharingStarted.Eagerly, localRssService)

    fun get() = get(accountService.getCurrentAccount().type.id)

    /** 后台任务按输入账户选择协议，避免 UI 账户切换改变排队任务归属。 */
    suspend fun forAccount(accountId: Int): AbstractRssRepository {
        val account = requireNotNull(accountService.getAccountById(accountId)) { "账户不存在：$accountId" }
        return get(account.type.id)
    }

    /** 收藏只解析一次文章所属账户，再将同一个账户传给协议服务和持久化入口。 */
    suspend fun markAsStarred(article: Article, isStarred: Boolean) {
        val account = requireNotNull(accountService.getAccountById(article.accountId)) {
            "收藏账户不存在：${article.accountId}"
        }
        get(account.type.id).markAsStarred(account, article.id, isStarred)
    }

    /** 订阅页已经固定目标账户；按它选择服务，实际落库处再校验分组归属。 */
    suspend fun subscribe(request: SubscriptionRequest): SubscriptionResult {
        val service = forAccount(request.accountId)
        require(service.addSubscription) { "此账户不支持添加订阅" }
        if (request.sourceType != me.ash.reader.domain.model.feed.SourceType.RSS || request.rssHub != null) {
            require(service === localRssService) { "网站、JSON 和 RSSHub 配置只支持本地账户" }
        }
        return service.subscribe(request)
    }

    fun flow() = currentServiceFlow


    fun get(accountTypeId: Int) =
        when (accountTypeId) {
            AccountType.Local.id -> localRssService
            AccountType.Fever.id -> feverRssService
            AccountType.GoogleReader.id -> googleReaderRssService
            AccountType.FreshRSS.id -> googleReaderRssService
            AccountType.Inoreader.id -> localRssService
            AccountType.Feedly.id -> localRssService
            else -> localRssService
        }
}
