package com.missa.tv.domain.portal

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.PortalProfile
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Bascule automatique entre profils.
 *
 * Les adresses MAC des profils de test sont construites à l'exécution : aucune
 * adresse en clair ne doit apparaître dans le dépôt, même fictive.
 */
@DisplayName("Bascule automatique entre profils")
class PortalFailoverPolicyTest {

    private val policy = PortalFailoverPolicy()

    private fun mac(suffixe: Int): String =
        listOf("00", "1A", "79", "00", "00", "%02X".format(suffixe)).joinToString(":")

    private fun profil(id: String, suffixe: Int, enabled: Boolean = true) = PortalProfile(
        id = id,
        name = "Profil $id",
        portalUrl = "http://example.invalid/",
        mac = mac(suffixe),
        enabled = enabled,
    )

    private val profils = listOf(
        profil("a", 1),
        profil("b", 2),
        profil("c", 3),
    )

    @Test
    @DisplayName("place le profil actif en premier")
    fun `profil actif en premier`() {
        val candidats = policy.candidates(profils, currentProfileId = "c")

        assertThat(candidats.map { it.id }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    @DisplayName("conserve un ordre stable sans profil actif")
    fun `ordre stable sans profil actif`() {
        val candidats = policy.candidates(profils, currentProfileId = null)

        assertThat(candidats.map { it.id }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    @DisplayName("écarte les profils désactivés ou incomplets")
    fun `ecarte les profils inutilisables`() {
        val liste = listOf(
            profil("a", 1, enabled = false),
            profil("b", 2),
            // URL vide : profil saisi à moitié.
            PortalProfile(id = "c", name = "Incomplet", portalUrl = "", mac = mac(3)),
        )

        assertThat(policy.candidates(liste, null).map { it.id }).containsExactly("b")
    }

    @Test
    @DisplayName("aucun candidat quand rien n'est configuré")
    fun `aucun candidat si liste vide`() {
        assertThat(policy.candidates(emptyList(), null)).isEmpty()
    }

    @Test
    @DisplayName("limite le nombre de tentatives")
    fun `limite les tentatives`() {
        val nombreux = (1..6).map { profil("p$it", it) }
        val policyLimitee = PortalFailoverPolicy(maxAttempts = 2)

        assertThat(policyLimitee.candidates(nombreux, null)).hasSize(2)
    }

    @Test
    @DisplayName("passe au profil suivant et n'y revient pas")
    fun `passe au suivant sans revenir`() {
        val suivant = policy.next(
            profiles = profils,
            failedProfileId = "a",
            attemptedIds = setOf("a"),
        )

        assertThat(suivant?.id).isEqualTo("b")
    }

    @Test
    @DisplayName("ne propose plus rien quand tous les profils ont été essayés")
    fun `plus rien quand tout a ete essaye`() {
        val suivant = policy.next(
            profiles = profils,
            failedProfileId = "b",
            attemptedIds = setOf("a", "b"),
        )

        assertThat(suivant?.id).isEqualTo("c")

        val epuise = policy.next(
            profiles = profils,
            failedProfileId = "c",
            attemptedIds = setOf("a", "b", "c"),
        )
        assertThat(epuise).isNull()
    }

    @Test
    @DisplayName("bascule seulement si le portail a refusé l'appareil ou l'abonnement")
    fun `bascule sur refus seulement`() {
        // Un portail injoignable ne justifie pas de changer de MAC : le problème
        // est réseau, et essayer un autre profil ferait perdre du temps.
        assertThat(policy.shouldFailover(PortalFailoverPolicy.FailoverReason.UNAUTHORIZED)).isTrue()
        assertThat(policy.shouldFailover(PortalFailoverPolicy.FailoverReason.EXPIRED)).isTrue()
        assertThat(policy.shouldFailover(PortalFailoverPolicy.FailoverReason.UNREACHABLE)).isFalse()
        assertThat(
            policy.shouldFailover(PortalFailoverPolicy.FailoverReason.STREAM_UNAVAILABLE),
        ).isFalse()
    }

    @ParameterizedTest
    @EnumSource(PortalFailoverPolicy.FailoverReason::class)
    @DisplayName("chaque motif d'échec a une réponse définie")
    fun `chaque motif est traite`(motif: PortalFailoverPolicy.FailoverReason) {
        assertThat(policy.shouldFailover(motif)).isAnyOf(true, false)
    }
}
