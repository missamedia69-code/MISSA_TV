package com.missa.tv.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Fournit les « dispatchers » de coroutines.
 *
 * Passer par cette interface plutôt que d'utiliser directement
 * [Dispatchers.IO] rend le code testable : les tests substituent un dispatcher
 * synchrone sans avoir à manipuler les threads.
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

/** Implémentation réelle, utilisée par l'application. */
class DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main.immediate
}
