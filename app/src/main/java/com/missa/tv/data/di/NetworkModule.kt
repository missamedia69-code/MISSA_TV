package com.missa.tv.data.di

import com.missa.tv.data.remote.portal.StalkerApi
import com.missa.tv.data.remote.portal.StalkerClient
import com.missa.tv.data.remote.portal.StalkerResponseParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Fournit la pile réseau.
 *
 * Particularités du protocole Stalker prises en compte ici :
 *  - l'URL de base n'est pas connue à l'avance : l'endpoint est découvert à
 *    l'exécution, chaque requête porte donc son URL complète (paramètre `@Url`) ;
 *    l'URL déclarée ci-dessous n'est qu'un support imposé par Retrofit ;
 *  - les délais sont généreux : les portails répondent parfois lentement, et un
 *    délai trop court ferait échouer une lecture qui aurait abouti ;
 *  - aucune redirection n'est suivie (un portail qui redirige signale en général
 *    une configuration erronée).
 *
 * Le journal HTTP est désactivé en release : il expose les URL complètes, donc
 * les identifiants de flux. En débogage, il reste limité aux en-têtes et à la
 * première ligne des réponses, jamais au corps.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** URL factice : remplacée par l'URL réelle de chaque requête. */
    private const val PLACEHOLDER_BASE_URL = "https://localhost/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
        )
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideStalkerApi(retrofit: Retrofit): StalkerApi = retrofit.create(StalkerApi::class.java)

    @Provides
    @Singleton
    fun provideStalkerResponseParser(json: Json): StalkerResponseParser =
        StalkerResponseParser(json)

    @Provides
    @Singleton
    fun provideStalkerClient(
        api: StalkerApi,
        parser: StalkerResponseParser,
    ): StalkerClient = StalkerClient(api = api, parser = parser)
}
