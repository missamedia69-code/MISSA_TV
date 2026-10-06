package com.missa.tv.domain.bandwidth

import com.missa.tv.domain.model.BandwidthSettings
import com.missa.tv.domain.model.QualityMode

/**
 * Décide du palier de qualité à appliquer pendant la lecture.
 *
 * Les règles sont volontairement stables et lisibles (« stabilité avant
 * qualité ») :
 *
 *  - **Démarrage** : on part toujours du plafond demandé par l'utilisateur. Si
 *    le débit est inconnu, on reste prudent ([ConnectionClass.INITIAL_ESTIMATE_KBPS]).
 *  - **Dégradation** : deux mises en mémoire tampon (rebuffering) en moins de
 *    60 secondes font descendre d'un palier. Jamais plus d'un palier à la fois :
 *    une chute brutale est souvent passagère.
 *  - **Remontée** : si le débit mesuré dépasse le double du débit nécessaire au
 *    palier supérieur pendant 90 secondes, on remonte d'un palier — sans jamais
 *    dépasser le plafond choisi par l'utilisateur.
 *  - **Verrouillage** : si l'utilisateur a verrouillé la qualité, aucune
 *    dégradation ni remontée automatique n'a lieu.
 *
 * L'horloge est injectée : toute la logique est donc testable sans attente.
 */
class QualityController(
    private val settings: BandwidthSettings = BandwidthSettings(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    /** Horodatages des mises en mémoire tampon récentes. */
    private val rebufferTimestamps = ArrayDeque<Long>()

    /** Instant depuis lequel la connexion est jugée suffisante pour remonter. */
    private var upgradeCandidateSinceMs: Long? = null

    /** Palier actuellement appliqué, ou `null` tant qu'aucune lecture n'a eu lieu. */
    var currentMode: QualityMode? = null
        private set

    /**
     * Palier à appliquer au démarrage d'une lecture.
     *
     * Un mode sans plafond (`MAX_QUALITY`) est respecté : c'est un choix
     * explicite de l'utilisateur, pas un réglage que l'application s'autorise à
     * corriger.
     */
    fun initialMode(userMode: QualityMode, connection: ConnectionClass): QualityMode {
        val mode = when {
            userMode == QualityMode.MAX_QUALITY -> QualityMode.MAX_QUALITY
            connection == ConnectionClass.LOW && userMode == QualityMode.AUTO_ECONOMY ->
                QualityMode.ECONOMY_480
            else -> userMode
        }
        currentMode = mode
        reset()
        return mode
    }

    /**
     * Enregistre une mise en mémoire tampon et renvoie le nouveau palier si une
     * dégradation est nécessaire, `null` si l'on conserve le palier courant.
     */
    fun onRebuffering(locked: Boolean): QualityMode? {
        val now = clockMs()
        rebufferTimestamps.addLast(now)
        pruneRebuffers(now)
        // Une remontée en cours est annulée dès que la lecture saccade.
        upgradeCandidateSinceMs = null

        if (locked) return null
        if (rebufferTimestamps.size < REBUFFERING_THRESHOLD) return null

        val current = currentMode ?: return null
        val lower = current.lowerStep() ?: return null
        currentMode = lower
        // Repartir d'un compteur vide évite d'enchaîner les paliers à cause du
        // même incident.
        rebufferTimestamps.clear()
        return lower
    }

    /**
     * À appeler lorsqu'une mesure de débit est disponible. Renvoie le nouveau
     * palier si une remontée est justifiée, `null` sinon.
     */
    fun onBandwidthSample(measuredKbps: Int, locked: Boolean): QualityMode? {
        val now = clockMs()
        val current = currentMode ?: return null

        if (locked || current == QualityMode.MAX_QUALITY) {
            upgradeCandidateSinceMs = null
            return null
        }

        val higher = current.higherStep()
        if (higher == null) {
            upgradeCandidateSinceMs = null
            return null
        }

        val required = settings.requiredKbpsFor(higher) * settings.upgradeHeadroomFactor
        if (measuredKbps < required) {
            upgradeCandidateSinceMs = null
            return null
        }

        val since = upgradeCandidateSinceMs
        if (since == null) {
            upgradeCandidateSinceMs = now
            return null
        }

        if (now - since < UPGRADE_STABLE_DURATION_MS) return null

        currentMode = higher
        upgradeCandidateSinceMs = null
        return higher
    }

    /** Réinitialise l'état interne (changement de chaîne, par exemple). */
    fun reset() {
        rebufferTimestamps.clear()
        upgradeCandidateSinceMs = null
    }

    private fun pruneRebuffers(now: Long) {
        while (rebufferTimestamps.isNotEmpty() && now - rebufferTimestamps.first() > REBUFFERING_WINDOW_MS) {
            rebufferTimestamps.removeFirst()
        }
    }

    companion object {
        /** Fenêtre d'observation des mises en mémoire tampon. */
        const val REBUFFERING_WINDOW_MS = 60_000L

        /** Nombre de saccades dans la fenêtre déclenchant une dégradation. */
        const val REBUFFERING_THRESHOLD = 2

        /** Durée pendant laquelle le débit doit rester confortable avant remontée. */
        const val UPGRADE_STABLE_DURATION_MS = 90_000L
    }
}
