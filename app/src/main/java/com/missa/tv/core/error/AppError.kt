package com.missa.tv.core.error

import androidx.annotation.StringRes
import com.missa.tv.R

/**
 * Erreurs applicatives, chacune associée au message affiché à l'utilisateur.
 *
 * Les causes techniques précises restent dans les journaux (masqués) ; ce qui
 * remonte à l'écran est toujours un message compréhensible en français,
 * accompagné d'une action possible.
 */
sealed class AppError(
    @StringRes val messageRes: Int,
    /** Indique si l'utilisateur peut raisonnablement réessayer. */
    val retryable: Boolean = true,
) {

    /** Le flux de la chaîne est indisponible (coupure, adresse expirée). */
    data object StreamUnavailable : AppError(R.string.error_stream_unavailable)

    /** Plus de réseau (Wi-Fi ou données mobiles perdues). */
    data object NetworkLost : AppError(R.string.error_network_lost)

    /** Configuration distante invalide : la précédente est conservée. */
    data object InvalidConfig : AppError(R.string.error_config_invalid, retryable = false)

    /** Le fichier de configuration est absent de la branche lue du dépôt. */
    data object ConfigNotFound : AppError(R.string.error_config_not_found, retryable = false)

    /** Aucune configuration disponible : saisie manuelle nécessaire. */
    data object MissingConfig : AppError(R.string.error_config_invalid, retryable = false)

    /** La playlist ne répond pas (DNS, délai dépassé, connexion refusée). */
    data object PlaylistUnreachable : AppError(R.string.error_playlist_unreachable)

    /** Le serveur a refusé la playlist (erreur HTTP qui n'est pas un problème réseau). */
    data object PlaylistRejected : AppError(R.string.error_playlist_rejected, retryable = false)

    /** Le contenu téléchargé n'est pas une playlist M3U exploitable. */
    data object PlaylistInvalid : AppError(R.string.error_playlist_invalid, retryable = false)

    /** La playlist est bien formée mais ne contient aucune chaîne exploitable. */
    data object PlaylistEmpty : AppError(R.string.error_playlist_empty, retryable = false)

    /** La playlist dépasse la taille ou le nombre d'entrées acceptés. */
    data object PlaylistTooLarge : AppError(R.string.error_playlist_too_large, retryable = false)

    /** Le contenu téléchargé n'est pas un guide de programmes XMLTV exploitable. */
    data object EpgInvalid : AppError(R.string.error_epg_invalid, retryable = false)

    /** Le guide est bien formé mais ne contient aucun programme exploitable. */
    data object EpgEmpty : AppError(R.string.error_epg_empty, retryable = false)

    /** Le guide dépasse le nombre maximal de programmes accepté. */
    data object EpgTooLarge : AppError(R.string.error_epg_too_large, retryable = false)

    /** Toute autre erreur ; la cause est journalisée, jamais affichée. */
    data class Unknown(val cause: Throwable? = null) : AppError(R.string.error_portal_unreachable)
}
