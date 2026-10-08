package com.missa.tv.domain.model

/**
 * Configuration distante de l'application (schéma v2).
 *
 * Elle est publiée dans le dépôt privé de configuration et lue via l'API
 * Contents. Elle permet d'ajuster le comportement de l'application — seuils de
 * débit, tampon de lecture, sources de playlists M3U — sans republier
 * l'application sur les magasins.
 *
 * Le rafraîchissement est conditionnel (empreinte ETag) : il n'y a pas de
 * numéro de version à incrémenter à la main, le serveur fait foi.
 *
 * Rien d'essentiel à la lecture ne doit dépendre d'elle : en l'absence de
 * configuration, l'application fonctionne avec ses valeurs par défaut.
 */
data class RemoteConfig(
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    val updatedAt: String? = null,
    /** VersionCode minimal requis ; 0 signifie « aucune exigence ». */
    val minAppVersion: Int = 0,
    val defaultPlaylistId: String? = null,
    val playlists: List<PlaylistSource> = emptyList(),
    val bandwidth: BandwidthSettings = BandwidthSettings(),
) {

    /**
     * Vrai si l'application installée est trop ancienne pour cette configuration.
     * L'application continue de fonctionner : elle prévient simplement l'utilisateur.
     */
    fun requiresAppUpdate(currentVersionCode: Int): Boolean =
        minAppVersion > 0 && minAppVersion > currentVersionCode

    companion object {
        /** Seule version de schéma comprise par cette version de l'application. */
        const val SUPPORTED_SCHEMA_VERSION = 2

        /**
         * Configuration par défaut : identique au gabarit livré dans le dépôt.
         * Elle sert tant qu'aucune configuration n'a été reçue, et de repli si
         * la configuration reçue est inexploitable.
         */
        val DEFAULTS = RemoteConfig()
    }
}
