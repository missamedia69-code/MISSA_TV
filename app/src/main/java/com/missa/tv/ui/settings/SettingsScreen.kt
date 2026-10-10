package com.missa.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.missa.tv.R
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.common.DeviceDiagnosticSection

/**
 * Réglages de l'application.
 *
 * Cinq sections : les chaînes (provenance, fraîcheur et actualisation de la
 * liste), la qualité d'image, la configuration distante, le diagnostic et la
 * version. Un rappel légal figure en bas : l'application est un lecteur, elle
 * ne fournit aucune chaîne.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    device: DeviceProfile,
    onBack: () -> Unit,
    onQualitySelected: (QualityMode?) -> Unit,
    onCheckConfig: () -> Unit,
    onRefreshCatalog: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.player_back),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionSources(state = state, onRefreshCatalog = onRefreshCatalog)

            SectionQualite(state = state, onQualitySelected = onQualitySelected)

            SectionConfiguration(
                state = state,
                onCheckConfig = onCheckConfig,
            )

            SectionDiagnostic(device = device)

            SectionAPropos(state = state)

            Box(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/**
 * Provenance des chaînes : source de la liste affichée (catalogue testé ou
 * playlists), fraîcheur du catalogue local, sources déclarées et actualisation.
 *
 * Les sources elles-mêmes restent en lecture seule : la configuration est
 * gérée en ligne. Seuls les noms sont affichés ; l'adresse d'une playlist est
 * un identifiant sensible et n'apparaît jamais ici.
 */
@Composable
private fun SectionSources(state: SettingsUiState, onRefreshCatalog: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitreSection(stringResource(R.string.settings_sources_section))

        // Provenance et fraîcheur de la liste actuellement affichée : c'est la
        // première information que l'utilisateur doit trouver ici.
        val origine = stringResource(
            if (state.fromTestedCatalog) {
                R.string.settings_catalog_source_tested
            } else {
                R.string.settings_catalog_source_playlists
            },
        )
        Text(
            text = stringResource(R.string.settings_catalog_origin, origine),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (state.channelCount > 0) {
            Text(
                text = stringResource(R.string.settings_catalog_count, state.channelCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = state.catalogUpdatedMs?.let {
                stringResource(R.string.settings_catalog_updated, dateLisible(it))
            } ?: stringResource(R.string.settings_catalog_never),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.playlists.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_sources_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.playlists.forEach { source ->
            LigneSource(nom = source.name, active = source.enabled)
        }

        state.refreshError?.let { erreur ->
            // Comme pour la vérification de configuration : sans message, un
            // échec d'actualisation serait invisible.
            Text(
                text = stringResource(erreur),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onRefreshCatalog, enabled = !state.isRefreshing) {
            if (state.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            Text(text = stringResource(R.string.settings_catalog_refresh))
        }

        Text(
            text = stringResource(R.string.settings_sources_online),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Une source : son nom, et une marque si elle est active. */
@Composable
private fun LigneSource(nom: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = nom,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (active) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionQualite(state: SettingsUiState, onQualitySelected: (QualityMode?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitreSection(stringResource(R.string.settings_quality_section))
        Text(
            text = stringResource(R.string.settings_quality_default),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LigneModeQualite(
            libelle = stringResource(R.string.quality_auto_economy),
            selectionne = state.qualityMode == null,
            onClick = { onQualitySelected(null) },
        )
        // `selectable` contient AUTO_ECONOMY, déjà proposé par la ligne
        // « automatique » ci-dessus : le filtrer évite une ligne en double.
        QualityMode.selectable.filterNot { it == QualityMode.AUTO_ECONOMY }.forEach { mode ->
            LigneModeQualite(
                libelle = stringResource(mode.labelRes),
                selectionne = state.qualityMode == mode,
                onClick = { onQualitySelected(mode) },
            )
        }
    }
}

@Composable
private fun LigneModeQualite(libelle: String, selectionne: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = libelle,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selectionne) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (selectionne) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionConfiguration(state: SettingsUiState, onCheckConfig: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitreSection(stringResource(R.string.settings_config_section))
        Text(
            text = if (state.lastSyncMs > 0L) {
                stringResource(R.string.settings_config_last_sync, dateLisible(state.lastSyncMs))
            } else {
                stringResource(R.string.settings_config_never_synced)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.syncError?.let { erreur ->
            // Sans ce message, un échec de vérification était invisible : le
            // bouton semblait n'avoir rien fait.
            Text(
                text = stringResource(erreur),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onCheckConfig, enabled = !state.isSyncing) {
            if (state.isSyncing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            Text(text = stringResource(R.string.settings_config_check_now))
        }
    }
}

@Composable
private fun SectionDiagnostic(device: DeviceProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitreSection(stringResource(R.string.settings_diagnostics_section))
        // Composant volontairement NON défilant : l'imbriquer dans cette colonne
        // défilante avec son propre défilement provoquait une exception de
        // contrainte de hauteur infinie, donc un arrêt immédiat de l'application.
        DeviceDiagnosticSection(deviceProfile = device)
    }
}

@Composable
private fun SectionAPropos(state: SettingsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitreSection(stringResource(R.string.settings_about_section))
        Text(
            text = stringResource(
                R.string.settings_version,
                state.versionName,
                state.versionCode,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.settings_legal),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.dernierIncident?.let { incident ->
            // Une ligne seulement : c'est assez pour identifier la cause, sans
            // transformer l'écran de réglages en journal technique.
            Text(
                text = stringResource(R.string.settings_last_incident, incident),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TitreSection(texte: String) {
    Text(
        text = texte,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Date lisible par un humain, sans dépendre du format de la locale. */
private fun dateLisible(instantMs: Long): String {
    val format = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
    return format.format(java.util.Date(instantMs))
}
