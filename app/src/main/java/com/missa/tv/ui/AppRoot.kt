package com.missa.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.missa.tv.core.ui.hiltViewModelFactory
import com.missa.tv.ui.adaptive.rememberDeviceProfile
import com.missa.tv.ui.epg.ChannelGuideScreen
import com.missa.tv.ui.epg.ChannelGuideViewModel
import com.missa.tv.ui.epg.EpgScreen
import com.missa.tv.ui.epg.EpgViewModel
import com.missa.tv.ui.home.HomeScreen
import com.missa.tv.ui.home.HomeViewModel
import com.missa.tv.ui.navigation.Screen
import com.missa.tv.ui.navigation.rememberNavigator
import com.missa.tv.ui.player.PlayerScreen
import com.missa.tv.ui.player.PlayerViewModel
import com.missa.tv.ui.settings.SettingsScreen
import com.missa.tv.ui.settings.SettingsViewModel
import com.missa.tv.ui.theme.MissaTvTheme

/**
 * Racine de l'interface.
 *
 * Elle assemble le thème (adapté à l'appareil), la navigation et les écrans.
 * Chaque écran construit son ViewModel à partir du point d'entrée Hilt : les
 * dépendances sont donc explicites, écran par écran.
 */
@Composable
fun AppRoot(onExit: () -> Unit) {
    val appareil = rememberDeviceProfile()

    MissaTvTheme(deviceProfile = appareil) {
        // La Surface porte la couleur de fond et, surtout, la couleur de contenu
        // par défaut : sans elle, un texte sans couleur explicite s'afficherait
        // en noir sur le fond sombre de l'application.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val navigateur = rememberNavigator()
            val contexte = LocalContext.current

            // Le retour matériel dépile l'écran courant ; sur le premier écran,
            // il rend la main au système, qui ferme l'application.
            BackHandler {
                if (!navigateur.back()) onExit()
            }

            when (val ecran = navigateur.current) {
                Screen.Home -> {
                    val vm: HomeViewModel = viewModel(
                        factory = hiltViewModelFactory<HomeViewModel>(contexte) { point ->
                            HomeViewModel(
                                catalogRepository = point.catalogRepository(),
                                configRepository = point.remoteConfigRepository(),
                                settingsStore = point.settingsStore(),
                                catalogCache = point.catalogCache(),
                                dispatchers = point.dispatcherProvider(),
                            )
                        },
                    )
                    val etat by vm.state.collectAsStateWithLifecycle()

                    // Se déclenche à chaque affichage de l'accueil, donc aussi au
                    // retour depuis les réglages : la configuration saisie devient
                    // visible sans redémarrer l'application.
                    LaunchedEffect(Unit) { vm.rafraichirSiNecessaire() }

                    HomeScreen(
                        state = etat,
                        device = appareil,
                        onRetry = vm::load,
                        onCategorySelected = vm::selectCategory,
                        onChannelSelected = { groupe ->
                            navigateur.open(Screen.Player(vm.channelToPlay(groupe)))
                        },
                        onOpenEpg = {
                            navigateur.open(Screen.Epg(groups = etat.visibleGroups))
                        },
                        onOpenSettings = { navigateur.open(Screen.Settings) },
                        onOpenManualSetup = { navigateur.open(Screen.ManualSetup) },
                    )
                }

                is Screen.Player -> {
                    val vm: PlayerViewModel = viewModel(
                        key = ecran.channel.id,
                        factory = hiltViewModelFactory<PlayerViewModel>(contexte) { point ->
                            PlayerViewModel(
                                channel = ecran.channel,
                                configRepository = point.remoteConfigRepository(),
                                settingsStore = point.settingsStore(),
                                playerFactory = point.playerFactory(),
                                qualityApplier = point.playbackQualityApplier(),
                                dispatchers = point.dispatcherProvider(),
                            )
                        },
                    )
                    val etat by vm.state.collectAsStateWithLifecycle()

                    // Le lecteur est libéré dès que l'écran disparaît : Media3
                    // n'arrête rien de lui-même, et un lecteur oublié continue
                    // de télécharger.
                    DisposableEffect(ecran.channel.id) {
                        onDispose { vm.releasePlayer() }
                    }

                    PlayerScreen(
                        state = etat,
                        exoPlayer = vm.exoPlayer,
                        device = appareil,
                        onSelectMode = vm::selectMode,
                        onClearMode = vm::clearMode,
                        onRetry = vm::retry,
                        onDismissNotice = vm::dismissNotice,
                        onOpenEpg = {
                            navigateur.open(Screen.ChannelGuide(ecran.channel))
                        },
                        onBack = { navigateur.back() },
                    )
                }

                is Screen.Epg -> {
                    val vm: EpgViewModel = viewModel(
                        factory = hiltViewModelFactory<EpgViewModel>(contexte) { point ->
                            EpgViewModel(
                                groups = ecran.groups,
                                profileSource = point.portalProfileSource(),
                                epgLoader = point.epgLoader(),
                                settingsStore = point.settingsStore(),
                                configRepository = point.remoteConfigRepository(),
                                timeSource = point.timeSource(),
                                dispatchers = point.dispatcherProvider(),
                            )
                        },
                    )
                    val etat by vm.state.collectAsStateWithLifecycle()

                    EpgScreen(
                        state = etat,
                        device = appareil,
                        onChannelSelected = { groupe ->
                            navigateur.open(Screen.ChannelGuide(vm.channelToPlay(groupe)))
                        },
                        onRowsVisible = vm::rafraichirPour,
                        onRefresh = vm::refresh,
                        onBack = { navigateur.back() },
                    )
                }

                is Screen.ChannelGuide -> {
                    val vm: ChannelGuideViewModel = viewModel(
                        key = ecran.channel.id,
                        factory = hiltViewModelFactory<ChannelGuideViewModel>(contexte) { point ->
                            ChannelGuideViewModel(
                                channel = ecran.channel,
                                portalRepository = point.portalRepository(),
                                profileSource = point.portalProfileSource(),
                                epgCache = point.epgCache(),
                                timeSource = point.timeSource(),
                                dispatchers = point.dispatcherProvider(),
                            )
                        },
                    )
                    val etat by vm.state.collectAsStateWithLifecycle()

                    ChannelGuideScreen(
                        state = etat,
                        onPlay = { navigateur.open(Screen.Player(ecran.channel)) },
                        onRefresh = vm::refresh,
                        onBack = { navigateur.back() },
                    )
                }

                Screen.Settings, Screen.ManualSetup -> {
                    val vm: SettingsViewModel = viewModel(
                        factory = hiltViewModelFactory<SettingsViewModel>(contexte) { point ->
                            SettingsViewModel(
                                profileSource = point.portalProfileSource(),
                                settingsStore = point.settingsStore(),
                                configRepository = point.remoteConfigRepository(),
                                portalRepository = point.portalRepository(),
                                crashRecorder = point.crashRecorder(),
                                dispatchers = point.dispatcherProvider(),
                            )
                        },
                    )
                    val etat by vm.state.collectAsStateWithLifecycle()

                    SettingsScreen(
                        state = etat,
                        device = appareil,
                        // Depuis l'accueil sans portail configuré, le formulaire
                        // est ouvert d'emblée : l'utilisateur n'a pas à le
                        // chercher.
                        ouvrirFormulaireParDefaut = !etat.hasProfiles || ecran == Screen.ManualSetup,
                        onBack = { navigateur.back() },
                        onSaveProfile = vm::enregistrerProfil,
                        onDeleteProfile = vm::supprimerProfil,
                        onActivateProfile = vm::activerProfil,
                        onQualitySelected = vm::definirQualite,
                        onCheckConfig = vm::verifierConfiguration,
                        onTestConnection = vm::testerConnexion,
                        onMessageShown = vm::effacerMessage,
                    )
                }
            }
        }
    }
}
