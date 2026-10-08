package com.missa.tv.ui.home

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.channel.ChannelVariantGrouper
import com.missa.tv.domain.model.Channel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Filtrage de la liste des chaînes par favoris, par catégorie et par recherche.
 *
 * Les adresses sont fictives (hôtes en `.invalid`). Ce qui est vérifié : la
 * combinaison des filtres entre eux, et la recherche insensible à la casse et
 * aux accents.
 */
@DisplayName("Liste des chaînes filtrée par favoris et recherche")
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
            chaine("m3u-equipe", "Équipe 21", "sport"),
        ),
    )

    private fun cleDe(nom: String): String = groupes.first { it.displayName == nom }.key

    private fun etat(
        favoris: Set<String> = emptySet(),
        favorisSeuls: Boolean = false,
        categorie: String? = null,
        requete: String = "",
    ) = HomeUiState(
        groups = groupes,
        selectedCategoryId = categorie,
        favoriteKeys = favoris,
        showFavoritesOnly = favorisSeuls,
        query = requete,
    )

    @Test
    @DisplayName("sans filtre, toutes les chaînes sont visibles")
    fun `sans filtre tout est visible`() {
        assertThat(etat().visibleGroups).hasSize(4)
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

    @Test
    @DisplayName("la recherche filtre par nom")
    fun `recherche par nom`() {
        val etat = etat(requete = "france")

        assertThat(etat.visibleGroups.map { it.displayName }).containsExactly("France 2")
    }

    @Test
    @DisplayName("la recherche ignore les accents et la casse")
    fun `recherche ignore accents et casse`() {
        val etat = etat(requete = "EQUIPE")

        assertThat(etat.visibleGroups.map { it.displayName }).containsExactly("Équipe 21")
    }

    @Test
    @DisplayName("une recherche vide n'écarte aucune chaîne")
    fun `recherche vide affiche tout`() {
        assertThat(etat(requete = "").visibleGroups).hasSize(4)
    }

    @Test
    @DisplayName("une recherche sans correspondance donne une liste vide")
    fun `recherche sans resultat`() {
        assertThat(etat(requete = "introuvable").visibleGroups).isEmpty()
    }

    @Test
    @DisplayName("recherche, favoris et catégorie se combinent")
    fun `recherche favoris et categorie se combinent`() {
        val etat = etat(
            favoris = setOf(cleDe("Match Direct"), cleDe("Équipe 21")),
            favorisSeuls = true,
            categorie = "sport",
            requete = "equipe",
        )

        assertThat(etat.visibleGroups.map { it.displayName }).containsExactly("Équipe 21")
    }
}
