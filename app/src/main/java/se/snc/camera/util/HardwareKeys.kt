package se.snc.camera.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import se.snc.camera.data.VolumeKeyAction

enum class KeyCommand { SHUTTER, ZOOM_IN, ZOOM_OUT }

/** Förmedlar volym- och kameraknappar från aktiviteten till kameravyn. */
object HardwareKeys {
    @Volatile var cameraActive: Boolean = false
    @Volatile var volumeAction: VolumeKeyAction = VolumeKeyAction.SHUTTER

    private val _events = MutableSharedFlow<KeyCommand>(extraBufferCapacity = 8)
    val events: SharedFlow<KeyCommand> = _events.asSharedFlow()

    fun emit(command: KeyCommand) {
        _events.tryEmit(command)
    }
}
