package top.lovepikachu.XiaoHeiHook.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import java.io.File

object AppRepository {
    private const val TAG = "XiaoHeiHook-AppRepo"
    private const val ICON_SIZE_PX = 96
    private const val ICON_CACHE_DIR = "app_icon_cache_v1"
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
            val iconCacheDir = File(appContext.noBackupFilesDir, ICON_CACHE_DIR).apply { mkdirs() }
            val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }

            val seenPackages = HashSet<String>()
            apps
                .asSequence()
                .filter { it.packageName != appContext.packageName }
                .distinctBy { it.packageName }
                .map { info ->
                    seenPackages.add(info.packageName)
                    val lastUpdateTime = pm.lastUpdateTimeOf(info.packageName)
                    InstalledAppInfo(
                        label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { info.packageName },
                        packageName = info.packageName,
                        icon = loadIconFromDiskOrSystem(pm, info, iconCacheDir, lastUpdateTime),
                        isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy { it.label.lowercase() })
                .toList()
                .also {
                    cachedApps = it
                    cleanupIconCache(iconCacheDir, seenPackages)
                }
        }
    }

    private fun loadIconFromDiskOrSystem(
        pm: PackageManager,
        info: ApplicationInfo,
        iconCacheDir: File,
        lastUpdateTime: Long
    ): Bitmap? {
        val cacheFile = iconCacheFile(iconCacheDir, info.packageName, lastUpdateTime)
        decodeIcon(cacheFile)?.let { return it }

        return runCatching {
            // 删除同包旧版本图标，只保留当前 lastUpdateTime 对应的缓存。
            deletePackageIconCache(iconCacheDir, info.packageName, except = cacheFile)
            val bitmap = info.loadIcon(pm).toBitmap(ICON_SIZE_PX)
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }.onFailure { error ->
                Log.w(TAG, "save icon cache failed: ${info.packageName}", error)
            }
            bitmap
        }.onFailure { error ->
            Log.w(TAG, "load icon failed: ${info.packageName}", error)
        }.getOrNull()
    }

    private fun decodeIcon(file: File): Bitmap? {
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
            ?.takeUnless { it.isRecycled }
    }

    private fun iconCacheFile(dir: File, packageName: String, lastUpdateTime: Long): File {
        return File(dir, "${safeFileName(packageName)}__$lastUpdateTime.png")
    }

    private fun deletePackageIconCache(dir: File, packageName: String, except: File? = null) {
        val prefix = "${safeFileName(packageName)}__"
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix) && file != except) {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupIconCache(dir: File, installedPackages: Set<String>) {
        val installedPrefixes = installedPackages.asSequence()
            .map { "${safeFileName(it)}__" }
            .toSet()
        dir.listFiles()?.forEach { file ->
            val stillInstalled = installedPrefixes.any { prefix -> file.name.startsWith(prefix) }
            if (!stillInstalled) {
                runCatching { file.delete() }
            }
        }
    }

    private fun safeFileName(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun PackageManager.lastUpdateTimeOf(packageName: String): Long {
        return runCatching {
            val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                getPackageInfo(packageName, 0)
            }
            info.lastUpdateTime
        }.getOrDefault(0L)
    }

    private fun Drawable.toBitmap(sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, sizePx, sizePx)
        draw(canvas)
        return bitmap
    }
}
