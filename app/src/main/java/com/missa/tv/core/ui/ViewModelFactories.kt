package com.missa.tv.core.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.log.CrashRecorder
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.data.player.PlaybackQualityApplier
import com.missa.tv.data.player.PlayerFactory
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.RemoteConfigRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Point d'accès aux dépendances pour les ViewModels.
 *
 * L'application n'utilise pas la navigation de Jetpack : elle exige minSdk 24
 * alors que le projet cible minSdk 23, pour rester installable sur les boîtiers
 * Android TV anciens. La navigation est donc écrite à la main, et les
 * ViewModels sont construits explicitement.
 *
 * Ce point d'entrée est le seul endroit où l'interface va chercher ses
 * dépendances : chaque écran y prend ce dont il a besoin, sans variable globale
 * ni conteneur accessible partout.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun remoteConfigRepository(): RemoteConfigRepository
    fun catalogRepository(): CatalogRepository
    fun settingsStore(): SettingsStore
    fun catalogCache(): CatalogCache
    fun playerFactory(): PlayerFactory
    fun playbackQualityApplier(): PlaybackQualityApplier
    fun dispatcherProvider(): DispatcherProvider
    fun crashRecorder(): CrashRecorder
}

/** Récupère le point d'entrée depuis n'importe quel contexte d'application. */
fun appEntryPoint(context: Context): AppEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, AppEntryPoint::class.java)

/**
 * Fabrique de ViewModel construite sur le point d'entrée.
 *
 * [create] reçoit le point d'entrée et renvoie le ViewModel voulu : chaque écran
 * garde ainsi la maîtrise de ses dépendances, et les tests peuvent fournir une
 * fabrique équivalente avec des doublures.
 */
inline fun <reified VM : ViewModel> hiltViewModelFactory(
    context: Context,
    crossinline create: (AppEntryPoint) -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        create(appEntryPoint(context)) as T
}
