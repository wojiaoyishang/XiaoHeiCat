package top.lovepikachu.XiaoHeiHook.composables

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libxposed.service.XposedService
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication

@Composable
fun XposedSettingItem(
    icon: ImageVector? = null,
    title: String,
    subtitle: String,
    statusKey: String,
    packageName: String
) {
    val moduleState by XiaoHeiApplication.moduleState.collectAsStateWithLifecycle()

    val prefs = remember { XiaoHeiApplication.remotePreferences }

    var status by remember {
        mutableStateOf(prefs?.getBoolean(statusKey, false) ?: false)
    }

    LaunchedEffect(status) {
        prefs?.edit()?.putBoolean(statusKey, status)?.apply()
    }

    val context = LocalContext.current

    // 仅在未传入 icon 时才尝试加载应用图标
    val appPainter: Painter? = if (icon == null) {
        remember(packageName) {
            try {
                val pm = context.packageManager
                val drawable = pm.getApplicationIcon(packageName)

                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 64
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 64

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)

                BitmapPainter(bitmap.asImageBitmap())
            } catch (e: Exception) {
                Log.e("XiaoHeiHook", "获取图标失败: $packageName", e)
                null
            }
        }
    } else {
        null
    }

    val effectiveIcon: ImageVector? = if (appPainter == null) {
        icon ?: Icons.Default.Star
    } else {
        null
    }

    SettingItem(
        icon = effectiveIcon,
        painter = appPainter,
        title = title,
        subtitle = subtitle,
        checked = status,
        onCheckedChange = { newChecked ->
            status = newChecked
            if (newChecked) {
                XiaoHeiApplication.xposedService?.requestScope(
                    listOf(packageName),
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: MutableList<String>) = Unit
                        override fun onScopeRequestFailed(message: String) = Unit
                    })
            } else {
                XiaoHeiApplication.xposedService?.removeScope(listOf(packageName))
            }
        },
        enabled = moduleState.isActivated
    )
}