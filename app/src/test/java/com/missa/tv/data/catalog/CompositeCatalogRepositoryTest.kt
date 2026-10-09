package com.missa.tv.data.catalog

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.error.AppError
import com.missa.tv.core.result.AppResult
import com.missa.tv.domain.model.Catalog
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Priorité du catalogue testé sur le catalogue M3U, et repli quand le premier
 * est indisponible. La clé de cache suit la dernière source en succès.
 */
@DisplayName("Catalogue testé prioritaire avec repli M3U")
class CompositeCatalogRepositoryTest {

    private class FakeDepot(
        private var resultat: AppResult<Catalog>,
        private val cle: String,
    ) : CatalogRepository {
        var appels = 0
            private set

        override suspend fun load(): AppResult<Catalog> {
            appels++
            return resultat
        }

        override suspend fun cacheKey(): String = cle
    }

    private fun catalogue(cle: String) = Catalog(
        categories = emptyList(),
        channels = listOf(
            Channel(id = cle, number = 1, name = cle, streamUrl = "http://exemple.invalid/$cle.ts"),
        ),
        loadedAtMs = 1L,
    )

    @Test
    @DisplayName("le catalogue testé gagne quand il répond")
    fun `teste prioritaire`() = runTest {
        val teste = FakeDepot(AppResult.success(catalogue("testé")), "catalogue-teste")
        val m3u = FakeDepot(AppResult.success(catalogue("m3u")), "principal")
        val composite = CompositeCatalogRepository(tested = teste, m3u = m3u)

        val resultat = composite.load().valueOrNull()!!

        assertThat(resultat.channels.first().name).isEqualTo("testé")
        assertThat(m3u.appels).isEqualTo(0)
        assertThat(composite.cacheKey()).isEqualTo("catalogue-teste")
    }

    @Test
    @DisplayName("repli M3U quand le catalogue testé est absent")
    fun `repli m3u`() = runTest {
        val teste = FakeDepot(AppResult.failure(AppError.ConfigNotFound), "catalogue-teste")
        val m3u = FakeDepot(AppResult.success(catalogue("m3u")), "principal")
        val composite = CompositeCatalogRepository(tested = teste, m3u = m3u)

        val resultat = composite.load().valueOrNull()!!

        assertThat(resultat.channels.first().name).isEqualTo("m3u")
        assertThat(m3u.appels).isEqualTo(1)
        assertThat(composite.cacheKey()).isEqualTo("principal")
    }

    @Test
    @DisplayName("la clé de cache par défaut est celle du repli")
    fun `cle par defaut`() = runTest {
        val teste = FakeDepot(AppResult.failure(AppError.NetworkLost), "catalogue-teste")
        val m3u = FakeDepot(AppResult.failure(AppError.NetworkLost), "principal")
        val composite = CompositeCatalogRepository(tested = teste, m3u = m3u)

        assertThat(composite.cacheKey()).isEqualTo("principal")
    }
}
