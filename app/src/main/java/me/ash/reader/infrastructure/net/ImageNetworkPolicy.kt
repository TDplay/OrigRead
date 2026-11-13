package me.ash.reader.infrastructure.net

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.ui.ext.dataStore

/**
 * 图片流量的全局闸门：设置里关闭「移动网络下加载图片」时，计费网络上的图片请求一律失败，
 * 由调用方展示占位/纯文本，避免列表缩略图和正文插图把流量吃光。
 */
@Singleton
class ImageNetworkPolicy @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {

    @Volatile
    private var allowOnMetered: Boolean = false

    init {
        scope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { it[KEY] ?: false }
                .collect { allowOnMetered = it }
        }
    }

    /** 当前是否应该拦截图片请求。 */
    fun shouldBlock(): Boolean = !allowOnMetered && context.isMeteredNetwork()

    private companion object {
        val KEY = booleanPreferencesKey("loadImagesOnMobile")
    }
}
