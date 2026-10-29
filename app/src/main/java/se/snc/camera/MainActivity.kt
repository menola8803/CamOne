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
import se.snc.camera.ui.theme.SncTheme
import se.snc.camera.util.HardwareKeys
import se.snc.camera.util.KeyCommand

sealed interface Screen {
    data object Camera : Screen
    data class Viewer(val uri: Uri?) : Screen
    data class Editor(val uri: Uri) : Screen
    data class Text(val uri: Uri) : Screen
    data object Settings : Screen
    data object About : Screen
    data object Update : Screen
}

class MainActivity : ComponentActivity() {

    private val backStack = mutableStateListOf<Screen>(Screen.Camera)
    private var requestedMode by mutableStateOf<CameraMode?>(null)

    private val app get() = application as SncCameraApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        // Sök efter ny version i bakgrunden (om "Sök automatiskt" är på)
        app.updateManager.autoCheckIfDue()

        setContent {
            val cameraViewModel: CameraViewModel = viewModel()
            val screen = backStack.last()

            LaunchedEffect(requestedMode) {
                requestedMode?.let {
                    cameraViewModel.requestMode(it)
                    requestedMode = null
                }
            }

            BackHandler(enabled = backStack.size > 1) { pop() }

            SncTheme {
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
                            onOpenSettings = { push(Screen.Settings) }
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
                        Screen.Settings -> SettingsScreen(
                            repository = app.settingsRepository,
                            onBack = { pop() },
                            onAbout = { push(Screen.About) },
                            onUpdates = { push(Screen.Update) }
                        )
                        Screen.Update -> UpdateScreen(
                            manager = app.updateManager,
                            repository = app.settingsRepository,
                            onBack = { pop() }
                        )
                        Screen.About -> AboutScreen(onBack = { pop() })
                    }
                }
            }
        }
    }

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
