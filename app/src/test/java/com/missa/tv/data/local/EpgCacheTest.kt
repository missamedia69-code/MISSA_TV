package com.missa.tv.data.local

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.time.TimeSource
import com.missa.tv.domain.model.EpgEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Vérifie le cache local du guide.
 *
 * La doublure [FakeEpgDao] reproduit la sémantique des requêtes SQL : fenêtre
 * de chevauchement, tri par début, fusion par identifiant, suppression des
 * programmes terminés. Ce qui est testé ici, c'est l'assemblage fait par
 * [EpgCache] — le SQL lui-même étant vérifié par Room dès la compilation.
 */
class EpgCacheTest {

    private val maintenant = 1_700_000_000_000L
    private val heure = 60 * 60 * 1000L

    /** Horloge pilotable : fait vieillir le cache sans attendre réellement. */
    private class HorlogeFixe(var maintenant: Long) : TimeSource {
        override fun nowMs(): Long = maintenant
    }

    private val horloge = HorlogeFixe(maintenant)

    private fun cache(): Pair<EpgCache, FakeEpgDao> {
        val dao = FakeEpgDao()
        return EpgCache(dao = dao, timeSource = horloge) to dao
    }

    private fun evenement(
        id: String,
        channelId: String = "42",
        debutMs: Long = maintenant - heure,
        finMs: Long = maintenant + heure,
        titre: String = "Journal",
    ) = EpgEvent(
        id = id,
        channelId = channelId,
        title = titre,
        startMs = debutMs,
        endMs = finMs,
    )

    @Test
    fun `le guide est enregistré puis relu`() = runTest {
        val (cache, _) = cache()

        cache.save(PORTAIL, "42", listOf(evenement("7")))
        val guide = cache.guide(PORTAIL, "42", maintenant, 24 * heure)

        assertThat(guide.current?.id).isEqualTo("7")
        assertThat(guide.channelId).isEqualTo("42")
    }

    @Test
    fun `le guide ne retient que ce qui chevauche la fenêtre`() = runTest {
        val (cache, _) = cache()
        cache.save(
            PORTAIL,
            "42",
            listOf(
                evenement("passe", debutMs = maintenant - 3 * heure, finMs = maintenant - 2 * heure),
                evenement("enCours", debutMs = maintenant - heure, finMs = maintenant + heure),
                evenement("aVenir", debutMs = maintenant + heure, finMs = maintenant + 2 * heure),
                evenement("lointain", debutMs = maintenant + 30 * heure, finMs = maintenant + 31 * heure),
            ),
        )

        val fenetre = cache.events(PORTAIL, "42", maintenant, maintenant + 24 * heure)

        assertThat(fenetre.map { it.id }).containsExactly("enCours", "aVenir").inOrder()
    }

    @Test
    fun `les programmes terminés sont supprimés à l écriture`() = runTest {
        val (cache, _) = cache()
        cache.save(
            PORTAIL,
            "42",
            listOf(
                evenement("termine", debutMs = maintenant - 3 * heure, finMs = maintenant - heure),
                evenement("enCours", debutMs = maintenant - heure, finMs = maintenant + heure),
            ),
        )

        val restants = cache.events(PORTAIL, "42", maintenant - 24 * heure, maintenant + 24 * heure)

        assertThat(restants.map { it.id }).containsExactly("enCours")
    }

    @Test
    fun `le guide court et le guide complet fusionnent par identifiant`() = runTest {
        val (cache, _) = cache()
        // Le guide court publie le programme en cours et le suivant.
        cache.save(
            PORTAIL,
            "42",
            listOf(
                evenement("7", debutMs = maintenant - heure, finMs = maintenant + heure),
                evenement("8", debutMs = maintenant + heure, finMs = maintenant + 2 * heure),
            ),
        )
        // Le guide complet publie la journée, dont les mêmes programmes.
        cache.save(
            PORTAIL,
            "42",
            listOf(
                evenement("7", debutMs = maintenant - heure, finMs = maintenant + heure),
                evenement("8", debutMs = maintenant + heure, finMs = maintenant + 2 * heure),
                evenement("9", debutMs = maintenant + 2 * heure, finMs = maintenant + 3 * heure),
                evenement("10", debutMs = maintenant + 3 * heure, finMs = maintenant + 4 * heure),
            ),
        )

        val fenetre = cache.events(PORTAIL, "42", maintenant, maintenant + 24 * heure)

        assertThat(fenetre.map { it.id }).containsExactly("7", "8", "9", "10").inOrder()
    }

    @Test
    fun `un programme modifié est remplacé`() = runTest {
        val (cache, _) = cache()
        cache.save(PORTAIL, "42", listOf(evenement("7", titre = "Ancien titre")))

        cache.save(PORTAIL, "42", listOf(evenement("7", titre = "Nouveau titre")))

        val guide = cache.guide(PORTAIL, "42", maintenant, 24 * heure)
        assertThat(guide.current?.title).isEqualTo("Nouveau titre")
        assertThat(cache.events(PORTAIL, "42", maintenant - heure, maintenant + 24 * heure)).hasSize(1)
    }

    @Test
    fun `un guide vide conserve les programmes encore en cours`() = runTest {
        val (cache, _) = cache()
        cache.save(PORTAIL, "42", listOf(evenement("7")))

        // Le portail ne publie plus rien : les programmes encore en cours
        // restent affichés — une réponse vide transitoire ne doit pas effacer
        // un guide vraisemblablement bon. Ils disparaissent d'eux-mêmes à leur fin.
        cache.save(PORTAIL, "42", emptyList())

        assertThat(cache.guide(PORTAIL, "42", maintenant, 24 * heure).current?.id).isEqualTo("7")

        // Le programme terminé, le guide ne retient plus rien.
        horloge.maintenant = maintenant + 2 * heure
        assertThat(cache.guide(PORTAIL, "42", horloge.maintenant, 24 * heure).hasGuide).isFalse()
    }

    @Test
    fun `les guides de deux portails ne se mélangent pas`() = runTest {
        val (cache, _) = cache()
        cache.save(PORTAIL, "42", listOf(evenement("7")))

        assertThat(cache.events("autre-portail", "42", maintenant - heure, maintenant + 24 * heure))
            .isEmpty()
    }

    @Test
    fun `un guide absent est signalé comme périmé`() = runTest {
        val (cache, _) = cache()

        assertThat(cache.isStale(PORTAIL, "42", maintenant, 30 * 60 * 1000L)).isTrue()
    }

    @Test
    fun `un guide récent n est pas périmé`() = runTest {
        val (cache, _) = cache()
        cache.save(PORTAIL, "42", listOf(evenement("7")))

        assertThat(cache.isStale(PORTAIL, "42", maintenant, 30 * 60 * 1000L)).isFalse()
    }

    @Test
    fun `un guide devient périmé avec le temps`() = runTest {
        val (cache, _) = cache()
        cache.save(PORTAIL, "42", listOf(evenement("7")))

        horloge.maintenant = maintenant + 31 * 60 * 1000L

        assertThat(cache.isStale(PORTAIL, "42", horloge.maintenant, 30 * 60 * 1000L)).isTrue()
    }

    @Test
    fun `les chaînes avec un guide sont listées`() = runTest {
        val (cache, _) = cache()
        cache.save(PORTAIL, "42", listOf(evenement("7")))
        cache.save(PORTAIL, "43", listOf(evenement("8", channelId = "43")))
        cache.save("autre-portail", "99", listOf(evenement("9", channelId = "99")))

        assertThat(cache.channelsWithEvents(PORTAIL)).containsExactly("42", "43")
    }

    @Test
    fun `la date du dernier enregistrement est exposée`() = runTest {
        val (cache, _) = cache()
        assertThat(cache.lastUpdatedMs(PORTAIL, "42")).isNull()

        cache.save(PORTAIL, "42", listOf(evenement("7")))

        assertThat(cache.lastUpdatedMs(PORTAIL, "42")).isEqualTo(maintenant)
    }

    @Test
    fun `le vidage efface le guide du portail`() = runTest {
        val (cache, _) = cache()
        cache.save(PORTAIL, "42", listOf(evenement("7")))
        cache.save("autre-portail", "43", listOf(evenement("8", channelId = "43")))

        cache.clear(PORTAIL)

        assertThat(cache.events(PORTAIL, "42", maintenant - heure, maintenant + 24 * heure)).isEmpty()
        assertThat(cache.events("autre-portail", "43", maintenant - heure, maintenant + 24 * heure))
            .hasSize(1)
    }

    @Test
    fun `le vidage global efface les guides de tous les portails`() = runTest {
        val (cache, _) = cache()
        cache.save(PORTAIL, "42", listOf(evenement("7")))
        cache.save("autre-portail", "43", listOf(evenement("8", channelId = "43")))

        cache.clearAll()

        assertThat(cache.channelsWithEvents(PORTAIL)).isEmpty()
        assertThat(cache.channelsWithEvents("autre-portail")).isEmpty()
    }

    private companion object {
        const val PORTAIL = "profil-test"
    }
}
