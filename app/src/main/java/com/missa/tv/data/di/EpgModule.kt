package com.missa.tv.data.di

import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.data.epg.OkHttpXmltvEpgDownloader
import com.missa.tv.data.epg.XmltvEpgDownloader
import com.missa.tv.data.epg.XmltvEpgRepository
import com.missa.tv.data.epg.XmltvParser
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.local.EpgCache
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.EpgRepository
import com.missa.tv.domain.repository.PlaylistSourceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Câblage du guide de programmes XMLTV.
 *
 * Le téléchargeur réutilise le client HTTP partagé ; le dépôt associe les
 * programmes téléchargés aux diffusions du catalogue via leur `tvgId` et les
 * mémorise sous la clé du catalogue, celle que lit l'interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object EpgModule {

    @Provides
    @Singleton
    fun provideXmltvEpgDownloader(client: OkHttpClient): XmltvEpgDownloader =
        OkHttpXmltvEpgDownloader(client)

    @Provides
    @Singleton
    fun provideEpgRepository(
        sourceStore: PlaylistSourceStore,
        catalogRepository: CatalogRepository,
        catalogCache: CatalogCache,
        downloader: XmltvEpgDownloader,
        epgCache: EpgCache,
        dispatchers: DispatcherProvider,
    ): EpgRepository = XmltvEpgRepository(
        sourceStore = sourceStore,
        catalogRepository = catalogRepository,
        catalogCache = catalogCache,
        downloader = downloader,
        epgCache = epgCache,
        parser = XmltvParser(),
        dispatchers = dispatchers,
    )
}
