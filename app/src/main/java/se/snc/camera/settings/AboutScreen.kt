package se.snc.camera.settings

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.snc.camera.BuildConfig
import se.snc.camera.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(Color(0xFF0E0F12)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(150.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.about_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

            item {
                InfoCard {
                    // Kravet: "Developer: Claes Sundman S.N.C AB" ska stå i Om
                    InfoLine(stringResource(R.string.about_developer_label), stringResource(R.string.about_developer_value), highlight = true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine(stringResource(R.string.about_version_label), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine(stringResource(R.string.about_target), stringResource(R.string.about_target_value))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine(stringResource(R.string.about_device), "${Build.MANUFACTURER} ${Build.MODEL}")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine(stringResource(R.string.about_android), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine(stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
                }
            }

            item {
                Text(
                    stringResource(R.string.about_licenses).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp)
                )
                Spacer(Modifier.height(8.dp))
                InfoCard {
                    InfoLine("AndroidX CameraX", "Apache License 2.0")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine("Jetpack Compose · Material 3", "Apache License 2.0")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine("AndroidX DataStore", "Apache License 2.0")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine("Kotlin Coroutines", "Apache License 2.0")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoLine("Google ML Kit (Text Recognition, Selfie Segmentation)", "ML Kit Terms of Service")
                }
            }

            item {
                Text(
                    stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f)
        )
    }
}
