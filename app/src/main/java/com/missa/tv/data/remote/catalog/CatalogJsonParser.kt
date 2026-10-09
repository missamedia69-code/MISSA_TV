package com.missa.tv.data.remote.catalog

import com.missa.tv.core.json.array
import com.missa.tv.core.json.asObjectOrNull
import com.missa.tv.core.json.int
import com.missa.tv.core.json.string
import com.missa.tv.core.log.MissaLog
import com.missa.tv.domain.model.Catalog
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.Channel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Lecture du fichier `catalog.json` publié par le workflow de test des chaînes
 * du dépôt de configuration.
 *
 * Ce catalogue ne contient que les chaînes qui ont répondu au test : c'est lui
 * qui permet de ne fournir à l'utilisateur que les flux fonctionnels, déjà
 * classés par groupe et par pays. Comme pour la configuration, un document reçu
 * du réseau n'est jamais digne de confiance : schéma inconnu ou JSON illisible
 * sont refusés en bloc, sans conséquence sur le catalogue en place.
 */
class CatalogJsonParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /**
     * Analyse un catalogue.
     *
     * @return le catalogue lu, ou `null` s'il doit être refusé (JSON illisible,
     *   schéma inconnu). Les entrées sans URL de flux sont simplement écartées.
     */
    fun parse(document: String): Catalog? {
        val racine = try {
            json.parseToJsonElement(document).asObjectOrNull()
        } catch (erreur: SerializationException) {
            MissaLog.w("Catalogue testé : JSON illisible", erreur)
            null
        } ?: return null

        if (racine.int("schemaVersion") != SUPPORTED_SCHEMA_VERSION) {
            MissaLog.w("Catalogue testé ignoré : schéma ${racine.int("schemaVersion")} non pris en charge")
            return null
        }

        val chaines = racine.array("channels")
            .mapNotNull { it.asObjectOrNull() }
            .mapIndexedNotNull { indice, objet -> chaine(objet, indice + 1) }

        return Catalog(
            categories = categoriesDe(chaines),
            channels = chaines,
            loadedAtMs = 0L,
        )
    }

    /** Une chaîne sans URL de flux est inexploitable : elle est écartée. */
    private fun chaine(objet: JsonObject, numero: Int): Channel? {
        val url = objet.string("url")?.takeIf { it.contains("://") } ?: return null
        return Channel(
            id = objet.string("id") ?: "catalogue-$numero",
            number = numero,
            name = objet.string("name")?.takeIf { it.isNotBlank() } ?: url,
            streamUrl = url,
            logoUrl = objet.string("logo")?.takeIf { it.isNotBlank() },
            categoryId = objet.string("group")?.takeIf { it.isNotBlank() },
            country = objet.string("country")?.takeIf { it.isNotBlank() },
        )
    }

    /** Catégories déduites des groupes, dans leur ordre d'apparition. */
    private fun categoriesDe(chaines: List<Channel>): List<Category> =
        chaines
            .mapNotNull { it.categoryId }
            .distinct()
            .map { Category(id = it, title = it) }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}
