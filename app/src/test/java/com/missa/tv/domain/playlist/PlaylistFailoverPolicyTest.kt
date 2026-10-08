package com.missa.tv.domain.playlist

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.error.AppError
import com.missa.tv.domain.model.PlaylistSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Ordre des sources et décision de bascule.
 */
@DisplayName("Politique de bascule des playlists")
class PlaylistFailoverPolicyTest {

    private val politique = PlaylistFailoverPolicy()

    private fun source(id: String, enabled: Boolean = true, url: String = "http://s.invalid/$id") =
        PlaylistSource(id = id, name = id, url = url, enabled = enabled)

    @Test
    fun `ecarte les sources desactivees ou sans url`() {
        val sources = listOf(
            source("a"),
            source("b", enabled = false),
            source("c", url = " "),
            source("d"),
        )

        val candidats = politique.candidates(sources)

        assertThat(candidats.map { it.id }).containsExactly("a", "d").inOrder()
    }

    @Test
    fun `limite le nombre de tentatives`() {
        val petite = PlaylistFailoverPolicy(maxAttempts = 2)
        val sources = listOf(source("a"), source("b"), source("c"))

        val candidats = petite.candidates(sources)

        assertThat(candidats.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `renvoie une liste vide quand aucune source n est exploitable`() {
        val candidats = politique.candidates(listOf(source("a", enabled = false)))

        assertThat(candidats).isEmpty()
    }

    @Test
    fun `autorise la bascule pour toutes les erreurs de playlist`() {
        val erreursPlaylist = listOf(
            AppError.PlaylistUnreachable,
            AppError.PlaylistRejected,
            AppError.PlaylistInvalid,
            AppError.PlaylistEmpty,
            AppError.PlaylistTooLarge,
        )

        for (erreur in erreursPlaylist) {
            assertThat(politique.shouldFailover(erreur)).isTrue()
        }
    }

    @Test
    fun `n autorise pas la bascule pour une erreur etrangere aux playlists`() {
        assertThat(politique.shouldFailover(AppError.NetworkLost)).isFalse()
    }
}
