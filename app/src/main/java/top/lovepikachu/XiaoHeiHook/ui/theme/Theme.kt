package top.lovepikachu.XiaoHeiHook.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = MaterialPrimaryDark,
    secondary = Color(0xFFBDCBB0),
    tertiary = Color(0xFFA0CFD1),
    error = Color(0xFFFFB4AB),
    background = MaterialDarkBackground,
    surface = MaterialDarkSurface,
    surfaceVariant = MaterialDarkSurfaceVariant,
    primaryContainer = Color(0xFF1F4F0B),
    secondaryContainer = Color(0xFF3E4A36),
    tertiaryContainer = Color(0xFF1E4E50),
    onPrimary = Color(0xFF123800),
    onSecondary = Color(0xFF283420),
    onTertiary = Color(0xFF003738),
    onBackground = Color(0xFFE1E4D8),
    onSurface = Color(0xFFE1E4D8),
    onSurfaceVariant = Color(0xFFC4C8BA),
    outline = Color(0xFF8E9286),
    outlineVariant = Color(0xFF44483D)
)

private val LightColorScheme = lightColorScheme(
    primary = MaterialPrimary,
    secondary = MaterialSecondary,
    tertiary = MaterialTertiary,
    error = MaterialError,
    background = MaterialLightBackground,
    surface = MaterialLightSurface,
    surfaceVariant = MaterialLightSurfaceVariant,
    primaryContainer = Color(0xFFB8F397),
    secondaryContainer = Color(0xFFD9E7CB),
    tertiaryContainer = Color(0xFFBCEBEE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1A1C18),
    onSurface = Color(0xFF1A1C18),
    onSurfaceVariant = Color(0xFF44483D),
    outline = Color(0xFF75796C),
    outlineVariant = Color(0xFFC4C8BA)
)

@Composable
fun XiaoHeiHookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
