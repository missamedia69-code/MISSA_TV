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
import com.missa.tv.data.playlist.M3uEntry
import com.missa.tv.data.playlist.PlaylistRepository
import com.missa.tv.domain.model.PlaylistSource
import com.missa.tv.domain.repository.PlaylistSourceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Construction du catalogue depuis les playlists M3U.
 *
 * Les entrées utilisées sont fictives : aucune adresse réelle, les hôtes sont en
 * `.invalid`. Ce qui est vérifié : la conversion en chaînes du domaine, la
 * déduction des catégories depuis les groupes, la propagation des échecs de
 * téléchargement et la mémorisation sous la clé de cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Catalogue construit depuis les playlists M3U")
class M3uCatalogRepositoryTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    private val horloge = object : TimeSource {
        override fun nowMs(): Long = 1_700_000_000_000
    }

    /** Dépôt de playlists piloté par le test. */
    private class FakePlaylistRepository(private var resultat: AppResult<List<M3uEntry>>) :
        PlaylistRepository {
        override suspend fun entries(): AppResult<List<M3uEntry>> = resultat
    }

    /** Magasin des sources en mémoire. */
    private class FakeSourceStore(private var liste: List<PlaylistSource>) : PlaylistSourceStore {
        override suspend fun sources(): List<PlaylistSource> = liste

        override suspend fun saveAll(sources: List<PlaylistSource>) {
            liste = sources
        }
    }

    /** Doublure minimale du DAO : enregistre les écritures de chaînes. */
    private class FakeCatalogDao : CatalogDao {
        var chainesEcrites: List<ChannelEntity> = emptyList()
            private set
        var cleEcriture: String? = null
            private set

        override suspend fun upsertCategories(categories: List<CategoryEntity>) = Unit

        override suspend fun upsertChannels(channels: List<ChannelEntity>) {
            chainesEcrites = channels
        }

        override suspend fun deleteCategories(portalId: String) = Unit

        override suspend fun deleteChannels(portalId: String) = Unit

        override suspend fun replaceCatalog(
            portalId: String,
            categories: List<CategoryEntity>,
            channels: List<ChannelEntity>,
        ) {
            cleEcriture = portalId
            upsertCategories(categories)
            upsertChannels(channels)
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

    private fun entree(titre: String, groupe: String?) = M3uEntry(
        title = titre,
        streamUrl = "http://exemple.invalid/${titre.replace(' ', '-')}.m3u8",
        groupTitle = groupe,
    )

    private fun depot(
        playlistRepository: PlaylistRepository,
        sourceStore: PlaylistSourceStore,
        dao: FakeCatalogDao,
    ) = M3uCatalogRepository(
        playlistRepository = playlistRepository,
        sourceStore = sourceStore,
        catalogCache = CatalogCache(dao = dao, timeSource = horloge),
        dispatchers = dispatchers,
        timeSource = horloge,
    )

    @Test
    @DisplayName("convertit les entrées en chaînes et déduit les catégories")
    fun `catalogue depuis les entrees`() = runTest {
        val entrees = listOf(
            entree("JT Soir", "Info"),
            entree("Match Direct", "Sport"),
            entree("JT Midi", "Info"),
        )
        val dao = FakeCatalogDao()
        val depot = depot(
            playlistRepository = FakePlaylistRepository(AppResult.success(entrees)),
            sourceStore = FakeSourceStore(emptyList()),
            dao = dao,
        )

        val catalogue = depot.load().valueOrNull()!!

        assertThat(catalogue.channels).hasSize(3)
        assertThat(catalogue.channels.map { it.name }).containsExactly("JT Soir", "Match Direct", "JT Midi")
        // Les numéros suivent l'ordre de la playlist.
        assertThat(catalogue.channels.map { it.number }).containsExactly(1, 2, 3).inOrder()
        // Les catégories reprennent les groupes distincts, sans doublon.
        assertThat(catalogue.categories.map { it.id }).containsExactly("Info", "Sport").inOrder()
        // Chaque chaîne référence sa catégorie par le groupe.
        assertThat(catalogue.channels.first().categoryId).isEqualTo("Info")
    }

    @Test
    @DisplayName("une chaîne sans groupe n'ajoute pas de catégorie")
    fun `sans groupe pas de categorie`() = runTest {
        val entrees = listOf(entree("Seule", null))
        val depot = depot(
            playlistRepository = FakePlaylistRepository(AppResult.success(entrees)),
            sourceStore = FakeSourceStore(emptyList()),
            dao = FakeCatalogDao(),
        )

        val catalogue = depot.load().valueOrNull()!!

        assertThat(catalogue.categories).isEmpty()
        assertThat(catalogue.channels.single().categoryId).isNull()
    }

    @Test
    @DisplayName("propage l'échec du téléchargement")
    fun `echec propage`() = runTest {
        val depot = depot(
            playlistRepository = FakePlaylistRepository(AppResult.failure(AppError.PlaylistUnreachable)),
            sourceStore = FakeSourceStore(emptyList()),
            dao = FakeCatalogDao(),
        )

        val resultat = depot.load()

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.PlaylistUnreachable)
    }

    @Test
    @DisplayName("le catalogue est mémorisé sous la clé de la première source complète")
    fun `memorise sous la cle de cache`() = runTest {
        val source = PlaylistSource(id = "principale", name = "Principale", url = "http://exemple.invalid/p.m3u8")
        val dao = FakeCatalogDao()
        val depot = depot(
            playlistRepository = FakePlaylistRepository(AppResult.success(listOf(entree("Une", "Info")))),
            sourceStore = FakeSourceStore(listOf(source)),
            dao = dao,
        )

        depot.load()

        assertThat(dao.cleEcriture).isEqualTo("principale")
        assertThat(dao.chainesEcrites).hasSize(1)
    }

    @Test
    @DisplayName("la clé de cache vaut la première source complète")
    fun `cle de cache source complete`() = runTest {
        val sources = listOf(
            PlaylistSource(id = "desactivee", name = "Off", url = "http://exemple.invalid/o.m3u8", enabled = false),
            PlaylistSource(id = "principale", name = "Principale", url = "http://exemple.invalid/p.m3u8"),
        )
        val depot = depot(
            playlistRepository = FakePlaylistRepository(AppResult.success(emptyList())),
            sourceStore = FakeSourceStore(sources),
            dao = FakeCatalogDao(),
        )

        assertThat(depot.cacheKey()).isEqualTo("principale")
    }

    @Test
    @DisplayName("sans source connue, la clé de cache est la valeur par défaut")
    fun `cle de cache par defaut`() = runTest {
        val depot = depot(
            playlistRepository = FakePlaylistRepository(AppResult.success(emptyList())),
            sourceStore = FakeSourceStore(emptyList()),
            dao = FakeCatalogDao(),
        )

        assertThat(depot.cacheKey()).isEqualTo("m3u")
    }
}
