package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalLoadImagesOnMobile =
    compositionLocalOf { LoadImagesOnMobilePreference.default }

/** 是否允许在计费网络（移动数据 / 收费热点）下加载图片。默认关闭以外节省流量。 */
sealed class LoadImagesOnMobilePreference(val value: Boolean) : Preference() {

    object ON : LoadImagesOnMobilePreference(true)
    object OFF : LoadImagesOnMobilePreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(
                DataStoreKey.loadImagesOnMobile,
                value
            )
        }
    }

    companion object {

        // 默认不在移动网络下加载图片：RSS 图片通常是流量主要来源。
        val default: LoadImagesOnMobilePreference
            get() = OFF

        val values: List<LoadImagesOnMobilePreference>
            get() = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (
                preferences[
                    DataStoreKey.keys[DataStoreKey.loadImagesOnMobile]?.key as Preferences.Key<Boolean>
                ]
            ) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}

operator fun LoadImagesOnMobilePreference.not(): LoadImagesOnMobilePreference =
    when (value) {
        true -> LoadImagesOnMobilePreference.OFF
        false -> LoadImagesOnMobilePreference.ON
    }
