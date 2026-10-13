package app.palimpsest.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.palimpsest.AppSettings

// Ink-and-paper fallback for devices without Material You (Android < 12).
private val LightInk = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF8E4A6B),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFFBF8F3),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF46464F),
)

private val DarkInk = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF2A3BA0),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF434659),
    tertiary = Color(0xFFFFB0D3),
    background = Color(0xFF131316),
    surface = Color(0xFF131316),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
)

/** Long-form reading and writing: a serif with generous line height. */
val ReadingStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun PalimpsestTheme(mode: AppSettings.ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        AppSettings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppSettings.ThemeMode.LIGHT -> false
        AppSettings.ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> DarkInk
        else -> LightInk
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
