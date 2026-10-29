package se.snc.camera

import android.app.Application
import se.snc.camera.data.MediaRepository
import se.snc.camera.data.SettingsRepository
import se.snc.camera.update.UpdateManager

class SncCameraApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val mediaRepository: MediaRepository by lazy { MediaRepository(this) }
    val updateManager: UpdateManager by lazy { UpdateManager(this, settingsRepository) }
}
