package com.missa.tv.domain.repository

import com.missa.tv.core.result.AppResult
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.PortalCatalog
import com.missa.tv.domain.model.PortalSession
import com.missa.tv.domain.model.StreamLink

/**
 * Accès au portail de l'utilisateur.
 *
 * C'est le point d'entrée unique de l'interface vers le direct : l'ouverture de
 * session (avec bascule automatique entre profils), le chargement du catalogue,
 * la création d'un lien de lecture et le maintien de session.
 */
interface PortalRepository {

    /**
     * Ouvre une session en essayant les profils enregistrés.
     *
     * Si le profil actif est refusé, les autres sont essayés automatiquement :
     * l'utilisateur n'a pas à intervenir quand un premier accès cesse de
     * fonctionner.
     */
    suspend fun connect(): AppResult<PortalSession>

    /** Recharge les catégories et les chaînes du portail. */
    suspend fun catalog(session: PortalSession): AppResult<PortalCatalog>

    /** Demande un lien de lecture temporaire pour une chaîne. */
    suspend fun createLink(session: PortalSession, channel: Channel): AppResult<StreamLink>

    /** Maintient la session active tant que la lecture est en cours. */
    suspend fun keepAlive(session: PortalSession)
}
