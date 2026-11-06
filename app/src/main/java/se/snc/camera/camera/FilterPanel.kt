package se.snc.camera.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.snc.camera.R
import se.snc.camera.editor.PhotoFilter
import se.snc.camera.ui.theme.CameraYellow
import se.snc.camera.ui.theme.GlassStrong
import se.snc.camera.ui.theme.OnGlass
import se.snc.camera.ui.theme.OnGlassDim
import se.snc.camera.ui.theme.Usb
import kotlin.math.roundToInt

private val ChannelRed = Color(0xFFE5574F)
private val ChannelGreen = Color(0xFF4CC38A)
private val ChannelBlue = Color(0xFF4A8FE8)

/** Panelen för live-filtret: förinställda filter och reglage för rött, grönt och blått. */
@Composable
fun FilterPanel(
    filter: LiveFilter,
    onPreset: (PhotoFilter) -> Unit,
    onChannel: (ColorChannel, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(GlassStrong)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.filter_panel_title),
                color = OnGlassDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f)
            )
            if (filter.isActive) {
                Chip(text = stringResource(R.string.reset), selected = false, onClick = onReset)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PhotoFilter.entries.forEach { f ->
                Chip(text = stringResource(f.label), selected = filter.preset == f, onClick = { onPreset(f) })
            }
        }
        Spacer(Modifier.height(4.dp))
        ChannelSlider("R", stringResource(R.string.filter_red), ChannelRed, filter.red) { onChannel(ColorChannel.RED, it) }
        ChannelSlider("G", stringResource(R.string.filter_green), ChannelGreen, filter.green) { onChannel(ColorChannel.GREEN, it) }
        ChannelSlider("B", stringResource(R.string.filter_blue), ChannelBlue, filter.blue) { onChannel(ColorChannel.BLUE, it) }
    }
}

@Composable
private fun ChannelSlider(short: String, name: String, color: Color, value: Float, onValue: (Float) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(short, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = 0f..LiveFilter.MAX_GAIN,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = Usb.Line
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = name }
        )
        Text(
            "${(value * 100).roundToInt()} %",
            color = if (value == 1f) OnGlassDim else OnGlass,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp)
        )
    }
}

/** Liten etikett i sökaren som visar att ett filter är på. Tryck för att öppna panelen. */
@Composable
fun FilterBadge(filter: LiveFilter, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = when {
        filter.preset != PhotoFilter.ORIGINAL && filter.rgbChanged -> stringResource(filter.preset.label) + " + RGB"
        filter.preset != PhotoFilter.ORIGINAL -> stringResource(filter.preset.label)
        else -> "RGB " + listOf(filter.red, filter.green, filter.blue).joinToString("/") { "${(it * 100).roundToInt()}" }
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Usb.Bg.copy(alpha = 0.72f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.PhotoFilter, contentDescription = null, tint = CameraYellow, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, color = Usb.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
