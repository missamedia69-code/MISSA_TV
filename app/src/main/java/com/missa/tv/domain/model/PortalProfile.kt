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
 * État du compte annoncé par le portail après le handshake.
 *
 * ⚠️ **Ces champs ne décident de rien.** Les portails ne s'accordent ni sur leur
 * présence, ni sur leur sens : `status` vaut 1 chez les uns et 0 chez les autres
 * pour un compte parfaitement actif, et `subscribed` arrive souvent sous forme de
 * tableau (`[1,1]`). Conditionner l'accès à leur valeur revenait à refuser des
 * abonnements valides — des portails qui fonctionnent très bien avec d'autres
 * lecteurs étaient déclarés « expirés ».
 *
 * Ils servent donc uniquement à **expliquer** un refus : quand le portail lui-même
 * refuse de fournir ses chaînes, un compte explicitement inactif permet de
 * présenter le bon message. Le juge de paix reste le portail, jamais une
 * déduction de l'application.
 *
 * Chaque champ vaut `null` quand le portail ne le fournit pas ou l'exprime dans
 * un format inattendu : *inconnu* n'est pas *inactif*.
 */
data class PortalAccount(
    val isActive: Boolean? = null,
    val isSubscribed: Boolean? = null,
    /** Date de fin d'abonnement telle que fournie par le portail (non interprétée). */
    val expiresAtRaw: String? = null,
    val isTrial: Boolean = false,
) {
    /**
     * Vrai **seulement** si le portail affirme explicitement que le compte est
     * inactif. Un champ absent ou illisible ne compte jamais comme un refus.
     */
    val explicitlyInactive: Boolean
        get() = isActive == false || isSubscribed == false
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
    /**
     * État de compte annoncé lors de l'ouverture de la session.
     *
     * Il voyage avec la session pour que les erreurs survenues plus tard puissent
     * être expliquées correctement, sans requête supplémentaire.
     */
    val account: PortalAccount = PortalAccount(),
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
