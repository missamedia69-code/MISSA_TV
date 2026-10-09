package com.missa.tv.data.di

import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.catalog.CompositeCatalogRepository
import com.missa.tv.data.catalog.M3uCatalogRepository
import com.missa.tv.data.catalog.TestedCatalogRepository
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.playlist.InMemoryPlaylistCache
import com.missa.tv.data.playlist.M3uPlaylistDownloader
import com.missa.tv.data.playlist.OkHttpPlaylistFetcher
import com.missa.tv.data.playlist.PlaylistCache
import com.missa.tv.data.playlist.PlaylistDownloader
import com.missa.tv.data.playlist.PlaylistHttpFetcher
import com.missa.tv.data.playlist.PlaylistRepository
import com.missa.tv.data.playlist.PlaylistRepositoryImpl
import com.missa.tv.data.remote.catalog.CatalogJsonParser
import com.missa.tv.data.remote.catalog.GitHubCatalogDataSource
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.PlaylistSourceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Câblage de la chaîne de chargement M3U.
 *
 * Chaque maillon est fourni en singleton : l'accès réseau, le téléchargeur qui
 * l'exploite, le cache de repli des entrées, le dépôt de playlists avec bascule
 * entre sources, puis le dépôt du catalogue exposé à l'interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaylistModule {

    @Provides
    @Singleton
    fun providePlaylistHttpFetcher(): PlaylistHttpFetcher = OkHttpPlaylistFetcher()

    @Provides
    @Singleton
    fun providePlaylistDownloader(fetcher: PlaylistHttpFetcher): PlaylistDownloader =
        M3uPlaylistDownloader(fetcher)

    @Provides
    @Singleton
    fun providePlaylistCache(): PlaylistCache = InMemoryPlaylistCache()

    @Provides
    @Singleton
    fun providePlaylistRepository(
        downloader: PlaylistDownloader,
        sourceStore: PlaylistSourceStore,
        cache: PlaylistCache,
        dispatchers: DispatcherProvider,
    ): PlaylistRepository = PlaylistRepositoryImpl(downloader, sourceStore, cache, dispatchers)

    @Provides
    @Singleton
    fun provideM3uCatalogRepository(
        playlistRepository: PlaylistRepository,
        sourceStore: PlaylistSourceStore,
        catalogCache: CatalogCache,
        dispatchers: DispatcherProvider,
        timeSource: TimeSource,
    ): M3uCatalogRepository = M3uCatalogRepository(
        playlistRepository = playlistRepository,
        sourceStore = sourceStore,
        catalogCache = catalogCache,
        timeSource = timeSource,
        dispatchers = dispatchers,
    )

    @Provides
    @Singleton
    fun provideTestedCatalogRepository(
        dataSource: GitHubCatalogDataSource,
        catalogCache: CatalogCache,
        dispatchers: DispatcherProvider,
        timeSource: TimeSource,
    ): TestedCatalogRepository = TestedCatalogRepository(
        dataSource = dataSource,
        parser = CatalogJsonParser(),
        catalogCache = catalogCache,
        timeSource = timeSource,
        dispatchers = dispatchers,
    )

    /** Catalogue testé en priorité, repli M3U sinon. */
    @Provides
    @Singleton
    fun provideCatalogRepository(
        tested: TestedCatalogRepository,
        m3u: M3uCatalogRepository,
    ): CatalogRepository = CompositeCatalogRepository(tested = tested, m3u = m3u)
}
