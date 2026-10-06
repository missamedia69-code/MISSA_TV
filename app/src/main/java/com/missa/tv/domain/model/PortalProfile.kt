package com.missa.tv.domain.model

/**
 * Profil de connexion à un portail.
 *
 * Cette structure contient des identifiants (URL et adresse MAC). Elle n'est
 * jamais journalisée telle quelle : les valeurs sensibles sont masquées avant
 * toute écriture dans les journaux (voir `core.log.Secrets`).
 *
 * Elle provient soit de la configuration distante, soit d'une saisie manuelle
 * dans l'application. Elle est stockée chiffrée sur l'appareil.
 */
data class PortalProfile(
    val id: String,
    val name: String,
    val portalUrl: String,
    val mac: String,
    val enabled: Boolean = true,
) {
    /** Vrai si le profil contient tout le nécessaire pour se connecter. */
    val isComplete: Boolean
        get() = portalUrl.isNotBlank() && mac.isNotBlank()

    /** Représentation sûre pour les journaux : ni URL complète, ni MAC. */
    override fun toString(): String = "PortalProfile(id=$id, name=$name)"
}

/**
 * État du compte renvoyé par le portail après le handshake.
 *
 * Le portail indique à la fois si la session est acceptée et si l'abonnement
 * est actif : les deux sont nécessaires pour autoriser la lecture.
 */
data class PortalAccount(
    val isActive: Boolean,
    val isSubscribed: Boolean,
    /** Date de fin d'abonnement telle que fournie par le portail (non interprétée). */
    val expiresAtRaw: String? = null,
    val isTrial: Boolean = false,
) {
    /** Vrai si la lecture est autorisée. */
    val canWatch: Boolean get() = isActive && isSubscribed
}

/**
 * Session ouverte sur un portail.
 *
 * Le jeton provient du handshake et doit être présenté en en-tête
 * `Authorization` des requêtes suivantes. Il n'est jamais journalisé.
 */
data class PortalSession(
    val profileId: String,
    /** URL de l'API effectivement retenue après découverte de l'endpoint. */
    val endpoint: String,
    val token: String,
    val timezone: String,
)

/**
 * Lien de lecture d'une chaîne.
 *
 * Un lien n'est jamais réutilisable durablement : il est recréé à chaque
 * lecture et à chaque erreur d'expiration.
 */
data class StreamLink(
    val channelId: String,
    val url: String,
    /** Vrai si l'URL correspond à un flux HLS adaptatif (variantes possibles). */
    val isHls: Boolean,
    val createdAtMs: Long,
) {
    fun isExpired(nowMs: Long, lifetimeMs: Long = DEFAULT_LIFETIME_MS): Boolean =
        nowMs - createdAtMs > lifetimeMs

    companion object {
        /** Durée de vie prudente d'un lien avant recréation, en millisecondes. */
        const val DEFAULT_LIFETIME_MS = 10 * 60 * 1000L
    }
}
