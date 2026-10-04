package com.example.sonara.playback.player.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-safe singleton cache manager for Sonara Media3 playback.
 * Enforces bounded 8 MB disk-backed cache with LRU eviction.
 */
@OptIn(UnstableApi::class)
object SonaraMediaCache {
    private const val TAG = "SonaraMediaCache"
    const val MAX_CACHE_BYTES = 8 * 1024 * 1024L // 8 MB POC Bound

    @Volatile
    private var instance: SimpleCache? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        return instance ?: synchronized(this) {
            instance ?: createCache(context.applicationContext).also { instance = it }
        }
    }

    private fun createCache(context: Context): SimpleCache {
        val cacheDir = File(context.cacheDir, "sonara_media_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val databaseProvider = StandaloneDatabaseProvider(context)
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
        return SimpleCache(cacheDir, evictor, databaseProvider)
    }

    @Synchronized
    fun release() {
        try {
            instance?.release()
        } catch (_: Exception) {}
        instance = null
    }

    fun isCached(context: Context, key: String, position: Long = 0L, length: Long = 256 * 1024L): Boolean {
        return try {
            val cache = getCache(context)
            cache.isCached(key, position, length)
        } catch (_: Exception) {
            false
        }
    }

    fun getCachedBytes(context: Context, key: String): Long {
        return try {
            val cache = getCache(context)
            cache.getCachedBytes(key, 0L, -1L)
        } catch (_: Exception) {
            0L
        }
    }
}
