package com.missa.tv.data.playlist

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.error.AppError
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Téléchargement d'une playlist M3U.
 *
 * L'accès réseau est doublé par [FetcherFactice] : aucun appel réel n'est
 * émis, et les hôtes des URLs de test sont en `.invalid`.
 */
@DisplayName("Téléchargement d'une playlist M3U")
class M3uPlaylistDownloaderTest {

    private val fetcher = FetcherFactice()
    private val telechargeur = M3uPlaylistDownloader(fetcher)

    private val playlistValide =
        """
        #EXTM3U
        #EXTINF:-1,France Info
        http://flux.invalid/france-info
        """.trimIndent()

    private fun corps(contenu: String): InputStream =
        ByteArrayInputStream(contenu.toByteArray(Charsets.UTF_8))

    @Nested
    @DisplayName("Succès")
    inner class Succes {

        @Test
        fun `renvoie les entrees d une playlist telechargee`() = runTest {
            fetcher.prochaineReponse = PlaylistHttpResponse.Body(corps(playlistValide))

            val resultat = telechargeur.download("http://source.invalid/liste.m3u8")

            assertThat(resultat.isSuccess).isTrue()
            assertThat(resultat.valueOrNull()).hasSize(1)
            assertThat(resultat.valueOrNull()!!.single().title).isEqualTo("France Info")
        }

        @Test
        fun `envoie l agent utilisateur et le referrent demandes`() = runTest {
            fetcher.prochaineReponse = PlaylistHttpResponse.Body(corps(playlistValide))

            telechargeur.download(
                url = "http://source.invalid/liste.m3u8",
                userAgent = "MISSA/1.0",
                referrer = "http://origine.invalid/",
            )

            val requete = fetcher.derniereRequete!!
            assertThat(requete.headers["User-Agent"]).isEqualTo("MISSA/1.0")
            assertThat(requete.headers["Referer"]).isEqualTo("http://origine.invalid/")
        }

        @Test
        fun `n envoie pas d en-tete quand les valeurs sont vides`() = runTest {
            fetcher.prochaineReponse = PlaylistHttpResponse.Body(corps(playlistValide))

            telechargeur.download("http://source.invalid/liste.m3u8", userAgent = "  ")

            assertThat(fetcher.derniereRequete!!.headers).isEmpty()
        }
    }

    @Nested
    @DisplayName("Échecs")
    inner class Echecs {

        @Test
        fun `signale une cible injoignable`() = runTest {
            fetcher.prochaineException = IOException("réseau coupé")

            val resultat = telechargeur.download("http://source.invalid/liste.m3u8")

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.PlaylistUnreachable)
        }

        @Test
        fun `signale une playlist refusee par le serveur`() = runTest {
            fetcher.prochaineReponse = PlaylistHttpResponse.Refused(403)

            val resultat = telechargeur.download("http://source.invalid/liste.m3u8")

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.PlaylistRejected)
        }

        @Test
        fun `signale un contenu non reconnu`() = runTest {
            fetcher.prochaineReponse = PlaylistHttpResponse.Body(corps("Page HTML sans playlist"))

            val resultat = telechargeur.download("http://source.invalid/liste.m3u8")

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.PlaylistInvalid)
        }

        @Test
        fun `signale une playlist sans aucune chaine`() = runTest {
            fetcher.prochaineReponse = PlaylistHttpResponse.Body(corps("#EXTM3U"))

            val resultat = telechargeur.download("http://source.invalid/liste.m3u8")

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.PlaylistEmpty)
        }

        @Test
        fun `signale un depassement de la taille maximale`() = runTest {
            val telechargeurBorne = M3uPlaylistDownloader(
                fetcher = fetcher,
                reader = M3uPlaylistReader(maxBytes = 20),
            )
            fetcher.prochaineReponse = PlaylistHttpResponse.Body(corps(playlistValide))

            val resultat = telechargeurBorne.download("http://source.invalid/liste.m3u8")

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.PlaylistTooLarge)
        }
    }

    /** Doublure de l'accès réseau : renvoie la réponse ou l'exception configurée. */
    private class FetcherFactice : PlaylistHttpFetcher {

        var prochaineReponse: PlaylistHttpResponse = PlaylistHttpResponse.Refused(0)
        var prochaineException: Throwable? = null
        var derniereRequete: PlaylistHttpRequest? = null

        override suspend fun fetch(request: PlaylistHttpRequest): PlaylistHttpResponse {
            derniereRequete = request
            val erreur = prochaineException
            if (erreur != null) throw erreur
            return prochaineReponse
        }
    }
}
