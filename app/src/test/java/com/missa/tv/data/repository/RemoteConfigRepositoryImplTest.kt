package com.missa.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.data.local.ConfigStore
import com.missa.tv.data.local.StoredConfig
import com.missa.tv.data.remote.config.ConfigFetchResult
import com.missa.tv.data.remote.config.ConfigRemoteDataSource
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.remote.config.PortalConfigParser
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.model.RemoteConfig
import com.missa.tv.domain.repository.PortalProfileSource
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
 * Synchronisation de la configuration distante.
 *
 * Les règles de prudence sont vérifiées une à une : refus d'un schéma inconnu,
 * pas de retour en arrière de version, conservation de la configuration en place
 * en cas d'échec réseau.
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

        fun repondre(resultat: ConfigFetchResult) {
            this.resultat = resultat
        }

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

    /** Source de profils en mémoire. */
    private class FakeProfiles : PortalProfileSource {
        var recus: List<PortalProfile> = emptyList()
            private set
        var defautRecu: String? = null
            private set

        override suspend fun profiles(): List<PortalProfile> = recus

        override suspend fun activeProfileId(): String? = defautRecu

        override suspend fun setActiveProfileId(id: String) {
            defautRecu = id
        }

        override suspend fun save(profile: PortalProfile) = Unit

        override suspend fun delete(id: String) = Unit

        override suspend fun syncRemote(
            remoteProfiles: List<PortalProfile>,
            defaultProfileId: String?,
        ) {
            recus = remoteProfiles
            defautRecu = defaultProfileId
        }
    }

    private val mac = listOf("00", "1A", "79", "00", "00", "01").joinToString(":")

    private fun document(configVersion: Int, schemaVersion: Int = 1): String = """
        {"schemaVersion":$schemaVersion,"configVersion":$configVersion,"profiles":[]}
    """.trimIndent()

    /** Horloge factice : les tests fixent la date, aucun temps réel n'est attendu. */
    private class FakeTime(private val valeur: Long) : TimeSource {
        override fun nowMs(): Long = valeur
    }

    private fun depot(
        remote: FakeRemote,
        store: FakeStore,
        profiles: FakeProfiles = FakeProfiles(),
        horloge: Long = 1_000L,
    ) = RemoteConfigRepositoryImpl(
        remote = remote,
        store = store,
        parser = PortalConfigParser(),
        profileSource = profiles,
        dispatchers = dispatchers,
        time = FakeTime(horloge),
    )

    private fun storeInitial(configVersion: Int = 1, etag: String? = "\"v1\"") = FakeStore(
        StoredConfig(
            config = RemoteConfig(configVersion = configVersion),
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
            val store = storeInitial(configVersion = 1)
            val remote = FakeRemote(
                ConfigFetchResult.Fetched(document(2), etag = "\"v2\""),
            )

            val resultat = depot(remote, store).refresh()

            assertThat(resultat.valueOrNull()?.configVersion).isEqualTo(2)
            assertThat(store.sauvegardes).isEqualTo(1)
            assertThat(store.stocke.etag).isEqualTo("\"v2\"")
        }

        @Test
        @DisplayName("l'empreinte de la version en place est envoyée à GitHub")
        fun `empreinte transmise`() = runTest {
            val store = storeInitial(configVersion = 1, etag = "\"v1\"")
            val remote = FakeRemote(ConfigFetchResult.Fetched(document(2), "\"v2\""))

            depot(remote, store).refresh()

            // C'est cet en-tête qui évite un retéléchargement inutile.
            assertThat(remote.derniereEmpreinte).isEqualTo("\"v1\"")
        }

        @Test
        @DisplayName("les profils distants sont fusionnés")
        fun `profils fusionnes`() = runTest {
            val store = storeInitial()
            val profils = FakeProfiles()
            val document = """
                {"schemaVersion":1,"configVersion":2,"defaultProfileId":"salon",
                "profiles":[{"id":"salon","name":"Salon",
                "portalUrl":"http://example.invalid/c/","mac":"$mac"}]}
            """.trimIndent()
            val remote = FakeRemote(ConfigFetchResult.Fetched(document, "\"v2\""))

            depot(remote, store, profils).refresh()

            assertThat(profils.recus.map { it.id }).containsExactly("salon")
            assertThat(profils.defautRecu).isEqualTo("salon")
        }

        @Test
        @DisplayName("la date de synchronisation est enregistrée")
        fun `date enregistree`() = runTest {
            val store = storeInitial()
            val remote = FakeRemote(ConfigFetchResult.Fetched(document(2), "\"v2\""))

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
            val remote = FakeRemote(
                ConfigFetchResult.Fetched(document(2, schemaVersion = 99), "\"v2\""),
            )

            val resultat = depot(remote, store).refresh()

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.InvalidConfig)
            assertThat(store.sauvegardes).isEqualTo(0)
        }

        @Test
        @DisplayName("une version plus ancienne n'est pas appliquée")
        fun `pas de retour en arriere`() = runTest {
            // Un retour en arrière de réglages est indésirable, même si le
            // document reçu est valide.
            val store = storeInitial(configVersion = 5)
            val remote = FakeRemote(ConfigFetchResult.Fetched(document(3), "\"v3\""))

            val resultat = depot(remote, store).refresh()

            assertThat(resultat.valueOrNull()?.configVersion).isEqualTo(5)
            assertThat(store.sauvegardes).isEqualTo(0)
        }

        @Test
        @DisplayName("une version identique n'est pas réappliquée")
        fun `version identique ignoree`() = runTest {
            val store = storeInitial(configVersion = 4)
            val remote = FakeRemote(ConfigFetchResult.Fetched(document(4), "\"v4\""))

            depot(remote, store).refresh()

            assertThat(store.sauvegardes).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Configuration inchangée ou inaccessible")
    inner class Inchangee {

        @Test
        @DisplayName("un 304 ne réécrit rien et rafraîchit seulement la date")
        fun `304 sans ecriture`() = runTest {
            val store = storeInitial(configVersion = 3)
            val remote = FakeRemote(ConfigFetchResult.NotModified)

            val resultat = depot(remote, store, horloge = 7_000L).refresh()

            assertThat(resultat.valueOrNull()?.configVersion).isEqualTo(3)
            assertThat(store.sauvegardes).isEqualTo(0)
            assertThat(store.rafraichissementsDeDate).isEqualTo(1)
            assertThat(store.stocke.syncedAtMs).isEqualTo(7_000L)
        }

        @Test
        @DisplayName("un échec réseau conserve la configuration en place")
        fun `echec reseau`() = runTest {
            val store = storeInitial(configVersion = 3)
            val remote = FakeRemote(ConfigFetchResult.Failed(AppError.NetworkLost))

            val resultat = depot(remote, store).refresh()

            assertThat(resultat.errorOrNull()).isEqualTo(AppError.NetworkLost)
            assertThat(store.sauvegardes).isEqualTo(0)
            assertThat(store.stocke.config.configVersion).isEqualTo(3)
        }

        @Test
        @DisplayName("la configuration par défaut est toujours lisible")
        fun `defaut toujours disponible`() = runTest {
            val store = storeInitial(configVersion = 0, etag = null)

            val courante = depot(FakeRemote(ConfigFetchResult.NotModified), store).current()

            assertThat(courante.configVersion).isEqualTo(0)
            assertThat(courante.bandwidth.defaultMode)
                .isEqualTo(com.missa.tv.domain.model.QualityMode.DEFAULT)
        }
    }
}
