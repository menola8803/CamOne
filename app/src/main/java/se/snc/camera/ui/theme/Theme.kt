package se.snc.camera.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import se.snc.camera.data.ThemeOption

/** Accentfärg – varm röd, i stil med OnePlus kameragränssnitt. */
val Accent = Color(0xFFFF4040)
val AccentSoft = Color(0xFFFF7A6E)
val CameraYellow = Color(0xFFFFC233)
val Glass = Color(0x66202226)
val GlassStrong = Color(0xB3151619)
val OnGlass = Color(0xFFF5F5F7)
val OnGlassDim = Color(0xB3F5F5F7)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1414),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = AccentSoft,
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF0E0F12),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF1E2026),
    onSurfaceVariant = Color(0xFFB9BCC6),
    surfaceContainer = Color(0xFF17181C),
    surfaceContainerHigh = Color(0xFF1F2126),
    surfaceContainerHighest = Color(0xFF282A30),
    outline = Color(0xFF3A3D45),
    outlineVariant = Color(0xFF2A2C32)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFD62828),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFFB4423A),
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF15161A),
    surface = Color(0xFFF7F7F9),
    onSurface = Color(0xFF15161A),
    surfaceVariant = Color(0xFFE8E8EE),
    onSurfaceVariant = Color(0xFF4A4C55),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F4),
    surfaceContainerHighest = Color(0xFFE6E6EC),
    outline = Color(0xFFC4C6CE),
    outlineVariant = Color(0xFFDADBE1)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp)
)

@Composable
fun SncTheme(
    theme: ThemeOption = ThemeOption.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (theme) {
        ThemeOption.SYSTEM -> isSystemInDarkTheme()
        ThemeOption.LIGHT -> false
        ThemeOption.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

/** Kameravyn är alltid mörk oavsett tema. */
@Composable
fun CameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}
