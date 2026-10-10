package com.missa.tv.data.remote.catalog

import com.missa.tv.core.log.MissaLog
import com.missa.tv.data.remote.config.GitHubContentsApi
import com.missa.tv.data.remote.config.RemoteConfigSource
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okio.ByteString.Companion.decodeBase64

/** Source du catalogue testé : le document reçu, ou `null` si indisponible. */
interface TestedCatalogSource {
    suspend fun fetch(): String?
}

/**
 * Téléchargement du catalogue des chaînes testées (`catalog.json`), publié à la
 * racine du même dépôt privé que la configuration, par le workflow de test.
 *
 * Le dépôt est privé : le jeton en lecture seule de la configuration est réutilisé.
 * Toute absence du fichier est un cas normal (workflow pas encore exécuté) :
 * elle est journalisée en discret et l'application se rabat sur les playlists.
 */
@Singleton
class GitHubCatalogDataSource @Inject constructor(
    private val api: GitHubContentsApi,
) : TestedCatalogSource {

    override suspend fun fetch(): String? = try {
        val reponse = api.contents(
            owner = RemoteConfigSource.owner,
            repo = RemoteConfigSource.repo,
            path = CHEMIN_CATALOGUE,
            authorization = RemoteConfigSource.authorization,
            ref = RemoteConfigSource.ref,
        )
        when {
            reponse.isSuccessful -> {
                val corps = reponse.body()
                var document = corps?.content?.decodeBase64()?.utf8()
                if (document.isNullOrBlank() && corps?.sha != null) {
                    // Au-delà d'un mébioctet, l'API Contents renvoie un contenu
                    // vide : le catalogue complet est alors lu par l'API Git
                    // Blobs, qui n'a pas cette limite.
                    MissaLog.d("Catalogue testé : ${corps.size} octets, lecture via l'API blob")
                    document = api.blob(
                        owner = RemoteConfigSource.owner,
                        repo = RemoteConfigSource.repo,
                        sha = corps.sha,
                        authorization = RemoteConfigSource.authorization,
                    ).body()?.content?.decodeBase64()?.utf8()
                }
                if (document.isNullOrBlank()) {
                    MissaLog.w("Catalogue testé : réponse sans contenu")
                    null
                } else {
                    document
                }
            }

            reponse.code() == HTTP_NOT_FOUND -> {
                MissaLog.d("Catalogue testé : fichier absent du dépôt de configuration")
                null
            }

            else -> {
                MissaLog.w("Catalogue testé : réponse ${reponse.code()} de GitHub")
                null
            }
        }
    } catch (erreur: IOException) {
        MissaLog.d("Catalogue testé : GitHub injoignable (${erreur.javaClass.simpleName})")
        null
    } catch (erreur: Exception) {
        MissaLog.w("Catalogue testé : échec inattendu", erreur)
        null
    }

    private companion object {
        const val CHEMIN_CATALOGUE = "catalog.json"
        const val HTTP_NOT_FOUND = 404
    }
}
