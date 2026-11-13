package me.ash.reader.infrastructure.di

import android.content.Context
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import me.ash.reader.infrastructure.net.ImageNetworkPolicy
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Provides singleton [ImageLoader] for Coil.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        networkPolicy: ImageNetworkPolicy,
    ): ImageLoader {
        // 计费网络下按设置拦截图片；OkHttp 拦截器只影响真正发出的请求，Coil 自身缓存不受影响。
        val imageHttpClient =
            okHttpClient
                .newBuilder()
                .addInterceptor { chain ->
                    if (networkPolicy.shouldBlock()) {
                        throw IOException("image loading paused on metered network")
                    }
                    chain.proceed(chain.request)
                }
                .build()
        return ImageLoader.Builder(context)
            .okHttpClient(imageHttpClient)
            // This slightly improves scrolling performance
            .dispatcher(Dispatchers.Default)
            .components {
                // Support SVG decoding
                add(SvgDecoder.Factory())
                // Support GIF decoding
                add(
                    if (SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoderDecoder.Factory()
                    } else {
                        GifDecoder.Factory()
                    }
                )
            }
            // Enable disk cache
            .diskCache(
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images"))
                    .maxSizePercent(0.02)
                    .build()
            )
            // Enable memory cache
            .memoryCache(
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            )
            .build()
    }
}
