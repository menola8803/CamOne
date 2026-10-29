package se.snc.camera.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import se.snc.camera.R

/**
 * Samma formspråk som USB-appen (menola8803/USB): mörk petrolgrön botten, guld som
 * huvudfärg, teal för rubriker och Space Grotesk / IBM Plex Sans / Cormorant Garamond.
 */
object Usb {
    val Bg = Color(0xFF0F1A1C)
    val Card = Color(0xFF172629)
    val Card2 = Color(0xFF1E3236)
    val Line = Color(0xFF24393D)
    val Text = Color(0xFFEAF1EF)
    val Muted = Color(0xFFB7C6C3)
    val Dim = Color(0xFF9FB1AE)
    val Teal = Color(0xFF5FC4B4)
    val TealL = Color(0xFF8FE0D2)
    val Gold = Color(0xFFE8B04A)
    val GoldL = Color(0xFFF3C76E)
    val Hl = Color(0xFF4A3A18)
    val Danger = Color(0xFFE8836B)
    val DangerBg = Color(0xFF3A2420)
    val DangerLine = Color(0xFF5A3027)
    val DangerText = Color(0xFFF6C7BB)
    val Code = Color(0xFF0A1314)

    /** Guldglans för ordmärket (samma toning som .wm-name i USB-appen). */
    val GoldShine = Brush.linearGradient(
        0f to Color(0xFFB9822A),
        0.28f to Color(0xFFF7DA94),
        0.48f to Color(0xFFE8B04A),
        0.62f to Color(0xFFFCEBC0),
        0.80f to Color(0xFFD59A35),
        1f to Color(0xFFB9822A)
    )
}

// Namn som används i kamera-, visnings- och redigeringsskärmarna
val Accent = Usb.Gold
val AccentSoft = Usb.GoldL
val CameraYellow = Usb.GoldL
val RecordRed = Usb.Danger
val Glass = Color(0x99172629)
val GlassStrong = Color(0xE6172629)
val OnGlass = Usb.Text
val OnGlassDim = Usb.Muted

val DisplayFont = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
    Font(R.font.space_grotesk_bold, FontWeight.SemiBold)
)

val BodyFont = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.Bold)
)

val SerifFont = FontFamily(
    Font(R.font.cormorant_garamond_semibold, FontWeight.SemiBold),
    Font(R.font.cormorant_garamond_semibold, FontWeight.Bold)
)

private val UsbScheme: ColorScheme = darkColorScheme(
    primary = Usb.Gold,
    onPrimary = Usb.Bg,
    primaryContainer = Usb.Hl,
    onPrimaryContainer = Usb.GoldL,
    secondary = Usb.Teal,
    onSecondary = Usb.Bg,
    secondaryContainer = Usb.Card2,
    onSecondaryContainer = Usb.TealL,
    tertiary = Usb.TealL,
    background = Usb.Bg,
    onBackground = Usb.Text,
    surface = Usb.Bg,
    onSurface = Usb.Text,
    surfaceVariant = Usb.Card2,
    onSurfaceVariant = Usb.Muted,
    surfaceContainerLowest = Usb.Bg,
    surfaceContainerLow = Usb.Card,
    surfaceContainer = Usb.Card,
    surfaceContainerHigh = Usb.Card,
    surfaceContainerHighest = Usb.Card2,
    surfaceBright = Usb.Card2,
    surfaceDim = Usb.Bg,
    inverseSurface = Usb.Text,
    inverseOnSurface = Usb.Bg,
    outline = Usb.Line,
    outlineVariant = Usb.Line,
    error = Usb.Danger,
    onError = Usb.Bg,
    scrim = Color(0x990A1314)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp)
)

/** Hela appen använder USB-stilen (alltid mörk, som USB-appen). */
@Composable
fun SncTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UsbScheme, typography = AppTypography, content = content)
}

/** Kamera-, bildvisare- och redigeringsskärmarna – samma palett. */
@Composable
fun CameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UsbScheme, typography = AppTypography, content = content)
}
