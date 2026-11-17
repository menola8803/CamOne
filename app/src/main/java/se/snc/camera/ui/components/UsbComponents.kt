package se.snc.camera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import se.snc.camera.ui.theme.BodyFont
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.SerifFont
import se.snc.camera.ui.theme.Ui
import se.snc.camera.ui.theme.shineBrush
import java.util.Locale

// Komponenter i samma stil som Alif (src/ui.tsx): kort med tunn kant, knappar med guldton, guldkant och
// guldtext, val i en glasram med guldstreck under det valda, rader med › och ett runt reglage.

/** Alifs `Card` – rundat kort med tunn kantlinje. */
@Composable
fun UsbCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ui.Card)
            .border(1.dp, Ui.Line, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/** Alifs `Label` – liten rubrik i versaler med spärrning (mint, eller guld). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Ui.Teal) {
    Text(
        text.uppercase(Locale.getDefault()),
        modifier = modifier,
        color = color,
        fontFamily = BodyFont,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp
    )
}

/** Fältetikett ovanför ett val. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Ui.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
}

/** Brödtext i dämpad färg. */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text,
        modifier = modifier,
        color = Ui.Muted,
        fontFamily = BodyFont,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        textAlign = textAlign
    )
}

/** Fyrkantig knapp med rundade hörn: guldton, guldkant och guldikon som i Alif. */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    glass: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Ui.BtnFg
) {
    val shape = RoundedCornerShape(if (size > 48.dp) 16.dp else 14.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (glass) Ui.Glass else Ui.BtnBg)
            .border(1.dp, if (glass) Ui.GlassBorder else Ui.BtnBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Alifs `Header`: tillbaka-knapp med guldkant, rubrik och valfri underrubrik. */
@Composable
fun ScreenHeader(
    title: String,
    backDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SquareIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, backDescription, onBack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Ui.Text,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = (-0.4).sp
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Ui.Muted, fontFamily = BodyFont, fontSize = 14.sp)
            }
        }
    }
}

/** Alifs knapp: guldton, tunn guldkant och guldtext. Samma utseende för alla knappar. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    AlifButton(text, onClick, modifier, icon, enabled, height, bold = true, danger = false)
}

/** Samma knapp med lite lättare text; [danger] = röd kant och text (t.ex. Ta bort, Återställ). */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    AlifButton(text, onClick, modifier, icon, enabled, 52.dp, bold = false, danger = danger)
}

@Composable
private fun AlifButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    height: Dp,
    bold: Boolean,
    danger: Boolean
) {
    val shape = RoundedCornerShape(16.dp)
    val fg = if (danger) Ui.Danger else Ui.BtnFg
    Row(
        modifier
            .heightIn(min = height)
            .clip(shape)
            .background(if (danger) Color.Transparent else Ui.BtnBg)
            .border(BorderStroke(1.dp, if (danger) Ui.Danger else Ui.BtnBorder), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = fg,
            fontFamily = BodyFont,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

/** Ett val i en glasram: guldton, guldkant, guldtext och ett litet guldstreck under det valda. */
@Composable
private fun SegmentOption(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val textColor by animateColorAsState(if (on) Ui.Gold else Ui.Muted, label = "segText")
    Column(
        modifier
            .heightIn(min = 46.dp)
            .clip(shape)
            .background(if (on) Ui.BtnBg else Color.Transparent)
            .border(1.dp, if (on) (if (Ui.isDark) Ui.Gold.copy(alpha = 0.5f) else Ui.Line) else Color.Transparent, shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text,
            color = textColor,
            fontFamily = BodyFont,
            fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (on) {
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .width(16.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ui.Gold)
            )
        }
    }
}

@Composable
private fun SegmentFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ui.Glass)
            .border(1.dp, Ui.GlassBorder, shape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

/** Val i rader om [columns] (t.ex. 4:3 | 1:1 | 16:9), i Alifs glasram. */
@Composable
fun <T> ChoiceGrid(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3
) {
    SegmentFrame(modifier) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { option ->
                    SegmentOption(label(option), option == selected, { onSelect(option) }, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Alifs `Segmented` – val sida vid sida (t.ex. Svenska | English). */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    SegmentFrame(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { option ->
                SegmentOption(label(option), option == selected, { onSelect(option) }, Modifier.weight(1f))
            }
        }
    }
}

/** Alifs reglage: spår i guld med mörk knopp när det är på. */
@Composable
fun UsbSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    val knob by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    val track by animateColorAsState(if (checked) Ui.Gold else Ui.Card2, label = "track")
    val thumb by animateColorAsState(if (checked) Ui.OnGold else Ui.Muted, label = "thumb")
    Box(
        Modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track)
            .then(
                if (onCheckedChange != null) Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                else Modifier
            )
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(22.dp)
                .clip(CircleShape)
                .background(thumb)
        )
    }
}

/** Rad med text och reglage (Alifs `Toggle`). */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ui.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Ui.Muted, fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        UsbSwitch(checked = checked, onCheckedChange = null)
    }
}

/** Tunt reglage med rund guldknopp, rubrik och värde. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val gold = Ui.Gold
    val line = Ui.Line
    val ring = Ui.Card
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ui.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = gold, fontFamily = DisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            thumb = {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(gold.copy(alpha = 0.55f))
                        .padding(1.dp)
                        .clip(CircleShape)
                        .background(ring)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(gold)
                )
            },
            track = { state ->
                val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
                val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(line)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(gold)
                    )
                }
            }
        )
    }
}

/**
 * Alifs `Row` – rad med titel, undertext, valfritt värde och ›. [compact] = rad inuti ett kort;
 * annars ett eget kort runt raden.
 */
@Composable
fun NavItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") leading: ImageVector? = null,
    compact: Boolean = false,
    subtitleColor: Color = Ui.Muted,
    dot: Boolean = false,
    value: String? = null
) {
    val shape = RoundedCornerShape(if (compact) 12.dp else 22.dp)
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (compact) 52.dp else 60.dp)
            .clip(shape)
            .then(if (compact) Modifier else Modifier.background(Ui.Card).border(1.dp, Ui.Line, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 0.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ui.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, color = subtitleColor, fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                value,
                color = Ui.Muted,
                fontFamily = BodyFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp)
            )
        }
        if (dot) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Ui.Gold)
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Ui.Muted)
    }
}

/** Tunn avdelare. */
@Composable
fun LineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Ui.Line)
    )
}

/** Runt emblem med guldring. */
@Composable
fun Seal(size: Dp = 48.dp, iconSize: Dp = 22.dp, icon: ImageVector = Icons.Outlined.CameraAlt) {
    val gold = Ui.Gold
    val dark = Ui.isDark
    val inner = if (dark) listOf(Color(0xFF24413F), Color(0xFF142427)) else listOf(Ui.Card, Ui.Card2)
    Box(
        Modifier
            .size(size)
            .drawBehind {
                // mjukt sken under emblemet
                drawCircle(
                    Brush.radialGradient(
                        listOf(gold.copy(alpha = if (dark) 0.20f else 0.10f), Color.Transparent),
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
                    inner,
                    center = Offset(this.size.width * 0.35f, this.size.height * 0.30f),
                    radius = r * 1.4f
                ),
                radius = r
            )
            drawCircle(gold.copy(alpha = 0.65f), radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(gold.copy(alpha = 0.35f), radius = r - 4.5.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
        Icon(icon, contentDescription = null, tint = if (dark) Ui.GoldL else Ui.Teal, modifier = Modifier.size(iconSize))
    }
}

/**
 * Ordmärket: emblem + "SNC" i Cinzel med guldglans + tunn linje med undertext.
 * Glansen sveper över namnet en gång.
 */
@Composable
fun Wordmark(subtitle: String, modifier: Modifier = Modifier, small: Boolean = false, name: String = "SNC") {
    var textWidth by remember { mutableFloatStateOf(0f) }
    val shine = remember { Animatable(1f) }
    LaunchedEffect(Unit) { shine.animateTo(0f, tween(2800, delayMillis = 200)) }
    val w = if (textWidth > 0f) textWidth else 300f
    val startX = -1.2f * w * shine.value
    val brush = Ui.shineBrush(Offset(startX, 0f), Offset(startX + 2.2f * w, 0f))
    val gold = Ui.Gold
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Seal(size = if (small) 42.dp else 48.dp, iconSize = if (small) 20.dp else 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                name,
                style = TextStyle(
                    brush = brush,
                    fontFamily = SerifFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (small) 32.sp else 38.sp,
                    letterSpacing = 0.14.em,
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
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, gold)))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    subtitle.uppercase(Locale.getDefault()),
                    color = Ui.TealL,
                    fontFamily = BodyFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.38.em
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .width(14.dp)
                        .height(1.dp)
                        .background(Brush.horizontalGradient(listOf(gold, Color.Transparent)))
                )
            }
        }
    }
}

/** Stor rund guldknapp i två ringar (startskärmen när kameran saknar behörighet). */
@Composable
fun HeroButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outer: Dp = 188.dp
) {
    val gold = Ui.Gold
    Box(
        modifier
            .size(outer)
            .clip(CircleShape)
            .background(Ui.Glass)
            .border(1.dp, Ui.GlassBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(outer * 0.72f)
                .clip(CircleShape)
                .background(Ui.BtnBg)
                .border(1.dp, Ui.BtnBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(outer * 0.5f)
                    .drawBehind {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(gold.copy(alpha = 0.28f), Color.Transparent),
                                radius = size.minDimension * 0.8f
                            ),
                            radius = size.minDimension * 0.8f
                        )
                    }
                    .clip(CircleShape)
                    .background(gold)
                    .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = contentDescription, tint = Ui.OnGold, modifier = Modifier.size(outer * 0.2f))
            }
        }
    }
}

/** Liten statusbricka med prick. */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, color: Color = Ui.TealL, dot: Color = Ui.Teal) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(Ui.Card2)
            .border(1.dp, Ui.Line, shape)
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
