package com.missa.tv.domain.bandwidth

import com.missa.tv.domain.model.BandwidthSettings

/**
 * Qualité de la connexion, déduite du débit mesuré.
 *
 * Trois niveaux suffisent à piloter l'application :
 *  - [LOW]    : moins de 1 Mb/s — profil économie appliqué automatiquement ;
 *  - [MEDIUM] : entre 1 et 3 Mb/s — 480p confortable, 720p incertain ;
 *  - [GOOD]   : au-delà de 3 Mb/s — la qualité choisie est tenable ;
 *  - [UNKNOWN]: aucune mesure fiable disponible (démarrage, réseau instable).
 */
enum class ConnectionClass {
    LOW,
    MEDIUM,
    GOOD,
    UNKNOWN,
    ;

    /** Vrai si la lecture doit démarrer prudemment, en basse qualité. */
    val isConstrained: Boolean get() = this == LOW

    companion object {

        /** Au-delà de ce débit, la connexion est considérée comme bonne. */
        const val GOOD_THRESHOLD_KBPS = 3_000

        /** Estimation prudente utilisée avant la première mesure. */
        const val INITIAL_ESTIMATE_KBPS = 600

        /**
         * Classe la connexion à partir d'un débit estimé en kb/s.
         *
         * Le seuil « faible » est paramétrable : il provient de la configuration
         * distante pour pouvoir être ajusté sans republier l'application.
         */
        fun from(estimatedKbps: Int?, settings: BandwidthSettings): ConnectionClass = when {
            estimatedKbps == null || estimatedKbps <= 0 -> UNKNOWN
            estimatedKbps < settings.lowBandwidthThresholdKbps -> LOW
            estimatedKbps < GOOD_THRESHOLD_KBPS -> MEDIUM
            else -> GOOD
        }

        /** Classement avec le seuil par défaut, pratique pour les tests. */
        fun from(estimatedKbps: Int?): ConnectionClass =
            from(estimatedKbps, BandwidthSettings())
    }
}
