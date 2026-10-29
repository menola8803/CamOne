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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import se.snc.camera.ui.theme.GlassStrong
import se.snc.camera.util.isGranted
import se.snc.camera.util.openVideo
import se.snc.camera.util.shareMedia
import java.util.Date

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
        LaunchedEffect(Unit) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && mediaPermissions.none { context.isGranted(it) }) {
                permissionLauncher.launch(mediaPermissions)
            }
        }
        LaunchedEffect(changes, permissionTick) {
            items = media.loadMedia()
        }

        val list = items
        if (list == null) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
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
                .background(Color.Black)
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
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color.White)
                    }
                    if (current != null) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                DateFormat.getMediumDateFormat(context).format(Date(current.dateTaken)),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                DateFormat.getTimeFormat(context).format(Date(current.dateTaken)),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        "${pagerState.currentPage + 1} / ${list.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(end = 12.dp)
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (!item.isVideo) {
                        BottomAction(Icons.Rounded.Edit, stringResource(R.string.action_edit)) { onEdit(item.uri) }
                        BottomAction(Icons.Rounded.TextFields, stringResource(R.string.action_text)) { onExtractText(item.uri) }
                    } else {
                        BottomAction(Icons.Rounded.PlayArrow, stringResource(R.string.action_play)) { context.openVideo(item.uri) }
                    }
                    BottomAction(Icons.Rounded.Share, stringResource(R.string.action_share)) {
                        context.shareMedia(
                            listOf(item.uri),
                            if (item.isVideo) "video/*" else "image/*",
                            context.getString(R.string.share_via)
                        )
                    }
                    BottomAction(Icons.Rounded.Info, stringResource(R.string.action_info)) { showInfo = true }
                    BottomAction(Icons.Rounded.Delete, stringResource(R.string.action_delete)) {
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
            bmp == null -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f), strokeWidth = 2.dp)
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
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.action_play), tint = Color.White, modifier = Modifier.size(44.dp))
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
private fun BottomAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun EmptyGallery(onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color.White)
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.gallery_empty), color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun InfoDialog(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(stringResource(R.string.action_info)) },
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
        containerColor = GlassStrong.copy(alpha = 0.95f)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
        Text(value, fontSize = 15.sp, color = Color.White)
    }
}
