package com.missa.tv.data.playlist

import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.log.Secrets
import com.missa.tv.core.result.AppResult
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Requête de téléchargement d'une playlist. */
data class PlaylistHttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Réponse brute du téléchargement d'une playlist. */
sealed interface PlaylistHttpResponse {

    /** Corps reçu ; le flux [stream] doit être fermé par l'appelant. */
    data class Body(val stream: InputStream) : PlaylistHttpResponse

    /** Le serveur a répondu par un statut d'erreur. */
    data class Refused(val code: Int) : PlaylistHttpResponse
}

/**
 * Accès réseau à une playlist M3U.
 *
 * Couture injectable : l'implémentation réelle passe par OkHttp, mais les
 * tests fournissent une doublure qui renvoie un flux en mémoire, sans réseau.
 */
interface PlaylistHttpFetcher {
    suspend fun fetch(request: PlaylistHttpRequest): PlaylistHttpResponse
}

/** Téléchargement d'une playlist via OkHttp. */
class OkHttpPlaylistFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) : PlaylistHttpFetcher {

    override suspend fun fetch(request: PlaylistHttpRequest): PlaylistHttpResponse =
        withContext(Dispatchers.IO) {
            val requete = Request.Builder()
                .url(request.url)
                .apply { request.headers.forEach { (cle, valeur) -> header(cle, valeur) } }
                .build()

            // execute() lève IOException si la cible est injoignable ; l'exception
            // remonte au téléchargeur, qui la traduit en erreur utilisateur.
            val reponse = client.newCall(requete).execute()
            if (reponse.isSuccessful) {
                val corps = reponse.body
                if (corps == null) {
                    reponse.close()
                    PlaylistHttpResponse.Refused(reponse.code)
                } else {
                    // Le flux reste ouvert : il est lu puis fermé par le téléchargeur.
                    PlaylistHttpResponse.Body(corps.byteStream())
                }
            } else {
                val code = reponse.code
                reponse.close()
                PlaylistHttpResponse.Refused(code)
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
    }
}

/**
 * Télécharge une playlist M3U puis l'analyse.
 *
 * Combine l'accès réseau ([PlaylistHttpFetcher]) et la lecture bornée
 * ([M3uPlaylistReader]) pour produire la liste des entrées, ou une erreur
 * explicite. Aucune URL de playlist n'apparaît en clair dans les journaux :
 * elle passe systématiquement par [Secrets.maskUrl].
 *
 * Correspondance des échecs :
 *  - cible injoignable (réseau) : [AppError.PlaylistUnreachable] ;
 *  - statut HTTP d'erreur : [AppError.PlaylistRejected] ;
 *  - contenu non reconnu, vide ou trop volumineux : erreurs de [M3uFailure].
 */
interface PlaylistDownloader {
    suspend fun download(
        url: String,
        userAgent: String? = null,
        referrer: String? = null,
    ): AppResult<List<M3uEntry>>
}

class M3uPlaylistDownloader(
    private val fetcher: PlaylistHttpFetcher,
    private val reader: M3uPlaylistReader = M3uPlaylistReader(),
) : PlaylistDownloader {

    override suspend fun download(
        url: String,
        userAgent: String?,
        referrer: String?,
    ): AppResult<List<M3uEntry>> {
        val entetes = buildMap {
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }

        return try {
            when (val reponse = fetcher.fetch(PlaylistHttpRequest(url, entetes))) {
                is PlaylistHttpResponse.Refused -> {
                    MissaLog.w(
                        "Playlist refusée par le serveur (${reponse.code}) : " +
                            Secrets.maskUrl(url),
                    )
                    AppResult.failure(AppError.PlaylistRejected)
                }

                is PlaylistHttpResponse.Body -> {
                    reponse.stream.use { flux ->
                        AppResult.success(reader.read(flux))
                    }
                }
            }
        } catch (erreur: M3uParseException) {
            MissaLog.w("Playlist illisible (${erreur.failure})", erreur)
            AppResult.failure(erreur.toAppError())
        } catch (erreur: IOException) {
            MissaLog.w("Playlist injoignable : ${Secrets.maskUrl(url)}", erreur)
            AppResult.failure(AppError.PlaylistUnreachable)
        } catch (erreur: Exception) {
            MissaLog.w("Échec inattendu du téléchargement de playlist", erreur)
            AppResult.failure(AppError.Unknown(erreur))
        }
    }
}
