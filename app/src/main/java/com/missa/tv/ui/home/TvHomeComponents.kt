package com.missa.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Card
import com.missa.tv.R
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.ChannelGroup

/**
 * Composants d'interface réservés à Android TV.
 *
 * Le matériel TV se pilote à la télécommande et se regarde à plusieurs mètres :
 * les cartes de `androidx.tv.material3` rendent un **focus franc** (mise à
 * l'échelle, halo) que les composants tactiles n'affichent pas.
 *
 * Les listes paresseuses de télévision (`TvLazyRow`) ont été fusionnées dans
 * Compose Foundation : `androidx.tv:tv-foundation` 1.0.0 ne les expose plus. La
 * liste horizontale est donc une `LazyRow` ordinaire — le défilement au D-pad
 * étant déjà assuré par Compose — et seule la carte est spécifique à la TV.
 *
 * Ces composants ne sont utilisés que si un téléviseur est détecté ; sur
 * téléphone et tablette, l'interface Material 3 habituelle reste en place.
 */

/** Barre de catégories défilante, pilotable à la télécommande. */
@Composable
fun TvCategoriesRow(
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
        item(key = "__toutes__") {
            TvPastille(
                titre = toutes,
                selectionnee = selection == null,
                onClick = { onSelected(null) },
            )
        }
        items(items = categories, key = { it.id }) { categorie ->
            TvPastille(
                titre = categorie.title,
                selectionnee = selection == categorie.id,
                onClick = { onSelected(categorie.id) },
            )
        }
    }
}

@Composable
private fun TvPastille(titre: String, selectionnee: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Text(
            text = titre,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selectionnee) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * Une chaîne, telle qu'elle apparaît sur un téléviseur.
 *
 * La carte entière est cliquable et réagit au focus : c'est indispensable à la
 * télécommande, où il n'y a pas de survol et où l'utilisateur doit voir sans
 * ambiguïté quel élément sera ouvert.
 */
@Composable
fun TvChannelCard(
    groupe: ChannelGroup,
    onSelected: (ChannelGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { onSelected(groupe) },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = groupe.lowest.channel.displayNumber,
                modifier = Modifier.width(48.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            LogoChaine(url = groupe.lowest.channel.logoUrl)

            Column(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            ) {
                Text(
                    text = groupe.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!groupe.hasSingleVariant) {
                    Text(
                        text = stringResource(R.string.home_variants, groupe.distinctQualityCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.player_open),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
