package com.missa.tv.core.result

import com.missa.tv.core.error.AppError

/**
 * Résultat d'une opération pouvant échouer.
 *
 * Toute la couche « data » renvoie ce type plutôt que de laisser remonter des
 * exceptions : les erreurs attendues (portail injoignable, MAC non autorisée,
 * abonnement expiré, flux indisponible) sont ainsi traitées explicitement par
 * l'interface, avec un message en français et une action « Réessayer ».
 */
sealed interface AppResult<out T> {

    data class Success<T>(val value: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>

    val isSuccess: Boolean
        get() = this is Success

    /** Valeur en cas de succès, `null` sinon. */
    fun valueOrNull(): T? = (this as? Success)?.value

    /** Erreur en cas d'échec, `null` sinon. */
    fun errorOrNull(): AppError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun <R> flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    companion object {

        fun <T> success(value: T): AppResult<T> = Success(value)

        fun <T> failure(error: AppError): AppResult<T> = Failure(error)

        /**
         * Exécute un bloc en capturant les exceptions imprévues, converties en
         * erreur générique plutôt que de faire planter l'application.
         */
        inline fun <T> runCatchingApp(block: () -> T): AppResult<T> = try {
            Success(block())
        } catch (error: Throwable) {
            Failure(AppError.Unknown(error))
        }
    }
}

/** Applique [action] à la valeur si le résultat est un succès. */
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

/** Applique [action] à l'erreur si le résultat est un échec. */
inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}
