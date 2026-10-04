package me.ash.reader.ui.page.home.feeds.subscribe

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.rss.DiscoveredFeed
import me.ash.reader.infrastructure.source.SourceCandidateKind

/** 普通 RSS 成功立即短路；目录辅助只读取当时已完成的结果，保留已有时序策略。 */
internal suspend fun SubscribeViewModel.probeRssSource(options: SubscribeDiscoveryContext): SubscribeDiscoveryResult =
    coroutineScope {
        updateSearchStage(SearchStage.CHECKING_RSS)
        val catalogProbe = options.catalog.preferredProbeUrl(options.feedLink)?.let { url ->
            async { runSuspendCatching { withTimeout(STRUCTURED_PROBE_TIMEOUT_MS) { rssHelper.discoverFeed(url) } } }
        }
        val direct = runSuspendCatching {
            withTimeout(STRUCTURED_PROBE_TIMEOUT_MS) { rssHelper.discoverFeed(options.feedLink) }
        }
        val candidates = listOfNotNull(direct.getOrNull()?.candidate())
        val accepted = SubscribeCandidateSelector.hasConfirmedRss(candidates)
        val result = if (!accepted && catalogProbe?.isCompleted == true) {
            candidates + listOfNotNull(catalogProbe.await().getOrNull()?.candidate(fromCatalog = true))
        } else {
            catalogProbe?.cancel()
            candidates
        }
        SubscribeDiscoveryResult(
            candidates = result.takeIf { SubscribeCandidateSelector.hasConfirmedRss(it) }.orEmpty(),
            error = direct.exceptionOrNull(),
        )
    }

/** HTTP validator 仍随实际成功 Feed 候选传到首次提交。 */
private fun DiscoveredFeed.candidate(fromCatalog: Boolean = false) = SubscribeCandidateProbe(
    feed = feed, feedLink = feedUrl, sourceType = SourceType.RSS,
    kind = if (discoveredFromPage && !fromCatalog) SourceCandidateKind.RSS_DISCOVERED else SourceCandidateKind.RSS_DIRECT,
    etag = etag, lastModified = lastModified,
)
