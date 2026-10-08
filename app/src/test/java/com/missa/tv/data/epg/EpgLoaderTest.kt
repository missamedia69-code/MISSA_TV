package com.missa.tv.data.epg

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.error.AppError
import com.missa.tv.core.result.AppResult
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.EpgCache
import com.missa.tv.data.local.FakeEpgDao
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.model.PortalSession
import com.missa.tv.domain.repository.PortalRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Chargement du guide court : guides mémorisés, péremption, limite de requêtes.
 *
 * Le dépôt est remplacé par une doublure : ce qui est vérifié ici est la
 * politique de l'application — ne rien demander tant que le guide est récent,
 * ouvrir une session quand il le faut, s'arrêter sur un échec, borner le
 * nombre de requêtes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Chargeur de guide (EPG)")
class EpgLoaderTest {

    private val maintenant = 1_700_000_000_000L
    private val heure = 60 * 60 * 1000L

    /** Horloge pilotable : fait vieillir le cache sans attendre réellement. */
    private class HorlogeFixe(var maintenant: Long) : TimeSource {
        override fun nowMs(): Long = maintenant
    }

    private val horloge = HorlogeFixe(maintenant)

    private val session = PortalSession(
        profileId = "p1",
        endpoint = "http://example.invalid/server/load.php",
        token = "jeton-de-test",
        timezone = "Europe/Paris",
    )

    private fun evenement(
        id: String,
        debutMs: Long = horloge.maintenant - heure,
        finMs: Long = horloge.maintenant + heure,
    ) = EpgEvent(
        id = id,
        channelId = "42",
        title = "Journal $id",
        startMs = debutMs,
        endMs = finMs,
    )

    private fun loader(depot: PortalRepository, dao: FakeEpgDao = FakeEpgDao()): EpgLoader {
        val cache = EpgCache(dao = dao, timeSource = horloge)
        return EpgLoader(portalRepository = depot, epgCache = cache)
    }

    /** Enregistre un guide frais directement dans le cache. */
    private suspend fun seed(dao: FakeEpgDao, channelId: String = "42", id: String = "7") {
        EpgCache(dao = dao, timeSource = horloge).save(PORTAIL, channelId, listOf(evenement(id)))
    }

    @Test
    fun `les guides mémorisés sont lus sans aucune requête`() = runTest {
        val depot = mockk<PortalRepository>()
        val dao = FakeEpgDao()
        seed(dao)
        val loader = loader(depot, dao)

        val guides = loader.cachedGuides(PORTAIL, listOf("42"), horloge.maintenant)

        assertThat(guides["42"]?.current?.id).isEqualTo("7")
        coVerify(exactly = 0) { depot.connect() }
        coVerify(exactly = 0) { depot.shortEpg(any(), any()) }
    }

    @Test
    fun `un guide récent n est pas redemandé au portail`() = runTest {
        val depot = mockk<PortalRepository>()
        val dao = FakeEpgDao()
        seed(dao)
        val loader = loader(depot, dao)

        val outcome = loader.refreshShortEpg(PORTAIL, listOf("42"), horloge.maintenant)

        assertThat(outcome.connectError).isNull()
        assertThat(outcome.guides["42"]?.current?.id).isEqualTo("7")
        coVerify(exactly = 0) { depot.connect() }
        coVerify(exactly = 0) { depot.shortEpg(any(), any()) }
    }

    @Test
    fun `un guide périmé est rafraîchi et enregistré`() = runTest {
        val depot = mockk<PortalRepository>()
        val dao = FakeEpgDao()
        seed(dao) // événement « 7 » en cours : [maintenant-1h, maintenant+1h]
        val loader = loader(depot, dao)

        // Le rafraîchissement renvoie l'événement en cours et le suivant.
        val enCours = evenement("7", maintenant - heure, maintenant + heure)
        val suivant = evenement("8", maintenant + heure, maintenant + 2 * heure)
        coEvery { depot.connect() } returns AppResult.success(session)
        coEvery { depot.shortEpg(any(), "42") } returns AppResult.success(listOf(enCours, suivant))

        // Le guide mémorisé a 31 minutes : périmé au-delà de 30.
        val plusTard = maintenant + 31 * 60 * 1000L
        val outcome = loader.refreshShortEpg(PORTAIL, listOf("42"), plusTard)

        assertThat(outcome.connectError).isNull()
        assertThat(outcome.guides["42"]?.current?.id).isEqualTo("7")
        assertThat(outcome.guides["42"]?.next?.id).isEqualTo("8")
        coVerify(exactly = 1) { depot.connect() }
        coVerify(exactly = 1) { depot.shortEpg(session, "42") }

        // L'événement « suivant » est mémorisé : il ressort d'une relecture.
        val relu = loader.cachedGuides(PORTAIL, listOf("42"), plusTard)
        assertThat(relu["42"]?.next?.id).isEqualTo("8")
    }

    @Test
    fun `une chaîne sans guide mémorisé est demandée au portail`() = runTest {
        val depot = mockk<PortalRepository>()
        coEvery { depot.connect() } returns AppResult.success(session)
        coEvery { depot.shortEpg(any(), "42") } returns AppResult.success(listOf(evenement("7")))
        val loader = loader(depot)

        val outcome = loader.refreshShortEpg(PORTAIL, listOf("42"), horloge.maintenant)

        assertThat(outcome.guides["42"]?.current?.id).isEqualTo("7")
        coVerify(exactly = 1) { depot.shortEpg(session, "42") }
    }

    @Test
    fun `une session déjà ouverte est réutilisée`() = runTest {
        val depot = mockk<PortalRepository>()
        coEvery { depot.shortEpg(any(), "42") } returns AppResult.success(listOf(evenement("7")))
        val loader = loader(depot)

        val outcome = loader.refreshShortEpg(
            PORTAIL,
            listOf("42"),
            horloge.maintenant,
            session = session,
        )

        assertThat(outcome.connectError).isNull()
        coVerify(exactly = 0) { depot.connect() }
        coVerify(exactly = 1) { depot.shortEpg(session, "42") }
    }

    @Test
    fun `un échec de connexion conserve le guide mémorisé`() = runTest {
        val depot = mockk<PortalRepository>()
        val dao = FakeEpgDao()
        seed(dao)
        val loader = loader(depot, dao)
        coEvery { depot.connect() } returns AppResult.failure(AppError.PortalUnreachable)

        horloge.maintenant = maintenant + 31 * 60 * 1000L
        val outcome = loader.refreshShortEpg(PORTAIL, listOf("42"), horloge.maintenant)

        assertThat(outcome.connectError).isEqualTo(AppError.PortalUnreachable)
        // Le guide mémorisé reste disponible.
        assertThat(outcome.guides["42"]?.current?.id).isEqualTo("7")
        coVerify(exactly = 0) { depot.shortEpg(any(), any()) }
    }

    @Test
    fun `un échec sur une chaîne interrompt le rafraîchissement`() = runTest {
        val depot = mockk<PortalRepository>()
        coEvery { depot.connect() } returns AppResult.success(session)
        coEvery { depot.shortEpg(any(), "42") } returns AppResult.failure(AppError.PortalUnreachable)
        coEvery { depot.shortEpg(any(), "43") } returns AppResult.success(listOf(evenement("8")))
        val loader = loader(depot)

        val outcome = loader.refreshShortEpg(PORTAIL, listOf("42", "43"), horloge.maintenant)

        assertThat(outcome.connectError).isNull()
        // La seconde chaîne n'a pas été demandée : un portail qui échoue sur
        // une chaîne ne reçoit pas une rafale de requêtes.
        coVerify(exactly = 1) { depot.shortEpg(session, "42") }
        coVerify(exactly = 0) { depot.shortEpg(session, "43") }
    }

    @Test
    fun `le nombre de chaînes rafraîchies est borné`() = runTest {
        val depot = mockk<PortalRepository>()
        coEvery { depot.connect() } returns AppResult.success(session)
        coEvery { depot.shortEpg(any(), any()) } returns AppResult.success(emptyList())
        val loader = loader(depot)

        val cinquante = (1..50).map { "chaine-$it" }
        val outcome = loader.refreshShortEpg(PORTAIL, cinquante, horloge.maintenant)

        assertThat(outcome.connectError).isNull()
        coVerify(exactly = EpgLoader.MAX_CHANNELS) { depot.shortEpg(any(), any()) }
    }

    @Test
    fun `la borne peut être ajustée`() = runTest {
        val depot = mockk<PortalRepository>()
        coEvery { depot.connect() } returns AppResult.success(session)
        coEvery { depot.shortEpg(any(), any()) } returns AppResult.success(emptyList())
        val loader = loader(depot)

        val cinquante = (1..50).map { "chaine-$it" }
        loader.refreshShortEpg(PORTAIL, cinquante, horloge.maintenant, maxChannels = 10)

        coVerify(exactly = 10) { depot.shortEpg(any(), any()) }
    }

    @Test
    fun `les identifiants en double ne provoquent qu une requête`() = runTest {
        val depot = mockk<PortalRepository>()
        coEvery { depot.connect() } returns AppResult.success(session)
        coEvery { depot.shortEpg(any(), any()) } returns AppResult.success(emptyList())
        val loader = loader(depot)

        loader.refreshShortEpg(PORTAIL, listOf("42", "42", "43"), horloge.maintenant)

        coVerify(exactly = 1) { depot.shortEpg(session, "42") }
        coVerify(exactly = 1) { depot.shortEpg(session, "43") }
    }

    private companion object {
        const val PORTAIL = "profil-test"
    }
}
