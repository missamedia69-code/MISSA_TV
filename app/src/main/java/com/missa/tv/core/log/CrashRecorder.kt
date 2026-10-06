package com.missa.tv.core.log

import android.content.Context
import com.missa.tv.core.time.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conserve la trace du dernier plantage.
 *
 * Sans cela, un arrêt inattendu ne laisse aucune trace exploitable sur un
 * appareil auquel on n'a pas accès par câble : l'utilisateur voit l'application
 * se fermer et ne peut rien transmettre. La trace est donc écrite dans un petit
 * fichier du stockage privé, et le dernier incident est affiché dans l'écran de
 * réglages.
 *
 * Deux garanties :
 *  - le texte enregistré passe par [Secrets.mask] : adresses MAC, URL complètes
 *    et jetons sont masqués, la trace peut donc être transmise sans précaution ;
 *  - la taille est bornée : un incident ne peut pas remplir le stockage de
 *    l'utilisateur, même en cas de boucle de plantages.
 */
@Singleton
class CrashRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeSource: TimeSource,
) {

    /** Installe le récepteur de plantages, en conservant celui du système. */
    fun install() {
        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, erreur ->
            // L'écriture ne doit jamais masquer le plantage d'origine : toute
            // erreur ici est ignorée, et le système reprend la main.
            runCatching { enregistrer(fil, erreur) }
            precedent?.uncaughtException(fil, erreur)
        }
    }

    /**
     * Dernier incident enregistré, en une ligne lisible, ou `null` si aucun
     * plantage n'a eu lieu depuis l'installation.
     */
    fun dernierIncident(): String? {
        val fichier = fichierIncident()
        if (!fichier.isFile) return null

        return runCatching {
            fichier.readLines()
                .map(String::trim)
                .firstOrNull { ligne -> ligne.isNotBlank() && !ligne.startsWith("(") }
        }.getOrNull()
    }

    private fun enregistrer(fil: Thread, erreur: Throwable) {
        val entete = StringBuilder()
            .append("(")
            .append(dateLisible(timeSource.nowMs()))
            .append(") ")
            .append(erreur.javaClass.name)
            .append(": ")
            .append(erreur.message.orEmpty())
            .toString()

        val pile = StringWriter().also { erreur.printStackTrace(PrintWriter(it)) }.toString()

        fichierIncident().writeText(
            assainir(entete + "\n" + pile).take(TAILLE_MAX),
        )
    }

    /** Retire toute donnée sensible (MAC, URL, jetons) avant écriture. */
    private fun assainir(texte: String): String = Secrets.mask(texte)

    private fun fichierIncident(): File = File(context.filesDir, NOM_FICHIER)

    private fun dateLisible(instantMs: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date(instantMs))

    private companion object {
        const val NOM_FICHIER = "dernier_incident.txt"

        /** 16 Ko : très au-delà d'une pile utile, très en deçà d'une gêne. */
        const val TAILLE_MAX = 16 * 1024
    }
}
