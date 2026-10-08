package com.missa.tv.data.epg

import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.log.Secrets
import com.missa.tv.core.result.AppResult
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Téléchargement d'un guide XMLTV.
 *
 * Couture injectable : l'implémentation réelle passe par OkHttp, mais les tests
 * fournissent une doublure qui renvoie un document en mémoire, sans réseau.
 * Aucune adresse de guide n'apparaît en clair dans les journaux.
 */
interface XmltvEpgDownloader {

    /** Renvoie le document XMLTV, ou une erreur si la lecture échoue ou dépasse la borne. */
    suspend fun download(url: String): AppResult<String>
}

/**
 * Téléchargement d'un guide via OkHttp, borné en taille.
 *
 * Le guide est lu en flux et la lecture s'interrompt au-delà de [maxBytes] :
 * un guide démesuré ne doit pas saturer la mémoire. Le corps est décodé en
 * UTF-8, l'encodage déclaré par les guides XMLTV.
 */
class OkHttpXmltvEpgDownloader(
    private val client: OkHttpClient,
    private val maxBytes: Long = MAX_BYTES,
) : XmltvEpgDownloader {

    override suspend fun download(url: String): AppResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val requete = Request.Builder().url(url).build()
                client.newCall(requete).execute().use { reponse ->
                    if (!reponse.isSuccessful) {
                        MissaLog.w("Guide refusé par le serveur (${reponse.code}) : ${Secrets.maskUrl(url)}")
                        return@withContext AppResult.failure(AppError.EpgInvalid)
                    }
                    val corps = reponse.body
                    if (corps == null) {
                        return@withContext AppResult.failure(AppError.EpgInvalid)
                    }
                    corps.byteStream().use { flux ->
                        val document = lireBorne(flux, maxBytes)
                            ?: return@withContext AppResult.failure(AppError.EpgTooLarge)
                        AppResult.success(document)
                    }
                }
            } catch (erreur: IOException) {
                MissaLog.w("Guide injoignable : ${Secrets.maskUrl(url)}", erreur)
                AppResult.failure(AppError.NetworkLost)
            } catch (erreur: Exception) {
                MissaLog.w("Échec inattendu du téléchargement du guide", erreur)
                AppResult.failure(AppError.Unknown(erreur))
            }
        }

    /** Lit le flux en UTF-8 ; renvoie `null` s'il dépasse [maxBytes]. */
    private fun lireBorne(flux: InputStream, maxBytes: Long): String? {
        val tampon = ByteArray(TAILLE_TAMPON)
        val sortie = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val lus = flux.read(tampon)
            if (lus < 0) break
            total += lus
            if (total > maxBytes) return null
            sortie.write(tampon, 0, lus)
        }
        return sortie.toString(Charsets.UTF_8.name())
    }

    companion object {
        /** Taille maximale acceptée pour un guide. */
        const val MAX_BYTES = 50L * 1024 * 1024

        private const val TAILLE_TAMPON = 8_192
    }
}
