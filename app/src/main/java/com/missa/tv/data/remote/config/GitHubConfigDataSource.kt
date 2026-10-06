package com.missa.tv.data.remote.config

import com.missa.tv.BuildConfig
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.log.Secrets
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.decodeBase64
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

/**
 * Réponse de l'API Contents de GitHub pour un fichier.
 *
 * Le contenu est transmis en base 64, sur plusieurs lignes.
 */
@Serializable
data class GitHubContentDto(
    val name: String? = null,
    val path: String? = null,
    val sha: String? = null,
    val size: Long? = null,
    val content: String? = null,
    val encoding: String? = null,
)

/** Accès au fichier de configuration publié dans le dépôt. */
interface GitHubContentsApi {

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun contents(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Header("If-None-Match") etag: String? = null,
        @Header("X-GitHub-Api-Version") apiVersion: String = API_VERSION,
        @Header("Authorization") authorization: String? = null,
    ): Response<GitHubContentDto>

    companion object {
        const val API_VERSION = "2022-11-28"
    }
}

/** Résultat d'une tentative de récupération de la configuration. */
sealed interface ConfigFetchResult {

    /** Le document n'a pas changé depuis la dernière synchronisation. */
    data object NotModified : ConfigFetchResult

    /** Nouveau document reçu. */
    data class Fetched(val document: String, val etag: String?) : ConfigFetchResult

    /** La récupération a échoué ; la configuration en place est conservée. */
    data class Failed(val error: AppError) : ConfigFetchResult
}

/** Source de la configuration distante. */
interface ConfigRemoteDataSource {
    suspend fun fetch(etag: String?): ConfigFetchResult
}

/**
 * Lecture de la configuration via l'API Contents de GitHub.
 *
 * La requête est **conditionnelle** : l'empreinte de la dernière version reçue
 * est envoyée dans `If-None-Match`. Le serveur répond alors « 304 Non modifié »
 * tant que le fichier n'a pas changé, ce qui évite de retélécharger et de
 * réanalyser la configuration à chaque vérification — utile sur une connexion
 * limitée.
 *
 * Le dépôt est public : aucun jeton n'est nécessaire pour lire le fichier. Un
 * jeton reste possible pour relever la limite de requêtes de GitHub.
 */
@Singleton
class GitHubConfigDataSource @Inject constructor(
    private val api: GitHubContentsApi,
) : ConfigRemoteDataSource {

    override suspend fun fetch(etag: String?): ConfigFetchResult {
        return try {
            val reponse = api.contents(
                owner = RemoteConfigSource.owner,
                repo = RemoteConfigSource.repo,
                path = RemoteConfigSource.path,
                etag = etag,
                authorization = RemoteConfigSource.authorization,
            )

            when {
                reponse.code() == HTTP_NOT_MODIFIED -> ConfigFetchResult.NotModified

                reponse.isSuccessful -> {
                    val corps = reponse.body()
                    val contenu = corps?.content
                    if (contenu.isNullOrBlank()) {
                        MissaLog.w("Configuration distante : réponse sans contenu")
                        ConfigFetchResult.Failed(AppError.InvalidConfig)
                    } else {
                        val document = contenu.decodeBase64()?.utf8()
                        if (document.isNullOrBlank()) {
                            MissaLog.w("Configuration distante : contenu illisible")
                            ConfigFetchResult.Failed(AppError.InvalidConfig)
                        } else {
                            ConfigFetchResult.Fetched(
                                document = document,
                                etag = reponse.headers()["ETag"] ?: corps.sha,
                            )
                        }
                    }
                }

                // 404 : le fichier a été renommé ou supprimé. 401/403 : jeton
                // invalide ou limite de requêtes atteinte.
                else -> {
                    MissaLog.w("Configuration distante : réponse ${reponse.code()} de GitHub")
                    ConfigFetchResult.Failed(AppError.InvalidConfig)
                }
            }
        } catch (erreur: IOException) {
            MissaLog.w("Configuration distante : GitHub injoignable", erreur)
            ConfigFetchResult.Failed(AppError.NetworkLost)
        } catch (erreur: Exception) {
            MissaLog.w("Configuration distante : échec inattendu", erreur)
            ConfigFetchResult.Failed(AppError.Unknown(erreur))
        }
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
    }
}

/**
 * Emplacement du fichier de configuration.
 *
 * Les valeurs sont injectées à la compilation depuis `local.properties` (voir
 * `app/build.gradle.kts`) : aucun identifiant n'est écrit dans le code source.
 * Le dépôt et le chemin sont publics, seul le jeton — facultatif — est secret.
 */
object RemoteConfigSource {

    /** Dépôt qui héberge le fichier de configuration. */
    val owner: String = BuildConfig.GITHUB_CONFIG_OWNER

    val repo: String = BuildConfig.GITHUB_CONFIG_REPO

    val path: String = BuildConfig.GITHUB_CONFIG_PATH

    /** En-tête d'autorisation, ou `null` si aucun jeton n'est configuré. */
    val authorization: String? = BuildConfig.GITHUB_CONFIG_TOKEN
        .takeIf { it.isNotBlank() }
        ?.let { jeton ->
            MissaLog.d("Jeton GitHub configuré : ${Secrets.maskToken(jeton)}")
            "Bearer $jeton"
        }
}
