package com.missa.tv.ui.epg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.missa.tv.R
import com.missa.tv.core.time.ClockFormat
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.ui.home.BandeauInformation
import com.missa.tv.ui.home.LogoChaine

/**
 * Programme complet d'une chaîne.
 *
 * La liste des programmes des 24 prochaines heures, le programme en cours
 * mis en évidence avec son avancement. L'action principale — regarder la
 * chaîne — est un bouton pleine largeur en haut d'écran : depuis le programme,
 * le geste attendu est de lancer la lecture.
 */
@Composable
fun ChannelGuideScreen(
    state: ChannelGuideUiState,
    onPlay: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    // L'instant de référence est recalculé à chaque recomposition : les
    // programmes en cours avancent avec les mises à jour d'état.
    val maintenant = System.currentTimeMillis()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        EnTeteGuide(
            state = state,
            onPlay = onPlay,
            onRefresh = onRefresh,
            onBack = onBack,
        )

        if (state.isFromCache && state.events.isNotEmpty()) {
            BandeauInformation(texte = stringResource(R.string.epg_cached_notice))
        }
        state.error?.let { erreur ->
            BandeauInformation(texte = stringResource(erreur.messageRes))
        }

        when {
            state.events.isNotEmpty() -> ListeProgrammes(
                events = state.events,
                maintenant = maintenant,
            )
            state.isLoading -> Chargement()
            else -> Vide(onBack = onBack)
        }
    }
}

@Composable
private fun EnTeteGuide(
    state: ChannelGuideUiState,
    onPlay: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.player_back),
                )
            }
            LogoChaine(url = state.channel.logoUrl)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = state.channel.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.epg_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.epg_refresh),
                )
            }
        }

        // Bouton de lecture, pleine largeur : depuis le programme d'une chaîne,
        // c'est l'action attendue, et elle reste atteignable au D-pad sur TV.
        Button(
            onClick = onPlay,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
            Text(
                text = stringResource(R.string.player_open),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ListeProgrammes(events: List<EpgEvent>, maintenant: Long) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = events, key = { it.id }) { evenement ->
            LigneProgramme(evenement = evenement, maintenant = maintenant)
        }
    }
}

/**
 * Un programme : horaires, titre, description, et avancement s'il est en cours.
 *
 * La liste est en lecture seule : sur télévision, le défilement au D-pad est
 * assuré par le conteneur, et les actions (lecture, rafraîchissement) sont des
 * boutons focalisables en haut d'écran.
 */
@Composable
private fun LigneProgramme(evenement: EpgEvent, maintenant: Long) {
    val enCours = evenement.isLiveAt(maintenant)
    val couleurFond = if (enCours) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val couleurTexteSecondaire = if (enCours) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = couleurFond),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.epg_time_range,
                        ClockFormat.hourMinute(evenement.startMs),
                        ClockFormat.hourMinute(evenement.endMs),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = couleurTexteSecondaire,
                )
                if (enCours) {
                    Text(
                        text = stringResource(R.string.guide_now_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = couleurTexteSecondaire,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                text = evenement.title,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            evenement.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = couleurTexteSecondaire,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (enCours) {
                LinearProgressIndicator(
                    progress = { evenement.progressAt(maintenant) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Chargement() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.epg_loading),
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Vide(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Tv,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.guide_empty),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = stringResource(R.string.player_back))
            }
        }
    }
}
