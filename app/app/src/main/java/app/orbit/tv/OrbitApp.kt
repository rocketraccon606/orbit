package app.orbit.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.util.DebugLogger

class OrbitApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader {
        val builder = ImageLoader.Builder(this)
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("logos"))
                    .maxSizeBytes(40L * 1024 * 1024)
                    .build()
            }
            .okHttpClient { container.okHttp }
        if (BuildConfig.DEBUG) builder.logger(DebugLogger())
        return builder.build()
    }
}
