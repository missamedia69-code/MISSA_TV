package com.missa.tv.domain.repository

import com.missa.tv.domain.model.PortalProfile

/**
 * Source des profils de connexion enregistrés sur l'appareil.
 *
 * Les profils contiennent l'URL du portail et l'adresse MAC : ils sont donc
 * stockés chiffrés et ne sont jamais journalisés. L'interface est séparée de
 * l'implémentation pour que les tests utilisent une source en mémoire.
 */
interface PortalProfileSource {

    /** Tous les profils enregistrés, dans un ordre stable. */
    suspend fun profiles(): List<PortalProfile>

    /** Profil utilisé en priorité, ou `null` si aucun n'a encore été choisi. */
    suspend fun activeProfileId(): String?

    /** Mémorise le profil à utiliser en priorité (après une bascule réussie). */
    suspend fun setActiveProfileId(id: String)

    /** Ajoute ou remplace un profil (saisie manuelle). */
    suspend fun save(profile: PortalProfile)

    /** Supprime un profil. */
    suspend fun delete(id: String)

    /** Vrai si au moins un profil complet est disponible. */
    suspend fun hasUsableProfile(): Boolean = profiles().any { it.enabled && it.isComplete }
}
