package com.missa.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.missa.tv.core.log.CrashRecorder
import com.missa.tv.core.log.MissaLog
import com.missa.tv.data.work.ConfigRefreshScheduler
import com.missa.tv.data.work.EpgRefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Point d'entrée de l'application.
 *
 * L'annotation [HiltAndroidApp] déclenche la génération du conteneur
 * d'injection de dépendances.
 */
@HiltAndroidApp
class MissaTvApplication : Application(), Configuration.Provider {

    /**
     * Fabrique fournie par Hilt : les Workers reçoivent leurs dépendances par
     * injection, comme le reste de l'application.
     *
     * WorkManager n'est donc pas initialisé automatiquement (voir le manifeste) :
     * l'initialisation a lieu au premier usage, avec cette fabrique.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var configRefreshScheduler: ConfigRefreshScheduler

    @Inject
    lateinit var epgRefreshScheduler: EpgRefreshScheduler

    @Inject
    lateinit var crashRecorder: CrashRecorder

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Un arrêt inattendu laisse une trace lisible dans l'écran de réglages :
        // sans cela, il ne resterait rien à transmettre depuis un appareil auquel
        // on n'a pas accès par câble.
        crashRecorder.install()

        // Les traces détaillées ne sont émises qu'en build de débogage : en
        // release, seuls les avertissements et les erreurs sont journalisés.
        MissaLog.verbose = BuildConfig.DEBUG
        MissaLog.i("Démarrage de MISSA TV ${BuildConfig.VERSION_NAME}")

        // Vérification périodique de la configuration distante, même si
        // l'utilisateur n'ouvre pas l'application.
        configRefreshScheduler.schedulePeriodic()

        // Rafraîchissement périodique du guide mémorisé : le programme en
        // cours affiché à l'ouverture de l'accueil est à jour sans que
        // l'utilisateur ait à attendre un chargement.
        epgRefreshScheduler.schedulePeriodic()
    }
}
