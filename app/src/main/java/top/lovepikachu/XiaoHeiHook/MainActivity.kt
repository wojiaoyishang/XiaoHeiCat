package top.lovepikachu.XiaoHeiHook

import android.os.Bundle
import top.lovepikachu.XiaoHeiHook.keepalive.AccessibilityKeepAliveManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF5D64D8),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFE5E7FF),
                    onPrimaryContainer = Color(0xFF252A73),
                    secondary = Color(0xFF5F6278),
                    secondaryContainer = Color(0xFFE4E5F8),
                    tertiary = Color(0xFF6F5B99),
                    background = Color(0xFFF7F7FF),
                    onBackground = Color(0xFF22232C),
                    surface = Color(0xFFFFFFFF),
                    onSurface = Color(0xFF272832),
                    surfaceVariant = Color(0xFFEFF0FA),
                    onSurfaceVariant = Color(0xFF5D6072),
                    outline = Color(0xFF86899A),
                    outlineVariant = Color(0xFFDADCE8),
                    error = Color(0xFFBA1A1A)
                )
            ) {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            AccessibilityKeepAliveManager.disableIfNoRuntimeNeedsKeepAlive(this)
        }
        super.onDestroy()
    }

}
