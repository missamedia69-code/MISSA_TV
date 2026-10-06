package com.missa.tv.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.missa.tv.R
import com.missa.tv.core.log.Secrets
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.common.DeviceDiagnosticSection

/**
 * Réglages de l'application.
 *
 * Trois sections : les profils de connexion, la qualité d'image, et la
 * configuration distante. Un rappel légal figure en bas : l'application est un
 * lecteur, elle ne fournit aucune chaîne.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    device: DeviceProfile,
    ouvrirFormulaireParDefaut: Boolean,
    onBack: () -> Unit,
    onSaveProfile: (nom: String, url: String, mac: String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onActivateProfile: (String) -> Unit,
    onQualitySelected: (QualityMode?) -> Unit,
    onCheckConfig: () -> Unit,
    onTestConnection: () -> Unit,
    onMessageShown: () -> Unit,
) {
    var formulaireOuvert by remember { mutableStateOf(ouvrirFormulaireParDefaut) }

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
            if (state.profileSaved) {
                MessageSucces(onMessageShown = onMessageShown)
            }
            state.formError?.let { erreur ->
                MessageErreur(texte = stringResource(erreur), onMessageShown = onMessageShown)
            }

            SectionProfils(
                state = state,
                formulaireOuvert = formulaireOuvert,
                onOuvrirFormulaire = { formulaireOuvert = true },
                onSaveProfile = onSaveProfile,
                onDeleteProfile = onDeleteProfile,
                onActivateProfile = onActivateProfile,
                onTestConnection = onTestConnection,
                onMessageShown = onMessageShown,
            )

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

@Composable
private fun SectionProfils(
    state: SettingsUiState,
    formulaireOuvert: Boolean,
    onOuvrirFormulaire: () -> Unit,
    onSaveProfile: (String, String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onActivateProfile: (String) -> Unit,
    onTestConnection: () -> Unit,
    onMessageShown: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitreSection(stringResource(R.string.settings_profiles))

        if (state.profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.home_config_first_time),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.profiles.forEach { profil ->
            CarteProfil(
                profil = profil,
                actif = profil.id == state.activeProfileId,
                onActivate = { onActivateProfile(profil.id) },
                onDelete = { onDeleteProfile(profil.id) },
            )
        }

        if (formulaireOuvert) {
            FormulaireProfil(onSaveProfile = onSaveProfile, onFieldEdited = onMessageShown)
        } else {
            Button(onClick = onOuvrirFormulaire) {
                Text(text = stringResource(R.string.settings_profile_add))
            }
        }

        // Test réel de la connexion : c'est la réponse immédiate à « j'ai
        // configuré mon portail, que se passe-t-il ? ». Sans lui, l'utilisateur
        // devait quitter les réglages pour découvrir le résultat, sans savoir si
        // sa saisie avait été prise en compte.
        if (state.profiles.isNotEmpty()) {
            Button(onClick = onTestConnection, enabled = !state.isTestingConnection) {
                if (state.isTestingConnection) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp))
                }
                Text(text = stringResource(R.string.settings_test_connection))
            }

            when (val resultat = state.connection) {
                is ConnectionCheck.Nombre -> Text(
                    text = stringResource(R.string.settings_connection_ok, resultat.chaines),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                is ConnectionCheck.Erreur -> Text(
                    text = stringResource(resultat.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }
        }

        Text(
            text = stringResource(R.string.settings_secrets_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Fiche d'un profil.
 *
 * L'adresse MAC n'est jamais réaffichée en clair : seule sa forme masquée est
 * montrée, ce qui suffit à identifier le profil.
 */
@Composable
private fun CarteProfil(
    profil: PortalProfile,
    actif: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onActivate),
        // Le profil actif se distingue par un contour, jamais par un fond rouge :
        // le rouge se lit comme une erreur, et un profil actif n'en est pas une.
        border = BorderStroke(
            width = if (actif) 2.dp else 1.dp,
            color = if (actif) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = profil.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = Secrets.maskMac(profil.mac),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!profil.enabled) {
                    Text(
                        text = stringResource(R.string.no),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (actif) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
            }
        }
    }
}

@Composable
private fun FormulaireProfil(
    onSaveProfile: (String, String, String) -> Unit,
    onFieldEdited: () -> Unit,
) {
    var nom by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }

    // Un message d'erreur qui reste affiché alors que l'utilisateur a corrigé le
    // champ concerné est trompeur : il disparaît dès la première frappe.
    val effacerErreur: (String) -> Unit = { nouvelleValeur ->
        onFieldEdited()
        nouvelleValeur
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = nom,
            onValueChange = { nom = effacerErreur(it) },
            label = { Text(stringResource(R.string.settings_profile_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = effacerErreur(it) },
            label = { Text(stringResource(R.string.settings_portal_url)) },
            placeholder = { Text(stringResource(R.string.settings_portal_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = mac,
            onValueChange = { mac = effacerErreur(it) },
            label = { Text(stringResource(R.string.settings_mac)) },
            placeholder = { Text(stringResource(R.string.settings_mac_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.Characters,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSaveProfile(nom, url, mac) }) {
            Text(text = stringResource(R.string.action_save))
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

@Composable
private fun MessageSucces(onMessageShown: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onMessageShown)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_profile_saved),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun MessageErreur(texte: String, onMessageShown: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onMessageShown)
            .padding(12.dp),
    ) {
        Text(text = texte, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/** Date lisible par un humain, sans dépendre du format de la locale. */
private fun dateLisible(instantMs: Long): String {
    val format = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
    return format.format(java.util.Date(instantMs))
}
