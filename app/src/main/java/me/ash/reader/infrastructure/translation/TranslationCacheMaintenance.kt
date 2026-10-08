package me.ash.reader.infrastructure.translation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.InvalidationTracker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import timber.log.Timber

/** 清理不是翻译任务：无网络约束、无 Provider、无生成或续翻入口。 */
@Singleton
class TranslationCacheMaintenance @Inject constructor(
    private val database: AndroidDatabase,
    private val cache: TranslationCache,
    private val workManager: WorkManager,
    @ApplicationScope private val scope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private val observer = object : InvalidationTracker.Observer("article", "feed", "account") {
        override fun onInvalidated(tables: Set<String>) { changes.trySend(Unit) }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch(ioDispatcher) {
            database.invalidationTracker.addObserver(observer)
            safely { cache.maintain() }
            for (event in changes) {
                // 合并一次事务及短时间内的重复通知；只查询已缓存文章的身份，不全盘扫描。
                delay(750)
                while (changes.tryReceive().isSuccess) { /* conflated changes */ }
                safely { cache.removeMissingOwners() }
            }
        }
        workManager.enqueueUniquePeriodicWork("translation-cache-retention",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TranslationCacheCleanupWorker>(1, TimeUnit.DAYS).build())
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Timber.w(error, "Translation cache cleanup deferred") }
    }
}

@HiltWorker
class TranslationCacheCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val cache: TranslationCache,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        cache.maintain()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        Timber.w(error, "Translation cache retention cleanup failed")
        Result.retry()
    }
}
