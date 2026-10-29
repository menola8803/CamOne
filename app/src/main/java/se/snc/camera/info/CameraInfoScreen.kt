package se.snc.camera.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.snc.camera.BuildConfig
import se.snc.camera.R
import se.snc.camera.text.copyToClipboard
import se.snc.camera.ui.components.LineDivider
import se.snc.camera.ui.components.MutedText
import se.snc.camera.ui.components.PrimaryButton
import se.snc.camera.ui.components.ScreenHeader
import se.snc.camera.ui.components.SecondaryButton
import se.snc.camera.ui.components.SectionLabel
import se.snc.camera.ui.components.UsbCard
import se.snc.camera.ui.theme.BodyFont
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.Usb
import se.snc.camera.util.shareText
import java.util.Locale

/** Teal = används av appen, guld = telefonen klarar det men det är inte byggt än, röd = finns inte. */
private enum class Level { USED, POSSIBLE, NO }

private fun mp(v: Float) = String.format(Locale.getDefault(), "%.1f", v)

@Composable
fun CameraInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val report by produceState<CameraReport?>(null) { value = CameraProbe.probe(context) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Usb.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        ScreenHeader(
            title = stringResource(R.string.cams_title),
            subtitle = stringResource(R.string.cams_sub),
            backDescription = stringResource(R.string.cd_back),
            onBack = onBack
        )
        Spacer(Modifier.height(14.dp))

        val r = report
        if (r == null) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = Usb.Gold)
                Spacer(Modifier.height(12.dp))
                MutedText(stringResource(R.string.cams_loading))
            }
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Summary(r)
            r.lenses.forEach { LensCard(it) }
            Text(
                "${r.device}\n${r.android}",
                color = Usb.Dim,
                fontFamily = BodyFont,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        Column(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val text = CameraProbe.report(r, BuildConfig.VERSION_NAME)
            PrimaryButton(
                text = stringResource(R.string.cams_copy),
                onClick = { copyToClipboard(context, text) },
                icon = Icons.Outlined.ContentCopy,
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryButton(
                text = stringResource(R.string.cams_share),
                onClick = { context.shareText(text, context.getString(R.string.share_via)) },
                icon = Icons.Outlined.Share,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Summary(r: CameraReport) {
    UsbCard(spacing = 12.dp) {
        SectionLabel(stringResource(R.string.cams_summary), color = Usb.Gold)

        @Composable
        fun accessRow(label: String, kind: LensKind) {
            val a = r.access(kind)
            StatusRow(
                label,
                stringResource(
                    when (a) {
                        Access.ZOOM -> R.string.cams_access_zoom
                        Access.OWN_CAMERA -> R.string.cams_access_own
                        Access.PHYSICAL -> R.string.cams_access_physical
                        Access.HIDDEN -> R.string.cams_access_hidden
                        Access.NONE -> R.string.cams_access_none
                    }
                ),
                when (a) {
                    Access.ZOOM -> Level.USED
                    Access.OWN_CAMERA, Access.PHYSICAL -> Level.POSSIBLE
                    else -> Level.NO
                }
            )
        }
        accessRow(stringResource(R.string.cams_uw), LensKind.ULTRA_WIDE)
        accessRow(stringResource(R.string.cams_tele), LensKind.TELE)

        val hires = r.maxResMp
        val normalMp = r.backListed.maxOfOrNull { it.megapixels } ?: 0f
        StatusRow(
            stringResource(R.string.cams_hires),
            if (hires != null) stringResource(R.string.cams_hires_yes, mp(hires)) else stringResource(R.string.cams_hires_no, mp(normalMp)),
            if (hires != null) Level.POSSIBLE else Level.NO
        )

        @Composable
        fun fpsRow(label: String, fps: Int?) {
            StatusRow(
                label,
                when {
                    fps == null -> stringResource(R.string.cams_unknown)
                    fps >= 55 -> stringResource(R.string.cams_fps_yes)
                    else -> stringResource(R.string.cams_fps_no, fps)
                },
                if (fps != null && fps >= 55) Level.POSSIBLE else Level.NO
            )
        }
        fpsRow(stringResource(R.string.cams_4k60), r.maxFps4k)
        fpsRow(stringResource(R.string.cams_1080_60), r.maxFps1080)

        val hs = r.bestHighSpeed
        StatusRow(
            stringResource(R.string.cams_slowmo),
            if (hs != null) stringResource(R.string.cams_not_built_value, "${hs.first.height}p ${hs.second} fps")
            else stringResource(R.string.cams_no),
            if (hs != null) Level.POSSIBLE else Level.NO
        )
        StatusRow(
            stringResource(R.string.cams_dual),
            stringResource(if (r.dualView) R.string.cams_can_not_built else R.string.cams_no),
            if (r.dualView) Level.POSSIBLE else Level.NO
        )
        StatusRow(
            stringResource(R.string.cams_pro),
            stringResource(if (r.manual) R.string.cams_used else R.string.cams_no),
            if (r.manual) Level.USED else Level.NO
        )
        StatusRow(
            stringResource(R.string.cams_raw),
            stringResource(if (r.raw) R.string.cams_can_not_built else R.string.cams_no),
            if (r.raw) Level.POSSIBLE else Level.NO
        )
        val ois = r.backListed.any { it.ois }
        StatusRow(
            stringResource(R.string.cams_ois),
            stringResource(if (ois) R.string.cams_used else R.string.cams_no),
            if (ois) Level.USED else Level.NO
        )
        val ext = (r.extensions.back + r.extensions.front).distinct()
        StatusRow(
            stringResource(R.string.cams_ext),
            if (ext.isNotEmpty()) stringResource(R.string.cams_used_value, ext.joinToString(", "))
            else stringResource(R.string.cams_ext_no),
            if (ext.isNotEmpty()) Level.USED else Level.NO
        )

        LineDivider(Modifier.padding(top = 2.dp))
        Text(stringResource(R.string.cams_legend), color = Usb.Dim, fontFamily = BodyFont, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun StatusRow(label: String, value: String, level: Level) {
    val (icon, color) = when (level) {
        Level.USED -> Icons.Outlined.Check to Usb.Teal
        Level.POSSIBLE -> Icons.Outlined.Star to Usb.Gold
        Level.NO -> Icons.Outlined.Close to Usb.Danger
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Usb.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(value, color = if (level == Level.NO) Usb.Dim else color, fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun LensCard(l: LensInfo) {
    UsbCard(spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                "${stringResource(if (l.front) R.string.cams_front else R.string.cams_back)} · ID ${l.id}",
                modifier = Modifier.weight(1f)
            )
            when {
                !l.listed && l.parentId == null -> Badge(stringResource(R.string.cams_hidden_badge), Usb.Danger)
                l.parentId != null -> Badge(stringResource(R.string.cams_physical_badge, l.parentId), Usb.Dim)
                l.logical -> Badge(stringResource(R.string.cams_logical_badge), Usb.Teal)
            }
        }
        Text(
            buildString {
                append(
                    stringResource(
                        when (l.kind) {
                            LensKind.ULTRA_WIDE -> R.string.cams_kind_uw
                            LensKind.WIDE -> R.string.cams_kind_wide
                            LensKind.TELE -> R.string.cams_kind_tele
                            LensKind.FRONT -> R.string.cams_kind_front
                            LensKind.OTHER -> R.string.cams_kind_other
                        }
                    )
                )
                l.eqMm?.let { append(" · $it mm") }
            },
            color = Usb.Text,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 21.sp
        )
        Spacer(Modifier.height(2.dp))
        InfoLine(
            stringResource(R.string.cams_resolution),
            l.maxResMegapixels?.let { stringResource(R.string.cams_mp_hires, mp(l.megapixels), mp(it)) }
                ?: stringResource(R.string.cams_mp_value, mp(l.megapixels))
        )
        InfoLine(
            stringResource(R.string.cams_optics),
            listOfNotNull(l.sensorInch, l.aperture?.let { "f/$it" }, l.fovDeg?.let { "$it°" }).joinToString(" · ").ifEmpty { "–" }
        )
        InfoLine(
            stringResource(R.string.cams_video),
            listOfNotNull(l.maxFps4k?.let { "4K $it fps" }, l.maxFps1080?.let { "1080p $it fps" }).joinToString(" · ").ifEmpty { "–" }
        )
        if (l.highSpeed.isNotEmpty()) {
            InfoLine(stringResource(R.string.cams_slowmo), l.highSpeed.take(3).joinToString(" · ") { "${it.first.height}p ${it.second}" })
        }
        InfoLine(
            stringResource(R.string.cams_stab),
            listOfNotNull(if (l.ois) "OIS" else null, if (l.eis) "EIS" else null).joinToString(" + ").ifEmpty { "–" }
        )
        InfoLine(
            stringResource(R.string.cams_focus),
            if (l.fixedFocus) stringResource(R.string.cams_focus_fixed)
            else stringResource(R.string.cams_focus_auto, l.minFocusCm ?: 0)
        )
        if (l.isoMin != null && l.isoMax != null) {
            InfoLine(
                stringResource(R.string.cams_manual),
                stringResource(
                    R.string.cams_manual_value,
                    l.isoMin,
                    l.isoMax,
                    l.maxExposureSec?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "–"
                )
            )
        }
        if (l.zoomMin != null && l.zoomMax != null && (l.zoomMin < 0.99f || l.zoomMax > 1.01f)) {
            InfoLine(
                stringResource(R.string.cams_zoom),
                String.format(Locale.getDefault(), "%.1f× – %.1f×", l.zoomMin, l.zoomMax)
            )
        }
        InfoLine(
            stringResource(R.string.cams_other),
            listOfNotNull(
                if (l.raw) "RAW" else null,
                if (l.manualSensor) "Pro" else null,
                if (l.flash) stringResource(R.string.cams_flash) else null,
                l.hardwareLevel,
                l.camera2Extensions.takeIf { it.isNotEmpty() }?.joinToString("/")
            ).joinToString(" · ")
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Usb.Dim, fontFamily = BodyFont, fontSize = 14.sp, modifier = Modifier.weight(0.38f))
        Text(value, color = Usb.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(0.62f))
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = Usb.Bg,
        fontFamily = BodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 9.dp, vertical = 2.dp)
    )
}
