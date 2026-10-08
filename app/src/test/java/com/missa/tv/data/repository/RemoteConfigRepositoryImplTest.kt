package com.missa.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.ConfigStore
import com.missa.tv.data.local.StoredConfig
import com.missa.tv.data.remote.config.ConfigFetchResult
import com.missa.tv.data.remote.config.ConfigRemoteDataSource
import com.missa.tv.data.remote.config.PortalConfigParser
import com.missa.tv.domain.model.PlaylistSource
import com.missa.tv.domain.model.RemoteConfig
import com.missa.tv.domain.repository.PlaylistSourceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Synchronisation de la configuration distante (schéma v2).
 *
 * Les règles de prudence sont vérifiées une à une : refus d'un schéma inconnu,
 * application conditionnelle guidée par l'empreinte, conservation de la
 * configuration en place en cas d'échec réseau, enregistrement des sources de
 * playlists déclarées par la configuration acceptée.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Dépôt de configuration distante")
class RemoteConfigRepositoryImplTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    /** Source distante pilotée par le test. */
    private class FakeRemote(private var resultat: ConfigFetchResult) : ConfigRemoteDataSource {
        var derniereEmpreinte: String? = null
            private set

        override suspend fun fetch(etag: String?): ConfigFetchResult {
            derniereEmpreinte = etag
            return resultat
        }
    }

    /** Stockage en mémoire, avec trace des écritures. */
    private class FakeStore(initial: StoredConfig) : ConfigStore {
        private val flux = MutableStateFlow(initial.config)
        var stocke: StoredConfig = initial
            private set

        var sauvegardes = 0
            private set
        var rafraichissementsDeDate = 0
            private set

        override fun observe(): Flow<RemoteConfig> = flux

        override suspend fun stored(): StoredConfig = stocke

        override suspend fun save(document: String, etag: String?, syncedAtMs: Long) {
            sauvegardes++
            val config = PortalConfigParser().parse(document) ?: return
            stocke = StoredConfig(config = config, etag = etag, syncedAtMs = syncedAtMs)
            flux.value = config
        }

        override suspend fun touch(syncedAtMs: Long) {
            rafraichissementsDeDate++
            stocke = stocke.copy(syncedAtMs = syncedAtMs)
        }
    }

    /** Magasin des sources de playlists en mémoire, avec trace des écritures. */
    private class FakePlaylistSourceStore : PlaylistSourceStore {
        var recues: List<PlaylistSource> = emptyList()
            private set
        var sauvegardes = 0
            private set

        fun recevoir(sources: List<PlaylistSource>) {
            recues = sources
        }

        override suspend fun sources(): List<PlaylistSource> = recues

        override suspend fun saveAll(sources: List<PlaylistSource>) {
            sauvegardes++
            recues = sources
        }
    }

    private fun document(playlists: String = "[]"): String =
        """
        {"schemaVersion":2,"defaultPlaylistId":null,"playlists":$playlists}
        """.trimIndent()

    /** Horloge factice : les tests fixent la date, aucun temps réel n'est attendu. */
    private class FakeTime(private val valeur: Long) : TimeSource {
        override fun nowMs(): Long = valeur
    }

    private fun depot(
        remote: FakeRemote,
        store: FakeStore,
        sources: FakePlaylistSourceStore = FakePlaylistSourceStore(),
        horloge: Long = 1_000L,
    ) = RemoteConfigRepositoryImpl(
        remote = remote,
        store = store,
        parser = PortalConfigParser(),
        sourceStore = sources,
        dispatchers = dispatchers,
        time = FakeTime(horloge),
    )

    private fun storeInitial(etag: String? = "\"v1\"") = FakeStore(
        StoredConfig(
            config = RemoteConfig.DEFAULTS,
            etag = etag,
            syncedAtMs = 0L,
        ),
    )

    @Nested
    @DisplayName("Nouvelle configuration")
    inner class Nouvelle {

        @Test
        @DisplayName("est enregistrée puis appliquée")
        fun `nouvelle configuration appliquee`() = runTest {
            val store = storeInitial()
            val remote = FakeRemote(ConfigFetchResult.Fetched(document(), etag = "\"v2\""))

            val resultat = depot(remote, store).refresh()

            assertThat(resultat.errorOrNull()).isNull()
            assertThat(store.sauvegardes).isEqualTo(1)
            assertThat(store.stocke.etag).isEqualTo("\"v2\"")
        }

        @Test
        @DisplayName("l'empreinte de la version en place est envoyée à GitHub")
        fun `empreinte transmise`() = runTest {
            val store = storeInitial(etag = "\"v1\"")
            val remote = FakeRemote(ConfigFetchResult.Fetched(document(), "\"v2\""))

            depot(remote, store).refresh()

            // C'est cet en-tête qui évite un retéléchargement inutile.
            assertThat(remote.derniereEmpreinte).isEqualTo("\"v1\"")
        }

        @Test
        @DisplayName("les playlists distantes remplacent le magasin des sources")
        fun `playlists enregistrees`() = runTest {
            val store = storeInitial()
            val sources = FakePlaylistSourceStore()
            val playlists =
                """[{"id":"principale","name":"Salon",
                "url":"http://exemple.invalid/liste.m3u8"}]"""
            val document =
                """
                {"schemaVersion":2,"defaultPlaylistId":"principale","playlists":$playlists}
                """.trimIndent()
            val remote = FakeRemote(ConfigFetchResult.Fetched(document, "\"v2\""))

            depot(remote, store, sources).refresh()

            assertThat(sources.sauvegardes).isEqualTo(1)
            assertThat(sources.recues.map { it.id }).containsExactly("principale")
        }

        @Test
        @DisplayName("la date de synchronisation est enregistrée")
        fun `date enregistree`() = runTest {
            val store = storeInitial()
            val remote = FakeRemote(ConfigFetchResult.Fetched(document(), "\"v2\""))

            depot(remote, store, horloge = 42_000L).refresh()

            assertThat(store.stocke.syncedAtMs).isEqualTo(42_000L)
        }
    }

    @Nested
    @DisplayName("Configuration refusée")
    inner class Refusee {

        @Test
        @DisplayName("un schéma inconnu est refusé et rien n'est écrit")
        fun `schema inconnu refuse`() = runTest {
            val store = storeInitial()
            val document = """{"schemaVersion":99,"playlists":[]}"""
            val remote = FakeRemote(ConfigFetchResult.Fetched(document, "\"v2\""))

            val resultat = depot(remote, store).refresh()

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.InvalidConfig)
            assertThat(store.sauvegardes).isEqualTo(0)
        }

        @Test
        @DisplayName("un document sans playlist ne vide pas le magasin des sources")
        fun `playlists vides conservent les sources`() = runTest {
            val store = storeInitial()
            val sources = FakePlaylistSourceStore()
            sources.recevoir(
                listOf(PlaylistSource(id = "existante", name = "Existante", url = "http://exemple.invalid/x.m3u8")),
            )
            val remote = FakeRemote(ConfigFetchResult.Fetched(document(), "\"v2\""))

            depot(remote, store, sources).refresh()

            // Aucune playlist déclarée : la configuration reste appliquée mais
            // le magasin des sources n'est pas écrasé.
            assertThat(store.sauvegardes).isEqualTo(1)
            assertThat(sources.sauvegardes).isEqualTo(0)
            assertThat(sources.recues.map { it.id }).containsExactly("existante")
        }
    }

    @Nested
    @DisplayName("Configuration inchangée ou inaccessible")
    inner class Inchangee {

        @Test
        @DisplayName("un 304 ne réécrit rien et rafraîchit seulement la date")
        fun `304 sans ecriture`() = runTest {
            val store = storeInitial()
            val remote = FakeRemote(ConfigFetchResult.NotModified)

            val resultat = depot(remote, store, horloge = 7_000L).refresh()

            assertThat(resultat.errorOrNull()).isNull()
            assertThat(store.sauvegardes).isEqualTo(0)
            assertThat(store.rafraichissementsDeDate).isEqualTo(1)
            assertThat(store.stocke.syncedAtMs).isEqualTo(7_000L)
        }

        @Test
        @DisplayName("un échec réseau conserve la configuration en place")
        fun `echec reseau`() = runTest {
            val store = storeInitial()
            val remote = FakeRemote(ConfigFetchResult.Failed(AppError.NetworkLost))

            val resultat = depot(remote, store).refresh()

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.NetworkLost)
            assertThat(store.sauvegardes).isEqualTo(0)
        }

        @Test
        @DisplayName("la configuration par défaut est toujours lisible")
        fun `defaut toujours disponible`() = runTest {
            val store = storeInitial(etag = null)

            val courante = depot(FakeRemote(ConfigFetchResult.NotModified), store).current()

            assertThat(courante.schemaVersion).isEqualTo(RemoteConfig.SUPPORTED_SCHEMA_VERSION)
            assertThat(courante.bandwidth.defaultMode)
                .isEqualTo(com.missa.tv.domain.model.QualityMode.DEFAULT)
        }
    }
}
