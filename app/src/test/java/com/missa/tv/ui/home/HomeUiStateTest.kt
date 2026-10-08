package com.missa.tv.ui.home

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.channel.ChannelVariantGrouper
import com.missa.tv.domain.model.Channel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Filtrage de la liste des chaînes par favoris et par catégorie.
 *
 * Les adresses sont fictives (hôtes en `.invalid`). Ce qui est vérifié : la
 * combinaison du filtre favoris avec le filtre de catégorie, et le fait qu'un
 * favori hors de la catégorie choisie n'apparaît pas.
 */
@DisplayName("Liste des chaînes filtrée par favoris")
class HomeUiStateTest {

    private fun chaine(id: String, nom: String, categorie: String?) = Channel(
        id = id,
        number = 1,
        name = nom,
        streamUrl = "http://exemple.invalid/$id.m3u8",
        categoryId = categorie,
    )

    private val groupes = ChannelVariantGrouper.group(
        listOf(
            chaine("m3u-tf1", "TF1", "info"),
            chaine("m3u-france2", "France 2", "info"),
            chaine("m3u-match", "Match Direct", "sport"),
        ),
    )

    private fun cleDe(nom: String): String = groupes.first { it.displayName == nom }.key

    private fun etat(
        favoris: Set<String>,
        favorisSeuls: Boolean,
        categorie: String? = null,
    ) = HomeUiState(
        groups = groupes,
        selectedCategoryId = categorie,
        favoriteKeys = favoris,
        showFavoritesOnly = favorisSeuls,
    )

    @Test
    @DisplayName("sans filtre favoris, toutes les chaînes sont visibles")
    fun `sans filtre favoris tout est visible`() {
        val etat = etat(favoris = setOf(cleDe("TF1")), favorisSeuls = false)

        assertThat(etat.visibleGroups).hasSize(3)
    }

    @Test
    @DisplayName("le filtre favoris ne garde que les groupes en favori")
    fun `filtre favoris ne garde que les favoris`() {
        val etat = etat(favoris = setOf(cleDe("TF1")), favorisSeuls = true)

        assertThat(etat.visibleGroups.map { it.displayName }).containsExactly("TF1")
    }

    @Test
    @DisplayName("favoris et catégorie se combinent")
    fun `favoris et categorie se combinent`() {
        val etat = etat(
            favoris = setOf(cleDe("France 2"), cleDe("Match Direct")),
            favorisSeuls = true,
            categorie = "info",
        )

        assertThat(etat.visibleGroups.map { it.displayName }).containsExactly("France 2")
    }

    @Test
    @DisplayName("un favori hors de la catégorie choisie est masqué")
    fun `favori hors categorie est masque`() {
        val etat = etat(
            favoris = setOf(cleDe("Match Direct")),
            favorisSeuls = true,
            categorie = "info",
        )

        assertThat(etat.visibleGroups).isEmpty()
    }
}
