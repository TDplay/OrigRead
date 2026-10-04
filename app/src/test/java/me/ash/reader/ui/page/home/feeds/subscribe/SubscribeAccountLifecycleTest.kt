package me.ash.reader.ui.page.home.feeds.subscribe

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.service.SubscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/** 固定账户的候选与所有在途操作共同取消，不以只保存最后一个 Job 通过。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscribeAccountLifecycleTest : SubscriptionPipelineFixture() {
    /** 订阅和两个分组操作同时阻塞时，切账户应全部取消并废弃候选。 */
    @Test fun switchingAccountCancelsEveryPendingOperation() = runBlocking<Unit> {
        val canceled = AtomicInteger()
        val started = AtomicInteger()
        whenever(rssService.subscribe(any())).doSuspendableAnswer {
            started.incrementAndGet()
            try { awaitCancellation() } finally { canceled.incrementAndGet() }
        }
        whenever(rssRepository.addGroup(eq(null), any(), eq(1))).doSuspendableAnswer {
            started.incrementAndGet()
            try { awaitCancellation() } finally { canceled.incrementAndGet() }
        }
        val vm = configuredViewModel()
        vm.subscribe()
        vm.inputNewGroup("One")
        vm.addNewGroup()
        vm.inputNewGroup("Two")
        vm.addNewGroup()
        testDispatcher.scheduler.runCurrent()
        assertEquals(PENDING_OPERATIONS, started.get())
        selectAccount(Account(2, "Other local", AccountType.Local))
        testDispatcher.scheduler.runCurrent()
        assertEquals(PENDING_OPERATIONS, canceled.get())
        assertEquals(SubscribeState.Hidden, vm.subscribeState.value)
        assertTrue(!vm.subscribeUiState.value.isSubscribing)
    }

    /** 同一候选按入口账户提交；观察流变更后候选不能留下。 */
    @Test fun discoveryCandidateBelongsToTheAccountThatStartedIt() = runBlocking<Unit> {
        val vm = configuredViewModel()
        assertEquals(1, (vm.subscribeState.value as SubscribeState.Configure).accountId)
        selectAccount(Account(2, "Other local", AccountType.Local))
        testDispatcher.scheduler.runCurrent()
        assertEquals(SubscribeState.Hidden, vm.subscribeState.value)
    }

    /** 用真实发现状态流构造候选，避免手工注入越过账户和分组检查。 */
    private suspend fun configuredViewModel(): SubscribeViewModel {
        val url = "https://example.com/feed"
        whenever(rssHelper.discoverFeed(url)).thenReturn(
            me.ash.reader.infrastructure.rss.DiscoveredFeed(feedUrl = url, feed = createFeed(), discoveredFromPage = false),
        )
        val vm = createViewModel()
        vm.showDrawer()
        (vm.subscribeState.value as SubscribeState.Idle).linkState.edit { replace(0, length, url) }
        vm.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private companion object {
        /** 一个订阅加两次分组创建，覆盖原单 Job 引用被反复覆盖的场景。 */
        const val PENDING_OPERATIONS = 3
    }
}
