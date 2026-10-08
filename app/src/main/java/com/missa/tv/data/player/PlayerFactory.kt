package com.missa.tv.data.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.missa.tv.domain.model.BufferSettings
import com.missa.tv.domain.playback.PlaybackCaps
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Construction du lecteur vidéo.
 *
 * Trois réglages sont essentiels pour la lecture en faible débit :
 *
 *  1. **Plafonds de piste** ([TrackSelectionParameters]) — le lecteur n'est pas
 *     laissé libre de choisir la meilleure qualité. Sur une liste HLS à
 *     plusieurs variantes, il sélectionne la meilleure piste *sous* le plafond,
 *     ce qui divise réellement le débit consommé.
 *  2. **Tampon généreux** ([DefaultLoadControl]) — mieux vaut accumuler de
 *     l'avance que s'arrêter toutes les dix secondes. Un flux SD avec 20 s
 *     d'avance tient sur une connexion instable.
 *  3. **Reprise après coupure** — le lecteur réessaie au lieu d'abandonner, et
 *     la lecture continue quand l'écran s'éteint (utile sur un téléviseur).
 */
@OptIn(UnstableApi::class)
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    /**
     * Crée un lecteur pour une URL donnée.
     *
     * Le lecteur est créé par écran de lecture puis libéré (`release()`), comme
     * l'exige Media3 : garder un lecteur vivant en arrière-plan consomme la
     * batterie et des données.
     */
    fun create(caps: PlaybackCaps, buffer: BufferSettings): ExoPlayer {
        val lecteur = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory())
            .setLoadControl(loadControl(buffer))
            .build()

        lecteur.setTrackSelectionParameters(trackSelectionParameters(caps))

        // La lecture doit continuer quand l'application perd le premier plan :
        // sur un téléviseur, l'utilisateur peut quitter l'écran sans arrêter la
        // chaîne. La libération reste explicite (`release()`), jamais implicite.
        lecteur.setWakeMode(C.WAKE_MODE_NETWORK)

        // Couper le son quand un casque est débranché : comportement attendu par
        // les utilisateurs, et évite une sortie sonore inattendue sur TV.
        lecteur.setHandleAudioBecomingNoisy(true)

        lecteur.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )

        return lecteur
    }

    /**
     * Crée une session multimédia liée à un lecteur.
     *
     * La session reçoit les touches média du système (lecture, pause, stop) —
     * celles de la télécommande sur un téléviseur comme celles d'un casque ou
     * d'Android Auto. Sans elle, ces touches resteraient sans effet. La session
     * est créée avec le lecteur puis libérée avec lui par l'écran de lecture.
     */
    fun createMediaSession(player: Player): MediaSession =
        MediaSession.Builder(context, player).build()

    /**
     * Paramètres de sélection de piste pour des contraintes données.
     *
     * La hauteur et le débit sont plafonnés ; le mode audio seul désactive
     * entièrement la piste vidéo, ce qui réduit la consommation au seul son
     * (environ 96 kb/s).
     */
    fun trackSelectionParameters(caps: PlaybackCaps): TrackSelectionParameters {
        val builder = TrackSelectionParameters.Builder(context)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, caps.videoDisabled)

        if (!caps.videoDisabled) {
            if (caps.maxHeightPx > 0) {
                // La largeur n'est pas un critère utile : le rapport d'image peut
                // être 4/3 ou 16/9, seule la hauteur situe la qualité. Une
                // largeur très élevée revient donc à ne pas filtrer.
                builder.setMaxVideoSize(NO_WIDTH_LIMIT, caps.maxHeightPx)
            }
            val debit = caps.effectiveMaxBitrateBps
            if (debit > 0) {
                builder.setMaxVideoBitrate(debit)
            }
        }

        return builder.build()
    }

    private fun loadControl(buffer: BufferSettings): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffer.minMs,
                buffer.maxMs,
                buffer.playbackMs,
                buffer.afterRebufferMs,
            )
            // En faible débit, la taille cible du tampon doit être atteinte même
            // si les segments sont longs : le lecteur ne doit pas se croire en
            // surcharge parce qu'il a peu de segments en mémoire.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    /**
     * Source des médias.
     *
     * OkHttp est réutilisé : mêmes connexions, mêmes délais, et un seul endroit
     * où les requêtes sortantes sont paramétrées.
     */
    private fun mediaSourceFactory(): DefaultMediaSourceFactory {
        // Les délais viennent du client OkHttp partagé : les redéfinir ici
        // créerait deux réglages concurrents.
        val dataSourceFactory: DataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(USER_AGENT)

        return DefaultMediaSourceFactory(dataSourceFactory)
    }

    private companion object {
        /** Le lecteur ignore la largeur : seule la hauteur situe la qualité. */
        const val NO_WIDTH_LIMIT = 100_000

        /** Chaîne générique : aucun décodeur n'est identifiable par ce biais. */
        const val USER_AGENT = "MISSA-TV/1.0 (Android; lecture IPTV)"
    }
}
