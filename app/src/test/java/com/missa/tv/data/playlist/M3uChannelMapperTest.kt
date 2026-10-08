package com.missa.tv.data.playlist

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Conversion des entrées M3U en chaînes du domaine.
 *
 * Les URLs de test sont en `.invalid` : aucune adresse réelle n'est utilisée.
 */
@DisplayName("Conversion M3U vers le domaine")
class M3uChannelMapperTest {

    @Test
    fun `reporte les champs de l entree vers la chaine`() {
        val entree = M3uEntry(
            title = "France Info",
            streamUrl = "http://flux.invalid/france-info",
            logoUrl = "http://logo.invalid/fi.png",
            groupTitle = "Info",
            tvgId = "fr.info",
            userAgent = "MISSA/1.0",
            referrer = "http://origine.invalid/",
        )

        val chaine = M3uChannelMapper.toChannels(listOf(entree)).single()

        assertThat(chaine.name).isEqualTo("France Info")
        assertThat(chaine.streamUrl).isEqualTo("http://flux.invalid/france-info")
        assertThat(chaine.logoUrl).isEqualTo("http://logo.invalid/fi.png")
        assertThat(chaine.categoryId).isEqualTo("Info")
        assertThat(chaine.tvgId).isEqualTo("fr.info")
        assertThat(chaine.userAgent).isEqualTo("MISSA/1.0")
        assertThat(chaine.referrer).isEqualTo("http://origine.invalid/")
        assertThat(chaine.isCensored).isFalse()
        assertThat(chaine.isAvailable).isTrue()
    }

    @Test
    fun `numerote les chaines dans l ordre de la playlist`() {
        val entrees = listOf(
            M3uEntry(title = "Une", streamUrl = "http://flux.invalid/une"),
            M3uEntry(title = "Deux", streamUrl = "http://flux.invalid/deux"),
            M3uEntry(title = "Trois", streamUrl = "http://flux.invalid/trois"),
        )

        val chaines = M3uChannelMapper.toChannels(entrees)

        assertThat(chaines.map { it.number }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `donne un identifiant stable pour une meme url`() {
        val entree = M3uEntry(title = "Une", streamUrl = "http://flux.invalid/une")

        val premier = M3uChannelMapper.toChannels(listOf(entree)).single().id
        val second = M3uChannelMapper.toChannels(listOf(entree)).single().id

        assertThat(premier).isEqualTo(second)
    }

    @Test
    fun `donne des identifiants distincts pour des urls differentes`() {
        val entrees = listOf(
            M3uEntry(title = "Une", streamUrl = "http://flux.invalid/une"),
            M3uEntry(title = "Deux", streamUrl = "http://flux.invalid/deux"),
        )

        val chaines = M3uChannelMapper.toChannels(entrees)

        assertThat(chaines[0].id).isNotEqualTo(chaines[1].id)
    }
}
