package se.snc.camera.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.snc.camera.BuildConfig
import se.snc.camera.data.SettingsRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/** En release på GitHub (samma fält som Directs `latestRelease`, plus storlek och kontrollsumma). */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long,
    /** SHA-256 i hex från GitHub (fältet `digest` på filen), om det finns. */
    val sha256: String?,
    val notes: List<String>,
    val publishedAt: Long,
    val prerelease: Boolean
)

sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState
    data class UpToDate(val latest: ReleaseInfo) : CheckState
    data class Available(val release: ReleaseInfo) : CheckState
    data object NoRelease : CheckState
    data class Failed(val detail: String?) : CheckState
}

enum class UpdateError { NETWORK, CHECKSUM, WRONG_PACKAGE, NOT_NEWER, SIGNATURE, INSTALL }

sealed interface DownloadState {
    data object None : DownloadState
    /** [progress] 0..1, eller -1 om storleken är okänd. */
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File, val verifiedSha256: Boolean) : DownloadState
    data class Failed(val error: UpdateError) : DownloadState
}

data class UpdateUiState(
    val check: CheckState = CheckState.Idle,
    val download: DownloadState = DownloadState.None,
    /** Visas när Android först måste tillåta installation från appen. */
    val allowInstallHint: Boolean = false
)

/**
 * Uppdateringar som i Direct-appen: senaste release hämtas från GitHub, APK-filen laddas ner,
 * kontrolleras och lämnas sedan till Androids paketinstallerare.
 */
class UpdateManager(private val context: Context, private val settings: SettingsRepository) {

    companion object {
        /** Repot där releaser publiceras. Måste vara publikt så att appen kan läsa det utan inloggning. */
        val UPDATE_REPO: String = BuildConfig.UPDATE_REPO
        val RELEASES_URL = "https://github.com/$UPDATE_REPO/releases/latest"
        private const val TAG = "SncUpdate"
        private const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** Jämför versioner som 1.10.2 och v1.9 – positivt om [a] är nyare än [b]. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-').split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }

        fun isNewer(tag: String, current: String = BuildConfig.VERSION_NAME) = compareVersions(tag, current) > 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var autoChecked = false

    private val updatesDir get() = File(context.cacheDir, "updates")

    /** Körs när appen startar: söker högst var sjätte timme om "Sök automatiskt" är på. */
    fun autoCheckIfDue() {
        if (autoChecked) return
        autoChecked = true
        scope.launch {
            val s = settings.settings.first()
            if (s.autoUpdateCheck && System.currentTimeMillis() - s.lastUpdateCheck > AUTO_INTERVAL_MS) check()
        }
    }

    fun check() {
        if (checkJob?.isActive == true || _state.value.download is DownloadState.Downloading) return
        checkJob = scope.launch {
            _state.update { it.copy(check = CheckState.Checking, allowInstallHint = false) }
            val result = try {
                val release = fetchLatest()
                when {
                    release == null -> CheckState.NoRelease
                    isNewer(release.version) -> CheckState.Available(release)
                    else -> CheckState.UpToDate(release)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Kunde inte söka efter uppdatering", e)
                CheckState.Failed(e.message)
            }
            val now = System.currentTimeMillis()
            settings.update {
                it.copy(
                    lastUpdateCheck = if (result is CheckState.Failed) it.lastUpdateCheck else now,
                    latestKnownVersion = when (result) {
                        is CheckState.Available -> result.release.version
                        is CheckState.UpToDate -> result.latest.version
                        CheckState.NoRelease -> ""
                        else -> it.latestKnownVersion
                    }
                )
            }
            // En redan nedladdad fil gäller bara samma version
            val keepDownload = (result as? CheckState.Available)?.release?.version ==
                ((_state.value.check as? CheckState.Available)?.release?.version)
            _state.update {
                it.copy(check = result, download = if (keepDownload) it.download else DownloadState.None)
            }
        }
    }

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SNC-Camera/${BuildConfig.VERSION_NAME}")
        }
        try {
            when (val code = conn.responseCode) {
                404 -> return@withContext null
                in 200..299 -> Unit
                else -> throw IllegalStateException("HTTP $code")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets")
            var apk: JSONObject? = null
            if (assets != null) {
                // Hellre filen med versionsnummer i namnet, annars första APK-filen
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (!name.lowercase().endsWith(".apk")) continue
                    if (apk == null || name.contains(tag.removePrefix("v"))) apk = a
                }
            }
            val digest = apk?.optString("digest").orEmpty()
            ReleaseInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                tag = tag,
                pageUrl = json.optString("html_url", RELEASES_URL),
                apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                apkName = apk?.optString("name"),
                apkSize = apk?.optLong("size") ?: 0L,
                sha256 = digest.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                notes = parseNotes(json.optString("body")),
                publishedAt = runCatching { Instant.parse(json.optString("published_at")).toEpochMilli() }.getOrDefault(0L),
                prerelease = json.optBoolean("prerelease")
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Punktlistan ur release-texten (före "---"), utan markdown. */
    private fun parseNotes(body: String): List<String> {
        val main = body.substringBefore("\n---")
        val bullets = main.lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.drop(2).replace("**", "").replace("`", "").trim() }
            .filter { it.isNotEmpty() }
        if (bullets.isNotEmpty()) return bullets.take(8)
        val text = main.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString(" ").replace("**", "").take(300)
        return if (text.isBlank()) emptyList() else listOf(text)
    }

    /** "Ladda ner och installera": laddar ner, kontrollerar och installerar sedan direkt. */
    fun downloadAndInstall() {
        val release = (_state.value.check as? CheckState.Available)?.release ?: return
        val url = release.apkUrl ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _state.update { it.copy(download = DownloadState.Downloading(0f), allowInstallHint = false) }
            val result = withContext(Dispatchers.IO) { downloadAndVerify(release, url) }
            _state.update { it.copy(download = result) }
            if (result is DownloadState.Ready) install()
        }
    }

    private suspend fun downloadAndVerify(release: ReleaseInfo, url: String): DownloadState {
        val dir = updatesDir.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "SNC-Camera-${release.version}.apk")
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SNC-Camera/${BuildConfig.VERSION_NAME}")
            }
            val sha = MessageDigest.getInstance("SHA-256")
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                var done = 0L
                var lastReport = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sha.update(buf, 0, n)
                            done += n
                            if (done - lastReport > 256 * 1024) {
                                lastReport = done
                                val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
                                _state.update { it.copy(download = DownloadState.Downloading(p)) }
                            }
                        }
                    }
                }
                if (release.apkSize > 0 && done != release.apkSize) throw IllegalStateException("Fel storlek $done")
            } finally {
                conn.disconnect()
            }

            // 1. Kontrollsumma från GitHub
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && release.sha256 != hex) {
                file.delete()
                return DownloadState.Failed(UpdateError.CHECKSUM)
            }
            // 2. Rätt app och nyare version
            val pm = context.packageManager
            val archive = archiveInfo(pm, file.path)
            if (archive == null || archive.packageName != context.packageName) {
                file.delete()
                return DownloadState.Failed(UpdateError.WRONG_PACKAGE)
            }
            val installed = installedInfo(pm)
            if (installed != null && archive.longVersionCode <= installed.longVersionCode) {
                file.delete()
                return DownloadState.Failed(UpdateError.NOT_NEWER)
            }
            // 3. Samma signeringsnyckel – annars vägrar Android installera ovanpå
            if (installed != null && !sameSigner(archive, installed)) {
                return DownloadState.Failed(UpdateError.SIGNATURE)
            }
            return DownloadState.Ready(file, verifiedSha256 = release.sha256 != null)
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Nedladdningen misslyckades", e)
            file.delete()
            return DownloadState.Failed(UpdateError.NETWORK)
        }
    }

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun installedInfo(pm: PackageManager): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull()

    private fun signerDigests(info: PackageInfo): Set<String>? {
        val si = info.signingInfo ?: return null
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        if (sigs.isNullOrEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Om nyckeln inte går att läsa ur filen låter vi Androids installerare avgöra. */
    private fun sameSigner(archive: PackageInfo, installed: PackageInfo): Boolean {
        val a = signerDigests(archive) ?: return true
        val b = signerDigests(installed) ?: return true
        return a.intersect(b).isNotEmpty()
    }

    /** Lämnar den kontrollerade filen till Androids paketinstallerare (som i Direct). */
    fun install() {
        val ready = _state.value.download as? DownloadState.Ready ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(allowInstallHint = true) }
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            _state.update { it.copy(allowInstallHint = false) }
        } catch (e: Exception) {
            Log.w(TAG, "Kunde inte starta installationen", e)
            _state.update { it.copy(download = DownloadState.Failed(UpdateError.INSTALL)) }
        }
    }

    fun openReleasePage() {
        val page = (_state.value.check as? CheckState.Available)?.release?.pageUrl ?: RELEASES_URL
        openUrl(page)
    }

    fun openRepository() = openUrl("https://github.com/$UPDATE_REPO")

    private fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        scope.launch { settings.update { it.copy(autoUpdateCheck = enabled) } }
    }
}
