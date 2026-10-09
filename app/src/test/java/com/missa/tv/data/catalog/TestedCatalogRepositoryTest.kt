package com.missa.tv.data.catalog

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.result.AppResult
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.local.db.CatalogDao
import com.missa.tv.data.local.db.CategoryEntity
import com.missa.tv.data.local.db.ChannelEntity
import com.missa.tv.data.local.db.ChannelGroupSummary
import com.missa.tv.data.remote.catalog.TestedCatalogSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Chargement du catalogue testé : absence du fichier, document refusé et
 * succès avec mémorisation sous la clé du catalogue testé.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Catalogue des chaînes testées")
class TestedCatalogRepositoryTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    private val horloge = object : TimeSource {
        override fun nowMs(): Long = 1_700_000_000_000
    }

    private class FakeSource(private var document: String?) : TestedCatalogSource {
        override suspend fun fetch(): String? = document
    }

    /** Doublure minimale du DAO : enregistre la clé d'écriture. */
    private class FakeCatalogDao : CatalogDao {
        var cleEcriture: String? = null
            private set

        override suspend fun upsertCategories(categories: List<CategoryEntity>) = Unit

        override suspend fun upsertChannels(channels: List<ChannelEntity>) = Unit

        override suspend fun deleteCategories(portalId: String) = Unit

        override suspend fun deleteChannels(portalId: String) = Unit

        override suspend fun replaceCatalog(
            portalId: String,
            categories: List<CategoryEntity>,
            channels: List<ChannelEntity>,
        ) {
            cleEcriture = portalId
        }

        override suspend fun categories(portalId: String): List<CategoryEntity> = emptyList()

        override suspend fun groups(portalId: String): List<ChannelGroupSummary> = emptyList()

        override suspend fun channelsOrderedByGroup(portalId: String): List<ChannelEntity> = emptyList()

        override suspend fun variants(portalId: String, groupKey: String): List<ChannelEntity> = emptyList()

        override suspend fun channelCount(portalId: String): Int = 0

        override suspend fun lastUpdatedMs(portalId: String): Long? = null

        override suspend fun clearAllChannels() = Unit

        override suspend fun clearAllCategories() = Unit
    }

    private fun depot(source: TestedCatalogSource, dao: FakeCatalogDao) = TestedCatalogRepository(
        dataSource = source,
        parser = com.missa.tv.data.remote.catalog.CatalogJsonParser(),
        catalogCache = CatalogCache(dao = dao, timeSource = horloge),
        dispatchers = dispatchers,
        timeSource = horloge,
    )

    @Test
    @DisplayName("fichier absent : echec sans casser le repli")
    fun `fichier absent`() = runTest {
        val resultat = depot(FakeSource(null), FakeCatalogDao()).load()
        assertThat(resultat).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    @DisplayName("document valide : chaînes lues et mémorisées sous la cle testée")
    fun `catalogue lu et memorise`() = runTest {
        val document = """
            {"schemaVersion": 1, "channels": [
              {"name": "CRTV", "url": "http://exemple.invalid/crtv.ts", "group": "Afrique", "country": "CM"}
            ]}
        """.trimIndent()
        val dao = FakeCatalogDao()

        val catalogue = depot(FakeSource(document), dao).load().valueOrNull()!!

        assertThat(catalogue.channels).hasSize(1)
        assertThat(catalogue.channels.first().country).isEqualTo("CM")
        assertThat(catalogue.loadedAtMs).isEqualTo(1_700_000_000_000)
        assertThat(dao.cleEcriture).isEqualTo(TestedCatalogRepository.CLE)
    }

    @Test
    @DisplayName("document sans chaîne : refuse")
    fun `catalogue sans chaine`() = runTest {
        val resultat = depot(FakeSource("""{"schemaVersion": 1, "channels": []}"""), FakeCatalogDao()).load()
        assertThat(resultat).isInstanceOf(AppResult.Failure::class.java)
    }
}
