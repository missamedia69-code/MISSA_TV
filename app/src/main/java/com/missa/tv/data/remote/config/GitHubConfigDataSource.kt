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
import retrofit2.http.Query

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
        // Branche ou étiquette lue. Omis (null) : GitHub sert la branche par
        // défaut du dépôt, qui est la seule à faire foi pour les appareils.
        @Query("ref") ref: String? = null,
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
 * Le dépôt de configuration est PRIVÉ : un jeton en lecture seule est requis et
 * transmis dans l'en-tête `Authorization`. Sans jeton, GitHub refuse la lecture.
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
                ref = RemoteConfigSource.ref,
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

                // 404 : le fichier n'existe pas **sur la branche lue** — cas le
                // plus fréquent : le fichier est publié sur une branche de travail
                // et absent de la branche par défaut. Le message le dit, car la
                // cause est sinon invisible depuis l'application.
                reponse.code() == HTTP_NOT_FOUND -> {
                    MissaLog.w("Configuration distante : fichier absent de la branche lue")
                    ConfigFetchResult.Failed(AppError.ConfigNotFound)
                }

                // 401/403 : jeton invalide ou limite de requêtes atteinte.
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
        const val HTTP_NOT_FOUND = 404
    }
}

/**
 * Emplacement du fichier de configuration.
 *
 * Les valeurs sont injectées à la compilation depuis `local.properties` (voir
 * `app/build.gradle.kts`) : aucun identifiant n'est écrit dans le code source.
 * Le dépôt de configuration est privé : le jeton en lecture seule est donc
 * requis pour lire le fichier, et il ne doit jamais être journalisé en clair.
 */
object RemoteConfigSource {

    /** Dépôt qui héberge le fichier de configuration. */
    val owner: String = BuildConfig.GITHUB_CONFIG_OWNER

    val repo: String = BuildConfig.GITHUB_CONFIG_REPO

    val path: String = BuildConfig.GITHUB_CONFIG_PATH

    /**
     * Branche ou étiquette lue, ou `null` pour la branche par défaut du dépôt.
     *
     * Par défaut, aucune valeur n'est fournie : les appareils lisent donc la
     * branche par défaut, celle qui fait autorité. Forcer une autre branche est
     * un réglage de mise au point, pas un fonctionnement normal.
     */
    val ref: String? = BuildConfig.GITHUB_CONFIG_REF.takeIf { it.isNotBlank() }

    /** En-tête d'autorisation, ou `null` si aucun jeton n'est configuré. */
    val authorization: String? = BuildConfig.GITHUB_CONFIG_TOKEN
        .takeIf { it.isNotBlank() }
        ?.let { jeton ->
            MissaLog.d("Jeton GitHub configuré : ${Secrets.maskToken(jeton)}")
            "Bearer $jeton"
        }
}
