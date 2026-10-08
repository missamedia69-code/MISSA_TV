package com.missa.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.missa.tv.R
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.adaptive.DeviceType
import com.missa.tv.ui.adaptive.WindowWidthClass

/**
 * Liste des chaînes.
 *
 * L'écran s'adapte à l'appareil : sur téléphone les chaînes s'affichent en une
 * colonne aérée, sur télévision en deux colonnes, avec des cibles plus grandes
 * adaptées à la navigation à la télécommande.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    device: DeviceProfile,
    onRetry: () -> Unit,
    onCategorySelected: (String?) -> Unit,
    onChannelSelected: (ChannelGroup) -> Unit,
    onOpenEpg: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenManualSetup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Les barres système sont translucides (affichage bord à bord) :
            // sans cette marge, le titre passerait sous l'heure et la batterie.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        EnTete(state = state, onOpenEpg = onOpenEpg, onOpenSettings = onOpenSettings)

        if (state.requiresAppUpdate) {
            BandeauInformation(texte = stringResource(R.string.home_update_required))
        }

        if (state.isFromCache) {
            BandeauInformation(texte = stringResource(R.string.home_cached_catalog))
        }

        if (device.isTv) {
            // Liste TV : recentrage sur l'élément sélectionné et focus visible à
            // distance, ce que les composants tactiles ne font pas.
            TvCategoriesRow(
                categories = state.categories,
                selection = state.selectedCategoryId,
                onSelected = onCategorySelected,
            )
        } else {
            CategoriesRow(
                categories = state.categories,
                selection = state.selectedCategoryId,
                onSelected = onCategorySelected,
            )
        }

        when {
            // Une liste disponible est toujours préférée à un écran de
            // chargement : elle vient du portail ou du catalogue mémorisé.
            state.groups.isNotEmpty() -> Liste(
                groups = state.visibleGroups,
                nowPlaying = state.nowPlaying,
                device = device,
                onChannelSelected = onChannelSelected,
            )
            state.isLoading -> Chargement()
            state.error != null -> Erreur(state = state, onRetry = onRetry)
            else -> AucuneChaine(onOpenManualSetup = onOpenManualSetup)
        }
    }
}

@Composable
private fun EnTete(
    state: HomeUiState,
    onOpenEpg: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.groups.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.home_channel_count, state.groups.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row {
            IconButton(onClick = onOpenEpg) {
                Icon(
                    imageVector = Icons.Filled.Tv,
                    contentDescription = stringResource(R.string.epg_open),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.home_open_settings),
                )
            }
        }
    }
}

/**
 * Bandeau d'information (mise à jour disponible, contenu mémorisé…).
 *
 * Partagé avec l'écran du programme d'une chaîne, qui signale de la même
 * façon un guide affiché depuis le cache.
 */
@Composable
internal fun BandeauInformation(texte: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = texte,
            modifier = Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Bandeau de catégories.
 *
 * Chaque catégorie est une cible tactile et, sur télévision, une cible
 * focalisable à la télécommande.
 */
@Composable
private fun CategoriesRow(
    categories: List<Category>,
    selection: String?,
    onSelected: (String?) -> Unit,
) {
    if (categories.isEmpty()) return

    val toutes = stringResource(R.string.home_categories_all)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PastilleCategorie(
                titre = toutes,
                selectionnee = selection == null,
                onClick = { onSelected(null) },
            )
        }
        items(items = categories, key = { it.id }) { categorie ->
            PastilleCategorie(
                titre = categorie.title,
                selectionnee = selection == categorie.id,
                onClick = { onSelected(categorie.id) },
            )
        }
    }
}

@Composable
private fun PastilleCategorie(titre: String, selectionnee: Boolean, onClick: () -> Unit) {
    val fond = if (selectionnee) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val texte = if (selectionnee) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(fond)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = titre, color = texte, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Chargement() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.home_loading),
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Erreur(state: HomeUiState, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(state.error?.messageRes ?: R.string.error_portal_unreachable),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                Text(
                    text = stringResource(R.string.action_retry),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AucuneChaine(onOpenManualSetup: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            // Engrenage : le geste attendu ici est de configurer le portail,
            // pas de lancer une lecture.
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.home_config_first_time),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.empty_channels),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenManualSetup, modifier = Modifier.padding(top = 20.dp)) {
                Text(text = stringResource(R.string.home_configure_portal))
            }
        }
    }
}

@Composable
private fun Liste(
    groups: List<ChannelGroup>,
    nowPlaying: Map<String, EpgEvent>,
    device: DeviceProfile,
    onChannelSelected: (ChannelGroup) -> Unit,
) {
    // Sur un téléviseur, l'écran est large et regardé de loin : deux colonnes et
    // des vignettes plus grandes sont plus lisibles et plus faciles à cibler.
    val surTeleviseur = device.type == DeviceType.TELEVISION
    val colonnes = if (device.widthClass == WindowWidthClass.EXPANDED && surTeleviseur) 2 else 1

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (colonnes == 1) {
            items(items = groups, key = { it.key }) { groupe ->
                val programme = nowPlaying[groupe.key]
                if (surTeleviseur) {
                    TvChannelCard(
                        groupe = groupe,
                        programme = programme,
                        onSelected = onChannelSelected,
                    )
                } else {
                    LigneChaine(
                        groupe = groupe,
                        programme = programme,
                        onSelected = onChannelSelected,
                    )
                }
            }
        } else {
            items(items = groups.chunked(colonnes), key = { it.first().key }) { rangee ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rangee.forEach { groupe ->
                        Box(modifier = Modifier.weight(1f)) {
                            val programme = nowPlaying[groupe.key]
                            if (surTeleviseur) {
                                TvChannelCard(
                                    groupe = groupe,
                                    programme = programme,
                                    onSelected = onChannelSelected,
                                )
                            } else {
                                LigneChaine(
                                    groupe = groupe,
                                    programme = programme,
                                    onSelected = onChannelSelected,
                                )
                            }
                        }
                    }
                    // Garde l'alignement des colonnes quand la dernière rangée
                    // ne contient qu'un seul élément.
                    if (rangee.size < colonnes) Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Une chaîne : numéro, logo, nom, qualités disponibles et programme en cours. */
@Composable
private fun LigneChaine(
    groupe: ChannelGroup,
    programme: EpgEvent?,
    onSelected: (ChannelGroup) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(groupe) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = groupe.lowest.channel.displayNumber,
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LogoChaine(url = groupe.lowest.channel.logoUrl)

            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = groupe.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!groupe.hasSingleVariant) {
                    // Information utile : elle explique à l'utilisateur qu'une
                    // même chaîne est diffusée en plusieurs qualités.
                    Text(
                        text = stringResource(R.string.home_variants, groupe.distinctQualityCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                programme?.let { ProgrammeEnCours(programme = it) }
            }

            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.player_open),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Programme en cours d'une chaîne : pastille « en direct », titre et avancement.
 *
 * Affiché sous le nom de la chaîne quand le portail publie un guide. Sans
 * guide, la ligne n'existe pas : l'absence d'information n'est pas un espace
 * réservé, elle est simplement absente.
 */
@Composable
internal fun ProgrammeEnCours(programme: EpgEvent) {
    // L'avancement est recalculé à chaque recomposition : il progresse avec
    // les mises à jour d'état, sans horloge dédiée.
    val maintenant = System.currentTimeMillis()

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                text = stringResource(R.string.epg_now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = programme.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(
            progress = { programme.progressAt(maintenant) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

@Composable
internal fun LogoChaine(url: String?) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) return@Box
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(44.dp),
        )
    }
}
