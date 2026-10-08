package com.missa.tv.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.missa.tv.R
import com.missa.tv.core.time.ClockFormat
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.ui.adaptive.DeviceProfile
import kotlinx.coroutines.delay

/**
 * Écran de lecture.
 *
 * La surface vidéo est fournie par Media3 ; les commandes sont dessinées par
 * l'application, ce qui permet de les adapter au téléphone comme à la
 * télécommande, et d'y placer le sélecteur de qualité.
 *
 * Aucun contrôle de piste n'est laissé au lecteur : les plafonds appliqués par
 * le moteur de lecture ([com.missa.tv.domain.playback.PlaybackCaps]) décident
 * réellement de ce qui est téléchargé.
 */
/** Délai avant le masquage automatique des commandes pendant la lecture. */
private const val COMMANDES_MASQUAGE_AUTO_MS = 6_000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    exoPlayer: ExoPlayer?,
    device: DeviceProfile,
    /** Vrai quand l'activité est en Picture-in-Picture : seule l'image reste. */
    enModePip: Boolean,
    onSelectMode: (QualityMode, Boolean) -> Unit,
    onClearMode: () -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenEpg: () -> Unit,
    onBack: () -> Unit,
) {
    var controlesVisibles by remember { mutableStateOf(true) }
    var selecteurOuvert by remember { mutableStateOf(false) }

    // Les commandes s'effacent d'elles-mêmes pendant la lecture : sur un
    // téléviseur, elles ne doivent pas rester incrustées sur l'image. Un appui
    // (tactile ou télécommande) les fait réapparaître et réarme la minuterie.
    LaunchedEffect(controlesVisibles, selecteurOuvert, state.phase) {
        if (controlesVisibles && !selecteurOuvert && state.phase == PlayerUiState.Phase.Playing) {
            delay(COMMANDES_MASQUAGE_AUTO_MS)
            controlesVisibles = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Un appui n'importe où affiche ou masque les commandes : c'est le
            // comportement attendu sur un téléviseur comme sur un téléphone.
            // Désactivé en Picture-in-Picture : la fenêtre flottante n'offre que
            // l'image, sans commandes à basculer.
            .clickable(enabled = !enModePip) { controlesVisibles = !controlesVisibles },
    ) {
        if (exoPlayer != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { contexte ->
                    PlayerView(contexte).apply {
                        // Les commandes par défaut de Media3 ne connaissent pas
                        // les modes de qualité de l'application : elles sont
                        // désactivées au profit de celles dessinées ici.
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                update = { vue -> vue.player = exoPlayer },
            )
        }

        when (state.phase) {
            PlayerUiState.Phase.Opening,
            PlayerUiState.Phase.Buffering,
            -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }

            PlayerUiState.Phase.Failed -> EtatErreur(state = state, onRetry = onRetry, onBack = onBack)

            else -> Unit
        }

        if (controlesVisibles && !enModePip) {
            Commandes(
                state = state,
                exoPlayer = exoPlayer,
                onOuvrirSelecteur = { selecteurOuvert = true },
                onOpenEpg = onOpenEpg,
                onBack = onBack,
            )
        }

        if (!enModePip) {
            state.notice?.let { annonce ->
                BandeauAnnonce(
                    state = state,
                    onDismiss = onDismissNotice,
                )
            }
        }
    }

    QualitySelector(
        visible = selecteurOuvert && !enModePip,
        state = state,
        device = device,
        onSelect = { mode, verrouille ->
            onSelectMode(mode, verrouille)
            selecteurOuvert = false
        },
        onClear = {
            onClearMode()
            selecteurOuvert = false
        },
        onDismiss = { selecteurOuvert = false },
    )
}

@Composable
private fun Commandes(
    state: PlayerUiState,
    exoPlayer: ExoPlayer?,
    onOuvrirSelecteur: () -> Unit,
    onOpenEpg: () -> Unit,
    onBack: () -> Unit,
) {
    // La vidéo occupe tout l'écran ; seules les commandes évitent les barres
    // système, sinon le bouton de retour tomberait sous la barre d'état.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.player_back),
                    tint = Color.White,
                )
            }
            Column(modifier = Modifier.padding(start = 4.dp).weight(1f)) {
                Text(
                    text = state.channel?.name.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // Programme en cours et suivant, quand le portail publie un
                // guide : l'information est présente sans jamais bloquer la lecture.
                state.epg?.current?.let { programme ->
                    Text(
                        text = stringResource(R.string.epg_now_title, programme.title),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.epg?.next?.let { suivant ->
                    Text(
                        text = stringResource(
                            R.string.epg_next_at,
                            ClockFormat.hourMinute(suivant.startMs),
                            suivant.title,
                        ),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.qualityLocked) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.quality_locked),
                    tint = Color.White,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                )
            }
            IconButton(onClick = onOpenEpg) {
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = stringResource(R.string.epg_open),
                    tint = Color.White,
                )
            }
        }

        Box(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { exoPlayer?.let { lecteur -> lecteur.playWhenReady = !lecteur.playWhenReady } }) {
                Icon(
                    imageVector = if (exoPlayer?.playWhenReady == true) {
                        Icons.Filled.Pause
                    } else {
                        Icons.Filled.PlayArrow
                    },
                    contentDescription = null,
                    tint = Color.White,
                )
            }

            // Pastille de qualité : elle affiche le mode en vigueur et ouvre le
            // sélecteur. C'est aussi le rappel permanent de ce que l'application
            // est en train de télécharger. Focalisable à la télécommande : le
            // fond s'éclaircit quand le focus D-pad est dessus.
            var pastilleFocalisee by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (pastilleFocalisee) Color(0xCC444444) else Color(0x66000000))
                    .focusable()
                    .onFocusChanged { pastilleFocalisee = it.isFocused }
                    .clickable(onClick = onOuvrirSelecteur)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(state.qualityMode.labelRes),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (state.measuredKbps != null) {
                Text(
                    text = stringResource(R.string.quality_measured, state.measuredKbps),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun BandeauAnnonce(state: PlayerUiState, onDismiss: () -> Unit) {
    val message = when (val annonce = state.notice) {
        is PlayerNotice.Degraded -> stringResource(
            R.string.notice_degraded,
            stringResource(annonce.mode.labelRes),
        )
        is PlayerNotice.Upgraded -> stringResource(
            R.string.notice_upgraded,
            stringResource(annonce.mode.labelRes),
        )
        PlayerNotice.SingleQuality -> stringResource(R.string.notice_single_quality_buffering)
        null -> return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC000000))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EtatErreur(state: PlayerUiState, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(
                    state.error?.messageRes ?: R.string.error_stream_unavailable,
                ),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                    Text(
                        text = stringResource(R.string.action_retry),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Button(onClick = onBack) {
                    Text(text = stringResource(R.string.player_back))
                }
            }
        }
    }
}
