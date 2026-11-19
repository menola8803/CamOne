package se.snc.camera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Platsen till foton och filmer (Inställningar › Spara plats, av från början). Lyssnar bara medan
 * kameraskärmen är öppen och inställningen är på, med Androids egna platstjänster (fused, GPS, nätverk –
 * inga Google Play-tjänster).
 */
class LocationTracker(private val context: Context) {

    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    @Volatile private var best: Location? = null
    private var listening = false
    private var logged = false

    private val listener = LocationListener { loc ->
        if (isBetter(loc, best)) best = loc
        if (!logged) {
            logged = true
            Log.i(TAG, "Plats: första positionen från ${loc.provider} (±${loc.accuracy.toInt()} m)")
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Börjar lyssna. False om behörighet eller platstjänst saknas. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listening) return true
        val lm = manager ?: return false
        if (!hasPermission()) {
            Log.i(TAG, "Plats: behörighet saknas")
            return false
        }
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        for (p in enabled) {
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { if (isBetter(it, best)) best = it }
        }
        val use = PROVIDERS.filter { it in enabled }
        for (p in use) {
            runCatching { lm.requestLocationUpdates(p, 5_000L, 5f, listener, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "Plats: $p gick inte att lyssna på: $it") }
        }
        listening = use.isNotEmpty()
        logged = false
        Log.i(TAG, "Plats: lyssnar på ${use.ifEmpty { listOf("inget") }} (påslagna: $enabled), senast känd ${if (best != null) "ja" else "nej"}")
        return listening
    }

    fun stop() {
        if (!listening) return
        runCatching { manager?.removeUpdates(listener) }
        listening = false
    }

    /** Platsen om den inte är äldre än 10 minuter, annars null. */
    fun fresh(): Location? {
        val loc = best ?: return null
        val age = SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos
        return loc.takeIf { age in 0..MAX_AGE_NS }
    }

    private fun isBetter(new: Location, old: Location?): Boolean {
        if (old == null) return true
        val dt = new.elapsedRealtimeNanos - old.elapsedRealtimeNanos
        if (dt > 30_000_000_000L) return true // mycket nyare
        if (dt < -30_000_000_000L) return false
        return !new.hasAccuracy() && !old.hasAccuracy() || (new.hasAccuracy() && (!old.hasAccuracy() || new.accuracy <= old.accuracy + 10f))
    }

    companion object {
        private const val TAG = "SncCamera"
        private const val MAX_AGE_NS = 10 * 60 * 1_000_000_000L
        private val PROVIDERS = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** Vilka platstjänster telefonen har och om de är påslagna (till sidan Kameror). */
        fun providers(context: Context): List<Pair<String, Boolean>> {
            val lm = context.getSystemService(LocationManager::class.java) ?: return emptyList()
            val all = runCatching { lm.allProviders }.getOrDefault(emptyList()).filter { it != LocationManager.PASSIVE_PROVIDER }
            return all.map { it to runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        }
    }
}
