package com.missa.tv.data.playlist

/**
 * Entrée brute issue de l'analyse d'une playlist M3U.
 *
 * Il s'agit d'une représentation intermédiaire, propre à la couche « data » :
 * elle n'est pas encore le modèle de domaine [com.missa.tv.domain.model.Channel].
 * La conversion (choix du nom, regroupement, validation de l'URL) est réalisée
 * par une étape ultérieure de la chaîne de chargement.
 *
 * Les attributs repris ici correspondent aux balises les plus courantes des
 * playlists IPTV (`tvg-id`, `tvg-logo`, `group-title`) ainsi qu'aux en-têtes
 * de lecture (`http-user-agent`, `http-referrer`) portés par `#EXTVLCOPT`.
 *
 * @property title nom de la chaîne tel qu'affiché ; jamais vide.
 * @property streamUrl URL directe du flux à lire.
 * @property logoUrl URL d'un logo, le cas échéant.
 * @property groupTitle groupe de chaînes (genre, pays…), le cas échéant.
 * @property tvgId identifiant de correspondance pour les guides de programmes.
 * @property userAgent agent utilisateur exigé par le flux, le cas échéant.
 * @property referrer référent exigé par le flux, le cas échéant.
 */
data class M3uEntry(
    val title: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
)
