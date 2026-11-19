package se.snc.camera.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.snc.camera.R
import se.snc.camera.data.MediaItem
import se.snc.camera.data.MediaOp
import se.snc.camera.data.MediaRepository
import se.snc.camera.ui.components.Seal
import se.snc.camera.ui.components.SquareIconButton
import se.snc.camera.ui.theme.CameraTheme
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.Usb
import se.snc.camera.util.isGranted
import se.snc.camera.util.openVideo
import se.snc.camera.util.shareMedia
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Frågar om fotobehörighet högst en gång per appstart. */
private var askedMediaPermission = false

/** Vad bildvisaren visar: allt, bara favoriterna eller papperskorgen. */
enum class ViewerFilter(@StringRes val label: Int) {
    ALL(R.string.viewer_all),
    FAVORITES(R.string.viewer_favorites),
    TRASH(R.string.viewer_trash)
}

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
        var filter by rememberSaveable { mutableStateOf(ViewerFilter.ALL) }
        var items by remember { mutableStateOf<List<MediaItem>?>(null) }
        var loadedFilter by remember { mutableStateOf<ViewerFilter?>(null) }
        var permissionTick by remember { mutableIntStateOf(0) }

        val mediaPermissions = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionTick++
        }
        LaunchedEffect(changes, permissionTick, filter) {
            val loaded = when (filter) {
                ViewerFilter.ALL -> media.loadMedia()
                ViewerFilter.FAVORITES -> media.loadMedia().filter { it.isFavorite }
                ViewerFilter.TRASH -> media.loadTrash()
            }
            items = loaded
            loadedFilter = filter
            // Appens egna bilder syns alltid. Behörigheten behövs bara för att se bilder från en
            // tidigare installation, så fråga bara när albumet ser tomt ut – och bara en gång.
            if (filter == ViewerFilter.ALL && loaded.isEmpty() && !askedMediaPermission &&
                mediaPermissions.none { context.isGranted(it) }
            ) {
                askedMediaPermission = true
                permissionLauncher.launch(mediaPermissions)
            }
        }

        // Androids egen dialog för filer som appen inte äger (t.ex. från en tidigare installation)
        val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            media.notifyChanged()
        }
        var undoItem by remember { mutableStateOf<MediaItem?>(null) }
        LaunchedEffect(undoItem) {
            if (undoItem != null) {
                delay(8000)
                undoItem = null
            }
        }
        var confirmDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
        val failedText = stringResource(R.string.viewer_op_failed)

        fun perform(op: suspend () -> MediaOp, onDone: () -> Unit = {}) {
            scope.launch {
                when (val r = op()) {
                    MediaOp.Done -> onDone()
                    is MediaOp.NeedsConsent -> runCatching {
                        consentLauncher.launch(IntentSenderRequest.Builder(r.intent).build())
                    }
                    MediaOp.Failed -> Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                }
            }
        }

        val list = items
        Box(
            Modifier
                .fillMaxSize()
                .background(Usb.Bg)
        ) {
            if (list == null || loadedFilter != filter) {
                CircularProgressIndicator(color = Usb.Gold, modifier = Modifier.align(Alignment.Center))
                ViewerTopBar(null, null, filter, onFilter = { filter = it }, onBack = onBack, onEmptyTrash = null)
            } else if (list.isEmpty()) {
                EmptyState(filter, Modifier.align(Alignment.Center))
                ViewerTopBar(null, null, filter, onFilter = { filter = it }, onBack = onBack, onEmptyTrash = null)
            } else key(filter) {
                val startIndex = remember {
                    if (filter == ViewerFilter.ALL) list.indexOfFirst { it.uri == startUri }.coerceAtLeast(0) else 0
                }
                val pagerState = rememberPagerState(initialPage = startIndex) { list.size }
                var chromeVisible by remember { mutableStateOf(true) }
                var showInfo by remember { mutableStateOf(false) }
                val current = list.getOrNull(pagerState.currentPage.coerceIn(0, list.size - 1))

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
                        onPlay = { if (!item.trashed) context.openVideo(item.uri) }
                    )
                }

                // Övre fältet
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    ViewerTopBar(
                        current = current,
                        counter = "${pagerState.currentPage + 1} / ${list.size}",
                        filter = filter,
                        onFilter = { filter = it },
                        onBack = onBack,
                        onEmptyTrash = if (filter == ViewerFilter.TRASH) ({ confirmDelete = list }) else null
                    )
                }

                // Nedre fältet
                AnimatedVisibility(
                    visible = chromeVisible && current != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    val item = current ?: return@AnimatedVisibility
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Usb.Bg.copy(alpha = 0.9f))))
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val undo = undoItem
                        if (undo != null && filter != ViewerFilter.TRASH) {
                            UndoPill(
                                onUndo = {
                                    perform({ media.setTrashed(listOf(undo), false) }) { undoItem = null }
                                }
                            )
                        }
                        if (item.trashed) {
                            val days = daysLeft(item.expiresAt)
                            if (days != null) {
                                Text(
                                    pluralStringResource(R.plurals.viewer_trash_days, days, days),
                                    color = Usb.Muted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        // Åtgärdsfält i samma stil som USB-appens meny (.nav)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(Usb.Card)
                                .border(1.dp, Usb.Line, RoundedCornerShape(18.dp))
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (item.trashed) {
                                BottomAction(Icons.Outlined.RestoreFromTrash, stringResource(R.string.action_restore), tint = Usb.Gold) {
                                    val restored = context.getString(R.string.viewer_restored)
                                    perform({ media.setTrashed(listOf(item), false) }) {
                                        Toast.makeText(context, restored, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                BottomAction(Icons.Outlined.Info, stringResource(R.string.action_info)) { showInfo = true }
                                BottomAction(Icons.Outlined.DeleteForever, stringResource(R.string.action_delete_forever), tint = Usb.Danger) {
                                    confirmDelete = listOf(item)
                                }
                            } else {
                                if (!item.isVideo) {
                                    BottomAction(Icons.Outlined.Edit, stringResource(R.string.action_edit)) { onEdit(item.uri) }
                                    BottomAction(Icons.Rounded.TextFields, stringResource(R.string.action_text)) { onExtractText(item.uri) }
                                } else {
                                    BottomAction(Icons.Rounded.PlayArrow, stringResource(R.string.action_play)) { context.openVideo(item.uri) }
                                }
                                BottomAction(
                                    if (item.isFavorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                                    stringResource(R.string.action_favorite),
                                    tint = if (item.isFavorite) Usb.Gold else Usb.Muted,
                                    selected = item.isFavorite
                                ) {
                                    perform({ media.setFavorite(listOf(item), !item.isFavorite) })
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
                                    // Till papperskorgen (går att ångra en stund och återställa i 30 dagar)
                                    perform({ media.setTrashed(listOf(item), true) }) { undoItem = item }
                                }
                            }
                        }
                    }
                }

                if (showInfo && current != null) {
                    InfoDialog(current, media) { showInfo = false }
                }
            }

            // Ångra syns även när den sista bilden flyttats (listan är tom)
            val undo = undoItem
            if (undo != null && list != null && list.isEmpty() && filter != ViewerFilter.TRASH) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                ) {
                    UndoPill(onUndo = { perform({ media.setTrashed(listOf(undo), false) }) { undoItem = null } })
                }
            }
        }

        confirmDelete?.let { toDelete ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                containerColor = Usb.Card,
                title = { Text(stringResource(R.string.viewer_delete_title), fontFamily = DisplayFont, fontWeight = FontWeight.Bold) },
                text = { Text(pluralStringResource(R.plurals.viewer_delete_text, toDelete.size, toDelete.size), color = Usb.Muted) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = null
                        perform({ media.deleteForever(toDelete) })
                    }) { Text(stringResource(R.string.viewer_delete_confirm), color = Usb.Danger, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel), color = Usb.Muted) }
                }
            )
        }
    }
}

/** Dagar kvar tills Android tar bort filen ur papperskorgen. */
private fun daysLeft(expiresAt: Long): Int? {
    if (expiresAt <= 0L) return null
    val ms = expiresAt - System.currentTimeMillis()
    return ceil(ms / 86_400_000.0).toInt().coerceAtLeast(0)
}

@Composable
private fun ViewerTopBar(
    current: MediaItem?,
    counter: String?,
    filter: ViewerFilter,
    onFilter: (ViewerFilter) -> Unit,
    onBack: () -> Unit,
    onEmptyTrash: (() -> Unit)?
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Usb.Bg.copy(alpha = 0.92f), Usb.Bg.copy(alpha = 0.6f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            if (counter != null) {
                Text(
                    counter,
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier
                    .clip(shape)
                    .background(Usb.Card.copy(alpha = 0.92f))
                    .border(1.dp, Usb.Line, shape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ViewerFilter.entries.forEach { f ->
                    val selected = f == filter
                    Text(
                        stringResource(f.label),
                        color = if (selected) Usb.Bg else Usb.Muted,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (selected) Usb.Gold else Color.Transparent)
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                            }
                            .clickable { onFilter(f) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (onEmptyTrash != null) {
                Text(
                    stringResource(R.string.viewer_empty_trash),
                    color = Usb.Danger,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Usb.Card.copy(alpha = 0.92f))
                        .border(1.dp, Usb.Danger.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .clickable(onClick = onEmptyTrash)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun UndoPill(onUndo: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .clip(shape)
            .background(Usb.Card2)
            .border(1.dp, Usb.Line, shape)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.viewer_moved_to_trash), color = Usb.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onUndo) {
            Text(stringResource(R.string.viewer_undo), color = Usb.Gold, fontWeight = FontWeight.Bold)
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
    // null = laddas fortfarande; en tom lista = gick inte att visa
    val image by produceState<List<ImageBitmap>?>(null, item.uri) {
        val bmp = when {
            item.trashed -> media.loadTrashPreview(item)
            item.isVideo -> media.loadThumbnail(item.uri, 1280)
            else -> media.loadBitmap(item.uri, 2560)
        }
        value = listOfNotNull(bmp?.asImageBitmap())
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val loaded = image
        val bmp = loaded?.firstOrNull()
        when {
            loaded == null -> CircularProgressIndicator(color = Usb.Gold.copy(alpha = 0.8f), strokeWidth = 2.dp)
            bmp == null -> Column(
                Modifier.clickable(onClick = onTap).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Seal(size = 72.dp, iconSize = 30.dp, icon = if (item.isVideo) Icons.Rounded.PlayArrow else Icons.Outlined.PhotoLibrary)
                Spacer(Modifier.height(12.dp))
                Text(item.name, color = Usb.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            item.isVideo -> {
                Image(
                    bitmap = bmp,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onTap)
                )
                if (!item.trashed) {
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
private fun RowScope.BottomAction(
    icon: ImageVector,
    label: String,
    tint: Color = Usb.Muted,
    selected: Boolean? = null,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(13.dp))
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun EmptyState(filter: ViewerFilter, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Seal(
            size = 72.dp,
            iconSize = 30.dp,
            icon = when (filter) {
                ViewerFilter.ALL -> Icons.Outlined.PhotoLibrary
                ViewerFilter.FAVORITES -> Icons.Outlined.FavoriteBorder
                ViewerFilter.TRASH -> Icons.Outlined.Delete
            }
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(
                when (filter) {
                    ViewerFilter.ALL -> R.string.gallery_empty
                    ViewerFilter.FAVORITES -> R.string.viewer_no_favorites
                    ViewerFilter.TRASH -> R.string.viewer_trash_empty
                }
            ),
            color = Usb.Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InfoDialog(item: MediaItem, media: MediaRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val location by produceState<Pair<Double, Double>?>(null, item.uri) { value = media.readLocation(item) }
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
                val loc = location
                if (loc != null) {
                    Column {
                        Text(stringResource(R.string.info_location), fontSize = 12.sp, color = Usb.Dim)
                        Text(
                            String.format(Locale.US, "%.5f, %.5f", loc.first, loc.second),
                            fontSize = 15.sp,
                            color = Usb.Text,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.info_show_map),
                            color = Usb.Gold,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val q = String.format(Locale.US, "%.6f,%.6f", loc.first, loc.second)
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure {
                                        Toast.makeText(context, R.string.scan_no_app, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
                if (item.trashed && item.expiresAt > 0) {
                    InfoRow(
                        stringResource(R.string.info_expires),
                        DateFormat.getLongDateFormat(context).format(Date(item.expiresAt))
                    )
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
