package top.lovepikachu.XiaoHeiHook.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build

object AppRepository {
    private const val ICON_SIZE_PX = 96
    private val cacheLock = Any()

    @Volatile
    private var cachedApps: List<InstalledAppInfo>? = null

    fun cachedInstalledApps(): List<InstalledAppInfo>? = cachedApps

    fun clearCache() {
        synchronized(cacheLock) {
            cachedApps = null
        }
    }

    fun loadInstalledApps(context: Context, forceRefresh: Boolean = false): List<InstalledAppInfo> {
        if (!forceRefresh) {
            cachedApps?.let { return it }
        }

        return synchronized(cacheLock) {
            if (!forceRefresh) {
                cachedApps?.let { return@synchronized it }
            }

            val appContext = context.applicationContext
            val pm = appContext.packageManager
            val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }

            apps
                .asSequence()
                .filter { it.packageName != appContext.packageName }
                .distinctBy { it.packageName }
                .map { info ->
                    InstalledAppInfo(
                        label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { info.packageName },
                        packageName = info.packageName,
                        icon = runCatching { info.loadIcon(pm).toBitmap(ICON_SIZE_PX) }.getOrNull(),
                        isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy { it.label.lowercase() })
                .toList()
                .also { cachedApps = it }
        }
    }

    private fun Drawable.toBitmap(sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, sizePx, sizePx)
        draw(canvas)
        return bitmap
    }
}
