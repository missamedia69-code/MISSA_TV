package com.missa.tv.data.epg

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.result.AppResult
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.local.EpgCache
import com.missa.tv.data.local.FakeEpgDao
import com.missa.tv.data.local.db.CatalogDao
import com.missa.tv.data.local.db.CategoryEntity
import com.missa.tv.data.local.db.ChannelEntity
import com.missa.tv.data.local.db.ChannelGroupSummary
import com.missa.tv.domain.model.Catalog
import com.missa.tv.domain.model.PlaylistSource
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.PlaylistSourceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Association du guide XMLTV aux diffusions du catalogue.
 *
 * Toutes les adresses sont fictives (hôtes en `.invalid`). Ce qui est vérifié :
 * l'appariement des programmes aux chaînes via leur `tvgId`, la recopie d'un
 * programme pour toutes les variantes qui partagent un `tvgId`, la mémorisation
 * sous la clé du catalogue, et la tolérance aux échecs partiels entre sources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Guide XMLTV associé au catalogue")
class XmltvEpgRepositoryTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    /** Instant présent figé : 2023-11-14 22:13:20 UTC. */
    private val horloge = object : TimeSource {
        override fun nowMs(): Long = 1_700_000_000_000
    }

    /** Magasin des sources en mémoire. */
    private class FakeSourceStore(private var liste: List<PlaylistSource>) : PlaylistSourceStore {
        override suspend fun sources(): List<PlaylistSource> = liste

        override suspend fun saveAll(sources: List<PlaylistSource>) {
            liste = sources
        }
    }

    /** Dépôt de catalogue piloté : seule la clé de cache importe ici. */
    private class FakeCatalogRepository(private val cle: String) : CatalogRepository {
        override suspend fun load(): AppResult<Catalog> =
            AppResult.success(Catalog(emptyList(), emptyList(), 0))

        override suspend fun cacheKey(): String = cle
    }

    /** Téléchargeur piloté : renvoie le document ou l'erreur associés à l'URL. */
    private class FakeDownloader(private val parUrl: Map<String, AppResult<String>>) : XmltvEpgDownloader {
        val demandes = mutableListOf<String>()

        override suspend fun download(url: String): AppResult<String> {
            demandes += url
            return parUrl[url] ?: AppResult.failure(AppError.NetworkLost)
        }
    }

    /** Doublure minimale du DAO de catalogue : renvoie des chaînes avec `tvgId`. */
    private class FakeCatalogDao(private val chaines: List<ChannelEntity>) : CatalogDao {
        override suspend fun upsertCategories(categories: List<CategoryEntity>) = Unit

        override suspend fun upsertChannels(channels: List<ChannelEntity>) = Unit

        override suspend fun deleteCategories(portalId: String) = Unit

        override suspend fun deleteChannels(portalId: String) = Unit

        override suspend fun replaceCatalog(
            portalId: String,
            categories: List<CategoryEntity>,
            channels: List<ChannelEntity>,
        ) = Unit

        override suspend fun categories(portalId: String): List<CategoryEntity> = emptyList()

        override suspend fun groups(portalId: String): List<ChannelGroupSummary> = emptyList()

        override suspend fun channelsOrderedByGroup(portalId: String): List<ChannelEntity> =
            chaines.filter { it.portalId == portalId }

        override suspend fun variants(portalId: String, groupKey: String): List<ChannelEntity> = emptyList()

        override suspend fun channelCount(portalId: String): Int = chaines.size

        override suspend fun lastUpdatedMs(portalId: String): Long? = null

        override suspend fun clearAllChannels() = Unit

        override suspend fun clearAllCategories() = Unit
    }

    private fun chaine(id: String, tvgId: String?, portalId: String): ChannelEntity = ChannelEntity(
        portalId = portalId,
        id = id,
        number = 1,
        name = id,
        streamUrl = "http://exemple.invalid/$id.m3u8",
        logoUrl = null,
        categoryId = null,
        country = null,
        isCensored = false,
        isAvailable = true,
        tvgId = tvgId,
        userAgent = null,
        referrer = null,
        groupKey = id,
        baseName = id,
        qualityRank = 0,
        qualityLabel = "",
        updatedAtMs = 0,
    )

    /**
     * Guide d'un programme en cours à l'instant figé, d'un programme pour une
     * chaîne inconnue du catalogue, et d'un second programme à venir.
     */
    private val documentGuide = """
        <tv>
          <programme start="20231114210000 +0000" stop="20231114233000 +0000" channel="tf1.epg">
            <title lang="fr">JT Soir</title>
            <desc lang="fr">Le journal du soir</desc>
          </programme>
          <programme start="20231115000000 +0000" stop="20231115010000 +0000" channel="tf1.epg">
            <title lang="fr">Film de minuit</title>
          </programme>
          <programme start="20231114210000 +0000" stop="20231114233000 +0000" channel="inconnue.epg">
            <title>Ignorée</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun depot(
        sources: List<PlaylistSource>,
        chaines: List<ChannelEntity>,
        parUrl: Map<String, AppResult<String>>,
        cleCatalogue: String = "principale",
    ): Pair<XmltvEpgRepository, FakeEpgDao> {
        val epgDao = FakeEpgDao()
        val depot = XmltvEpgRepository(
            sourceStore = FakeSourceStore(sources),
            catalogRepository = FakeCatalogRepository(cleCatalogue),
            catalogCache = CatalogCache(dao = FakeCatalogDao(chaines), timeSource = horloge),
            downloader = FakeDownloader(parUrl),
            epgCache = EpgCache(dao = epgDao, timeSource = horloge),
            dispatchers = dispatchers,
        )
        return depot to epgDao
    }

    private fun source(id: String, epgUrl: String? = "http://exemple.invalid/$id.xml") =
        PlaylistSource(id = id, name = id, url = "http://exemple.invalid/$id.m3u8", epgUrl = epgUrl)

    @Test
    @DisplayName("un programme est recopié pour toutes les variantes partageant le tvgId")
    fun `programme reparti sur les variantes`() = runTest {
        val cle = "principale"
        val (depot, epgDao) = depot(
            sources = listOf(source("principale")),
            chaines = listOf(
                chaine("m3u-tf1-hd", "tf1.epg", cle),
                chaine("m3u-tf1-sd", "tf1.epg", cle),
                chaine("m3u-france2", "france2.epg", cle),
            ),
            parUrl = mapOf("http://exemple.invalid/principale.xml" to AppResult.success(documentGuide)),
            cleCatalogue = cle,
        )

        val resultat = depot.refresh()

        assertThat(resultat.isSuccess).isTrue()
        // Les deux variantes TF1 reçoivent le guide, pas France 2 (aucun programme) ni la chaîne inconnue.
        val fenetre = 0L..4_000_000_000_000
        val tf1Hd = epgDao.eventsInWindow(cle, "m3u-tf1-hd", fenetre.first, fenetre.last)
        val tf1Sd = epgDao.eventsInWindow(cle, "m3u-tf1-sd", fenetre.first, fenetre.last)
        val france2 = epgDao.eventsInWindow(cle, "m3u-france2", fenetre.first, fenetre.last)
        assertThat(tf1Hd.map { it.title }).containsExactly("JT Soir", "Film de minuit").inOrder()
        assertThat(tf1Sd.map { it.title }).containsExactly("JT Soir", "Film de minuit").inOrder()
        assertThat(france2).isEmpty()
        // Identifiant stable par chaîne et heure de début.
        assertThat(tf1Hd.first().id).isEqualTo("m3u-tf1-hd-${tf1Hd.first().startMs}")
        // Mémorisé sous la clé du catalogue.
        assertThat(tf1Hd.first().portalId).isEqualTo(cle)
    }

    @Test
    @DisplayName("sans source déclarant de guide, rien n'est téléchargé")
    fun `aucun guide declare`() = runTest {
        val (depot, epgDao) = depot(
            sources = listOf(source("principale", epgUrl = null)),
            chaines = listOf(chaine("m3u-tf1-hd", "tf1.epg", "principale")),
            parUrl = emptyMap(),
        )

        val resultat = depot.refresh()

        assertThat(resultat.isSuccess).isTrue()
        assertThat(epgDao.channelsWithEvents("principale")).isEmpty()
    }

    @Test
    @DisplayName("aucune chaîne ne déclarant de tvgId, rien n'est mémorisé")
    fun `aucun tvgId dans le catalogue`() = runTest {
        val (depot, epgDao) = depot(
            sources = listOf(source("principale")),
            chaines = listOf(chaine("m3u-tf1-hd", null, "principale")),
            parUrl = mapOf("http://exemple.invalid/principale.xml" to AppResult.success(documentGuide)),
        )

        val resultat = depot.refresh()

        assertThat(resultat.isSuccess).isTrue()
        assertThat(epgDao.channelsWithEvents("principale")).isEmpty()
    }

    @Test
    @DisplayName("un document non XMLTV remonte une erreur de guide invalide")
    fun `document invalide`() = runTest {
        val (depot, _) = depot(
            sources = listOf(source("principale")),
            chaines = listOf(chaine("m3u-tf1-hd", "tf1.epg", "principale")),
            parUrl = mapOf(
                "http://exemple.invalid/principale.xml" to AppResult.success("<html>pas un guide</html>"),
            ),
        )

        val resultat = depot.refresh()

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.EpgInvalid)
    }

    @Test
    @DisplayName("le succès d'une source compense l'échec d'une autre")
    fun `echec partiel tolere`() = runTest {
        val cle = "principale"
        val (depot, epgDao) = depot(
            sources = listOf(source("principale"), source("secours")),
            chaines = listOf(chaine("m3u-tf1-hd", "tf1.epg", cle)),
            parUrl = mapOf(
                "http://exemple.invalid/principale.xml" to AppResult.failure(AppError.NetworkLost),
                "http://exemple.invalid/secours.xml" to AppResult.success(documentGuide),
            ),
            cleCatalogue = cle,
        )

        val resultat = depot.refresh()

        assertThat(resultat.isSuccess).isTrue()
        val evenements = epgDao.eventsInWindow(cle, "m3u-tf1-hd", 0L, 4_000_000_000_000)
        assertThat(evenements).isNotEmpty()
    }

    @Test
    @DisplayName("si toutes les sources échouent, l'erreur de la dernière remonte")
    fun `echec total remonte`() = runTest {
        val (depot, _) = depot(
            sources = listOf(source("principale")),
            chaines = listOf(chaine("m3u-tf1-hd", "tf1.epg", "principale")),
            parUrl = mapOf(
                "http://exemple.invalid/principale.xml" to AppResult.failure(AppError.EpgTooLarge),
            ),
        )

        val resultat = depot.refresh()

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.EpgTooLarge)
    }
}
