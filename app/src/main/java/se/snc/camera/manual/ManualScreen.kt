package se.snc.camera.manual

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.snc.camera.R
import se.snc.camera.ui.components.ScreenHeader
import se.snc.camera.ui.components.SectionLabel
import se.snc.camera.ui.components.UsbCard
import se.snc.camera.ui.theme.BodyFont
import se.snc.camera.ui.theme.Ui

/** Ett avsnitt i manualen: rubrik och punkter. */
data class ManualSection(val title: String, val items: List<String>)

data class Manual(val version: String, val sections: List<ManualSection>)

/**
 * Manualen i appen, på svenska och engelska (assets/manual.json). Den uppdateras med varje version –
 * bygget stoppar om versionen i filen inte är appens version, eller om språken inte har samma avsnitt.
 * Filen läses först när manualen öppnas, så den påverkar inte starttiden.
 */
object ManualRepository {
    fun load(context: Context, language: String): Manual? = runCatching {
        val json = context.assets.open("manual.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val lang = if (language == "sv") "sv" else "en"
        val arr = root.getJSONArray(lang)
        val sections = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val items = o.getJSONArray("items")
            ManualSection(o.getString("title"), (0 until items.length()).map { items.getString(it) })
        }
        Manual(root.getString("version"), sections)
    }.getOrNull()
}

@Composable
fun ManualScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    var manual by remember { mutableStateOf<Manual?>(null) }
    LaunchedEffect(language) {
        manual = withContext(Dispatchers.IO) { ManualRepository.load(context, language) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ui.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = stringResource(R.string.manual_title),
            backDescription = stringResource(R.string.cd_back),
            onBack = onBack,
            subtitle = manual?.let { stringResource(R.string.manual_version, it.version) }
                ?: stringResource(R.string.manual_row_sub)
        )
        manual?.sections?.forEach { section ->
            UsbCard {
                SectionLabel(section.title)
                section.items.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(Ui.Gold)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            item,
                            color = Ui.Text,
                            fontFamily = BodyFont,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
