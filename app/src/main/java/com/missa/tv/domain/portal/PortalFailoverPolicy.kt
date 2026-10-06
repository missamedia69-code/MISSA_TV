package com.missa.tv.domain.portal

import com.missa.tv.domain.model.PortalProfile

/**
 * Choix du profil de connexion, avec bascule automatique.
 *
 * Plusieurs profils (donc plusieurs adresses MAC) peuvent être configurés pour
 * un même portail. Si l'un d'eux est refusé — appareil désactivé côté portail,
 * abonnement suspendu, trop d'appareils simultanés — l'application essaie le
 * suivant au lieu d'abandonner. L'utilisateur n'a donc rien à faire quand un
 * premier accès cesse de fonctionner.
 *
 * Cette classe ne contient que la décision ; la tentative elle-même appartient à
 * la couche « data ».
 */
class PortalFailoverPolicy(
    /** Nombre maximum de profils essayés avant abandon. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {

    /**
     * Ordre des profils à essayer, en commençant par le profil actif.
     *
     * Les profils désactivés ou incomplets sont écartés : ils ne peuvent pas
     * aboutir, et les essayer ne ferait que retarder l'accès au direct.
     */
    fun candidates(profiles: List<PortalProfile>, currentProfileId: String?): List<PortalProfile> {
        val utilisables = profiles.filter { it.enabled && it.isComplete }
        if (utilisables.isEmpty()) return emptyList()

        val ordonnes = utilisables.sortedBy { it.id }
        val actif = currentProfileId?.let { id -> ordonnes.firstOrNull { it.id == id } }
            ?: return ordonnes.take(maxAttempts)

        // Le profil actif passe en premier, les autres gardent un ordre stable.
        return (listOf(actif) + ordonnes.filterNot { it.id == actif.id }).take(maxAttempts)
    }

    /** Profil à utiliser après l'échec de [failedProfileId]. */
    fun next(
        profiles: List<PortalProfile>,
        failedProfileId: String,
        attemptedIds: Set<String>,
    ): PortalProfile? = candidates(profiles, failedProfileId)
        .firstOrNull { it.id != failedProfileId && it.id !in attemptedIds }

    /**
     * Vrai si l'échec justifie d'essayer un autre profil.
     *
     * Un échec réseau est temporaire : le portail est peut-être simplement
     * injoignable, et changer de MAC n'y changerait rien. La bascule n'a de sens
     * que lorsque le portail a répondu en refusant l'appareil ou en signalant un
     * abonnement inactif.
     */
    fun shouldFailover(failure: FailoverReason): Boolean = when (failure) {
        FailoverReason.UNAUTHORIZED, FailoverReason.EXPIRED -> true
        FailoverReason.UNREACHABLE, FailoverReason.STREAM_UNAVAILABLE -> false
    }

    /** Motif d'échec, exprimé sans dépendre de la couche « data ». */
    enum class FailoverReason {
        /** L'appareil n'est pas reconnu par le portail. */
        UNAUTHORIZED,

        /** L'abonnement est inactif ou expiré. */
        EXPIRED,

        /** Le portail n'a pas répondu. */
        UNREACHABLE,

        /** Aucun flux pour cette chaîne : le profil n'est pas en cause. */
        STREAM_UNAVAILABLE,
    }

    companion object {
        /** Trois tentatives suffisent : au-delà, l'attente devient pénible. */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
