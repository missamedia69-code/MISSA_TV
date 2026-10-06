package com.missa.tv.data.di

import com.missa.tv.core.dispatchers.DefaultDispatcherProvider
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.data.local.EncryptedPortalProfileSource
import com.missa.tv.data.repository.PortalRepositoryImpl
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

    companion object {

        @Provides
        @Singleton
        fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
    }
}
