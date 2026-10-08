package com.missa.tv.domain.model

/**
 * Un programme du guide électronique des programmes (EPG).
 *
 * Les horodatages sont en millisecondes depuis l'époque Unix, dans le fuseau
 * du portail : c'est lui qui fait autorité sur les heures de diffusion. Ils
 * sont convertis à l'affichage (voir `core.time.ClockFormat`).
 */
data class EpgEvent(
    val id: String,
    /** Chaîne à laquelle appartient le programme, tel que l'identifie le portail. */
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startMs: Long,
    val endMs: Long,
) {
    /** Durée du programme ; jamais négative, même si le portail envoie une fin antérieure au début. */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** Vrai si le programme est en cours à l'instant donné. */
    fun isLiveAt(nowMs: Long): Boolean = nowMs in startMs until endMs

    /** Vrai si le programme a commencé sans être terminé. */
    fun hasStartedAt(nowMs: Long): Boolean = nowMs >= startMs && nowMs < endMs

    /**
     * Avancement dans le programme, entre 0 et 1.
     *
     * Un programme sans durée exploitable (fin antérieure au début, cas d'un
     * portail incohérent) vaut 1 tant qu'il est en cours, 0 sinon : la barre de
     * progression ne doit jamais afficher une valeur hors de [0, 1].
     */
    fun progressAt(nowMs: Long): Float {
        if (durationMs <= 0L) return if (isLiveAt(nowMs)) 1f else 0f
        val ecoule = (nowMs - startMs).coerceIn(0L, durationMs)
        return ecoule.toFloat() / durationMs.toFloat()
    }

    /** Vrai si le programme chevauche la fenêtre [fromMs, toMs[. */
    fun overlaps(fromMs: Long, toMs: Long): Boolean = startMs < toMs && endMs > fromMs
}

/**
 * Guide d'une chaîne à un instant donné : le programme en cours, le suivant,
 * et la suite.
 *
 * Construit par [of] à partir d'une liste brute d'événements : la sélection ne
 * retient que ce qui est encore visible à l'instant donné, ce qui rend les
 * données périmées inoffensives — un guide mémorisé depuis des heures ne peut
 * pas afficher un programme terminé comme s'il était en cours.
 */
data class ChannelEpg(
    val channelId: String,
    /** Programme en cours, ou `null` si aucun ne couvre l'instant donné. */
    val current: EpgEvent? = null,
    /** Premier programme commençant après l'instant donné. */
    val next: EpgEvent? = null,
    /** Programmes à venir, triés par heure de début. */
    val upcoming: List<EpgEvent> = emptyList(),
) {
    /** Vrai si le portail a publié au moins un programme pour cette chaîne. */
    val hasGuide: Boolean get() = current != null || upcoming.isNotEmpty()

    companion object {

        /**
         * Sélectionne, dans [events], ce qui est visible à [nowMs].
         *
         * Les événements sont d'abord dédoublonnés par identifiant (le portail
         * peut les renvoyer en double entre deux actions) et triés par début :
         * l'ordre de la réponse n'est pas un contrat.
         */
        fun of(channelId: String, events: List<EpgEvent>, nowMs: Long): ChannelEpg {
            val visibles = events
                .distinctBy { it.id }
                .filter { it.endMs > nowMs }
                .sortedWith(compareBy({ it.startMs }, { it.endMs }))

            val courant = visibles.firstOrNull { it.isLiveAt(nowMs) }
            val aVenir = visibles.filter { it.startMs > nowMs }

            return ChannelEpg(
                channelId = channelId,
                current = courant,
                next = aVenir.firstOrNull(),
                upcoming = aVenir,
            )
        }
    }
}
