package se.snc.camera.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import se.snc.camera.BuildConfig
import se.snc.camera.MainActivity
import se.snc.camera.R

/**
 * Omstart efter en uppdatering som appen själv startade, som i Alif (AlifUpdate.kt): när Android har
 * installerat den nya versionen öppnas SNC Camera igen. Eftersom Android kan stoppa en app från att
 * öppna sig själv i bakgrunden kommer också en avisering "SNC Camera är uppdaterad – tryck för att öppna".
 * Efter andra uppdateringar händer ingenting.
 */
object UpdateRestart {
    private const val PREFS = "snc_update"
    private const val CHANNEL = "snc-update"
    private const val NOTIFICATION_ID = 7320
    private const val PENDING_MAX_AGE_MS = 60L * 60 * 1000

    /** Anropas precis innan Androids installationsruta öppnas. */
    fun markPending(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("pending", true)
            .putString("version", version)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    /** Från [UpdateRestartReceiver] när den nya versionen har installerats. */
    fun afterReplaced(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Bara för en uppdatering som appen startade, inom den senaste timmen
        if (!p.getBoolean("pending", false) || System.currentTimeMillis() - p.getLong("at", 0) > PENDING_MAX_AGE_MS) return
        p.edit().putBoolean("pending", false).apply()

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(launch) }
        runCatching { notifyUpdated(context, launch) }
    }

    private fun notifyUpdated(context: Context, launch: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            context, NOTIFICATION_ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE8B04A.toInt())
            .setContentTitle(context.getString(R.string.updated_title, BuildConfig.VERSION_NAME))
            .setContentText(context.getString(R.string.updated_notification_body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Anropas när appen har startat (efter att sökaren är igång). Ger versionen om appen har
     * uppdaterats sedan förra starten, annars null. Första starten efter en ny installation ger null.
     * Tar också bort aviseringen, eftersom appen redan är öppen.
     */
    fun consumeJustUpdated(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong("last_run_code", -1L)
        val now = BuildConfig.VERSION_CODE.toLong()
        if (last != now) p.edit().putLong("last_run_code", now).apply()
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        return if (last in 1 until now) BuildConfig.VERSION_NAME else null
    }
}

/** Tar emot MY_PACKAGE_REPLACED – skickas till den nya versionen direkt efter installationen. */
class UpdateRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UpdateRestart.afterReplaced(context)
    }
}
