package com.missa.tv.domain.model

/**
 * Source de chaînes au format M3U.
 *
 * [url] est un identifiant sensible : il ne doit jamais être journalisé en clair
 * ni écrit non chiffré sur le disque. Il est conservé par le magasin chiffré des
 * sources ([com.missa.tv.domain.repository.PlaylistSourceStore]).
 *
 * @property id identifiant technique stable de la source.
 * @property name nom affiché à l'utilisateur.
 * @property url adresse de la playlist M3U.
 * @property epgUrl adresse optionnelle d'un guide XMLTV associé.
 * @property enabled source active ou non.
 */
data class PlaylistSource(
    val id: String,
    val name: String,
    val url: String,
    val epgUrl: String? = null,
    val enabled: Boolean = true,
) {
    /** Vrai si la source est exploitable : active et dotée d'une URL non vide. */
    val isComplete: Boolean get() = enabled && url.isNotBlank()
}
