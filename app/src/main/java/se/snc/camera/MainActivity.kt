package se.snc.camera

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import se.snc.camera.camera.CameraMode
import se.snc.camera.camera.CameraScreen
import se.snc.camera.camera.CameraViewModel
import se.snc.camera.data.VolumeKeyAction
import se.snc.camera.editor.EditorScreen
import se.snc.camera.gallery.ViewerScreen
import se.snc.camera.settings.AboutScreen
import se.snc.camera.settings.SettingsScreen
import se.snc.camera.text.TextRecognitionScreen
import se.snc.camera.update.UpdateScreen
import se.snc.camera.info.CameraInfoScreen
import se.snc.camera.ui.theme.SncTheme
import se.snc.camera.ui.theme.CameraTheme
import se.snc.camera.data.AppSettings
import se.snc.camera.data.ThemeOption
import se.snc.camera.manual.ManualScreen
import se.snc.camera.update.UpdateRestart
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import se.snc.camera.util.HardwareKeys
import se.snc.camera.util.KeyCommand

sealed interface Screen {
    data object Camera : Screen
    data class Viewer(val uri: Uri?) : Screen
    data class Editor(val uri: Uri) : Screen
    data class Text(val uri: Uri) : Screen
    data object Settings : Screen
    data object About : Screen
    data class Update(val autoStart: Boolean = false) : Screen
    data object Cameras : Screen
    data object Manual : Screen
}

class MainActivity : ComponentActivity() {

    private val backStack = mutableStateListOf<Screen>(Screen.Camera)
    private var requestedMode by mutableStateOf<CameraMode?>(null)
    /** Visas en gång i kameran efter en uppdatering: "SNC Camera är uppdaterad till …". */
    private var updatedTo by mutableStateOf<String?>(null)

    private val app get() = application as SncCameraApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val cameraViewModel: CameraViewModel = viewModel()
            val screen = backStack.last()
            val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

            // Kameran först: allt annat startar först när sökaren redan är igång.
            LaunchedEffect(Unit) {
                delay(2500)
                // Sök efter ny version i bakgrunden (om "Sök automatiskt" är på)
                app.updateManager.autoCheckIfDue()
                updatedTo = withContext(Dispatchers.IO) { UpdateRestart.consumeJustUpdated(this@MainActivity) }
            }

            // Ljust tema på inställningssidorna: mörka ikoner i statusfältet
            val lightBars = settings.theme == ThemeOption.BEIGE && screen.isThemed()
            LaunchedEffect(lightBars) {
                val style = if (lightBars) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                else SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val beige = settings.theme == ThemeOption.BEIGE

            LaunchedEffect(requestedMode) {
                requestedMode?.let {
                    cameraViewModel.requestMode(it)
                    requestedMode = null
                }
            }

            BackHandler(enabled = backStack.size > 1) { pop() }

            CameraTheme {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220))) togetherWith
                            fadeOut(tween(160))
                    },
                    contentKey = { it },
                    label = "screens"
                ) { s ->
                    when (s) {
                        Screen.Camera -> CameraScreen(
                            viewModel = cameraViewModel,
                            onOpenGallery = { uri -> push(Screen.Viewer(uri)) },
                            onOpenSettings = { push(Screen.Settings) },
                            updatedTo = updatedTo,
                            onDismissUpdated = { updatedTo = null }
                        )
                        is Screen.Viewer -> ViewerScreen(
                            media = app.mediaRepository,
                            startUri = s.uri,
                            onBack = { pop() },
                            onEdit = { push(Screen.Editor(it)) },
                            onExtractText = { push(Screen.Text(it)) }
                        )
                        is Screen.Editor -> EditorScreen(
                            media = app.mediaRepository,
                            uri = s.uri,
                            onClose = { pop() },
                            onSaved = { newUri -> onEditorSaved(newUri) }
                        )
                        is Screen.Text -> TextRecognitionScreen(
                            media = app.mediaRepository,
                            uri = s.uri,
                            onBack = { pop() }
                        )
                        Screen.Settings -> SncTheme(beige) {
                            SettingsScreen(
                                repository = app.settingsRepository,
                                onBack = { pop() },
                                onAbout = { push(Screen.About) },
                                onUpdates = { push(Screen.Update()) },
                                onCameras = { push(Screen.Cameras) },
                                onManual = { push(Screen.Manual) },
                                onUpdateNow = { push(Screen.Update(autoStart = true)) }
                            )
                        }
                        Screen.Cameras -> SncTheme(beige) { CameraInfoScreen(onBack = { pop() }) }
                        is Screen.Update -> SncTheme(beige) {
                            UpdateScreen(
                                manager = app.updateManager,
                                repository = app.settingsRepository,
                                onBack = { pop() },
                                autoStart = s.autoStart
                            )
                        }
                        Screen.About -> SncTheme(beige) { AboutScreen(onBack = { pop() }) }
                        Screen.Manual -> SncTheme(beige) { ManualScreen(onBack = { pop() }) }
                    }
                }
            }
        }
    }

    private fun Screen.isThemed() = this is Screen.Settings || this is Screen.Cameras || this is Screen.Update ||
        this is Screen.About || this is Screen.Manual

    private fun push(screen: Screen) {
        backStack.add(screen)
    }

    private fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else finish()
    }

    /** Efter sparad redigering: visa den nya bilden i bildvisaren. */
    private fun onEditorSaved(newUri: Uri) {
        backStack.removeAt(backStack.lastIndex)
        val previous = backStack.lastOrNull()
        if (previous is Screen.Viewer) {
            backStack[backStack.lastIndex] = Screen.Viewer(newUri)
        } else {
            backStack.add(Screen.Viewer(newUri))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_EDIT -> intent.data?.let {
                resetTo(Screen.Camera)
                push(Screen.Editor(it))
            }
            Intent.ACTION_SEND -> intent.streamUri()?.let {
                resetTo(Screen.Camera)
                push(Screen.Text(it))
            }
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA -> {
                resetTo(Screen.Camera)
                requestedMode = CameraMode.PHOTO
            }
            MediaStore.INTENT_ACTION_VIDEO_CAMERA -> {
                resetTo(Screen.Camera)
                requestedMode = CameraMode.VIDEO
            }
            // Snabbinställningsrutan "Skanna QR"
            se.snc.camera.scan.ScanTileService.ACTION_SCAN -> {
                resetTo(Screen.Camera)
                requestedMode = CameraMode.SCAN
            }
        }
    }

    private fun resetTo(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (HardwareKeys.cameraActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> when (HardwareKeys.volumeAction) {
                    VolumeKeyAction.SHUTTER -> {
                        if (event.repeatCount == 0) HardwareKeys.emit(KeyCommand.SHUTTER)
                        return true
                    }
                    VolumeKeyAction.ZOOM -> {
                        HardwareKeys.emit(
                            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) KeyCommand.ZOOM_IN else KeyCommand.ZOOM_OUT
                        )
                        return true
                    }
                    VolumeKeyAction.VOLUME -> Unit
                }
                KeyEvent.KEYCODE_CAMERA -> {
                    if (event.repeatCount == 0) HardwareKeys.emit(KeyCommand.SHUTTER)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (HardwareKeys.cameraActive && HardwareKeys.volumeAction != VolumeKeyAction.VOLUME &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) return true
        return super.onKeyUp(keyCode, event)
    }
}
