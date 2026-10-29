package se.snc.camera.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.snc.camera.R
import se.snc.camera.ui.theme.CameraYellow
import se.snc.camera.ui.theme.Glass
import se.snc.camera.ui.theme.GlassStrong
import se.snc.camera.ui.theme.OnGlass
import se.snc.camera.ui.theme.OnGlassDim
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

fun formatShutter(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 0.3) {
        String.format(Locale.US, "%.1fs", seconds)
    } else {
        "1/${(1.0 / seconds).roundToInt()}"
    }
}

/** Logaritmisk skala 0..1 ↔ värde (passar ISO och slutartid). */
private fun toLog(value: Double, min: Double, max: Double): Float =
    ((ln(value) - ln(min)) / (ln(max) - ln(min))).toFloat().coerceIn(0f, 1f)

private fun fromLog(fraction: Float, min: Double, max: Double): Double =
    exp(ln(min) + fraction * (ln(max) - ln(min)))

@Composable
fun ProPanel(
    state: CameraUiState,
    rotation: Float,
    onSelect: (ProControl?) -> Unit,
    onPro: ((ProState) -> ProState) -> Unit,
    onExposure: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pro = state.pro
    val active = state.activeProControl
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = active != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(GlassStrong)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                when (active) {
                    ProControl.EV -> ValueSlider(
                        title = stringResource(R.string.pro_ev),
                        value = formatEv(state.exposureIndex, state.exposureStep),
                        fraction = if (state.exposureMax > state.exposureMin) {
                            (state.exposureIndex - state.exposureMin).toFloat() / (state.exposureMax - state.exposureMin)
                        } else 0.5f,
                        onFraction = { f ->
                            onExposure((state.exposureMin + f * (state.exposureMax - state.exposureMin)).roundToInt())
                        },
                        auto = null,
                        onAuto = {}
                    )
                    ProControl.ISO -> ValueSlider(
                        title = stringResource(R.string.pro_iso),
                        value = if (pro.isoAuto) stringResource(R.string.pro_auto) else pro.iso.toString(),
                        fraction = toLog(pro.iso.toDouble(), pro.isoMin.toDouble(), pro.isoMax.toDouble()),
                        onFraction = { f ->
                            val iso = fromLog(f, pro.isoMin.toDouble(), pro.isoMax.toDouble()).roundToInt()
                            onPro { it.copy(isoAuto = false, iso = iso.coerceIn(it.isoMin, it.isoMax)) }
                        },
                        auto = pro.isoAuto,
                        onAuto = { onPro { it.copy(isoAuto = !it.isoAuto) } }
                    )
                    ProControl.SHUTTER -> ValueSlider(
                        title = stringResource(R.string.pro_shutter),
                        value = if (pro.shutterAuto) stringResource(R.string.pro_auto) else formatShutter(pro.shutterNs),
                        fraction = toLog(pro.shutterNs.toDouble(), pro.shutterMinNs.toDouble(), pro.shutterMaxNs.toDouble()),
                        onFraction = { f ->
                            val ns = fromLog(f, pro.shutterMinNs.toDouble(), pro.shutterMaxNs.toDouble()).roundToLong()
                            onPro { it.copy(shutterAuto = false, shutterNs = ns.coerceIn(it.shutterMinNs, it.shutterMaxNs)) }
                        },
                        auto = pro.shutterAuto,
                        onAuto = { onPro { it.copy(shutterAuto = !it.shutterAuto) } }
                    )
                    ProControl.FOCUS -> ValueSlider(
                        title = stringResource(R.string.pro_focus),
                        value = when {
                            pro.focusAuto -> stringResource(R.string.pro_af)
                            pro.focusDiopters <= 0.05f -> "∞"
                            else -> String.format(Locale.US, "%.2f m", 1f / pro.focusDiopters)
                        },
                        fraction = if (pro.focusMaxDiopters > 0f) pro.focusDiopters / pro.focusMaxDiopters else 0f,
                        onFraction = { f -> onPro { it.copy(focusAuto = false, focusDiopters = f * it.focusMaxDiopters) } },
                        auto = pro.focusAuto,
                        onAuto = { onPro { it.copy(focusAuto = !it.focusAuto) } }
                    )
                    ProControl.WB -> Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WhiteBalance.entries.forEach { wb ->
                            Chip(
                                text = stringResource(wb.label),
                                selected = pro.whiteBalance == wb,
                                onClick = { onPro { it.copy(whiteBalance = wb) } }
                            )
                        }
                    }
                    null -> Unit
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ProItem(stringResource(R.string.pro_ev), formatEv(state.exposureIndex, state.exposureStep), active == ProControl.EV, rotation) {
                onSelect(ProControl.EV)
            }
            if (pro.supported) {
                ProItem(stringResource(R.string.pro_iso), if (pro.isoAuto) stringResource(R.string.pro_auto) else "${pro.iso}", active == ProControl.ISO, rotation) {
                    onSelect(ProControl.ISO)
                }
                ProItem(stringResource(R.string.pro_shutter_short), if (pro.shutterAuto) stringResource(R.string.pro_auto) else formatShutter(pro.shutterNs), active == ProControl.SHUTTER, rotation) {
                    onSelect(ProControl.SHUTTER)
                }
                ProItem(stringResource(R.string.pro_wb), stringResource(pro.whiteBalance.label), active == ProControl.WB, rotation) {
                    onSelect(ProControl.WB)
                }
                if (pro.focusMaxDiopters > 0f) {
                    ProItem(stringResource(R.string.pro_focus_short), if (pro.focusAuto) stringResource(R.string.pro_af) else "MF", active == ProControl.FOCUS, rotation) {
                        onSelect(ProControl.FOCUS)
                    }
                }
            }
        }
        if (!pro.supported) {
            Text(
                stringResource(R.string.pro_not_supported),
                color = OnGlassDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ProItem(label: String, value: String, selected: Boolean, rotation: Float, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Glass else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .rotate(rotation),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = if (selected) CameraYellow else OnGlassDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = if (selected) CameraYellow else OnGlass, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ValueSlider(
    title: String,
    value: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
    auto: Boolean?,
    onAuto: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = OnGlassDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(value, color = CameraYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (auto != null) {
                Chip(text = stringResource(R.string.pro_auto), selected = auto, onClick = onAuto)
            }
        }
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = onFraction,
            colors = SliderDefaults.colors(
                thumbColor = CameraYellow,
                activeTrackColor = CameraYellow,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            )
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        color = if (selected) Color.Black else OnGlass,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) CameraYellow else Glass)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
