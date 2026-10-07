package com.missa.tv.data.di

import com.missa.tv.core.security.AndroidKeystoreCipher
import com.missa.tv.core.security.SecretCipher
import com.missa.tv.data.local.ConfigStore
import com.missa.tv.data.local.DataStoreConfigStore
import com.missa.tv.data.remote.config.ConfigRemoteDataSource
import com.missa.tv.data.remote.config.GitHubConfigDataSource
import com.missa.tv.data.remote.config.GitHubContentsApi
import com.missa.tv.data.remote.config.PortalConfigParser
import com.missa.tv.data.repository.RemoteConfigRepositoryImpl
import com.missa.tv.domain.repository.RemoteConfigRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * Assemblage de la configuration distante.
 *
 * Le client GitHub est construit séparément de la pile réseau du portail : les
 * deux n'ont ni les mêmes délais, ni les mêmes en-têtes, ni les mêmes règles de
 * redirection.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConfigModule {

    @Binds
    @Singleton
    abstract fun bindRemoteConfigRepository(impl: RemoteConfigRepositoryImpl): RemoteConfigRepository

    @Binds
    @Singleton
    abstract fun bindConfigStore(impl: DataStoreConfigStore): ConfigStore

    @Binds
    @Singleton
    abstract fun bindConfigRemoteDataSource(impl: GitHubConfigDataSource): ConfigRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindSecretCipher(impl: AndroidKeystoreCipher): SecretCipher

    companion object {

        @Provides
        @Singleton
        fun providePortalConfigParser(json: Json): PortalConfigParser = PortalConfigParser(json)

        /**
         * Client de l'API GitHub.
         *
         * Délais courts : la vérification ne doit jamais retarder le démarrage
         * ni retenir un travail en arrière-plan. Une vérification manquée n'a
         * aucune conséquence, la suivante aura lieu six heures plus tard.
         */
        @Provides
        @Singleton
        fun provideGitHubApi(json: Json): GitHubContentsApi = Retrofit.Builder()
            .baseUrl(GITHUB_API_BASE_URL)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.SECONDS)
                    .build(),
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
    }
}

/** Adresse de l'API GitHub ; le fichier de configuration y est publié. */
private const val GITHUB_API_BASE_URL = "https://api.github.com/"
