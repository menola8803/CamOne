package se.snc.camera.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import se.snc.camera.R

/**
 * Samma tema som Alif (och Direct): mörk petrolgrön botten med guld och mint, eller ljust beige.
 * Typsnitten är Plus Jakarta Sans och Cinzel för ordmärket.
 *
 * - [Usb] är alltid den mörka paletten – kameran, bildvisaren och redigeraren är alltid mörka.
 * - [Ui] följer temat som valts i Inställningar (Mörk eller Beige) och används av inställningssidorna
 *   och komponenterna i `ui.components`.
 */
@Immutable
class SncPalette(
    val isDark: Boolean,
    val Bg: Color,
    val Card: Color,
    /** "chip" i Alif: knappar, val och spår. */
    val Card2: Color,
    val Line: Color,
    val Text: Color,
    val Muted: Color,
    val Dim: Color,
    /** Accent för rubriker (mint i mörkt, brons i beige). */
    val Teal: Color,
    val TealL: Color,
    /** Huvudfärg: knappar, val, reglage (guld i mörkt, kol i beige). */
    val Gold: Color,
    val GoldL: Color,
    val OnGold: Color,
    val Hl: Color,
    val Good: Color,
    val Danger: Color,
    val DangerBg: Color,
    val DangerLine: Color,
    val DangerText: Color,
    val Code: Color,
    /** Alifs knapputseende: en ton av guld, tunn guldkant och guldtext. */
    val BtnBg: Color,
    val BtnBorder: Color,
    val BtnFg: Color,
    /** Glas: menyn och valraden som i Alif. */
    val Glass: Color,
    val GlassBorder: Color,
    /** Glans för ordmärket. */
    val shine: List<Color>
)

val DarkPalette = SncPalette(
    isDark = true,
    Bg = Color(0xFF0F1A1C),
    Card = Color(0xFF172629),
    Card2 = Color(0xFF1E3236),
    Line = Color(0xFF25393D),
    Text = Color(0xFFEAF1EF),
    Muted = Color(0xFFB7C6C3),
    Dim = Color(0xFFA3B4B1),
    Teal = Color(0xFF5FC4B4),
    TealL = Color(0xFF8FE0D2),
    Gold = Color(0xFFE8B04A),
    GoldL = Color(0xFFF3C76E),
    OnGold = Color(0xFF0F1A1C),
    Hl = Color(0xFF4A3A18),
    Good = Color(0xFF5FC4B4),
    Danger = Color(0xFFE8776D),
    DangerBg = Color(0xFF3A2420),
    DangerLine = Color(0xFF5A3027),
    DangerText = Color(0xFFF6C7BB),
    Code = Color(0xFF0A1314),
    BtnBg = Color(0x1FE8B04A),
    BtnBorder = Color(0x8CE8B04A),
    BtnFg = Color(0xFFE8B04A),
    Glass = Color(0xC7132225),
    GlassBorder = Color(0x38E8B04A),
    shine = listOf(
        Color(0xFFB9822A), Color(0xFFF7DA94), Color(0xFFE8B04A),
        Color(0xFFFCEBC0), Color(0xFFD59A35), Color(0xFFB9822A)
    )
)

val BeigePalette = SncPalette(
    isDark = false,
    Bg = Color(0xFFEFEDE8),
    Card = Color(0xFFFAF8F4),
    Card2 = Color(0xFFE9E2D3),
    Line = Color(0xFFDDD8CD),
    Text = Color(0xFF26231F),
    Muted = Color(0xFF6A6358),
    Dim = Color(0xFF6A6358),
    Teal = Color(0xFF7A5C2B),
    TealL = Color(0xFF7A5C2B),
    Gold = Color(0xFF26231F),
    GoldL = Color(0xFF26231F),
    OnGold = Color(0xFFF3E9D2),
    Hl = Color(0xFFE9E2D3),
    Good = Color(0xFF2F6E4C),
    Danger = Color(0xFFA8403A),
    DangerBg = Color(0xFFF6E3DF),
    DangerLine = Color(0xFFE1B9B2),
    DangerText = Color(0xFF8E2F2A),
    Code = Color(0xFFE9E2D3),
    BtnBg = Color(0xFFE9E2D3),
    BtnBorder = Color(0xFF26231F),
    BtnFg = Color(0xFF26231F),
    Glass = Color(0xDBFAF8F4),
    GlassBorder = Color(0xFFDDD8CD),
    shine = listOf(
        Color(0xFF5A4320), Color(0xFF9C7A3E), Color(0xFF7A5C2B),
        Color(0xFFB8955A), Color(0xFF6B4F25), Color(0xFF5A4320)
    )
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Alltid mörk – kameran, bildvisaren och redigeraren. Kan läsas var som helst. */
val Usb: SncPalette get() = DarkPalette

/** Paletten för temat som valts i Inställningar (Mörk eller Beige). */
val Ui: SncPalette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

/** Guldglans för ordmärket. */
fun SncPalette.shineBrush(start: androidx.compose.ui.geometry.Offset, end: androidx.compose.ui.geometry.Offset) =
    Brush.linearGradient(
        0f to shine[0], 0.28f to shine[1], 0.48f to shine[2], 0.62f to shine[3], 0.80f to shine[4], 1f to shine[5],
        start = start,
        end = end
    )

// Namn som används i kamera-, visnings- och redigeringsskärmarna (alltid mörka)
val Accent = DarkPalette.Gold
val AccentSoft = DarkPalette.GoldL
val CameraYellow = DarkPalette.Gold
val RecordRed = DarkPalette.Danger
val Glass = Color(0xA6132225)
val GlassStrong = Color(0xD9132225)
val GlassLine = DarkPalette.GlassBorder
val GoldTint = DarkPalette.BtnBg
val GoldTintStrong = Color(0x40E8B04A)
val GoldEdge = DarkPalette.BtnBorder
val OnGlass = DarkPalette.Text
val OnGlassDim = DarkPalette.Muted

private val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.Black)
)

/** Rubriker – Plus Jakarta Sans som i Alif. */
val DisplayFont = Jakarta

/** Brödtext – Plus Jakarta Sans. */
val BodyFont = Jakarta

/** Ordmärket SNC – Cinzel som Alifs ordmärke. */
val SerifFont = FontFamily(
    Font(R.font.cinzel_bold, FontWeight.Normal),
    Font(R.font.cinzel_bold, FontWeight.SemiBold),
    Font(R.font.cinzel_bold, FontWeight.Bold)
)

private fun SncPalette.scheme(): ColorScheme =
    if (isDark) darkColorScheme(
        primary = Gold, onPrimary = OnGold, primaryContainer = Hl, onPrimaryContainer = GoldL,
        secondary = Teal, onSecondary = Bg, secondaryContainer = Card2, onSecondaryContainer = TealL,
        tertiary = TealL, background = Bg, onBackground = Text, surface = Bg, onSurface = Text,
        surfaceVariant = Card2, onSurfaceVariant = Muted, surfaceContainerLowest = Bg, surfaceContainerLow = Card,
        surfaceContainer = Card, surfaceContainerHigh = Card, surfaceContainerHighest = Card2, surfaceBright = Card2,
        surfaceDim = Bg, inverseSurface = Text, inverseOnSurface = Bg, outline = Line, outlineVariant = Line,
        error = Danger, onError = Bg, scrim = Color(0x990A1314)
    ) else lightColorScheme(
        primary = Gold, onPrimary = OnGold, primaryContainer = Card2, onPrimaryContainer = Text,
        secondary = Teal, onSecondary = Card, secondaryContainer = Card2, onSecondaryContainer = Text,
        tertiary = Teal, background = Bg, onBackground = Text, surface = Bg, onSurface = Text,
        surfaceVariant = Card2, onSurfaceVariant = Muted, surfaceContainerLowest = Card, surfaceContainerLow = Card,
        surfaceContainer = Card, surfaceContainerHigh = Card, surfaceContainerHighest = Card2, surfaceBright = Card,
        surfaceDim = Bg, inverseSurface = Text, inverseOnSurface = Bg, outline = Line, outlineVariant = Line,
        error = Danger, onError = Card, scrim = Color(0x6626231F)
    )

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp)
)

/** Inställningar, Om, Uppdateringar, Kameror och Manual: följer valt tema (Mörk eller Beige). */
@Composable
fun SncTheme(beige: Boolean = false, content: @Composable () -> Unit) {
    val palette = if (beige) BeigePalette else DarkPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.scheme(), typography = AppTypography, content = content)
    }
}

/** Kamera-, bildvisare- och redigeringsskärmarna – alltid mörka. */
@Composable
fun CameraTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides DarkPalette) {
        MaterialTheme(colorScheme = DarkPalette.scheme(), typography = AppTypography, content = content)
    }
}
