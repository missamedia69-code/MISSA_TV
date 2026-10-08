package com.missa.tv.data.epg

import com.missa.tv.core.error.AppError
import java.util.Calendar
import java.util.TimeZone

/** Programme brut issu d'un guide XMLTV, avant conversion vers le domaine. */
data class XmltvProgramme(
    /** Identifiant de la chaîne dans le guide (correspond au `tvg-id` des playlists). */
    val channelId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

/** Nature d'un échec d'analyse d'un guide XMLTV. */
enum class XmltvFailure {
    /** Le contenu n'est pas un guide XMLTV exploitable. */
    INVALID,

    /** Le guide est bien formé mais ne contient aucun programme exploitable. */
    EMPTY,

    /** Le guide dépasse le nombre maximal de programmes accepté. */
    TOO_LARGE,
}

/**
 * Échec d'analyse d'un guide XMLTV.
 *
 * Converti en [AppError] avant de remonter à l'interface ; le message technique
 * ne contient jamais l'adresse du guide, qui est un identifiant sensible.
 */
class XmltvParseException(
    val failure: XmltvFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    fun toAppError(): AppError = when (failure) {
        XmltvFailure.INVALID -> AppError.EpgInvalid
        XmltvFailure.EMPTY -> AppError.EpgEmpty
        XmltvFailure.TOO_LARGE -> AppError.EpgTooLarge
    }
}

/**
 * Analyseur de guides de programmes au format XMLTV.
 *
 * L'analyse parcourt le document sans le charger en arbre : seuls le programme
 * en cours et son titre/description sont conservés en mémoire. Le parseur est
 * tolérant aux variantes courantes (déclaration XML, commentaires, sections
 * CDATA, attributs entre guillemets simples ou doubles, balises auto-fermantes)
 * et n'exige que ce qui fait un programme exploitable : un identifiant de
 * chaîne, un titre et deux horodatages lisibles.
 *
 * Le nombre de programmes est borné par [maxProgrammes] : au-delà, l'analyse
 * s'interrompt et signale [XmltvFailure.TOO_LARGE], pour protéger la mémoire sur
 * des guides très volumineux.
 */
class XmltvParser(
    private val maxProgrammes: Int = MAX_PROGRAMMES,
) {

    /**
     * Analyse un document XMLTV et renvoie les programmes exploitables.
     *
     * @throws XmltvParseException si le contenu n'est pas du XMLTV
     * ([XmltvFailure.INVALID]), s'il ne contient aucun programme
     * ([XmltvFailure.EMPTY]) ou s'il dépasse [maxProgrammes] programmes
     * ([XmltvFailure.TOO_LARGE]).
     */
    fun parse(document: CharSequence): List<XmltvProgramme> {
        val programmes = ArrayList<XmltvProgramme>()
        var vuRacine = false

        var i = 0
        val n = document.length
        while (i < n) {
            if (document[i] != '<') {
                i++
                continue
            }

            // Délimite la fin de la balise, en respectant CDATA et commentaires.
            if (document.startsWith("<!--", i)) {
                i = document.indexOf("-->", i)
                i = if (i < 0) n else i + 3
                continue
            }
            if (document.startsWith("<![CDATA[", i)) {
                i = document.indexOf("]]>", i)
                i = if (i < 0) n else i + 3
                continue
            }

            val fin = document.indexOf('>', i)
            if (fin < 0) break
            val balise = document.substring(i + 1, fin).trim()
            i = fin + 1

            if (balise.isEmpty()) continue
            if (balise.startsWith("?") || balise.startsWith("!")) continue

            if (balise.startsWith("/")) {
                // Balise fermante : rien à faire, la pile est implicite ici.
                continue
            }

            val (nom, attributs, autoFermeture) = lireBalise(balise)
            if (nom.equals("tv", ignoreCase = true)) vuRacine = true

            if (!nom.equals("programme", ignoreCase = true) || autoFermeture) continue

            val channelId = attributs["channel"]?.takeIf { it.isNotBlank() } ?: continue
            val startMs = dateEnMillisecondes(attributs["start"]) ?: continue
            val endMs = dateEnMillisecondes(attributs["stop"]) ?: continue
            if (endMs <= startMs) continue

            // Lit le contenu du programme jusqu'à sa balise fermante.
            val (titre, description, apres) = lireContenuProgramme(document, i)
            i = apres

            if (titre.isNullOrBlank()) continue

            if (programmes.size >= maxProgrammes) {
                throw XmltvParseException(
                    XmltvFailure.TOO_LARGE,
                    "Guide au-delà du nombre maximal de programmes",
                )
            }
            programmes += XmltvProgramme(
                channelId = channelId,
                title = titre.trim(),
                description = description?.takeIf { it.isNotBlank() }?.trim(),
                startMs = startMs,
                endMs = endMs,
            )
        }

        if (programmes.isEmpty()) {
            if (!vuRacine) {
                throw XmltvParseException(
                    XmltvFailure.INVALID,
                    "Contenu non reconnu comme guide XMLTV",
                )
            }
            throw XmltvParseException(
                XmltvFailure.EMPTY,
                "Guide sans aucun programme exploitable",
            )
        }
        return programmes
    }

    /**
     * Lit le nom, les attributs et l'auto-fermeture d'une balise ouvrante.
     */
    private fun lireBalise(balise: String): Triple<String, Map<String, String>, Boolean> {
        val corps = if (balise.endsWith("/")) balise.dropLast(1).trimEnd() else balise
        val autoFermeture = balise.endsWith("/")

        var debutNom = 0
        while (debutNom < corps.length && corps[debutNom].isWhitespace()) debutNom++
        var finNom = debutNom
        while (finNom < corps.length && !corps[finNom].isWhitespace()) finNom++
        val nom = corps.substring(debutNom, finNom)

        val attributs = HashMap<String, String>()
        var i = finNom
        while (i < corps.length) {
            while (i < corps.length && corps[i].isWhitespace()) i++
            val egal = corps.indexOf('=', i)
            if (egal < 0) break
            val cle = corps.substring(i, egal).trim().lowercase()
            var j = egal + 1
            while (j < corps.length && corps[j].isWhitespace()) j++
            if (j >= corps.length) break
            val delimiteur = corps[j]
            if (delimiteur != '"' && delimiteur != '\'') break
            val finValeur = corps.indexOf(delimiteur, j + 1)
            if (finValeur < 0) break
            attributs[cle] = decoderEntites(corps.substring(j + 1, finValeur))
            i = finValeur + 1
        }

        return Triple(nom, attributs, autoFermeture)
    }

    /**
     * Lit le contenu d'un `<programme>` jusqu'à sa balise fermante et renvoie
     * le titre, la description et l'indice où reprendre la lecture.
     */
    private fun lireContenuProgramme(
        document: CharSequence,
        depart: Int,
    ): Triple<String?, String?, Int> {
        var titre: String? = null
        var description: String? = null
        var i = depart
        val n = document.length

        while (i < n) {
            val ouverture = document.indexOf('<', i)
            if (ouverture < 0) break

            val fin = document.indexOf('>', ouverture)
            if (fin < 0) break
            val balise = document.substring(ouverture + 1, fin).trim()
            i = fin + 1

            if (balise.startsWith("/")) {
                if (balise.startsWith("/programme", ignoreCase = true)) break
                continue
            }
            if (balise.startsWith("?") || balise.startsWith("!")) continue

            val (nom, _, autoFermeture) = lireBalise(balise)
            if (autoFermeture) continue

            // Le texte d'un élément feuille s'arrête à la balise suivante ; les
            // éléments imbriqués (crédits, catégorie…) sont ainsi traversés sans
            // être confondus avec du texte.
            val prochain = document.indexOf('<', i)
            val texte = if (prochain < 0) document.substring(i) else document.substring(i, prochain)
            i = if (prochain < 0) n else prochain

            when {
                nom.equals("title", ignoreCase = true) && titre == null ->
                    titre = decoderEntites(texte.trim())
                nom.equals("desc", ignoreCase = true) && description == null ->
                    description = decoderEntites(texte.trim())
            }
        }

        return Triple(titre, description, i)
    }

    /**
     * Convertit une date XMLTV (`AAAAMMJJHHMMSS ±HHMM`) en millisecondes depuis
     * l'époque Unix. Renvoie `null` si la date est absente ou illisible.
     */
    internal fun dateEnMillisecondes(valeur: String?): Long? {
        if (valeur.isNullOrBlank()) return null

        val morceaux = valeur.trim().split(Regex("\\s+"))
        val chiffres = morceaux[0]
        if (chiffres.length < 14 || !chiffres.all { it.isDigit() }) return null

        val annee = chiffres.substring(0, 4).toInt()
        val mois = chiffres.substring(4, 6).toInt()
        val jour = chiffres.substring(6, 8).toInt()
        val heure = chiffres.substring(8, 10).toInt()
        val minute = chiffres.substring(10, 12).toInt()
        val seconde = chiffres.substring(12, 14).toInt()

        // Décalage horaire en millisecondes ; absent = UTC.
        var decalageMs = 0L
        if (morceaux.size > 1) {
            val signe = when (morceaux[1].first()) {
                '+' -> 1
                '-' -> -1
                else -> return null
            }
            val zzzz = morceaux[1].drop(1)
            if (zzzz.length == 4 && zzzz.all { it.isDigit() }) {
                val heures = zzzz.substring(0, 2).toInt()
                val minutes = zzzz.substring(2, 4).toInt()
                decalageMs = signe * (heures * 3_600_000L + minutes * 60_000L)
            }
        }

        val calendrier = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
        calendrier.clear()
        calendrier.set(annee, mois - 1, jour, heure, minute, seconde)

        // L'horodatage est une heure locale accompagnée de son décalage :
        // l'instant UTC s'obtient en retranchant ce décalage.
        return calendrier.timeInMillis - decalageMs
    }

    /**
     * Décode les entités XML : numériques (`&#233;`, `&#x00E9;`) puis nommées.
     * `&amp;` est décodé en dernier pour ne pas dédoubler une entité littérale.
     */
    private fun decoderEntites(texte: String): String {
        val sansNumeriques = ENTITE_NUMERIQUE.replace(texte) { correspondance ->
            val hexadecimale = correspondance.groupValues[1].equals("x", ignoreCase = true)
            val base = if (hexadecimale) 16 else 10
            val codePoint = correspondance.groupValues[2].toIntOrNull(base)
            codePoint?.toChar()?.toString() ?: correspondance.value
        }
        return sansNumeriques
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    companion object {
        /** Nombre maximal de programmes acceptés dans un guide. */
        const val MAX_PROGRAMMES = 50_000

        /** Entité numérique `&#233;` ou hexadécimale `&#x00E9;`. */
        private val ENTITE_NUMERIQUE = Regex("&#(x?)([0-9a-fA-F]+);")
    }
}
