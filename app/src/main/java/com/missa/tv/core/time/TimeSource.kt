package com.missa.tv.core.time

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source de temps de l'application.
 *
 * Le temps est injecté plutôt que lu directement : les règles d'adaptation au
 * débit (fenêtre anti-saccade, durée de stabilité avant remontée de palier) et
 * la synchronisation de la configuration dépendent toutes de la date. Les tests
 * les pilotent donc sans attendre réellement, et une horloge fausse ne peut pas
 * déclencher de comportement aberrant.
 */
interface TimeSource {

    /** Date courante en millisecondes depuis l'époque Unix. */
    fun nowMs(): Long
}

/** Horloge réelle de l'appareil. */
@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {

    override fun nowMs(): Long = System.currentTimeMillis()
}
