package se.snc.camera.scan

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import se.snc.camera.MainActivity

/**
 * Snabbinställningsrutan "Skanna QR": öppnar kameran direkt i läget Skanna (QR-kod). Är telefonen låst
 * låses den upp först.
 */
class ScanTileService : TileService() {

    companion object {
        const val ACTION_SCAN = "se.snc.camera.action.SCAN_CODE"
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openScanner() } else openScanner()
    }

    private fun openScanner() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_SCAN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
