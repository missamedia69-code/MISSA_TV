package com.missa.tv.domain.repository

import com.missa.tv.core.result.AppResult
import com.missa.tv.domain.model.RemoteConfig
import kotlinx.coroutines.flow.Flow

/**
 * Configuration distante de l'application.
 *
 * L'interface expose la configuration **courante** en flux : l'interface
 * utilisateur se met à jour dès qu'une nouvelle version est reçue, sans
 * rechargement.
 */
interface RemoteConfigRepository {

    /**
     * Configuration courante.
     *
     * Elle est toujours exploitable : les valeurs par défaut sont utilisées
     * tant qu'aucune configuration n'a été reçue et si celle reçue est
     * inexploitable.
     */
    val config: Flow<RemoteConfig>

    /** Configuration courante, lue une seule fois. */
    suspend fun current(): RemoteConfig

    /**
     * Vérifie et applique une éventuelle nouvelle configuration.
     *
     * La requête est conditionnelle : si le fichier n'a pas changé, rien n'est
     * retéléchargé. En cas d'échec réseau, la configuration en place est
     * conservée et l'échec est signalé à l'appelant, qui décide de réessayer.
     */
    suspend fun refresh(): AppResult<RemoteConfig>

    /** Date de la dernière synchronisation réussie, en millisecondes. */
    suspend fun lastSyncMs(): Long
}
