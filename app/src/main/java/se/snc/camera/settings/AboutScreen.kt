package se.snc.camera.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.snc.camera.BuildConfig
import se.snc.camera.R
import se.snc.camera.ui.components.LineDivider
import se.snc.camera.ui.components.MutedText
import se.snc.camera.ui.components.ScreenHeader
import se.snc.camera.ui.components.SectionLabel
import se.snc.camera.ui.components.UsbCard
import se.snc.camera.ui.components.Wordmark
import se.snc.camera.ui.theme.BodyFont
import se.snc.camera.ui.theme.Usb

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Usb.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(stringResource(R.string.about_title_full), stringResource(R.string.cd_back), onBack)

        // Kortet med ordmärket – samma uppställning som "Om USB"
        UsbCard(spacing = 14.dp) {
            Wordmark(subtitle = stringResource(R.string.wm_sub), small = true)
            Text(
                stringResource(R.string.about_tagline),
                color = Usb.Muted,
                fontFamily = BodyFont,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(stringResource(R.string.about_version_label), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow(stringResource(R.string.about_target), stringResource(R.string.about_target_value))
                InfoRow(stringResource(R.string.about_device), "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow(stringResource(R.string.about_android), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
            LineDivider()
            // Kravet: "Developer: Claes Sundman S.N.C AB" ska stå i Om
            Text(
                stringResource(R.string.about_developer_line),
                color = Usb.Gold,
                fontFamily = BodyFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }

        UsbCard {
            SectionLabel(stringResource(R.string.about_licenses))
            License("AndroidX CameraX", "Apache 2.0")
            License("Jetpack Compose · Material 3", "Apache 2.0")
            License("AndroidX DataStore", "Apache 2.0")
            License("Kotlin Coroutines", "Apache 2.0")
            License("Google ML Kit", "ML Kit Terms")
            License("Space Grotesk · IBM Plex Sans · Cormorant Garamond", "SIL OFL 1.1")
        }

        MutedText(
            stringResource(R.string.about_package_line, BuildConfig.APPLICATION_ID),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(R.string.about_copyright),
            color = Usb.Dim,
            fontFamily = BodyFont,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** `.about dl` – etikett till vänster, värde i fetstil. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Usb.Dim, fontFamily = BodyFont, fontSize = 15.sp, modifier = Modifier.weight(0.42f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = Usb.Text,
            fontFamily = BodyFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
private fun License(name: String, license: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(name, color = Usb.Muted, fontFamily = BodyFont, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(license, color = Usb.TealL, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
