package com.missa.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.missa.tv.R
import com.missa.tv.domain.bandwidth.ConnectionClass
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.adaptive.DeviceType

/**
 * Choix de la qualité d'image.
 *
 * Deux présentations pour un même contenu : une feuille glissante sur téléphone
 * et tablette, une boîte de dialogue sur télévision. Sur télévision, la
 * sélection au clavier est indispensable — chaque ligne est donc focalisable, et
 * le quatrième bouton de la télécommande ne sert pas de sélecteur implicite.
 *
 * Le contenu est identique dans les deux cas : liste des modes, verrouillage
 * éventuel, et **débit réellement mesuré**, affiché pour que l'utilisateur
 * comprenne les décisions de l'application plutôt que de les subir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelector(
    visible: Boolean,
    state: PlayerUiState,
    device: DeviceProfile,
    onSelect: (QualityMode, Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    var verrouille by remember { mutableStateOf(state.qualityLocked) }

    val contenu: @Composable () -> Unit = {
        SelecteurContenu(
            state = state,
            verrouille = verrouille,
            onVerrouilleChange = { verrouille = it },
            onSelect = onSelect,
            onClear = onClear,
        )
    }

    if (device.type == DeviceType.TELEVISION) {
        // Sur télévision, une feuille glissante serait malcommode à la
        // télécommande : la boîte de dialogue garde la navigation par flèches.
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
            ) {
                contenu()
            }
        }
    } else {
        val etatFeuille = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = etatFeuille) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                contenu()
            }
        }
    }
}

@Composable
private fun SelecteurContenu(
    state: PlayerUiState,
    verrouille: Boolean,
    onVerrouilleChange: (Boolean) -> Unit,
    onSelect: (QualityMode, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Text(
        text = stringResource(R.string.quality_choose),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )

    Text(
        text = etatConnexion(state),
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.isSingleQuality) {
        // Honnêteté de l'interface : sur une chaîne mono-qualité, réduire la
        // qualité ne change pas l'image. Le dire évite de faire croire à une
        // amélioration qui n'existe pas.
        Text(
            text = stringResource(R.string.notice_single_quality_buffering),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Column(
        modifier = Modifier
            .padding(top = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QualityMode.selectable.forEach { mode ->
            LigneMode(
                mode = mode,
                selectionne = state.qualityMode == mode,
                onClick = { onSelect(mode, verrouille) },
            )
        }

        // Rétablir l'adaptation automatique, y compris après un verrouillage.
        if (state.qualityLocked) {
            LigneAction(
                texte = stringResource(R.string.quality_unlock),
                onClick = onClear,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (verrouille) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
            Text(
                text = stringResource(R.string.quality_lock),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.player_quality_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = verrouille, onCheckedChange = onVerrouilleChange)
    }
}

/** Une ligne de mode : libellé, description et marque de sélection. */
@Composable
private fun LigneMode(mode: QualityMode, selectionne: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(mode.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selectionne) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (mode == QualityMode.AUTO_ECONOMY) {
                Text(
                    text = stringResource(R.string.quality_auto_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (mode.isAudioOnly) {
                Text(
                    text = stringResource(R.string.player_audio_only_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
private fun LigneAction(texte: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = texte,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Débit mesuré et classe de connexion, en clair. */
@Composable
private fun etatConnexion(state: PlayerUiState): String {
    val debit = state.measuredKbps
    val mesure = if (debit == null) {
        stringResource(R.string.quality_measured_unknown)
    } else {
        stringResource(R.string.quality_measured, debit)
    }
    val connexion = stringResource(
        when (state.connectionClass) {
            ConnectionClass.LOW -> R.string.quality_connection_low
            ConnectionClass.MEDIUM -> R.string.quality_connection_medium
            ConnectionClass.GOOD -> R.string.quality_connection_good
            ConnectionClass.UNKNOWN -> R.string.quality_connection_unknown
        },
    )
    return "$mesure\n$connexion"
}
