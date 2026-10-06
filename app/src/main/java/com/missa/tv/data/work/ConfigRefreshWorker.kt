package com.missa.tv.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.missa.tv.core.log.MissaLog
import com.missa.tv.domain.repository.RemoteConfigRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vérification périodique de la configuration distante.
 *
 * Le travail est confié à WorkManager plutôt qu'à une boucle dans
 * l'application : le système décide du meilleur moment, regroupe les réveils et
 * n'exécute rien si le réseau est absent. Sur une connexion limitée, c'est ce
 * qui évite de consommer de la batterie et des données pour rien.
 */
@HiltWorker
class ConfigRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: RemoteConfigRepository,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        // Une vérification déclenchée par l'utilisateur ne doit pas être
        // comptabilisée comme un échec définitif : on réessaie tant que la
        // limite d'essais n'est pas atteinte.
        val resultat = repository.refresh()

        return if (resultat.isSuccess) {
            MissaLog.d("Vérification de configuration terminée")
            Result.success()
        } else if (runAttemptCount < MAX_ATTEMPTS) {
            MissaLog.d("Vérification de configuration à réessayer")
            Result.retry()
        } else {
            // Après plusieurs échecs, on abandonne : la configuration en place
            // reste utilisable et la prochaine vérification périodique reprendra.
            MissaLog.w("Vérification de configuration abandonnée après $runAttemptCount essais")
            Result.success()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        /** Nom unique du travail périodique : une seule instance planifiée. */
        const val PERIODIC_WORK_NAME = "missa_config_refresh"

        /** Nom du travail immédiat, déclenché par l'utilisateur. */
        const val IMMEDIATE_WORK_NAME = "missa_config_refresh_now"

        /** Intervalle entre deux vérifications automatiques. */
        const val REFRESH_INTERVAL_HOURS = 6L
    }
}

/**
 * Planification des vérifications de configuration.
 *
 * Séparé du travail lui-même pour que l'application puisse planifier sans
 * connaître WorkManager, et pour que les tests n'aient pas besoin d'un
 * environnement Android.
 */
@Singleton
class ConfigRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Planifie la vérification périodique.
     *
     * `UPDATE` conserve le travail existant tout en appliquant la nouvelle
     * définition : appeler cette méthode à chaque démarrage ne crée donc pas de
     * doublon.
     */
    fun schedulePeriodic() {
        val contraintes = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val travail = PeriodicWorkRequestBuilder<ConfigRefreshWorker>(
            ConfigRefreshWorker.REFRESH_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(contraintes)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ConfigRefreshWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            travail,
        )
        MissaLog.d(
            "Vérification de configuration planifiée toutes les " +
                "${ConfigRefreshWorker.REFRESH_INTERVAL_HOURS} h",
        )
    }

    /** Demande une vérification immédiate, sans attendre la prochaine échéance. */
    fun refreshNow() {
        val travail = OneTimeWorkRequestBuilder<ConfigRefreshWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ConfigRefreshWorker.IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            travail,
        )
    }
}
