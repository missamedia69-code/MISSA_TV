package com.missa.tv.core.time

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Mise en forme des horodatages, pour l'affichage comme pour le portail.
 *
 * `java.time` est indisponible : le projet cible minSdk 23 sans désugaring des
 * API de base (`isCoreLibraryDesugaringEnabled = false`). `SimpleDateFormat`
 * reste donc le socle, toujours avec une locale explicite — sans elle, la mise
 * en forme dépendrait de la langue de l'appareil, et les paramètres envoyés au
 * portail deviendraient imprévisibles.
 */
object ClockFormat {

    /** Heure « HH:mm », dans le fuseau indiqué (celui de l'appareil par défaut). */
    fun hourMinute(instantMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        format(instantMs, "HH:mm", timeZone)

    /**
     * Jour « yyyy-MM-dd », tel que l'attendent les paramètres `date_from` et
     * `date_to` de l'action `get_events` du portail, dans son propre fuseau.
     */
    fun dayParam(instantMs: Long, timeZone: TimeZone): String =
        format(instantMs, "yyyy-MM-dd", timeZone)

    private fun format(instantMs: Long, pattern: String, timeZone: TimeZone): String =
        SimpleDateFormat(pattern, Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(instantMs))
}
