package com.missa.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
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
import com.missa.tv.core.time.ClockFormat
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.EpgEvent

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
 * L'habillage s'inspire des guides des plateformes de streaming : une ligne
 * large qui présente l'identité de la chaîne à gauche (numéro + logo), puis le
 * programme en cours avec sa progression, et le programme suivant. Sans guide,
 * la ligne reste propre : seule la mention « aucun programme annoncé »
 * apparaît, jamais un bloc vide.
 *
 * La carte entière est cliquable et réagit au focus : c'est indispensable à la
 * télécommande, où il n'y a pas de survol et où l'utilisateur doit voir sans
 * ambiguïté quel élément sera ouvert.
 */
@Composable
fun TvChannelCard(
    groupe: ChannelGroup,
    programme: EpgEvent?,
    suivant: EpgEvent?,
    isFavorite: Boolean,
    onSelected: (ChannelGroup) -> Unit,
    onToggleFavorite: (ChannelGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { onSelected(groupe) },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Identité de la chaîne : numéro au-dessus du logo, comme dans la
            // colonne des en-têtes d'un guide.
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = groupe.lowest.channel.displayNumber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LogoChaine(url = groupe.lowest.channel.logoUrl)
            }

            Column(
                modifier = Modifier
                    .padding(start = 20.dp)
                    .weight(1f),
            ) {
                Text(
                    text = groupe.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (programme != null) {
                    val maintenant = System.currentTimeMillis()
                    Text(
                        text = stringResource(R.string.epg_now),
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${ClockFormat.hourMinute(programme.startMs)} – " +
                            ClockFormat.hourMinute(programme.endMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { programme.progressAt(maintenant) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    if (suivant != null) {
                        Text(
                            text = stringResource(
                                R.string.epg_next_at,
                                ClockFormat.hourMinute(suivant.startMs),
                                suivant.title,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.home_no_program),
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!groupe.hasSingleVariant) {
                        Text(
                            text = stringResource(R.string.home_variants, groupe.distinctQualityCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // L'étoile est une cible focalisable distincte : à la télécommande,
            // elle bascule le favori sans ouvrir la chaîne.
            EtoileFavori(groupe = groupe, isFavorite = isFavorite, onToggleFavorite = onToggleFavorite)

            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.player_open),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
