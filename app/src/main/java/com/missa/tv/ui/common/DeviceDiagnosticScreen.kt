package com.missa.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.missa.tv.R
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.adaptive.DeviceType
import com.missa.tv.ui.adaptive.WindowWidthClass
import com.missa.tv.ui.theme.MissaTvTheme

/**
 * Écran de diagnostic de l'adaptation à l'appareil.
 *
 * Il affiche ce que l'application a détecté : type d'appareil, classe de
 * largeur de fenêtre, présence d'un écran tactile, prise en charge du
 * Picture-in-Picture.
 *
 * Cet écran est provisoire : il sera remplacé par le routeur de navigation
 * (interface TV / mobile / tablette). Il reste utile pour vérifier le
 * comportement sur un matériel donné.
 */
@Composable
fun DeviceDiagnosticScreen(
    deviceProfile: DeviceProfile,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.device_diagnostic_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        DiagnosticCard(
            rows = listOf(
                stringResource(R.string.device_type) to deviceProfile.type.label(),
                stringResource(R.string.device_window) to deviceProfile.widthClass.label(),
                stringResource(R.string.device_touch) to deviceProfile.hasTouchscreen.yesNoLabel(),
                stringResource(R.string.device_pip) to deviceProfile.supportsPictureInPicture.yesNoLabel(),
                stringResource(R.string.device_font_scale) to deviceProfile.fontScale.toString(),
            ),
        )
    }
}

@Composable
private fun DiagnosticCard(rows: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rows.forEach { (label, value) ->
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun DeviceType.label(): String = when (this) {
    DeviceType.TELEVISION -> "TELEVISION"
    DeviceType.TABLET -> "TABLET"
    DeviceType.PHONE -> "PHONE"
    DeviceType.DESKTOP -> "DESKTOP"
}

private fun WindowWidthClass.label(): String = when (this) {
    WindowWidthClass.COMPACT -> "COMPACT"
    WindowWidthClass.MEDIUM -> "MEDIUM"
    WindowWidthClass.EXPANDED -> "EXPANDED"
}

@Composable
private fun Boolean.yesNoLabel(): String =
    stringResource(if (this) R.string.yes else R.string.no)

@Preview(name = "Téléphone", widthDp = 411, heightDp = 891)
@Preview(name = "Tablette", widthDp = 800, heightDp = 1280)
@Preview(name = "Télévision", widthDp = 960, heightDp = 540)
@Composable
private fun DeviceDiagnosticScreenPreview() {
    val television = DeviceProfile(
        type = DeviceType.TELEVISION,
        widthClass = WindowWidthClass.EXPANDED,
        isLandscape = true,
        hasTouchscreen = false,
        supportsPictureInPicture = false,
        fontScale = 1f,
    )
    MissaTvTheme(deviceProfile = television) {
        DeviceDiagnosticScreen(deviceProfile = television)
    }
}
