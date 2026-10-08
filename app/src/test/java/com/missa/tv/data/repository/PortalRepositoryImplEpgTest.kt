package com.missa.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.data.remote.portal.PortalFailure
import com.missa.tv.data.remote.portal.PortalProtocolException
import com.missa.tv.data.remote.portal.StalkerClient
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.model.PortalSession
import com.missa.tv.domain.repository.PortalProfileSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guide (EPG) au niveau du dépôt.
 *
 * Le client Stalker est remplacé par une doublure : ce qui est vérifié ici est
 * la politique de l'application — traduction des échecs, session inconnue,
 * session oubliée après un refus du portail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Dépôt du portail — guide (EPG)")
class PortalRepositoryImplEpgTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    /** Source de profils en mémoire, suffisante pour la connexion. */
    private class FakeProfileSource(
        private val liste: List<PortalProfile>,
    ) : PortalProfileSource {
        override suspend fun profiles(): List<PortalProfile> = liste
        override suspend fun activeProfileId(): String? = liste.firstOrNull()?.id
        override suspend fun setActiveProfileId(id: String) = Unit
        override suspend fun save(profile: PortalProfile) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun syncRemote(
            remoteProfiles: List<PortalProfile>,
            defaultProfileId: String?,
        ) = Unit
    }

    private val profil = PortalProfile(
        id = "p1",
        name = "Test",
        portalUrl = "http://example.invalid/",
        mac = listOf("00", "1A", "79", "00", "00", "01").joinToString(":"),
    )

    private val session = PortalSession(
        profileId = "p1",
        endpoint = "http://example.invalid/server/load.php",
        token = "jeton-de-test",
        timezone = "Europe/Paris",
    )

    private fun evenement(id: String) = EpgEvent(
        id = id,
        channelId = "42",
        title = "Journal",
        startMs = 1_700_000_000_000L,
        endMs = 1_700_003_600_000L,
    )

    /** Dépôt connecté : la session renvoyée est reconnue par les appels suivants. */
    private suspend fun depotConnecte(client: StalkerClient): Pair<PortalRepositoryImpl, PortalSession> {
        coEvery { client.connect(any()) } returns session
        val depot = PortalRepositoryImpl(client, FakeProfileSource(listOf(profil)), dispatchers)
        val resultat = depot.connect()
        return depot to (resultat.valueOrNull() ?: error("Connexion refusée dans le test"))
    }

    @Test
    fun `le guide court est transmis`() = runTest {
        val client = mockk<StalkerClient>()
        coEvery { client.shortEpg(session, profil, "42") } returns listOf(evenement("7"))
        val (depot, session) = depotConnecte(client)

        val resultat = depot.shortEpg(session, "42")

        assertThat(resultat.valueOrNull()?.map { it.id }).containsExactly("7")
        coVerify(exactly = 1) { client.shortEpg(session, profil, "42") }
    }

    @Test
    fun `le guide complet transmet la fenêtre`() = runTest {
        val client = mockk<StalkerClient>()
        coEvery { client.events(session, profil, "42", 1_000L, 2_000L) } returns listOf(evenement("7"))
        val (depot, session) = depotConnecte(client)

        val resultat = depot.epg(session, "42", 1_000L, 2_000L)

        assertThat(resultat.valueOrNull()).hasSize(1)
        coVerify(exactly = 1) { client.events(session, profil, "42", 1_000L, 2_000L) }
    }

    @Test
    fun `une session inconnue est refusée`() = runTest {
        val client = mockk<StalkerClient>()
        val depot = PortalRepositoryImpl(client, FakeProfileSource(listOf(profil)), dispatchers)
        val sessionInconnue = session.copy(token = "jeton-inconnu")

        val resultat = depot.shortEpg(sessionInconnue, "42")

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.SessionExpired)
        coVerify(exactly = 0) { client.shortEpg(any(), any(), any()) }
    }

    @Test
    fun `un portail injoignable est signalé`() = runTest {
        val client = mockk<StalkerClient>()
        coEvery { client.shortEpg(session, profil, "42") } throws IOException("hôte injoignable")
        val (depot, session) = depotConnecte(client)

        val resultat = depot.shortEpg(session, "42")

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.PortalUnreachable)
    }

    @Test
    fun `un refus de session est signalé puis la session est oubliée`() = runTest {
        val client = mockk<StalkerClient>()
        coEvery { client.shortEpg(session, profil, "42") } throws PortalProtocolException(
            PortalFailure.UNAUTHORIZED,
            "Session refusée par le portail",
        )
        val (depot, session) = depotConnecte(client)

        val premier = depot.shortEpg(session, "42")
        // Le jeton refusé ne sera pas accepté à nouveau : la session est oubliée.
        val second = depot.shortEpg(session, "42")

        assertThat(premier.errorOrNull()).isEqualTo(AppError.MacUnauthorized)
        assertThat(second.errorOrNull()).isEqualTo(AppError.SessionExpired)
    }

    @Test
    fun `un guide vide du portail est transmis tel quel`() = runTest {
        val client = mockk<StalkerClient>()
        coEvery { client.shortEpg(session, profil, "42") } returns emptyList()
        val (depot, session) = depotConnecte(client)

        val resultat = depot.shortEpg(session, "42")

        assertThat(resultat.valueOrNull()).isEmpty()
    }

    @Test
    fun `l epg d une session inconnue est refusée`() = runTest {
        val client = mockk<StalkerClient>()
        val depot = PortalRepositoryImpl(client, FakeProfileSource(listOf(profil)), dispatchers)
        val sessionInconnue = session.copy(token = "jeton-inconnu")

        val resultat = depot.epg(sessionInconnue, "42", 0L, 1_000L)

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.SessionExpired)
        coVerify(exactly = 0) { client.events(any(), any(), any(), any(), any()) }
    }
}
