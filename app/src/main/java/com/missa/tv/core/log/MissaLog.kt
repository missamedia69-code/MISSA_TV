package com.missa.tv.core.log

import android.util.Log

/**
 * Journalisation de l'application.
 *
 * Toutes les entrées passent par [Secrets.mask] : il est donc impossible qu'une
 * URL de portail, une adresse MAC ou un jeton se retrouve en clair dans les
 * journaux, y compris par inadvertance dans un message d'erreur.
 *
 * Les messages de débogage ne sont émis que si [verbose] est actif, ce qui est
 * le cas uniquement dans les builds de développement.
 */
object MissaLog {

    private const val TAG = "MISSA_TV"

    /** Active les traces détaillées (build de débogage). */
    var verbose: Boolean = false

    fun d(message: String) {
        if (verbose) Log.d(TAG, Secrets.mask(message))
    }

    fun i(message: String) {
        if (verbose) Log.i(TAG, Secrets.mask(message))
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, Secrets.mask(message), error)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, Secrets.mask(message), error)
    }
}
