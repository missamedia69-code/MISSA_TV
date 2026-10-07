package com.missa.tv.domain.model

/**
 * Configuration distante de l'application.
 *
 * Elle est publiée dans le dépôt GitHub sous `remote-config/portal-config.json`
 * et lue via l'API Contents. Elle permet d'ajuster le comportement de
 * l'application — seuils de débit, tampon de lecture, profils de connexion —
 * sans republier l'application sur les magasins.
 *
 * Rien d'essentiel à la lecture ne doit dépendre d'elle : en l'absence de
 * configuration, l'application fonctionne avec ses valeurs par défaut.
 */
data class RemoteConfig(
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    val configVersion: Int = 0,
    val updatedAt: String? = null,
    /** VersionCode minimal requis ; 0 signifie « aucune exigence ». */
    val minAppVersion: Int = 0,
    val defaultProfileId: String? = null,
    val profiles: List<PortalProfile> = emptyList(),
    val bandwidth: BandwidthSettings = BandwidthSettings(),
) {

    /** Vrai si cette configuration est plus récente que [other]. */
    fun isNewerThan(other: RemoteConfig): Boolean = configVersion > other.configVersion

    /**
     * Vrai si l'application installée est trop ancienne pour cette configuration.
     * L'application continue de fonctionner : elle prévient simplement l'utilisateur.
     */
    fun requiresAppUpdate(currentVersionCode: Int): Boolean =
        minAppVersion > 0 && minAppVersion > currentVersionCode

    companion object {
        /** Seule version de schéma comprise par cette version de l'application. */
        const val SUPPORTED_SCHEMA_VERSION = 1

        /**
         * Configuration par défaut : identique à celle livrée dans le dépôt.
         * Elle sert tant qu'aucune configuration n'a été reçue, et de repli si
         * la configuration reçue est inexploitable.
         */
        val DEFAULTS = RemoteConfig()
    }
}
