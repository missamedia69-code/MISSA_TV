package com.missa.tv.ui.player

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.data.player.PlaybackQualityApplier
import com.missa.tv.data.player.PlayerFactory
import com.missa.tv.domain.bandwidth.ConnectionClass
import com.missa.tv.domain.bandwidth.QualityController
import com.missa.tv.domain.model.BandwidthSettings
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.ChannelEpg
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.domain.playback.PlaybackCaps
import com.missa.tv.domain.repository.RemoteConfigRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Message informatif affiché par-dessus la lecture. */
sealed interface PlayerNotice {

    /** Qualité abaissée automatiquement pour éviter les coupures. */
    data class Degraded(val mode: QualityMode) : PlayerNotice

    /** Qualité remontée automatiquement, la connexion le permettant. */
    data class Upgraded(val mode: QualityMode) : PlayerNotice

    /**
     * La chaîne n'est diffusée qu'en une seule qualité.
     *
     * Message essentiel pour l'honnêteté de l'interface : en mode économie, une
     * chaîne mono-qualité n'aura pas d'image moins gourmande, seuls le tampon et
     * la stabilité changeront. Laisser croire le contraire serait mensonger.
     */
    data object SingleQuality : PlayerNotice
}

/** État de l'écran de lecture. */
data class PlayerUiState(
    val channel: Channel? = null,
    val phase: Phase = Phase.Idle,
    val qualityMode: QualityMode = QualityMode.DEFAULT,
    val qualityLocked: Boolean = false,
    val connectionClass: ConnectionClass = ConnectionClass.UNKNOWN,
    val measuredKbps: Int? = null,
    /** Hauteurs des variantes vidéo proposées par le flux, de la plus basse à la plus haute. */
    val availableHeights: List<Int> = emptyList(),
    val notice: PlayerNotice? = null,
    val error: AppError? = null,
    /**
     * Guide de la chaîne : programme en cours et suivant.
     *
     * Renseigné par la source de guide de programmes ; reste `null` tant qu'aucun
     * guide n'est disponible. Son absence n'interrompt jamais la lecture.
     */
    val epg: ChannelEpg? = null,
) {
    /** Vrai si le flux ne propose qu'une seule qualité d'image. */
    val isSingleQuality: Boolean get() = availableHeights.size <= 1

    enum class Phase { Idle, Opening, Buffering, Playing, Paused, Finished, Failed }
}

/**
 * Lecture directe d'une chaîne.
 *
 * L'adresse du flux ([Channel.streamUrl]) est lue telle quelle : aucun lien
 * temporaire n'est demandé, la lecture commence dès que le tampon est prêt.
 *
 * Le mode de qualité appliqué résulte de trois volontés, dans cet ordre :
 *  1. le choix explicite de l'utilisateur, s'il existe — il est respecté, y
 *     compris lorsqu'il est verrouillé (plus aucune adaptation automatique) ;
 *  2. sinon le mode recommandé par la configuration distante ;
 *  3. corrigé par l'état réel de la connexion : une connexion lente au démarrage
 *     fait partir d'un cran plus bas, jamais d'un cran plus haut.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel @Inject constructor(
    private val channel: Channel,
    private val configRepository: RemoteConfigRepository,
    private val settingsStore: SettingsStore,
    private val playerFactory: PlayerFactory,
    private val qualityApplier: PlaybackQualityApplier,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState(channel = channel))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Réglages d'adaptation en vigueur (configuration distante incluse). */
    private var bandwidthSettings = BandwidthSettings()

    /** Décide des dégradations et remontées de palier. */
    private var qualityController = QualityController(bandwidthSettings)

    private var player: ExoPlayer? = null

    /**
     * Session multimédia du lecteur : elle rend la lecture pilotable par les
     * touches média du système (télécommande, casque), sans autre effet.
     */
    private var mediaSession: MediaSession? = null

    /** Lecteur exposé à l'interface, créé une seule fois par écran. */
    val exoPlayer: ExoPlayer? get() = player

    init {
        viewModelScope.launch {
            val config = configRepository.current()
            bandwidthSettings = config.bandwidth
            qualityController = QualityController(bandwidthSettings)
            startPlayback(config.bandwidth.defaultMode)
        }
    }

    /**
     * Lance la lecture du flux de la chaîne.
     *
     * Le mode est d'abord arrêté (choix utilisateur, sinon recommandation de la
     * configuration, corrigée par l'état de la connexion), puis le lecteur est
     * préparé sur l'adresse directe du flux.
     */
    fun startPlayback(recommendedMode: QualityMode = bandwidthSettings.defaultMode) {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(phase = PlayerUiState.Phase.Opening, error = null) }

            val preferences = settingsStore.playback()
            val modeDemande = preferences.qualityMode ?: recommendedMode
            val mode = qualityController.initialMode(
                userMode = modeDemande,
                connection = ConnectionClass.UNKNOWN,
            )

            _state.update {
                it.copy(
                    qualityMode = mode,
                    qualityLocked = preferences.qualityLocked,
                    phase = PlayerUiState.Phase.Buffering,
                )
            }

            withContext(dispatchers.main) {
                preparePlayer(channel.streamUrl, mode)
            }
        }
    }

    /** Recharge la chaîne après une erreur. */
    fun retry() {
        player?.release()
        player = null
        qualityController.reset()
        startPlayback()
    }

    /**
     * Choix explicite d'un mode par l'utilisateur.
     *
     * [locked] à vrai interdit toute adaptation ultérieure : c'est le moyen, pour
     * l'utilisateur, de garder la main sur sa qualité.
     */
    fun selectMode(mode: QualityMode, locked: Boolean = false) {
        viewModelScope.launch {
            settingsStore.setQualityMode(mode, locked)
            _state.update { it.copy(qualityMode = mode, qualityLocked = locked, notice = null) }
            applyMode(mode)
        }
    }

    /** Rétablit l'adaptation automatique. */
    fun clearMode() {
        viewModelScope.launch {
            settingsStore.setQualityMode(mode = null, locked = false)
            val mode = qualityController.initialMode(
                userMode = bandwidthSettings.defaultMode,
                connection = _state.value.connectionClass,
            )
            _state.update { it.copy(qualityMode = mode, qualityLocked = false, notice = null) }
            applyMode(mode)
        }
    }

    /** Masque le message courant (dégradation, mono-qualité). */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * Arrête et libère le lecteur.
     *
     * Media3 impose une libération explicite : un lecteur conservé consomme la
     * batterie et le réseau même sans image affichée.
     */
    fun releasePlayer() {
        // La session d'abord : elle référence le lecteur et doit être libérée
        // avant lui.
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        _state.update { it.copy(phase = PlayerUiState.Phase.Idle) }
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    private fun preparePlayer(url: String, mode: QualityMode) {
        val caps = PlaybackCaps.forMode(mode, bandwidthSettings)
        val lecteur = playerFactory.create(caps, bandwidthSettings.buffer)
        player = lecteur

        // La session précédente est libérée avant d'en créer une nouvelle :
        // elle reste liée au lecteur qu'elle pilote.
        mediaSession?.release()
        mediaSession = playerFactory.createMediaSession(lecteur)

        lecteur.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _state.update { it.copy(phase = PlayerUiState.Phase.Buffering) }
                        onRebuffering()
                    }
                    Player.STATE_READY -> {
                        _state.update { it.copy(phase = PlayerUiState.Phase.Playing) }
                    }
                    Player.STATE_ENDED -> {
                        _state.update { it.copy(phase = PlayerUiState.Phase.Finished) }
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && lecteur.playbackState == Player.STATE_READY) {
                    _state.update { it.copy(phase = PlayerUiState.Phase.Paused) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                MissaLog.w("Erreur de lecture (${error.errorCodeName})", error)
                _state.update {
                    it.copy(
                        phase = PlayerUiState.Phase.Failed,
                        error = AppError.StreamUnavailable,
                    )
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val hauteurs = tracks.groups
                    .filter { groupe ->
                        groupe.type == C.TRACK_TYPE_VIDEO &&
                            groupe.isSelected &&
                            groupe.length > 0
                    }
                    .flatMap { groupe ->
                        (0 until groupe.length).mapNotNull { index ->
                            groupe.getTrackFormat(index).height.takeIf { it > 0 }
                        }
                    }
                    .distinct()
                    .sorted()

                _state.update { it.copy(availableHeights = hauteurs.ifEmpty { listOf(0) }) }

                if (hauteurs.size <= 1 && _state.value.qualityMode.isCapped) {
                    // Une seule variante : annoncer honnêtement que la réduction
                    // de qualité ne changera pas l'image.
                    _state.update { it.copy(notice = PlayerNotice.SingleQuality) }
                }
            }
        })

        lecteur.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                // L'estimation est en bits par seconde ; l'application raisonne en
                // kilobits par seconde.
                val kbps = (bitrateEstimate / 1_000L).toInt()
                if (kbps <= 0) return
                onBandwidthSample(kbps)
            }
        })

        lecteur.setMediaItem(MediaItem.fromUri(url))
        lecteur.prepare()
        lecteur.playWhenReady = true
    }

    /** Enregistre une mesure de débit et adapte le palier si nécessaire. */
    private fun onBandwidthSample(kbps: Int) {
        val classe = ConnectionClass.from(kbps, bandwidthSettings)
        _state.update { it.copy(measuredKbps = kbps, connectionClass = classe) }

        val nouveau = qualityController.onBandwidthSample(kbps, locked = _state.value.qualityLocked)
            ?: return
        if (nouveau == _state.value.qualityMode) return

        _state.update { it.copy(qualityMode = nouveau, notice = PlayerNotice.Upgraded(nouveau)) }
        applyMode(nouveau)
        MissaLog.i("Qualité remontée automatiquement (${nouveau.name})")
    }

    /** Enregistre une coupure et abaisse le palier si nécessaire. */
    private fun onRebuffering() {
        val nouveau = qualityController.onRebuffering(locked = _state.value.qualityLocked) ?: return
        if (nouveau == _state.value.qualityMode) return

        _state.update { it.copy(qualityMode = nouveau, notice = PlayerNotice.Degraded(nouveau)) }
        applyMode(nouveau)
        MissaLog.i("Qualité abaissée automatiquement (${nouveau.name})")
    }

    private fun applyMode(mode: QualityMode) {
        val lecteur = player ?: return
        qualityApplier.applyCaps(lecteur, PlaybackCaps.forMode(mode, bandwidthSettings))
    }
}
