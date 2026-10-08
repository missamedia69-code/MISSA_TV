package com.missa.tv.data.epg

import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.log.Secrets
import com.missa.tv.core.result.AppResult
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.local.EpgCache
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.EpgRepository
import com.missa.tv.domain.repository.PlaylistSourceStore
import kotlinx.coroutines.withContext

/**
 * Guide de programmes téléchargé au format XMLTV.
 *
 * Le guide d'une source est récupéré depuis son adresse `epgUrl`, puis associé
 * aux diffusions du catalogue via leur identifiant `tvgId` : une diffusion et
 * ses variantes partagent le même `tvgId`, aussi chaque programme est-il
 * recopié pour toutes les chaînes qui le déclarent. Les programmes sont
 * mémorisés sous la clé du catalogue, celle-là même que lit l'interface : le
 * guide reste donc cohérent avec la liste affichée, même hors connexion.
 *
 * L'échec d'une source n'empêche pas les autres d'être mises à jour ; le
 * rafraîchissement n'échoue que si aucune n'a abouti.
 */
class XmltvEpgRepository(
    private val sourceStore: PlaylistSourceStore,
    private val catalogRepository: CatalogRepository,
    private val catalogCache: CatalogCache,
    private val downloader: XmltvEpgDownloader,
    private val epgCache: EpgCache,
    private val parser: XmltvParser = XmltvParser(),
    private val dispatchers: DispatcherProvider,
) : EpgRepository {

    override suspend fun refresh(): AppResult<Unit> =
        withContext(dispatchers.io) {
            val sources = sourceStore.sources().filter { it.isComplete && !it.epgUrl.isNullOrBlank() }
            if (sources.isEmpty()) {
                MissaLog.d("Aucune source ne déclare de guide XMLTV")
                return@withContext AppResult.success(Unit)
            }

            val cleCatalogue = catalogRepository.cacheKey()
            val idsParTvg = catalogCache.channels(cleCatalogue)
                .filter { !it.tvgId.isNullOrBlank() }
                .groupBy({ it.tvgId!! }, { it.id })
            if (idsParTvg.isEmpty()) {
                MissaLog.d("Aucune chaîne du catalogue ne déclare d'identifiant tvgId")
                return@withContext AppResult.success(Unit)
            }

            var auMoinsUneReussite = false
            var derniereErreur: AppError? = null
            for (source in sources) {
                when (val resultat = rafraichirSource(source.epgUrl!!, idsParTvg, cleCatalogue)) {
                    is AppResult.Success -> auMoinsUneReussite = true
                    is AppResult.Failure -> derniereErreur = resultat.error
                }
            }

            when {
                auMoinsUneReussite -> AppResult.success(Unit)
                derniereErreur != null -> AppResult.failure(derniereErreur)
                else -> AppResult.success(Unit)
            }
        }

    /** Télécharge, analyse puis mémorise le guide d'une source. */
    private suspend fun rafraichirSource(
        epgUrl: String,
        idsParTvg: Map<String, List<String>>,
        cleCatalogue: String,
    ): AppResult<Unit> {
        val document = when (val telechargement = downloader.download(epgUrl)) {
            is AppResult.Failure -> return telechargement
            is AppResult.Success -> telechargement.value
        }

        val programmes = try {
            parser.parse(document)
        } catch (erreur: XmltvParseException) {
            MissaLog.w("Guide illisible : ${Secrets.maskUrl(epgUrl)}", erreur)
            return AppResult.failure(erreur.toAppError())
        }

        val evenementsParChaine = HashMap<String, MutableList<EpgEvent>>()
        for (programme in programmes) {
            val idsChaines = idsParTvg[programme.channelId] ?: continue
            for (idChaine in idsChaines) {
                evenementsParChaine
                    .getOrPut(idChaine) { mutableListOf() }
                    .add(
                        EpgEvent(
                            id = "$idChaine-${programme.startMs}",
                            channelId = idChaine,
                            title = programme.title,
                            description = programme.description,
                            startMs = programme.startMs,
                            endMs = programme.endMs,
                        ),
                    )
            }
        }

        if (evenementsParChaine.isEmpty()) {
            MissaLog.d("Guide sans programme correspondant au catalogue : ${Secrets.maskUrl(epgUrl)}")
            return AppResult.success(Unit)
        }

        for ((idChaine, evenements) in evenementsParChaine) {
            epgCache.save(cleCatalogue, idChaine, evenements)
        }
        MissaLog.d("Guide mis à jour : ${evenementsParChaine.size} chaînes depuis ${Secrets.maskUrl(epgUrl)}")
        return AppResult.success(Unit)
    }
}
