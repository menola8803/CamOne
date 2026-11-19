package se.snc.camera.scan

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.snc.camera.R
import se.snc.camera.text.copyToClipboard
import se.snc.camera.ui.theme.Glass
import se.snc.camera.ui.theme.GlassLine
import se.snc.camera.ui.theme.GlassStrong
import se.snc.camera.ui.theme.GoldTint
import se.snc.camera.ui.theme.Usb
import se.snc.camera.util.shareText
import kotlin.math.hypot
import kotlin.math.min

private fun CodeKind.icon(): ImageVector = when (this) {
    CodeKind.URL -> Icons.Rounded.Language
    CodeKind.WIFI -> Icons.Rounded.Wifi
    CodeKind.CONTACT -> Icons.Rounded.Person
    CodeKind.EMAIL -> Icons.Rounded.Email
    CodeKind.PHONE -> Icons.Rounded.Call
    CodeKind.SMS -> Icons.Rounded.Sms
    CodeKind.GEO -> Icons.Rounded.Map
    CodeKind.PRODUCT -> Icons.Rounded.QrCode2
    CodeKind.TEXT -> Icons.Rounded.TextFields
}

/** En liten knapp i glas med guldkant (kortens åtgärder). */
@Composable
fun ScanAction(icon: ImageVector, text: String, onClick: () -> Unit, primary: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (primary) GoldTint else Color.Transparent)
            .border(1.dp, if (primary) Usb.Gold.copy(alpha = 0.7f) else GlassLine, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) Usb.GoldL else Usb.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (primary) Usb.GoldL else Usb.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Kortet för en avläst kod: vad den innehåller och knappar för det man vill göra (öppna länken, ansluta till
 * Wi-Fi, ringa, mejla, kopiera, dela …). [onClose] = krysset (null = inget kryss).
 */
@Composable
fun CodeCard(code: ScannedCode, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shareVia = stringResource(R.string.share_via)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassStrong)
            .border(1.dp, GlassLine, shape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(GoldTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(code.kind.icon(), contentDescription = null, tint = Usb.GoldL, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${stringResource(code.kind.label)} · ${code.format}",
                    color = Usb.GoldL,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                val detail = when {
                    code.kind == CodeKind.WIFI && !code.password.isNullOrEmpty() ->
                        stringResource(R.string.scan_wifi_details, code.ssid ?: code.display, code.password)
                    code.kind == CodeKind.WIFI -> stringResource(R.string.scan_wifi_open, code.ssid ?: code.display)
                    else -> code.display
                }
                Text(detail, color = Usb.Text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (onClose != null) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = Usb.Muted,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (code.kind) {
                CodeKind.URL -> ScanAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.scan_open), { ScanCodes.openUrl(context, code.url ?: code.raw) }, primary = true)
                CodeKind.WIFI -> {
                    ScanAction(Icons.Rounded.Wifi, stringResource(R.string.scan_connect), { ScanCodes.connectWifi(context, code) }, primary = true)
                    code.password?.takeIf { it.isNotEmpty() }?.let { pw ->
                        ScanAction(Icons.Rounded.ContentCopy, stringResource(R.string.scan_copy_password), { copyToClipboard(context, pw) })
                    }
                }
                CodeKind.CONTACT -> ScanAction(Icons.Rounded.PersonAdd, stringResource(R.string.scan_add_contact), { ScanCodes.addContact(context, code) }, primary = true)
                CodeKind.EMAIL -> ScanAction(Icons.Rounded.Email, stringResource(R.string.scan_send_email), { ScanCodes.sendEmail(context, code) }, primary = true)
                CodeKind.PHONE -> code.phones.firstOrNull()?.let { n ->
                    ScanAction(Icons.Rounded.Call, stringResource(R.string.scan_call), { ScanCodes.dial(context, n) }, primary = true)
                }
                CodeKind.SMS -> ScanAction(Icons.Rounded.Sms, stringResource(R.string.scan_send_sms), { ScanCodes.sendSms(context, code) }, primary = true)
                CodeKind.GEO -> ScanAction(Icons.Rounded.Map, stringResource(R.string.scan_map), { ScanCodes.showOnMap(context, code) }, primary = true)
                CodeKind.PRODUCT -> ScanAction(Icons.Rounded.Search, stringResource(R.string.scan_search), { ScanCodes.searchWeb(context, code.raw) }, primary = true)
                CodeKind.TEXT -> Unit
            }
            ScanAction(Icons.Rounded.ContentCopy, stringResource(R.string.scan_copy), { copyToClipboard(context, code.raw) })
            ScanAction(Icons.Rounded.Share, stringResource(R.string.scan_share), { context.shareText(code.raw, shareVia) })
        }
    }
}

/** Kortet när PDF:en har sparats: öppna, dela eller stäng. */
@Composable
fun PdfSavedCard(uri: Uri, pages: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    val noApp = stringResource(R.string.msg_no_pdf_app)
    val shareVia = stringResource(R.string.share_via)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassStrong)
            .border(1.dp, GlassLine, shape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = Usb.GoldL, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.doc_pdf_saved, pages.toString()),
                color = Usb.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_close),
                tint = Usb.Muted,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(6.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.doc_open), {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(i)
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, noApp, android.widget.Toast.LENGTH_LONG).show()
                }
            }, primary = true)
            ScanAction(Icons.Rounded.Share, stringResource(R.string.doc_share), {
                val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(i, shareVia).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    }
}

/** Raden med sidorna som väntar: antal, Spara PDF och Släng. */
@Composable
fun DocPagesBar(pages: Int, busy: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassLine, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (busy) stringResource(R.string.doc_working) else stringResource(R.string.doc_pages, pages),
            color = Usb.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (!busy) {
            ScanAction(Icons.Rounded.PictureAsPdf, stringResource(R.string.doc_save_pdf), onSave, primary = true)
            ScanAction(Icons.Rounded.Delete, stringResource(R.string.doc_discard), onDiscard)
        }
    }
}

/** En kod som syns i sökaren: dess ruta i den upprätta analysbilden. */
class CodeBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Det analysen ser just nu i läget Skanna: rutor runt koder eller papperets hörn. */
class ScanFrame(
    val imageWidth: Int,
    val imageHeight: Int,
    val boxes: List<CodeBox> = emptyList(),
    /** Papperets hörn (normaliserade, upprätt), eller null. */
    val quad: FloatArray? = null
)

/** [ScanOverlay] som läser flödet själv, så att bara lagret ritas om för varje bildruta. */
@Composable
fun ScanOverlayFrom(frames: kotlinx.coroutines.flow.StateFlow<ScanFrame?>, modifier: Modifier = Modifier) {
    val frame by frames.collectAsStateWithLifecycle()
    ScanOverlay(frame, modifier)
}

/**
 * Ritar guldrutor runt koderna och papperets kanter ovanpå sökaren. Analysbilden har samma bildyta som
 * sökaren (4:3) och beskärs likadant (FILL_CENTER).
 */
@Composable
fun ScanOverlay(frame: ScanFrame?, modifier: Modifier = Modifier) {
    val f = frame ?: return
    if (f.imageWidth <= 0 || f.imageHeight <= 0) return
    Canvas(modifier.fillMaxSize()) {
        val scale = maxOf(size.width / f.imageWidth, size.height / f.imageHeight)
        val dx = (size.width - f.imageWidth * scale) / 2f
        val dy = (size.height - f.imageHeight * scale) / 2f
        val stroke = Stroke(width = 3.dp.toPx())
        f.boxes.forEach { b ->
            val pad = 6.dp.toPx()
            drawRoundRect(
                Usb.Gold,
                topLeft = Offset(dx + b.left * scale - pad, dy + b.top * scale - pad),
                size = androidx.compose.ui.geometry.Size((b.right - b.left) * scale + 2 * pad, (b.bottom - b.top) * scale + 2 * pad),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                style = stroke
            )
        }
        f.quad?.let { q ->
            val w = f.imageWidth * scale
            val h = f.imageHeight * scale
            val path = Path().apply {
                moveTo(dx + q[0] * w, dy + q[1] * h)
                lineTo(dx + q[2] * w, dy + q[3] * h)
                lineTo(dx + q[4] * w, dy + q[5] * h)
                lineTo(dx + q[6] * w, dy + q[7] * h)
                close()
            }
            drawPath(path, Usb.Gold.copy(alpha = 0.18f))
            drawPath(path, Usb.Gold, style = stroke)
        }
    }
}

/**
 * Dokumentets redigerare: den tagna bilden med fyra hörn i guld som går att dra, val av utseende
 * (svartvit, färg, original) och knapparna Ta om och Lägg till sida.
 */
@Composable
fun DocumentEditor(
    bitmap: Bitmap,
    initialQuad: FloatArray,
    onRetake: () -> Unit,
    onAdd: (FloatArray, DocFilter) -> Unit
) {
    val quad = remember(bitmap) { mutableStateOf(initialQuad.copyOf()) }
    // Färg är valt från början (användarens val); Svartvit för vitt papper och tydlig text
    var filter by remember { mutableStateOf(DocFilter.COLOR) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableIntStateOf(-1) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Usb.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Text(
            stringResource(R.string.doc_adjust_title),
            color = Usb.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp)
        )
        Text(
            stringResource(R.string.doc_adjust_hint),
            color = Usb.Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(20.dp)
                .onSizeChanged { box = it },
            contentAlignment = Alignment.Center
        ) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            val scale = if (box.width > 0) min(box.width / bw, box.height / bh) else 1f
            val left = (box.width - bw * scale) / 2f
            val top = (box.height - bh * scale) / 2f
            val iw = bw * scale
            val ih = bh * scale
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap, box) {
                        val grab = 44.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val q = quad.value
                            var best = -1
                            var bestD = Float.MAX_VALUE
                            for (i in 0 until 4) {
                                val d = hypot(left + q[i * 2] * iw - down.position.x, top + q[i * 2 + 1] * ih - down.position.y)
                                if (d < bestD) {
                                    bestD = d
                                    best = i
                                }
                            }
                            if (best < 0 || bestD > grab) return@awaitEachGesture
                            dragging = best
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                val d = c.positionChange()
                                c.consume()
                                val nq = quad.value.copyOf()
                                nq[best * 2] = (nq[best * 2] + d.x / iw).coerceIn(0f, 1f)
                                nq[best * 2 + 1] = (nq[best * 2 + 1] + d.y / ih).coerceIn(0f, 1f)
                                quad.value = nq
                            }
                            dragging = -1
                        }
                    }
            ) {
                val q = quad.value
                fun pt(i: Int) = Offset(left + q[i * 2] * iw, top + q[i * 2 + 1] * ih)
                val path = Path().apply {
                    moveTo(pt(0).x, pt(0).y)
                    for (i in 1..3) lineTo(pt(i).x, pt(i).y)
                    close()
                }
                drawPath(path, Usb.Gold.copy(alpha = 0.16f))
                drawPath(path, Usb.Gold, style = Stroke(width = 2.dp.toPx()))
                for (i in 0 until 4) {
                    val r = if (i == dragging) 16.dp.toPx() else 12.dp.toPx()
                    drawCircle(Usb.Bg.copy(alpha = 0.6f), radius = r + 2.dp.toPx(), center = pt(i))
                    drawCircle(Usb.Gold, radius = r, center = pt(i), style = Stroke(width = 3.dp.toPx()))
                    drawCircle(Usb.GoldL, radius = 3.dp.toPx(), center = pt(i))
                }
            }
        }
        // Utseende
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            listOf(
                DocFilter.COLOR to R.string.doc_filter_color,
                DocFilter.DOCUMENT to R.string.doc_filter_document,
                DocFilter.ORIGINAL to R.string.doc_filter_original
            ).forEach { (f, label) ->
                val selected = f == filter
                val shape = RoundedCornerShape(20.dp)
                Text(
                    stringResource(label),
                    color = if (selected) Usb.GoldL else Usb.Text,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(shape)
                        .background(if (selected) GoldTint else Color.Transparent)
                        .border(1.dp, if (selected) Usb.Gold.copy(alpha = 0.6f) else GlassLine, shape)
                        .selectable(selected = selected, role = Role.RadioButton) { filter = f }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.weight(1f)) {
                ScanWideButton(stringResource(R.string.doc_retake), primary = false, onClick = onRetake)
            }
            Box(Modifier.weight(1f)) {
                ScanWideButton(stringResource(R.string.doc_add_page), primary = true) { onAdd(quad.value.copyOf(), filter) }
            }
        }
    }
}

@Composable
private fun ScanWideButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(if (primary) GoldTint else Color.Transparent)
            .border(1.dp, if (primary) Usb.Gold.copy(alpha = 0.7f) else GlassLine, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (primary) Usb.GoldL else Usb.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
