package app.toolkit.netguard

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用图标缓存：按包名缓存已解码的小图，避免滚动时反复解码 drawable。
 *
 * 图标解码放到 IO 线程，组合期只读取缓存。
 */
object AppIconCache {

    private const val ICON_SIZE_PX = 96
    private const val MAX_ENTRIES = 240

    private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

    /**
     * 同步读缓存，未命中返回 null。
     * 供组合期取初始值用：命中时首帧就能画出真实图标，不会先闪一次占位图。
     */
    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            // 二次检查只是缩小并发未命中时的重复解码窗口；LruCache 本身线程安全，无需额外加锁。
            cache.get(packageName) ?: runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX)
                    .asImageBitmap()
            }.getOrNull()?.also { cache.put(packageName, it) }
        }
    }

    fun clear() = cache.evictAll()
}
