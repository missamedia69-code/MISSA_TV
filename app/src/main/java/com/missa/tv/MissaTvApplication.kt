package com.missa.tv

import android.app.Application
import com.missa.tv.core.log.MissaLog
import dagger.hilt.android.HiltAndroidApp

/**
 * Point d'entrée de l'application.
 *
 * L'annotation [HiltAndroidApp] déclenche la génération du conteneur
 * d'injection de dépendances.
 */
@HiltAndroidApp
class MissaTvApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Les traces détaillées ne sont émises qu'en build de débogage : en
        // release, seuls les avertissements et les erreurs sont journalisés.
        MissaLog.verbose = BuildConfig.DEBUG
        MissaLog.i("Demarrage de MISSA TV ${BuildConfig.VERSION_NAME}")
    }
}
