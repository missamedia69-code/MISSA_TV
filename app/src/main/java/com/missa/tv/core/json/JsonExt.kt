package com.missa.tv.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Lecture tolérante d'un document JSON.
 *
 * Les portails Stalker ne respectent pas de contrat strict : le même champ peut
 * arriver tantôt sous forme de chaîne, tantôt de nombre (`"number": "3"` ou
 * `"number": 3`), et un champ absent est fréquent. Ces fonctions évitent donc de
 * faire échouer tout le chargement des chaînes à cause d'une seule valeur
 * inattendue.
 */

/** Chaîne, que la valeur JSON soit textuelle ou numérique. */
fun JsonElement?.asStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive === JsonNull) return null
    return primitive.content.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
}

/** Entier, en acceptant les valeurs écrites entre guillemets ou décimales. */
fun JsonElement?.asIntOrNull(): Int? =
    asStringOrNull()?.let { text -> text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt() }

/** Booléen, en acceptant `0`/`1` et `"true"`/`"false"`. */
fun JsonElement?.asBooleanOrNull(): Boolean? = when (val text = asStringOrNull()?.lowercase()) {
    null -> null
    "1", "true", "vrai", "yes" -> true
    "0", "false", "faux", "no" -> false
    else -> null
}

fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

/** Champ textuel d'un objet JSON. */
fun JsonObject?.string(field: String): String? = this?.get(field).asStringOrNull()

/** Champ entier d'un objet JSON. */
fun JsonObject?.int(field: String): Int? = this?.get(field).asIntOrNull()

/** Champ booléen d'un objet JSON. */
fun JsonObject?.boolean(field: String): Boolean? = this?.get(field).asBooleanOrNull()

/** Objet imbriqué, ou `null` s'il est absent. */
fun JsonObject?.obj(field: String): JsonObject? = this?.get(field).asObjectOrNull()

/** Tableau imbriqué, ou une liste vide s'il est absent. */
fun JsonObject?.array(field: String): JsonArray = this?.get(field).asArrayOrNull() ?: JsonArray(emptyList())

/** Représentation textuelle utilisée par les messages d'erreur (jamais journalisée telle quelle). */
fun JsonElement?.rawOrEmpty(): String = this?.let { element ->
    (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
}.orEmpty()
