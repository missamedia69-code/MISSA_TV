package com.missa.tv.data.repository

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.data.remote.portal.PortalFailure
import com.missa.tv.data.remote.portal.PortalProtocolException
import com.missa.tv.data.remote.portal.StalkerClient
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.model.PortalSession
import com.missa.tv.domain.portal.PortalFailoverPolicy
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
 * Bascule automatique entre profils, au niveau du dépôt.
 *
 * Le client Stalker est remplacé par une doublure : ce qui est vérifié ici est
 * la politique de l'application — quel profil est essayé, dans quel ordre, et
 * ce qui est retenu après un succès.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Dépôt du portail")
class PortalRepositoryImplTest {

    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    /** Source de profils en mémoire, avec mémorisation du profil actif. */
    private class FakeProfileSource(
        private var liste: List<PortalProfile>,
        private var actif: String? = null,
    ) : PortalProfileSource {

        var profilsEcrits = mutableListOf<String>()

        override suspend fun profiles(): List<PortalProfile> = liste

        override suspend fun activeProfileId(): String? = actif

        override suspend fun setActiveProfileId(id: String) {
            actif = id
            profilsEcrits += id
        }

        override suspend fun save(profile: PortalProfile) {
            liste = liste.filterNot { it.id == profile.id } + profile
        }

        override suspend fun delete(id: String) {
            liste = liste.filterNot { it.id == id }
        }

        override suspend fun syncRemote(
            remoteProfiles: List<PortalProfile>,
            defaultProfileId: String?,
        ) {
            liste = (liste.filterNot { p -> remoteProfiles.any { it.id == p.id } } + remoteProfiles)
            if (defaultProfileId != null) actif = defaultProfileId
        }
    }

    private fun mac(suffixe: Int): String =
        listOf("00", "1A", "79", "00", "00", "%02X".format(suffixe)).joinToString(":")

    private fun profil(id: String, suffixe: Int, enabled: Boolean = true) = PortalProfile(
        id = id,
        name = "Profil $id",
        portalUrl = "http://example.invalid/",
        mac = mac(suffixe),
        enabled = enabled,
    )

    private fun session(profil: PortalProfile, jeton: String = "jeton-${profil.id}") = PortalSession(
        profileId = profil.id,
        endpoint = "http://example.invalid/server/load.php",
        token = jeton,
        timezone = "Europe/Paris",
    )

    @Test
    @DisplayName("signale l'absence de configuration utilisable")
    fun `aucun profil configure`() = runTest {
        val depot = PortalRepositoryImpl(
            client = mockk(),
            profileSource = FakeProfileSource(emptyList()),
            dispatchers = dispatchers,
        )

        val resultat = depot.connect()

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.MissingConfig)
    }

    @Test
    @DisplayName("ouvre la session avec le profil actif")
    fun `ouvre avec le profil actif`() = runTest {
        val a = profil("a", 1)
        val b = profil("b", 2)
        val source = FakeProfileSource(listOf(a, b), actif = "b")
        val client = mockk<StalkerClient>()
        coEvery { client.connect(b) } returns session(b)

        val resultat = PortalRepositoryImpl(client, source, dispatchers).connect()

        assertThat(resultat.valueOrNull()?.token).isEqualTo("jeton-b")
        // Aucune bascule : le profil actif fonctionnait.
        assertThat(source.profilsEcrits).isEmpty()
    }

    @Test
    @DisplayName("bascule vers le profil suivant quand l'appareil est refusé")
    fun `bascule apres refus`() = runTest {
        val a = profil("a", 1)
        val b = profil("b", 2)
        val source = FakeProfileSource(listOf(a, b), actif = "a")
        val client = mockk<StalkerClient>()
        coEvery { client.connect(a) } throws PortalProtocolException(
            PortalFailure.UNAUTHORIZED,
            "appareil refusé",
        )
        coEvery { client.connect(b) } returns session(b)

        val resultat = PortalRepositoryImpl(client, source, dispatchers).connect()

        assertThat(resultat.valueOrNull()?.token).isEqualTo("jeton-b")
        // Le profil de secours devient le profil préféré.
        assertThat(source.profilsEcrits).containsExactly("b")
    }

    @Test
    @DisplayName("bascule aussi quand l'abonnement du premier profil est inactif")
    fun `bascule apres abonnement expire`() = runTest {
        val a = profil("a", 1)
        val b = profil("b", 2)
        val source = FakeProfileSource(listOf(a, b), actif = "a")
        val client = mockk<StalkerClient>()
        coEvery { client.connect(a) } throws PortalProtocolException(
            PortalFailure.EXPIRED,
            "abonnement inactif",
        )
        coEvery { client.connect(b) } returns session(b)

        val resultat = PortalRepositoryImpl(client, source, dispatchers).connect()

        assertThat(resultat.isSuccess).isTrue()
    }

    @Test
    @DisplayName("n'essaie pas d'autre profil quand le portail est injoignable")
    fun `pas de bascule si portail injoignable`() = runTest {
        val a = profil("a", 1)
        val b = profil("b", 2)
        val source = FakeProfileSource(listOf(a, b), actif = "a")
        val client = mockk<StalkerClient>()
        coEvery { client.connect(any()) } throws IOException("hôte injoignable")

        val resultat = PortalRepositoryImpl(client, source, dispatchers).connect()

        // Changer de MAC ne réglerait rien : le problème est réseau.
        assertThat(resultat.errorOrNull()).isEqualTo(AppError.PortalUnreachable)
        coVerify(exactly = 1) { client.connect(any()) }
    }

    @Test
    @DisplayName("échoue proprement quand tous les profils sont refusés")
    fun `tous les profils refuses`() = runTest {
        val a = profil("a", 1)
        val b = profil("b", 2)
        val source = FakeProfileSource(listOf(a, b), actif = "a")
        val client = mockk<StalkerClient>()
        coEvery { client.connect(any()) } throws PortalProtocolException(
            PortalFailure.UNAUTHORIZED,
            "appareil refusé",
        )

        val resultat = PortalRepositoryImpl(client, source, dispatchers).connect()

        assertThat(resultat.errorOrNull()).isEqualTo(AppError.MacUnauthorized)
        coVerify(exactly = 2) { client.connect(any()) }
    }

    @Test
    @DisplayName("respecte la limite de tentatives de la politique")
    fun `limite les tentatives`() = runTest {
        val profils = (1..5).map { profil("p$it", it) }
        val source = FakeProfileSource(profils, actif = "p1")
        val client = mockk<StalkerClient>()
        coEvery { client.connect(any()) } throws PortalProtocolException(
            PortalFailure.UNAUTHORIZED,
            "appareil refusé",
        )

        val depot = PortalRepositoryImpl(
            client = client,
            profileSource = source,
            dispatchers = dispatchers,
            failoverPolicy = PortalFailoverPolicy(maxAttempts = 3),
        )
        depot.connect()

        coVerify(exactly = 3) { client.connect(any()) }
    }

    @Test
    @DisplayName("refuse d'utiliser une session d'un autre lancement")
    fun `session inconnue refusee`() = runTest {
        val source = FakeProfileSource(listOf(profil("a", 1)))
        val client = mockk<StalkerClient>()

        val resultat = PortalRepositoryImpl(client, source, dispatchers)
            .catalog(session(profil("a", 1), jeton = "jamais-ouverte"))

        // La session ne correspond à aucune connexion de ce lancement : on ne
        // l'utilise pas plutôt que de l'envoyer au hasard.
        assertThat(resultat.isSuccess).isFalse()
    }
}
