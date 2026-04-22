package top.lovepikachu.XiaoHeiHook.composables

import android.graphics.Bitmap
import android.graphics.Canvas
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
import io.github.libxposed.service.XposedService
import top.lovepikachu.XiaoHeiHook.XiaoHeiApplication

@Composable
fun XposedSettingItem(
    icon: ImageVector? = null,   // ← 现在可省略
    title: String,
    subtitle: String,
    statusKey: String,
    packageName: String
) {
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

                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)

                BitmapPainter(bitmap.asImageBitmap())
            } catch (e: Exception) {
                // 应用未安装或无图标 → 返回 null，后续会回退到默认图标
                null
            }
        }
    } else {
        null
    }

    val effectiveIcon: ImageVector? = if (appPainter == null) {
        icon ?: Icons.Default.Star   // 未加载到应用图标时使用传入的 icon 或默认 Star
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
                    object : XposedService.OnScopeEventListener {})
            } else {
                XiaoHeiApplication.xposedService?.removeScope(listOf(packageName))
            }
        },
        enabled = XiaoHeiApplication.isModuleActivated
    )
}