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
import com.missa.tv.domain.repository.EpgRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renouvellement périodique du guide de programmes.
 *
 * Le guide XMLTV est téléchargé en arrière-plan et associé aux diffusions du
 * catalogue : l'utilisateur retrouve un guide à jour sans avoir à l'ouvrir.
 * Comme pour la configuration, le travail est confié à WorkManager, qui ne fait
 * rien sans réseau et regroupe les réveils pour ménager batterie et données.
 *
 * Un échec ne vide jamais le guide mémorisé : la prochaine échéance reprendra,
 * et le guide en place reste consultable entre-temps.
 */
@HiltWorker
class EpgRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: EpgRepository,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val resultat = repository.refresh()

        return if (resultat.isSuccess) {
            MissaLog.d("Renouvellement du guide terminé")
            Result.success()
        } else if (runAttemptCount < MAX_ATTEMPTS) {
            MissaLog.d("Renouvellement du guide à réessayer")
            Result.retry()
        } else {
            // Après plusieurs échecs, on abandonne : le guide mémorisé reste
            // utilisable et la prochaine échéance périodique reprendra.
            MissaLog.w("Renouvellement du guide abandonné après $runAttemptCount essais")
            Result.success()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        /** Nom unique du travail périodique : une seule instance planifiée. */
        const val PERIODIC_WORK_NAME = "missa_epg_refresh"

        /** Intervalle entre deux renouvellements du guide. */
        const val REFRESH_INTERVAL_HOURS = 4L
    }
}

/**
 * Planification du renouvellement du guide.
 *
 * Séparé du travail lui-même pour que l'application puisse planifier sans
 * connaître WorkManager, et pour que les tests n'aient pas besoin d'un
 * environnement Android.
 */
@Singleton
class EpgRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Planifie le renouvellement périodique du guide.
     *
     * `UPDATE` conserve le travail existant tout en appliquant la nouvelle
     * définition : appeler cette méthode à chaque démarrage ne crée pas de
     * doublon.
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
            "Renouvellement du guide planifié toutes les " +
                "${EpgRefreshWorker.REFRESH_INTERVAL_HOURS} h",
        )
    }
}
