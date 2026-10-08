package com.missa.tv.ui.epg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card as TvCard
import com.missa.tv.R
import com.missa.tv.core.time.ClockFormat
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.home.LogoChaine
import com.missa.tv.ui.home.ProgrammeEnCours

/**
 * Grille du programme.
 *
 * Une ligne par chaîne de la sélection courante : le programme en cours (avec
 * son avancement) et le programme suivant. Cliquer une ligne ouvre le
 * programme complet de la chaîne ; la lecture se lance depuis cet écran.
 *
 * Sur télévision, les lignes sont des cartes `tv-material` : le focus au
 * D-pad doit rester visible à distance.
 */
@Composable
fun EpgScreen(
    state: EpgUiState,
    device: DeviceProfile,
    onChannelSelected: (ChannelGroup) -> Unit,
    onRowsVisible: (List<ChannelGroup>) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        EnTeteGrille(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            onBack = onBack,
        )

        when {
            state.rows.isNotEmpty() -> Grille(
                rows = state.rows,
                device = device,
                onChannelSelected = onChannelSelected,
                onRowsVisible = onRowsVisible,
            )
            else -> Vide(onBack = onBack)
        }
    }
}

@Composable
private fun EnTeteGrille(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
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
        Icon(
            imageVector = Icons.Filled.Tv,
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 8.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.epg_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.epg_refresh),
            )
        }
    }
}

@Composable
private fun Grille(
    rows: List<EpgRow>,
    device: DeviceProfile,
    onChannelSelected: (ChannelGroup) -> Unit,
    onRowsVisible: (List<ChannelGroup>) -> Unit,
) {
    val listState = rememberLazyListState()

    // Les chaînes rendues visibles au défilement sont transmises au ViewModel :
    // leur guide est rafraîchi à la demande, les chaînes hors champ ne sont
    // jamais sollicitées.
    val groupesVisibles by remember(rows) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { info -> rows.getOrNull(info.index)?.group }
        }
    }
    LaunchedEffect(groupesVisibles) {
        onRowsVisible(groupesVisibles)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = rows, key = { it.group.key }) { ligne ->
            if (device.isTv) {
                TvLigneGrille(ligne = ligne, onSelected = onChannelSelected)
            } else {
                LigneGrille(ligne = ligne, onSelected = onChannelSelected)
            }
        }
    }
}

/** Une ligne de la grille, sur téléphone et tablette. */
@Composable
private fun LigneGrille(ligne: EpgRow, onSelected: (ChannelGroup) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(ligne.group) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TexteLigne(ligne = ligne)
        }
    }
}

/** Une ligne de la grille, sur télévision : la carte entière réagit au focus. */
@Composable
private fun TvLigneGrille(ligne: EpgRow, onSelected: (ChannelGroup) -> Unit) {
    TvCard(
        onClick = { onSelected(ligne.group) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TexteLigne(ligne = ligne)
        }
    }
}

/** Contenu d'une ligne : numéro, logo, nom, programme en cours et suivant. */
@Composable
private fun RowScope.TexteLigne(ligne: EpgRow) {
    Text(
        text = ligne.group.lowest.channel.displayNumber,
        modifier = Modifier.width(40.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    LogoChaine(url = ligne.group.lowest.channel.logoUrl)

    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
        Text(
            text = ligne.group.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ligne.current?.let { ProgrammeEnCours(programme = it) }
        ligne.next?.let { suivant ->
            Text(
                text = stringResource(
                    R.string.epg_next_at,
                    ClockFormat.hourMinute(suivant.startMs),
                    suivant.title,
                ),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = stringResource(R.string.epg_open_channel),
        tint = MaterialTheme.colorScheme.primary,
    )
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
                text = stringResource(R.string.epg_empty_grid),
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
