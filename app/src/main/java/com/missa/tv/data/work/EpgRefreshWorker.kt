package com.missa.tv.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.epg.EpgLoader
import com.missa.tv.data.local.EpgCache
import com.missa.tv.domain.repository.PortalProfileSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rafraîchissement périodique du guide mémorisé.
 *
 * Le travail est confié à WorkManager plutôt qu'à une boucle dans
 * l'application : le système décide du meilleur moment et n'exécute rien si le
 * réseau est absent. Seules les chaînes dont un guide est **déjà mémorisé**
 * sont redemandées — jamais le catalogue entier, qui représenterait des
 * milliers de requêtes sur un portail conséquent — et dans la limite de
 * [MAX_CHANNELS_PER_RUN] chaînes par exécution.
 *
 * Un échec sur une chaîne ne fait pas échouer le travail : le guide mémorisé
 * reste en place, et la prochaine exécution retentera. Seule une session
 * impossible à ouvrir est signalée comme un échec à réessayer.
 */
@HiltWorker
class EpgRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val profileSource: PortalProfileSource,
    private val epgCache: EpgCache,
    private val epgLoader: EpgLoader,
    private val timeSource: TimeSource,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        if (!profileSource.hasUsableProfile()) {
            // Aucun portail configuré : rien à rafraîchir, ce n'est pas un échec.
            return Result.success()
        }

        val portalId = profileSource.activePortalKey()
        val chaines = epgCache.channelsWithEvents(portalId)
        if (chaines.isEmpty()) {
            // Le guide n'est chargé qu'à la demande : sans guide mémorisé, le
            // travail n'a rien à faire tant que l'utilisateur n'ouvre pas l'application.
            MissaLog.d("Rafraîchissement du guide : aucune chaîne mémorisée")
            return Result.success()
        }

        val resultat = epgLoader.refreshShortEpg(
            portalId = portalId,
            channelIds = chaines,
            nowMs = timeSource.nowMs(),
            maxChannels = MAX_CHANNELS_PER_RUN,
        )

        return if (resultat.connectError != null && runAttemptCount < MAX_ATTEMPTS) {
            MissaLog.d("Rafraîchissement du guide à réessayer")
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        /**
         * Limite de chaînes rafraîchies par exécution : le travail périodique
         * reste discret, même sur un portail très suivi.
         */
        const val MAX_CHANNELS_PER_RUN = 30

        /** Nom unique du travail périodique : une seule instance planifiée. */
        const val PERIODIC_WORK_NAME = "missa_epg_refresh"

        /** Intervalle entre deux rafraîchissements automatiques. */
        const val REFRESH_INTERVAL_HOURS = 3L
    }
}

/**
 * Planification du rafraîchissement du guide.
 *
 * Séparé du travail lui-même pour que l'application puisse planifier sans
 * connaître WorkManager, comme pour la configuration distante.
 */
@Singleton
class EpgRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Planifie le rafraîchissement périodique.
     *
     * `UPDATE` conserve le travail existant tout en appliquant la nouvelle
     * définition : appeler cette méthode à chaque démarrage ne crée donc pas
     * de doublon.
     */
    fun schedulePeriodic() {
        val contraintes = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val travail = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
            EpgRefreshWorker.REFRESH_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(contraintes)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            EpgRefreshWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            travail,
        )
        MissaLog.d(
            "Rafraîchissement du guide planifié toutes les " +
                "${EpgRefreshWorker.REFRESH_INTERVAL_HOURS} h",
        )
    }
}
