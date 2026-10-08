package com.missa.tv

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.missa.tv.domain.player.PipController
import com.missa.tv.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Unique activité de l'application.
 *
 * Elle porte les deux points d'entrée (téléphone et Android TV) et délègue tout
 * le reste à Compose. Les changements de configuration sont déclarés dans le
 * manifeste : la rotation ne recrée donc pas l'activité, et l'écran de lecture
 * n'est pas interrompu par un simple pivotement du téléphone.
 *
 * C'est aussi elle qui entre en Picture-in-Picture : seule une activité peut le
 * faire. Quand l'utilisateur presse la touche accueil pendant une lecture, la
 * lecture bascule dans une fenêtre flottante au lieu de s'arrêter.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var pipController: PipController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppRoot(onExit = { finish() })
        }
    }

    /**
     * Appelé quand l'utilisateur quitte le premier plan (touche accueil).
     *
     * Si une lecture vidéo est en cours, on bascule en PiP plutôt que de passer
     * en arrière-plan : l'image reste visible dans une fenêtre flottante.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController.entrerEnPip(this)
    }

    /**
     * Répercuté vers l'interface, qui masque ses commandes en mode PiP : la
     * fenêtre flottante ne doit montrer que l'image.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipController.setEnModePip(isInPictureInPictureMode)
    }
}
