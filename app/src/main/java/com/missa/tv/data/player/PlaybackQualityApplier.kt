package com.missa.tv.data.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.missa.tv.domain.model.BufferSettings
import com.missa.tv.domain.playback.PlaybackCaps
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application d'un mode de qualité au lecteur en cours de lecture.
 *
 * Le changement de mode ne recrée **pas** le lecteur : les paramètres de
 * sélection de piste sont remplacés à chaud, ce qui évite de couper le direct.
 * Sur un flux HLS à plusieurs variantes, le lecteur bascule alors sur la
 * variante correspondant au nouveau plafond ; sur un flux mono-qualité, rien ne
 * change à l'image — c'est au tampon et aux coupures de faire la différence, et
 * l'interface le dit explicitement à l'utilisateur plutôt que de laisser croire
 * à une amélioration.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlaybackQualityApplier @Inject constructor(
    private val playerFactory: PlayerFactory,
) {

    /** Applique les contraintes d'un mode à un lecteur vivant. */
    fun applyCaps(player: ExoPlayer, caps: PlaybackCaps) {
        player.trackSelectionParameters = playerFactory.trackSelectionParameters(caps)
    }

    /**
     * Indique si un changement de tampon impose de recréer le lecteur.
     *
     * Le tampon est fixé à la construction du lecteur : il n'est pas modifiable
     * à chaud. Cette méthode existe pour que cette contrainte soit exprimée à un
     * seul endroit, et vérifiable par un test.
     */
    fun bufferNeedsRecreation(current: BufferSettings, target: BufferSettings): Boolean =
        current != target
}
