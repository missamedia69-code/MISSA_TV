package com.missa.tv.data.di

import com.missa.tv.core.dispatchers.DefaultDispatcherProvider
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.time.SystemTimeSource
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.DataStoreSettingsStore
import com.missa.tv.data.local.EncryptedPlaylistSourceStore
import com.missa.tv.data.local.EncryptedPortalProfileSource
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.data.repository.PortalRepositoryImpl
import com.missa.tv.domain.portal.PortalFailoverPolicy
import com.missa.tv.domain.repository.PlaylistSourceStore
import com.missa.tv.domain.repository.PortalProfileSource
import com.missa.tv.domain.repository.PortalRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Liaisons entre les interfaces du domaine et leurs implémentations.
 *
 * Les classes du domaine ne dépendent que d'interfaces : le remplacement d'une
 * implémentation (par exemple une source de profils en mémoire pour les tests)
 * ne demande alors aucune modification des couches supérieures.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindPortalRepository(impl: PortalRepositoryImpl): PortalRepository

    @Binds
    @Singleton
    abstract fun bindPortalProfileSource(impl: EncryptedPortalProfileSource): PortalProfileSource

    @Binds
    @Singleton
    abstract fun bindPlaylistSourceStore(impl: EncryptedPlaylistSourceStore): PlaylistSourceStore

    @Binds
    @Singleton
    abstract fun bindTimeSource(impl: SystemTimeSource): TimeSource

    @Binds
    @Singleton
    abstract fun bindSettingsStore(impl: DataStoreSettingsStore): SettingsStore

    companion object {

        @Provides
        @Singleton
        fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

        /**
         * Politique de bascule entre profils.
         *
         * Elle n'a aucun état et aucune dépendance : la fournir explicitement
         * évite de l'annoter, ce qui permettrait à n'importe quelle classe de la
         * construire par erreur, y compris ses doublures de test.
         */
        @Provides
        @Singleton
        fun providePortalFailoverPolicy(): PortalFailoverPolicy = PortalFailoverPolicy()
    }
}
