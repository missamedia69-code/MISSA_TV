package com.missa.tv.domain.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import com.missa.tv.core.log.MissaLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pilotage du mode Picture-in-Picture.
 *
 * Le mode PiP n'est demandé que si une lecture vidéo est en cours et que
 * l'appareil le permet (API 26 et plus). L'écran de lecture signale ici qu'une
 * vidéo est lue ; l'activité, qui seule peut entrer en PiP, lit cet état quand
 * l'utilisateur quitte le premier plan (touche accueil) et bascule la lecture
 * dans une fenêtre flottante au lieu de l'interrompre.
 *
 * L'état « en mode PiP » est tenu par l'activité et relu par l'interface, qui
 * masque alors ses commandes : la fenêtre flottante ne montre que l'image.
 */
@Singleton
class PipController @Inject constructor() {

    private val _eligible = MutableStateFlow(false)

    /** Vrai quand une lecture vidéo en cours peut passer en PiP. */
    val eligible: StateFlow<Boolean> = _eligible.asStateFlow()

    private val _enModePip = MutableStateFlow(false)

    /** Vrai quand l'activité est actuellement en mode Picture-in-Picture. */
    val enModePip: StateFlow<Boolean> = _enModePip.asStateFlow()

    /** Signale qu'une vidéo est (ou n'est plus) en cours de lecture. */
    fun setEligible(value: Boolean) {
        _eligible.value = value
    }

    /** Tenu par l'activité à chaque changement de mode PiP. */
    fun setEnModePip(value: Boolean) {
        _enModePip.value = value
    }

    /**
     * Demande le passage en Picture-in-Picture.
     *
     * @return `true` si la demande a été faite. Sur un appareil trop ancien ou
     * sans lecture en cours, la demande n'est pas faite et `false` est renvoyé :
     * l'application suit alors son comportement habituel.
     */
    fun entrerEnPip(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!_eligible.value) return false

        return try {
            val parametres = PictureInPictureParams.Builder()
                // La fenêtre flottante garde le format de la vidéo : un rapport
                // 16/9 convient aux flux TV et évite les bandes noires.
                .setAspectRatio(Rational(16, 9))
                .build()
            activity.enterPictureInPictureMode(parametres)
        } catch (erreur: IllegalStateException) {
            MissaLog.w("Passage en PiP refusé par le système", erreur)
            false
        }
    }
}
