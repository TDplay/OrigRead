package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.ash.reader.R
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch
import me.ash.reader.infrastructure.discovery.FeedDiscoveryCatalog
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubInputParser
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.source.SourceInputHint
import me.ash.reader.infrastructure.source.sourceInputHint
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.ui.ext.formatUrl

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SearchStage {
    /** 直接 Feed 和网页 alternate 发现。 */
    CHECKING_RSS,
    /** RSSHub 路由及实例的真实预览。 */
    CHECKING_RSSHUB,
    /** JSON API 与内嵌框架数据。 */
    CHECKING_JSON,
    /** 静态网站列表识别。 */
    CHECKING_WEBSITE,
    /** WebView 动态网站列表识别。 */
    CHECKING_DYNAMIC_WEBSITE,
}

data class SubscribeUiState(
    val newGroupDialogVisible: Boolean = false,
    val newGroupContent: String = "",
    val newName: String = "",
    val renameDialogVisible: Boolean = false,
    val isSubscribing: Boolean = false,
    val operationMessage: String? = null,
)

sealed interface SubscribeState {
    object Hidden : SubscribeState

    sealed interface Visible

    sealed interface Input : SubscribeState, Visible {
        val linkState: TextFieldState
    }

    data class Idle(
        override val linkState: TextFieldState = TextFieldState(),
        val importFromOpmlEnabled: Boolean = false,
        val errorMessage: String? = null,
        val rssHubResults: List<me.ash.reader.infrastructure.rsshub.RssHubProbeResult> = emptyList(),
        val catalogMatches: List<FeedCatalogEntry> = emptyList(),
        val catalogMatchCount: Int = catalogMatches.size,
    ) : SubscribeState, Input

    data class Fetching(
        override val linkState: TextFieldState,
        val job: Job,
        val stage: SearchStage,
    ) :
        SubscribeState, Input

    data class Configure(
        val searchedFeed: SyndFeed,
        val feedLink: String,
        val sourcePageUrl: String,
        val groups: List<Group> = emptyList(),
        val notification: Boolean = false,
        val fullContent: Boolean = false,
        val browser: Boolean = false,
        val selectedGroupId: String,
        val accountId: Int? = null,
        val submissionMessage: String? = null,
        val sourceType: SourceType = SourceType.RSS,
        val sourceNotice: String? = null,
        val candidates: List<SubscribeSourceCandidate> = emptyList(),
        val selectedCandidateId: String? = null,
        val selectedCandidateIds: Set<String> = selectedCandidateId?.let(::setOf) ?: emptySet(),
        val dynamicRendering: Boolean = false,
        val rssHubResults: List<me.ash.reader.infrastructure.rsshub.RssHubProbeResult> = emptyList(),
        val catalogMatches: List<FeedCatalogEntry> = emptyList(),
        val catalogMatchCount: Int = catalogMatches.size,
    ) : SubscribeState, Visible
}

/**
 * suspend 探测允许单阶段 timeout 作为普通失败继续 fallback，但用户/生命周期主动取消必须立即向上传播。
 */
internal suspend inline fun <T> runSuspendCatching(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
