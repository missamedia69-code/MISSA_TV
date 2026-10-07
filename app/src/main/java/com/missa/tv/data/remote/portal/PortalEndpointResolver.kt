package com.missa.tv.data.remote.portal

/**
 * Découverte de l'endpoint d'API d'un portail.
 *
 * Selon l'installation, l'API n'est pas au même chemin : `/server/load.php` sur
 * un Ministra récent, `/portal.php` sur certaines variantes, ou
 * `/stalker_portal/server/load.php` quand l'installation est dans un
 * sous-répertoire. L'application essaie les chemins connus et retient le
 * premier qui répond correctement.
 */
object PortalEndpointResolver {

    /**
     * Normalise l'URL saisie par l'utilisateur : suppression des espaces et
     * garantie d'une barre oblique finale (sinon les chemins relatifs seraient
     * erronés).
     */
    fun normalize(portalUrl: String): String {
        val trimmed = portalUrl.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /**
     * Construit la liste ordonnée des URL candidates pour un portail.
     * Les doublons sont écartés, ce qui évite deux tentatives identiques quand
     * l'URL contient déjà un chemin.
     */
    fun candidates(portalUrl: String): List<String> {
        val base = normalize(portalUrl)
        return StalkerProtocol.ENDPOINT_PATHS
            .map { base + it }
            .distinct()
    }

    /**
     * Vérifie grossièrement qu'une URL de portail est exploitable.
     *
     * Le portail est choisi par l'utilisateur : on n'impose ni HTTPS (beaucoup
     * de portails sont en HTTP), ni domaine (beaucoup sont désignés par une
     * adresse IP).
     */
    fun isValidPortalUrl(portalUrl: String): Boolean {
        val trimmed = portalUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        val host = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        return host.isNotBlank() && !host.contains(' ')
    }
}
