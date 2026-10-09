package com.missa.tv.ui.home

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.channel.ChannelVariantGrouper
import com.missa.tv.domain.model.Channel
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tri (numérotation, alphabétique, groupes, pays) et filtre par pays de la
 * liste des chaînes. Les adresses sont fictives (hôtes en .invalid).
 */
@DisplayName("Tri et filtre par pays de la liste des chaînes")
class HomeUiStateSortTest {

    private fun chaine(id: String, nom: String, groupe: String?, pays: String?, numero: Int) = Channel(
        id = id,
        number = numero,
        name = nom,
        streamUrl = "http://exemple.invalid/$id.m3u8",
        categoryId = groupe,
        country = pays,
    )

    private val groupes = ChannelVariantGrouper.group(
        listOf(
            chaine("c1", "Zebra", "Sport", "CM", 1),
            chaine("c2", "Alpha TV", "Info", "FR", 2),
            chaine("c3", "Étoile", "Info", null, 3),
        ),
    )

    private fun etat(tri: SortMode = SortMode.NUMERATION, pays: String? = null) = HomeUiState(
        groups = groupes,
        sortMode = tri,
        selectedCountry = pays,
    )

    @Test
    @DisplayName("la numerotation suit l ordre de la source")
    fun `numerotation`() {
        assertThat(etat().visibleGroups.map { it.displayName })
            .containsExactly("Zebra", "Alpha TV", "Étoile").inOrder()
    }

    @Test
    @DisplayName("le tri alphabetique ignore les accents")
    fun `tri alphabetique`() {
        assertThat(etat(tri = SortMode.ALPHABETIQUE).visibleGroups.map { it.displayName })
            .containsExactly("Alpha TV", "Étoile", "Zebra").inOrder()
    }

    @Test
    @DisplayName("le tri par groupes classe par genre puis par nom")
    fun `tri par groupes`() {
        assertThat(etat(tri = SortMode.GROUPES).visibleGroups.map { it.displayName })
            .containsExactly("Alpha TV", "Étoile", "Zebra").inOrder()
    }

    @Test
    @DisplayName("le tri par pays pousse les sans-pays en fin de liste")
    fun `tri par pays`() {
        assertThat(etat(tri = SortMode.PAYS).visibleGroups.map { it.displayName })
            .containsExactly("Zebra", "Alpha TV", "Étoile").inOrder()
    }

    @Test
    @DisplayName("le filtre pays ne garde que le pays choisi")
    fun `filtre pays`() {
        assertThat(etat(pays = "CM").visibleGroups.map { it.displayName }).containsExactly("Zebra")
    }
}
