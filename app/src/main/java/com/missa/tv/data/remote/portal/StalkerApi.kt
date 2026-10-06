package com.missa.tv.data.remote.portal

import com.missa.tv.core.error.AppError
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.QueryMap
import retrofit2.http.Url

/**
 * Accès HTTP au portail.
 *
 * Une seule méthode suffit : le protocole Stalker se pilote par paramètres de
 * requête (`type`, `action`, `...`) et non par des ressources REST. Les URL
 * exactes sont construites par [PortalEndpointResolver] (découverte de
 * l'endpoint) et les en-têtes par [StalkerProtocol] (signature du boîtier).
 *
 * Les réponses sont récupérées brutes puis analysées par
 * [StalkerResponseParser] : les portails font varier la forme de leurs
 * réponses, une analyse explicite est donc plus robuste que des classes
 * générées.
 */
interface StalkerApi {

    @GET
    suspend fun load(
        @Url url: String,
        @QueryMap parameters: Map<String, String>,
        @HeaderMap headers: Map<String, String>,
    ): ResponseBody
}

/** Nature d'un échec renvoyé par le portail. */
enum class PortalFailure {
    /** Le portail refuse la session : appareil inconnu ou non autorisé. */
    UNAUTHORIZED,

    /** Session acceptée mais abonnement inactif ou expiré. */
    EXPIRED,

    /** Réponse incompréhensible ou champ indispensable absent. */
    MALFORMED,

    /** Le portail répond, mais aucun flux n'est disponible pour cette chaîne. */
    STREAM_UNAVAILABLE,
}

/**
 * Échec signalé par le portail ou par l'analyse de sa réponse.
 *
 * Cette exception ne traverse jamais la couche « data » : elle est convertie en
 * [AppError] avec un message destiné à l'utilisateur.
 */
class PortalProtocolException(
    val failure: PortalFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    fun toAppError(): AppError = when (failure) {
        PortalFailure.UNAUTHORIZED -> AppError.MacUnauthorized
        PortalFailure.EXPIRED -> AppError.SubscriptionExpired
        PortalFailure.MALFORMED -> AppError.Unknown(this)
        PortalFailure.STREAM_UNAVAILABLE -> AppError.StreamUnavailable
    }
}
