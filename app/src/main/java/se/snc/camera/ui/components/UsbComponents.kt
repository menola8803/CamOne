package se.snc.camera.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import se.snc.camera.ui.theme.BodyFont
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.SerifFont
import se.snc.camera.ui.theme.Usb
import java.util.Locale

// Komponenter som efterliknar USB-appens CSS-klasser (.card, .label, .primary, .seg, .presets …)

/** `.card` – rundat kort på mörk botten. */
@Composable
fun UsbCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Usb.Card)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/** `.label` – liten rubrik i versaler med spärrning (teal eller guld). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Usb.Teal) {
    Text(
        text.uppercase(Locale.getDefault()),
        modifier = modifier,
        color = color,
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp
    )
}

/** `.flabel` – fältetikett. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Usb.Muted, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
}

/** `.muted` – brödtext. */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text,
        modifier = modifier,
        color = Usb.Muted,
        fontFamily = BodyFont,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        textAlign = textAlign
    )
}

/** `.icon-btn` – kvadratisk knapp med rundade hörn. */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    glass: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Usb.Muted
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(if (size > 48.dp) 16.dp else 12.dp))
            .background(if (glass) Color.White.copy(alpha = 0.06f) else Usb.Card)
            .then(
                if (glass) Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Sidhuvud: tillbakaknapp + rubrik (`.top` + `.h1`). */
@Composable
fun ScreenHeader(title: String, backDescription: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, backDescription, onBack, tint = Usb.Muted)
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            color = Usb.Text,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/** `.primary` – guldknapp. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp
) {
    Row(
        modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(20.dp))
            .background(Usb.Gold)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Usb.Bg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Usb.Bg, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

/** `.secondary` – kantad knapp. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(if (danger) Usb.DangerBg else Usb.Card)
            .border(BorderStroke(1.dp, if (danger) Usb.DangerLine else Usb.Line), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (danger) Usb.DangerText else Usb.Text
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = color, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** `.presets` – rutnät med val där det valda är guld. */
@Composable
fun <T> ChoiceGrid(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val on = option == selected
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(shape)
                            .background(if (on) Usb.Gold else Usb.Card2)
                            .border(1.dp, if (on) Usb.Gold else Usb.Line, shape)
                            .clickable(role = Role.RadioButton) { onSelect(option) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label(option),
                            color = if (on) Usb.Bg else Usb.Muted,
                            fontFamily = BodyFont,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** `.seg` – segmenterad knapp (t.ex. Svenska | English). */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Usb.Card)
            .border(1.dp, Usb.Line, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Usb.Gold else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    color = if (on) Usb.Bg else Usb.Muted,
                    fontFamily = BodyFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun UsbSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Usb.Bg,
            checkedTrackColor = Usb.Gold,
            checkedBorderColor = Usb.Gold,
            uncheckedThumbColor = Usb.Dim,
            uncheckedTrackColor = Usb.Card2,
            uncheckedBorderColor = Usb.Line
        )
    )
}

/** Rad med text och reglage (motsvarar `.toggle`). */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Usb.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = Usb.Dim, fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        UsbSwitch(checked = checked, onCheckedChange = null)
    }
}

/** Reglage i guld med rubrik och värde. */
@Composable
fun GoldSlider(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Usb.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Usb.GoldL, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Usb.Gold,
                activeTrackColor = Usb.Gold,
                inactiveTrackColor = Usb.Card2,
                activeTickColor = Usb.Bg.copy(alpha = 0.5f),
                inactiveTickColor = Usb.Dim.copy(alpha = 0.6f)
            )
        )
    }
}

/** `.item` – klickbar rad med pil. */
@Composable
fun NavItem(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Usb.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Usb.Gold.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(leading, contentDescription = null, tint = Usb.Gold, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Usb.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Usb.Dim, fontFamily = BodyFont, fontSize = 13.sp)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Usb.Dim)
    }
}

/** Tunn avdelare (`border-top: 1px solid var(--line)`). */
@Composable
fun LineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Usb.Line)
    )
}

/** `.wm-seal` – runt emblem med guldring. */
@Composable
fun Seal(size: Dp = 48.dp, iconSize: Dp = 22.dp, icon: ImageVector = Icons.Outlined.CameraAlt) {
    Box(
        Modifier
            .size(size)
            .drawBehind {
                // mjukt guldsken under emblemet
                drawCircle(
                    Brush.radialGradient(
                        listOf(Usb.Gold.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(center.x, center.y + 4.dp.toPx()),
                        radius = this.size.minDimension * 0.85f
                    ),
                    radius = this.size.minDimension * 0.85f,
                    center = Offset(center.x, center.y + 4.dp.toPx())
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF24413F), Color(0xFF142427)),
                    center = Offset(this.size.width * 0.35f, this.size.height * 0.30f),
                    radius = r * 1.4f
                ),
                radius = r
            )
            drawCircle(Usb.Gold.copy(alpha = 0.65f), radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(Usb.Gold.copy(alpha = 0.35f), radius = r - 4.5.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
        Icon(icon, contentDescription = null, tint = Usb.GoldL, modifier = Modifier.size(iconSize))
    }
}

/**
 * `.wordmark` – emblem + "SNC" i guld med glans + tunn linje med undertext.
 * Glansen sveper över namnet en gång, som i USB-appen.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun Wordmark(subtitle: String, modifier: Modifier = Modifier, small: Boolean = false, name: String = "SNC") {
    var textWidth by remember { mutableFloatStateOf(0f) }
    val shine = remember { Animatable(1f) }
    LaunchedEffect(Unit) { shine.animateTo(0f, tween(2800, delayMillis = 200)) }
    val w = if (textWidth > 0f) textWidth else 300f
    val startX = -1.2f * w * shine.value
    val brush = Brush.linearGradient(
        0f to Color(0xFFB9822A),
        0.28f to Color(0xFFF7DA94),
        0.48f to Color(0xFFE8B04A),
        0.62f to Color(0xFFFCEBC0),
        0.80f to Color(0xFFD59A35),
        1f to Color(0xFFB9822A),
        start = Offset(startX, 0f),
        end = Offset(startX + 2.2f * w, 0f)
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Seal(size = if (small) 42.dp else 48.dp, iconSize = if (small) 20.dp else 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                name,
                style = TextStyle(
                    brush = brush,
                    fontFamily = SerifFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (small) 36.sp else 42.sp,
                    letterSpacing = 0.2.em,
                    lineHeight = if (small) 34.sp else 40.sp
                ),
                onTextLayout = { textWidth = it.size.width.toFloat() }
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(14.dp)
                        .height(1.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Usb.Gold)))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    subtitle.uppercase(Locale.getDefault()),
                    color = Usb.TealL,
                    fontFamily = BodyFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 0.38.em
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .width(14.dp)
                        .height(1.dp)
                        .background(Brush.horizontalGradient(listOf(Usb.Gold, Color.Transparent)))
                )
            }
        }
    }
}

/** `.ring1/.ring2/.mic` – stor rund guldknapp i två ringar (startskärmen i USB-appen). */
@Composable
fun HeroButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outer: Dp = 188.dp
) {
    Box(
        modifier
            .size(outer)
            .clip(CircleShape)
            .background(Usb.Card)
            .border(1.dp, Usb.Line, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(outer * 0.72f)
                .clip(CircleShape)
                .background(Usb.Card2),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(outer * 0.5f)
                    .drawBehind {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Usb.Gold.copy(alpha = 0.28f), Color.Transparent),
                                radius = size.minDimension * 0.8f
                            ),
                            radius = size.minDimension * 0.8f
                        )
                    }
                    .clip(CircleShape)
                    .background(Usb.Gold)
                    .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = contentDescription, tint = Usb.Bg, modifier = Modifier.size(outer * 0.2f))
            }
        }
    }
}

/** `.pill` – liten statusbricka med prick. */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, color: Color = Usb.TealL, dot: Color = Usb.Teal) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Usb.Card2)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
