package se.snc.camera.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Bildformat. [portraitRatio] = bredd/höjd när telefonen hålls stående. */
enum class AspectOption(val label: String, val portraitRatio: Float) {
    RATIO_4_3("4:3", 3f / 4f),
    RATIO_1_1("1:1", 1f),
    RATIO_16_9("16:9", 9f / 16f),
    RATIO_3_2("3:2", 2f / 3f),
    /** Helskärm: sökaren fyller hela skärmen och bilden beskärs till skärmens format (ca 20:9). */
    FULL("Full", 0f);

    /**
     * Formatet beskärs efter fotograferingen (3:2, 16:9 och helskärm), inte av kameran. Kameran tar alltid
     * fotot i 4:3 – OnePlus 13R:s eget 16:9-läge för foton ger en randig remsa på ena sidan.
     */
    val croppedAfter get() = this == RATIO_3_2 || this == RATIO_16_9 || this == FULL
}

/** Filformat för bilder. RAW (DNG) kräver att kameran stöder det; RAW gäller i Foto och Pro. */
enum class PhotoFormat(val label: String, val extension: String, val mime: String) {
    JPEG("JPEG", "jpg", "image/jpeg"),
    HEIF("HEIF", "heic", "image/heic"),
    PNG("PNG", "png", "image/png"),
    RAW("RAW", "dng", "image/x-adobe-dng"),
    RAW_JPEG("RAW + JPEG", "jpg", "image/jpeg");

    val isRaw get() = this == RAW || this == RAW_JPEG
}

enum class FlashOption { OFF, AUTO, ON }

enum class TimerOption(val seconds: Int) { OFF(0), S3(3), S5(5), S10(10) }

enum class VideoQualityOption(val label: String) { HD("720p"), FHD("1080p"), UHD("4K") }

enum class VolumeKeyAction { SHUTTER, ZOOM, VOLUME }

enum class GridOption { OFF, THIRDS, GOLDEN, SQUARE }

/** Tema för inställningssidorna, som i Alif. Kameran är alltid mörk. */
enum class ThemeOption { DARK, BEIGE }

data class AppSettings(
    val aspect: AspectOption = AspectOption.RATIO_4_3,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val flash: FlashOption = FlashOption.OFF,
    val timer: TimerOption = TimerOption.OFF,
    val hdr: Boolean = false,
    val videoQuality: VideoQualityOption = VideoQualityOption.FHD,
    val videoStabilization: Boolean = true,
    /** 30 eller 60 bilder per sekund i videoläget. */
    val videoFps: Int = 30,
    val grid: GridOption = GridOption.OFF,
    val levelIndicator: Boolean = true,
    val shutterSound: Boolean = true,
    val haptics: Boolean = true,
    val mirrorFront: Boolean = true,
    val maxQuality: Boolean = true,
    /** HDR-bilder sparas som Ultra HDR (gain map) så att HDR-skärmar visar ljusa partier ljusare. */
    val ultraHdr: Boolean = true,
    /** Pro-hjälpmedel i sökaren: histogram, fokusmarkering och zebra. */
    val histogram: Boolean = false,
    val focusPeaking: Boolean = false,
    val zebra: Boolean = false,
    val jpegQuality: Int = 95,
    val volumeKeyAction: VolumeKeyAction = VolumeKeyAction.SHUTTER,
    val recordAudio: Boolean = true,
    /** Vattenstämpel i hörnet på foton från kameran (inte i film eller RAW). */
    val watermark: Boolean = false,
    /** Vattenstämpelns text (tom = "SNC Camera"). */
    val watermarkText: String = "SNC Camera",
    /** Vattenstämpeln visar också brännvidd, bländare, tid, ISO och datum. */
    val watermarkDetails: Boolean = true,
    /** Spara platsen (GPS) i foton och filmer. Av från början – kräver platsbehörighet. */
    val saveLocation: Boolean = false,
    val keepLastMode: Boolean = true,
    val lastMode: String = "PHOTO",
    val lastLensFront: Boolean = false,
    /** Sök efter ny version på GitHub när appen startar. */
    val autoUpdateCheck: Boolean = true,
    /** När uppdateringar senast söktes (ms), 0 = aldrig. */
    val lastUpdateCheck: Long = 0L,
    /** Senaste versionen som hittades på GitHub (för pricken på kugghjulet). */
    val latestKnownVersion: String = "",
    val theme: ThemeOption = ThemeOption.DARK
) {
    /** Minst ett Pro-hjälpmedel är på (då kopplas en liten analysbild till sökaren i Pro). */
    val anyAssist get() = histogram || focusPeaking || zebra
}

private val Context.dataStore by preferencesDataStore(name = "snc_camera_settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val aspect = stringPreferencesKey("aspect")
        val photoFormat = stringPreferencesKey("photo_format")
        val flash = stringPreferencesKey("flash")
        val timer = stringPreferencesKey("timer")
        val hdr = booleanPreferencesKey("hdr")
        val videoQuality = stringPreferencesKey("video_quality")
        val videoStab = booleanPreferencesKey("video_stabilization")
        val videoFps = intPreferencesKey("video_fps")
        val grid = stringPreferencesKey("grid")
        val level = booleanPreferencesKey("level")
        val shutterSound = booleanPreferencesKey("shutter_sound")
        val haptics = booleanPreferencesKey("haptics")
        val mirrorFront = booleanPreferencesKey("mirror_front")
        val maxQuality = booleanPreferencesKey("max_quality")
        val ultraHdr = booleanPreferencesKey("ultra_hdr")
        val histogram = booleanPreferencesKey("assist_histogram")
        val focusPeaking = booleanPreferencesKey("assist_peaking")
        val zebra = booleanPreferencesKey("assist_zebra")
        val jpegQuality = intPreferencesKey("jpeg_quality")
        val volumeKey = stringPreferencesKey("volume_key")
        val recordAudio = booleanPreferencesKey("record_audio")
        val watermark = booleanPreferencesKey("watermark")
        val watermarkText = stringPreferencesKey("watermark_text")
        val watermarkDetails = booleanPreferencesKey("watermark_details")
        val saveLocation = booleanPreferencesKey("save_location")
        val keepLastMode = booleanPreferencesKey("keep_last_mode")
        val lastMode = stringPreferencesKey("last_mode")
        val lastLensFront = booleanPreferencesKey("last_lens_front")
        val autoUpdate = booleanPreferencesKey("auto_update_check")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val latestKnown = stringPreferencesKey("latest_known_version")
        val theme = stringPreferencesKey("theme")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { read(it) }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(read(prefs))
            write(prefs, updated)
        }
    }

    private inline fun <reified T : Enum<T>> Preferences.enumOr(key: Preferences.Key<String>, default: T): T {
        val raw = this[key] ?: return default
        return enumValues<T>().firstOrNull { it.name == raw } ?: default
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            aspect = p.enumOr(K.aspect, d.aspect),
            photoFormat = p.enumOr(K.photoFormat, d.photoFormat),
            flash = p.enumOr(K.flash, d.flash),
            timer = p.enumOr(K.timer, d.timer),
            hdr = p[K.hdr] ?: d.hdr,
            videoQuality = p.enumOr(K.videoQuality, d.videoQuality),
            videoStabilization = p[K.videoStab] ?: d.videoStabilization,
            videoFps = if ((p[K.videoFps] ?: d.videoFps) >= 60) 60 else 30,
            grid = p.enumOr(K.grid, d.grid),
            levelIndicator = p[K.level] ?: d.levelIndicator,
            shutterSound = p[K.shutterSound] ?: d.shutterSound,
            haptics = p[K.haptics] ?: d.haptics,
            mirrorFront = p[K.mirrorFront] ?: d.mirrorFront,
            maxQuality = p[K.maxQuality] ?: d.maxQuality,
            ultraHdr = p[K.ultraHdr] ?: d.ultraHdr,
            histogram = p[K.histogram] ?: d.histogram,
            focusPeaking = p[K.focusPeaking] ?: d.focusPeaking,
            zebra = p[K.zebra] ?: d.zebra,
            jpegQuality = (p[K.jpegQuality] ?: d.jpegQuality).coerceIn(50, 100),
            volumeKeyAction = p.enumOr(K.volumeKey, d.volumeKeyAction),
            recordAudio = p[K.recordAudio] ?: d.recordAudio,
            watermark = p[K.watermark] ?: d.watermark,
            watermarkText = (p[K.watermarkText] ?: d.watermarkText).take(40),
            watermarkDetails = p[K.watermarkDetails] ?: d.watermarkDetails,
            saveLocation = p[K.saveLocation] ?: d.saveLocation,
            keepLastMode = p[K.keepLastMode] ?: d.keepLastMode,
            lastMode = p[K.lastMode] ?: d.lastMode,
            lastLensFront = p[K.lastLensFront] ?: d.lastLensFront,
            autoUpdateCheck = p[K.autoUpdate] ?: d.autoUpdateCheck,
            lastUpdateCheck = p[K.lastUpdateCheck] ?: d.lastUpdateCheck,
            latestKnownVersion = p[K.latestKnown] ?: d.latestKnownVersion,
            theme = p[K.theme]?.let { v -> ThemeOption.entries.firstOrNull { it.name == v } } ?: d.theme
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.aspect] = s.aspect.name
        p[K.photoFormat] = s.photoFormat.name
        p[K.flash] = s.flash.name
        p[K.timer] = s.timer.name
        p[K.hdr] = s.hdr
        p[K.videoQuality] = s.videoQuality.name
        p[K.videoStab] = s.videoStabilization
        p[K.videoFps] = s.videoFps
        p[K.grid] = s.grid.name
        p[K.level] = s.levelIndicator
        p[K.shutterSound] = s.shutterSound
        p[K.haptics] = s.haptics
        p[K.mirrorFront] = s.mirrorFront
        p[K.maxQuality] = s.maxQuality
        p[K.ultraHdr] = s.ultraHdr
        p[K.histogram] = s.histogram
        p[K.focusPeaking] = s.focusPeaking
        p[K.zebra] = s.zebra
        p[K.jpegQuality] = s.jpegQuality
        p[K.volumeKey] = s.volumeKeyAction.name
        p[K.recordAudio] = s.recordAudio
        p[K.watermark] = s.watermark
        p[K.watermarkText] = s.watermarkText.take(40)
        p[K.watermarkDetails] = s.watermarkDetails
        p[K.saveLocation] = s.saveLocation
        p[K.keepLastMode] = s.keepLastMode
        p[K.lastMode] = s.lastMode
        p[K.lastLensFront] = s.lastLensFront
        p[K.autoUpdate] = s.autoUpdateCheck
        p[K.lastUpdateCheck] = s.lastUpdateCheck
        p[K.latestKnown] = s.latestKnownVersion
        p[K.theme] = s.theme.name
    }
}
