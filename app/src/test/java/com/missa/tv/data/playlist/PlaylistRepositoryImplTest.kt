package com.missa.tv.data.playlist

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.result.AppResult
import com.missa.tv.domain.model.PlaylistSource
import com.missa.tv.domain.repository.PlaylistSourceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Bascule entre sources de playlists et repli sur le cache, au niveau du dépôt.
 *
 * Le téléchargement et le magasin de sources sont doublés : ce qui est vérifié
 * ici est la politique — quelle source est essayée, dans quel ordre, et ce qui
 * est renvoyé quand tout échoue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Dépôt des playlists")
class PlaylistRepositoryImplTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    private val telechargeur = TelechargeurFactice()

    private fun source(id: String) = PlaylistSource(
        id = id,
        name = id,
        url = "http://source.invalid/$id",
    )

    private fun entree(nom: String) = M3uEntry(
        title = nom,
        streamUrl = "http://flux.invalid/$nom",
    )

    private fun depot(magasin: PlaylistSourceStore, cache: PlaylistCache) = PlaylistRepositoryImpl(
        downloader = telechargeur,
        sourceStore = magasin,
        cache = cache,
        dispatchers = dispatchers,
    )

    @Test
    fun `renvoie les entrees de la premiere source qui repond`() = runTest {
        val entrees = listOf(entree("une"), entree("deux"))
        telechargeur.repondre("http://source.invalid/a", AppResult.success(entrees))

        val resultat = depot(MagasinFactice(listOf(source("a"))), InMemoryPlaylistCache()).entries()

        assertThat(resultat.valueOrNull()).isEqualTo(entrees)
    }

    @Test
    fun `un succes est memorise dans le cache`() = runTest {
        val entrees = listOf(entree("une"))
        telechargeur.repondre("http://source.invalid/a", AppResult.success(entrees))
        val cache = InMemoryPlaylistCache()

        depot(MagasinFactice(listOf(source("a"))), cache).entries()

        assertThat(cache.load()).isEqualTo(entrees)
    }

    @Test
    fun `bascule sur la source suivante en cas d echec`() = runTest {
        val entreesB = listOf(entree("b"))
        telechargeur.repondre("http://source.invalid/a", AppResult.failure(AppError.PlaylistRejected))
        telechargeur.repondre("http://source.invalid/b", AppResult.success(entreesB))

        val resultat = depot(
            MagasinFactice(listOf(source("a"), source("b"))),
            InMemoryPlaylistCache(),
        ).entries()

        assertThat(resultat.valueOrNull()).isEqualTo(entreesB)
        assertThat(telechargeur.urlsTentees)
            .containsExactly("http://source.invalid/a", "http://source.invalid/b").inOrder()
    }

    @Test
    fun `se replie sur le cache quand toutes les sources echouent`() = runTest {
        telechargeur.repondre("http://source.invalid/a", AppResult.failure(AppError.PlaylistRejected))
        val cache = InMemoryPlaylistCache()
        val memorisees = listOf(entree("ancienne"))
        cache.store(memorisees)

        val resultat = depot(MagasinFactice(listOf(source("a"))), cache).entries()

        assertThat(resultat.valueOrNull()).isEqualTo(memorisees)
    }

    @Test
    fun `renvoie la derniere erreur quand toutes les sources echouent et le cache est vide`() =
        runTest {
            telechargeur.repondre("http://source.invalid/a", AppResult.failure(AppError.PlaylistRejected))

            val resultat = depot(
                MagasinFactice(listOf(source("a"))),
                InMemoryPlaylistCache(),
            ).entries()

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.PlaylistRejected)
        }

    @Test
    fun `renvoie le cache quand aucune source n est exploitable`() = runTest {
        val cache = InMemoryPlaylistCache()
        val memorisees = listOf(entree("ancienne"))
        cache.store(memorisees)

        val resultat = depot(MagasinFactice(emptyList()), cache).entries()

        assertThat(resultat.valueOrNull()).isEqualTo(memorisees)
    }

    @Test
    fun `signale une configuration absente quand il n y a ni source ni cache`() = runTest {
        val resultat = depot(MagasinFactice(emptyList()), InMemoryPlaylistCache()).entries()

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.MissingConfig)
    }

    /** Doublure du téléchargeur : renvoie la réponse configurée pour chaque URL. */
    private class TelechargeurFactice : PlaylistDownloader {

        private val reponses = mutableMapOf<String, AppResult<List<M3uEntry>>>()
        val urlsTentees = mutableListOf<String>()

        fun repondre(url: String, resultat: AppResult<List<M3uEntry>>) {
            reponses[url] = resultat
        }

        override suspend fun download(
            url: String,
            userAgent: String?,
            referrer: String?,
        ): AppResult<List<M3uEntry>> {
            urlsTentees += url
            return reponses[url] ?: AppResult.failure(AppError.PlaylistUnreachable)
        }
    }

    /** Doublure du magasin de sources, en mémoire. */
    private class MagasinFactice(private var liste: List<PlaylistSource>) : PlaylistSourceStore {

        override suspend fun sources(): List<PlaylistSource> = liste

        override suspend fun saveAll(sources: List<PlaylistSource>) {
            liste = sources
        }
    }
}
