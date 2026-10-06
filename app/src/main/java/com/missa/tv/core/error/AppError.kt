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

    /** Le portail ne répond pas (DNS, port fermé, délai dépassé). */
    data object PortalUnreachable : AppError(R.string.error_portal_unreachable)

    /** Handshake refusé : l'appareil n'est pas reconnu par le portail. */
    data object MacUnauthorized : AppError(R.string.error_mac_unauthorized, retryable = false)

    /** Abonnement expiré ou désactivé côté portail. */
    data object SubscriptionExpired : AppError(R.string.error_subscription_expired, retryable = false)

    /** Le portail répond mais ne fournit aucun flux pour cette chaîne. */
    data object StreamUnavailable : AppError(R.string.error_stream_unavailable)

    /** Plus de réseau (Wi-Fi ou données mobiles perdues). */
    data object NetworkLost : AppError(R.string.error_network_lost)

    /** Configuration distante invalide : la précédente est conservée. */
    data object InvalidConfig : AppError(R.string.error_config_invalid, retryable = false)

    /** Aucune configuration disponible : saisie manuelle nécessaire. */
    data object MissingConfig : AppError(R.string.error_config_invalid, retryable = false)

    /** Toute autre erreur ; la cause est journalisée, jamais affichée. */
    data class Unknown(val cause: Throwable? = null) : AppError(R.string.error_portal_unreachable)
}
