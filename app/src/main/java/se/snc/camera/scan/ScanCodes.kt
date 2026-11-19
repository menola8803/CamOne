package se.snc.camera.scan

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import se.snc.camera.R

/** Vad en QR- eller streckkod innehåller. */
enum class CodeKind(@StringRes val label: Int) {
    URL(R.string.code_kind_url),
    WIFI(R.string.code_kind_wifi),
    CONTACT(R.string.code_kind_contact),
    EMAIL(R.string.code_kind_email),
    PHONE(R.string.code_kind_phone),
    SMS(R.string.code_kind_sms),
    GEO(R.string.code_kind_geo),
    PRODUCT(R.string.code_kind_product),
    TEXT(R.string.code_kind_text)
}

/** En avläst kod med det som behövs för åtgärderna (öppna, anslut, ring …). */
data class ScannedCode(
    val kind: CodeKind,
    val raw: String,
    /** Det som visas i kortet. */
    val display: String,
    /** Kodens typ, t.ex. "QR" eller "EAN-13". */
    val format: String,
    val url: String? = null,
    val ssid: String? = null,
    val password: String? = null,
    /** Wi-Fi: 0 = öppet, 1 = WPA/WPA2, 2 = WEP. */
    val wifiType: Int = 0,
    val name: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val organization: String? = null,
    val subject: String? = null,
    val body: String? = null,
    val lat: Double? = null,
    val lng: Double? = null
)

object ScanCodes {

    /** ML Kit med inbäddad modell (fungerar utan Google Play-tjänster). Skapas först när den behövs. */
    fun newScanner(): BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
    )

    fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        else -> "Kod"
    }

    /** Gör om ML Kits resultat till appens modell (null om koden saknar innehåll). */
    fun from(b: Barcode): ScannedCode? {
        val raw = b.rawValue ?: return null
        if (raw.isBlank()) return null
        val format = formatName(b.format)
        val display = (b.displayValue ?: raw).trim()
        return when (b.valueType) {
            Barcode.TYPE_URL -> {
                val url = b.url?.url ?: raw
                ScannedCode(CodeKind.URL, raw, url, format, url = url)
            }
            Barcode.TYPE_WIFI -> {
                val w = b.wifi
                val type = when (w?.encryptionType) {
                    Barcode.WiFi.TYPE_WPA -> 1
                    Barcode.WiFi.TYPE_WEP -> 2
                    else -> 0
                }
                ScannedCode(CodeKind.WIFI, raw, w?.ssid ?: display, format, ssid = w?.ssid, password = w?.password, wifiType = type)
            }
            Barcode.TYPE_CONTACT_INFO -> {
                val c = b.contactInfo
                val name = c?.name?.formattedName
                ScannedCode(
                    CodeKind.CONTACT, raw,
                    listOfNotNull(name, c?.organization).joinToString(" · ").ifBlank { display },
                    format,
                    name = name,
                    phones = c?.phones?.mapNotNull { it.number }.orEmpty(),
                    emails = c?.emails?.mapNotNull { it.address }.orEmpty(),
                    organization = c?.organization
                )
            }
            Barcode.TYPE_EMAIL -> {
                val e = b.email
                ScannedCode(CodeKind.EMAIL, raw, e?.address ?: display, format, emails = listOfNotNull(e?.address), subject = e?.subject, body = e?.body)
            }
            Barcode.TYPE_PHONE -> ScannedCode(CodeKind.PHONE, raw, b.phone?.number ?: display, format, phones = listOfNotNull(b.phone?.number))
            Barcode.TYPE_SMS -> {
                val s = b.sms
                ScannedCode(CodeKind.SMS, raw, listOfNotNull(s?.phoneNumber, s?.message).joinToString(" · ").ifBlank { display }, format,
                    phones = listOfNotNull(s?.phoneNumber), body = s?.message)
            }
            Barcode.TYPE_GEO -> {
                val g = b.geoPoint
                ScannedCode(CodeKind.GEO, raw, if (g != null) "%.5f, %.5f".format(java.util.Locale.US, g.lat, g.lng) else display, format,
                    lat = g?.lat, lng = g?.lng)
            }
            Barcode.TYPE_PRODUCT, Barcode.TYPE_ISBN -> ScannedCode(CodeKind.PRODUCT, raw, display, format)
            else -> if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                ScannedCode(CodeKind.URL, raw, raw, format, url = raw)
            } else {
                ScannedCode(CodeKind.TEXT, raw, display, format)
            }
        }
    }

    // ------------------------------------------------------------------ åtgärder

    fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.scan_no_app, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, R.string.scan_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun openUrl(context: Context, url: String) {
        val u = if (url.contains("://")) url else "https://$url"
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    /** Lägger till nätverket med Androids egen dialog (Inställningar › Lägg till nätverk). */
    fun connectWifi(context: Context, code: ScannedCode) {
        val ssid = code.ssid ?: return
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
        val pw = code.password
        if (code.wifiType == 1 && !pw.isNullOrEmpty()) b.setWpa2Passphrase(pw)
        val suggestion = runCatching { b.build() }.getOrNull()
        if (suggestion == null || code.wifiType == 2) {
            // WEP kan inte läggas till av appar: öppna Wi-Fi-inställningarna
            start(context, Intent(Settings.ACTION_WIFI_SETTINGS))
            return
        }
        start(
            context,
            Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion)
            )
        )
    }

    fun addContact(context: Context, code: ScannedCode) {
        val i = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            code.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            code.phones.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            code.emails.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            code.organization?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        }
        start(context, i)
    }

    fun sendEmail(context: Context, code: ScannedCode) {
        val to = code.emails.firstOrNull() ?: return
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to))).apply {
            code.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            code.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        start(context, i)
    }

    fun dial(context: Context, number: String) = start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))

    fun sendSms(context: Context, code: ScannedCode) {
        val number = code.phones.firstOrNull() ?: return
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
        code.body?.let { i.putExtra("sms_body", it) }
        start(context, i)
    }

    fun showOnMap(context: Context, code: ScannedCode) {
        val lat = code.lat ?: return
        val lng = code.lng ?: return
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
    }

    fun searchWeb(context: Context, query: String) =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query))))
}
