package top.lovepikachu.XiaoHeiHook

import android.os.Bundle
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
                    primary = Color(0xFF1E88E5),
                    background = Color(0xFFF8F9FA),
                    surface = Color.White,
                    onSurface = Color(0xFF1F1F1F),
                    onSurfaceVariant = Color(0xFF666666)
                )
            ) {
                MainScreen()
            }
        }
    }
}
