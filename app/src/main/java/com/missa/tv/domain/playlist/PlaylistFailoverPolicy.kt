package com.missa.tv.domain.playlist

import com.missa.tv.core.error.AppError
import com.missa.tv.domain.model.PlaylistSource

/**
 * Choix de l'ordre des sources de playlists, avec bascule automatique.
 *
 * Plusieurs sources M3U peuvent être configurées. Si l'une d'elles échoue —
 * serveur injoignable, accès refusé, contenu illisible ou vide — l'application
 * essaie la suivante au lieu d'abandonner. Les sources étant indépendantes
 * (hôtes et contenus distincts), un échec sur l'une ne préjuge pas des autres.
 *
 * Cette classe ne contient que la décision ; le téléchargement lui-même
 * appartient à la couche « data ».
 */
class PlaylistFailoverPolicy(
    /** Nombre maximum de sources essayées avant abandon. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {

    /**
     * Ordre des sources à essayer.
     *
     * Les sources désactivées ou sans URL sont écartées : elles ne peuvent pas
     * aboutir, et les essayer ne ferait que retarder le chargement.
     */
    fun candidates(sources: List<PlaylistSource>): List<PlaylistSource> =
        sources.filter { it.isComplete }.take(maxAttempts)

    /**
     * Vrai si l'échec justifie d'essayer une autre source.
     *
     * Chaque source est indépendante : qu'elle soit injoignable, refusée,
     * illisible, vide ou trop volumineuse, une autre source peut convenir. La
     * bascule est donc systématique pour les erreurs propres aux playlists.
     */
    fun shouldFailover(error: AppError): Boolean = when (error) {
        AppError.PlaylistUnreachable,
        AppError.PlaylistRejected,
        AppError.PlaylistInvalid,
        AppError.PlaylistEmpty,
        AppError.PlaylistTooLarge -> true
        else -> false
    }

    companion object {
        /** Trois tentatives suffisent : au-delà, l'attente devient pénible. */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
