package se.snc.camera.gallery

import android.Manifest
import android.app.Activity
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import se.snc.camera.R
import se.snc.camera.data.MediaItem
import se.snc.camera.data.MediaRepository
import se.snc.camera.ui.theme.CameraTheme
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.Usb
import se.snc.camera.ui.components.Seal
import se.snc.camera.ui.components.SquareIconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import se.snc.camera.util.isGranted
import se.snc.camera.util.openVideo
import se.snc.camera.util.shareMedia
import java.util.Date

/** Frågar om fotobehörighet högst en gång per appstart. */
private var askedMediaPermission = false

@Composable
fun ViewerScreen(
    media: MediaRepository,
    startUri: Uri?,
    onBack: () -> Unit,
    onEdit: (Uri) -> Unit,
    onExtractText: (Uri) -> Unit
) {
    CameraTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val changes by media.changes.collectAsState()
        var items by remember { mutableStateOf<List<MediaItem>?>(null) }
        var permissionTick by remember { mutableStateOf(0) }

        val mediaPermissions = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionTick++
        }
        LaunchedEffect(changes, permissionTick) {
            val loaded = media.loadMedia()
            items = loaded
            // Appens egna bilder syns alltid. Behörigheten behövs bara för att se bilder från en
            // tidigare installation, så fråga bara när albumet ser tomt ut – och bara en gång.
            if (loaded.isEmpty() && !askedMediaPermission &&
                mediaPermissions.none { context.isGranted(it) }
            ) {
                askedMediaPermission = true
                permissionLauncher.launch(mediaPermissions)
            }
        }

        val list = items
        if (list == null) {
            Box(Modifier.fillMaxSize().background(Usb.Bg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Usb.Gold)
            }
            return@CameraTheme
        }
        if (list.isEmpty()) {
            EmptyGallery(onBack)
            return@CameraTheme
        }

        val startIndex = remember(list.isNotEmpty()) {
            list.indexOfFirst { it.uri == startUri }.coerceAtLeast(0)
        }
        val pagerState = rememberPagerState(initialPage = startIndex) { list.size }
        var chromeVisible by remember { mutableStateOf(true) }
        var showInfo by remember { mutableStateOf(false) }
        val current = list.getOrNull(pagerState.currentPage.coerceIn(0, list.size - 1))

        val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                media.notifyChanged()
                if (list.size <= 1) onBack()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Usb.Bg)
        ) {
            HorizontalPager(
                state = pagerState,
                key = { list[it].id * if (list[it].isVideo) -1 else 1 },
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = list[page]
                MediaPage(
                    item = item,
                    media = media,
                    isCurrent = page == pagerState.currentPage,
                    onTap = { chromeVisible = !chromeVisible },
                    onPlay = { context.openVideo(item.uri) }
                )
            }

            // Övre fältet
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Usb.Bg.copy(alpha = 0.9f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SquareIconButton(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        stringResource(R.string.cd_back),
                        onBack,
                        glass = true,
                        tint = Usb.Text
                    )
                    Spacer(Modifier.width(14.dp))
                    if (current != null) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                DateFormat.getMediumDateFormat(context).format(Date(current.dateTaken)),
                                color = Usb.Text,
                                fontFamily = DisplayFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                DateFormat.getTimeFormat(context).format(Date(current.dateTaken)),
                                color = Usb.Dim,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        "${pagerState.currentPage + 1} / ${list.size}",
                        color = Usb.TealL,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Usb.Card2.copy(alpha = 0.9f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Nedre fältet
            AnimatedVisibility(
                visible = chromeVisible && current != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val item = current ?: return@AnimatedVisibility
                // Åtgärdsfält i samma stil som USB-appens meny (.nav)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Usb.Bg.copy(alpha = 0.9f))))
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Usb.Card)
                        .border(1.dp, Usb.Line, RoundedCornerShape(18.dp))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!item.isVideo) {
                        BottomAction(Icons.Outlined.Edit, stringResource(R.string.action_edit)) { onEdit(item.uri) }
                        BottomAction(Icons.Rounded.TextFields, stringResource(R.string.action_text)) { onExtractText(item.uri) }
                    } else {
                        BottomAction(Icons.Rounded.PlayArrow, stringResource(R.string.action_play)) { context.openVideo(item.uri) }
                    }
                    BottomAction(Icons.Outlined.Share, stringResource(R.string.action_share)) {
                        context.shareMedia(
                            listOf(item.uri),
                            if (item.isVideo) "video/*" else "image/*",
                            context.getString(R.string.share_via)
                        )
                    }
                    BottomAction(Icons.Outlined.Info, stringResource(R.string.action_info)) { showInfo = true }
                    BottomAction(Icons.Outlined.Delete, stringResource(R.string.action_delete), tint = Usb.Danger) {
                        scope.launch {
                            runCatching {
                                deleteLauncher.launch(IntentSenderRequest.Builder(media.deleteRequest(listOf(item.uri))).build())
                            }
                        }
                    }
                }
            }
        }

        if (showInfo && current != null) {
            InfoDialog(current) { showInfo = false }
        }
    }
}

@Composable
private fun MediaPage(
    item: MediaItem,
    media: MediaRepository,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onPlay: () -> Unit
) {
    val image by produceState<ImageBitmap?>(null, item.uri) {
        value = if (item.isVideo) {
            media.loadThumbnail(item.uri, 1280)?.asImageBitmap()
        } else {
            media.loadBitmap(item.uri, 2560)?.asImageBitmap()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = image
        when {
            bmp == null -> CircularProgressIndicator(color = Usb.Gold.copy(alpha = 0.8f), strokeWidth = 2.dp)
            item.isVideo -> {
                Image(
                    bitmap = bmp,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onTap)
                )
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Usb.Gold)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.action_play), tint = Usb.Bg, modifier = Modifier.size(44.dp))
                }
            }
            else -> se.snc.camera.ui.components.ZoomableImage(
                bitmap = bmp,
                contentDescription = item.name,
                onTap = onTap,
                resetKey = isCurrent
            )
        }
    }
}

@Composable
private fun RowScope.BottomAction(icon: ImageVector, label: String, tint: Color = Usb.Muted, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun EmptyGallery(onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Usb.Bg)
    ) {
        SquareIconButton(
            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            stringResource(R.string.cd_back),
            onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, top = 12.dp)
        )
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Seal(size = 72.dp, iconSize = 30.dp, icon = Icons.Outlined.PhotoLibrary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.gallery_empty), color = Usb.Muted, fontSize = 15.sp)
        }
    }
}

@Composable
private fun InfoDialog(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok), color = Usb.Gold, fontWeight = FontWeight.SemiBold) } },
        title = { Text(stringResource(R.string.action_info), fontFamily = DisplayFont, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow(stringResource(R.string.info_name), item.name)
                InfoRow(
                    stringResource(R.string.info_date),
                    DateFormat.getLongDateFormat(context).format(Date(item.dateTaken)) + " " +
                        DateFormat.getTimeFormat(context).format(Date(item.dateTaken))
                )
                if (item.width > 0 && item.height > 0) {
                    val mp = item.width.toLong() * item.height / 1_000_000.0
                    InfoRow(stringResource(R.string.info_resolution), "${item.width} × ${item.height} (${String.format("%.1f", mp)} MP)")
                }
                InfoRow(stringResource(R.string.info_size), Formatter.formatShortFileSize(context, item.sizeBytes))
                if (item.isVideo) {
                    val s = item.durationMs / 1000
                    InfoRow(stringResource(R.string.info_duration), String.format("%d:%02d", s / 60, s % 60))
                }
                InfoRow(stringResource(R.string.info_folder), MediaRepository.RELATIVE_PATH)
            }
        },
        containerColor = Usb.Card
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = Usb.Dim)
        Text(value, fontSize = 15.sp, color = Usb.Text, fontWeight = FontWeight.SemiBold)
    }
}
