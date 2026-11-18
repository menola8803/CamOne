package se.snc.camera.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.snc.camera.R
import se.snc.camera.ui.theme.CameraYellow
import se.snc.camera.ui.theme.Glass
import se.snc.camera.ui.theme.GlassLine
import se.snc.camera.ui.theme.GoldTint
import se.snc.camera.ui.theme.OnGlass
import se.snc.camera.ui.theme.OnGlassDim
import se.snc.camera.ui.theme.Usb
import kotlin.math.roundToInt

/** Exponeringstider för ljusspår och silkesvatten (sekunder). */
val ExposureSecondsOptions = listOf(1, 2, 4, 8)

private fun StackStyle.label(): Int = when (this) {
    StackStyle.NIGHT -> R.string.night_style_night
    StackStyle.TRAILS -> R.string.night_style_trails
    StackStyle.SILK -> R.string.night_style_silk
}

/** Val i nattläget: Natt, Ljusspår, Silkesvatten – och exponeringstid för de två sista. */
@Composable
fun NightStylePicker(
    style: StackStyle,
    seconds: Int,
    enabled: Boolean,
    onStyle: (StackStyle) -> Unit,
    onSeconds: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GlassChipRow {
            StackStyle.entries.forEach { s ->
                GlassChip(stringResource(s.label()), selected = s == style, enabled = enabled) { onStyle(s) }
            }
        }
        if (style != StackStyle.NIGHT) {
            GlassChipRow {
                ExposureSecondsOptions.forEach { sec ->
                    GlassChip(stringResource(R.string.night_seconds, sec), selected = sec == seconds, enabled = enabled) {
                        onSeconds(sec)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GlassChipRow(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassLine, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
internal fun GlassChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Text(
        text,
        color = when {
            selected -> CameraYellow
            enabled -> OnGlass
            else -> OnGlassDim
        },
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) GoldTint else Color.Transparent)
            .border(1.dp, if (selected) Usb.Gold.copy(alpha = 0.6f) else Color.Transparent, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * Etiketten överst i sökaren i nattläget: hur många bilder som tas (i handen eller på stativ),
 * förloppet medan bilderna tas och "Lägger ihop bilderna…" till sist.
 */
@Composable
fun NightStatus(state: CameraUiState, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val progress = state.stackProgress
    val text = when {
        !state.stackAvailable -> {
            // Tillverkarens nattläge: bara uppmaningen att hålla stilla
            if (state.isCapturing) return
            stringResource(R.string.night_hold_steady)
        }
        state.stackMerging -> stringResource(R.string.night_merging)
        progress != null && state.nightStyle == StackStyle.NIGHT -> {
            val total = state.stackFrames.coerceAtLeast(1)
            stringResource(R.string.night_capturing, (progress * total).roundToInt().coerceIn(0, total), total)
        }
        progress != null -> stringResource(
            R.string.night_exposing,
            String.format(locale, "%.1f", progress * state.exposureSeconds),
            state.exposureSeconds
        )
        state.nightStyle == StackStyle.NIGHT -> if (state.steady) {
            stringResource(R.string.night_tripod_frames, NightFrames.STEADY)
        } else {
            stringResource(R.string.night_hand_frames, NightFrames.HAND)
        }
        state.steady -> stringResource(R.string.night_effect_ready)
        else -> stringResource(R.string.night_effect_steady)
    }
    StackPill(Icons.Rounded.NightsStay, text, if (state.stackMerging) null else progress, modifier)
}

/** Etiketten överst i sökaren medan en HDR-bild tas: "HDR · 2/3", sedan "Lägger ihop bilderna…". */
@Composable
fun HdrStatus(state: CameraUiState, modifier: Modifier = Modifier) {
    val progress = state.stackProgress
    val total = state.stackFrames.coerceAtLeast(1)
    val text = if (state.stackMerging || progress == null) {
        stringResource(R.string.night_merging)
    } else {
        stringResource(R.string.hdr_capturing, (progress * total).roundToInt().coerceIn(0, total), total)
    }
    StackPill(Icons.Rounded.HdrOn, text, if (state.stackMerging) null else progress, modifier)
}

/** Mörk etikett med ikon, text och (om [progress] inte är null) en tunn guldstapel. */
@Composable
private fun StackPill(icon: ImageVector, text: String, progress: Float?, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Usb.Bg.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CameraYellow, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = Usb.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (progress != null) {
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .width(150.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Usb.Line)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Usb.Gold)
                )
            }
        }
    }
}
