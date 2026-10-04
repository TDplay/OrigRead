package me.ash.reader.ui.page.settings.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.service.AccountService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** 验证页面的成功回调取决于实际删除结果。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelDeleteTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)

    @Test fun `refusal keeps the page and success invokes the callback once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val accountService = mock<AccountService>()
            whenever(accountService.getAccounts()).thenReturn(flowOf(emptyList()))
            whenever(accountService.delete(ACCOUNT_ID)).thenReturn(false, true)
            val viewModel = AccountViewModel(
                accountService = accountService,
                rssService = mock(),
                opmlService = mock(),
                ioDispatcher = dispatcher,
                defaultDispatcher = dispatcher,
                mainDispatcher = dispatcher,
                applicationScope = backgroundScope,
            )
            var callbacks = 0

            viewModel.delete(ACCOUNT_ID) { callbacks++ }
            advanceUntilIdle()
            assertEquals(0, callbacks)

            viewModel.delete(ACCOUNT_ID) { callbacks++ }
            advanceUntilIdle()
            assertEquals(1, callbacks)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        // 将旧阻塞路径造成的测试停滞限制在一分钟内。
        const val TEST_TIMEOUT_SECONDS = 60L
        // 同一删除目标依次返回拒绝与成功，用于验证回调边界。
        const val ACCOUNT_ID = 1
    }
}
