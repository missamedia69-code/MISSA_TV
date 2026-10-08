package com.missa.tv.data.remote.config

import com.missa.tv.core.json.array
import com.missa.tv.core.json.asIntOrNull
import com.missa.tv.core.json.asObjectOrNull
import com.missa.tv.core.json.boolean
import com.missa.tv.core.json.int
import com.missa.tv.core.json.obj
import com.missa.tv.core.json.string
import com.missa.tv.core.log.MissaLog
import com.missa.tv.domain.model.BandwidthSettings
import com.missa.tv.domain.model.BufferSettings
import com.missa.tv.domain.model.PlaylistSource
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.domain.model.RemoteConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Lecture du fichier `portal-config.json` publié dans le dépôt privé de
 * configuration (schéma v2).
 *
 * Un document reçu du réseau n'est jamais digne de confiance : chaque champ est
 * relu avec les mêmes règles que le schéma JSON du dépôt, et les valeurs
 * aberrantes sont écartées plutôt qu'appliquées. Une configuration refusée n'a
 * aucune conséquence : l'application conserve la précédente.
 */
class PortalConfigParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /**
     * Analyse une configuration.
     *
     * @return la configuration lue, ou `null` si elle doit être refusée en bloc
     *   (JSON illisible ou version de schéma inconnue).
     */
    fun parse(document: String): RemoteConfig? {
        val racine = try {
            json.parseToJsonElement(document).asObjectOrNull()
        } catch (erreur: SerializationException) {
            MissaLog.w("Configuration distante : JSON illisible", erreur)
            null
        } ?: return null

        val schemaVersion = racine.int("schemaVersion")
        if (schemaVersion != RemoteConfig.SUPPORTED_SCHEMA_VERSION) {
            // Une version de schéma différente signifie que le format a changé :
            // l'appliquer à l'aveugle pourrait produire des réglages absurdes.
            MissaLog.w(
                "Configuration distante ignorée : schéma $schemaVersion non pris en charge",
            )
            return null
        }

        return RemoteConfig(
            schemaVersion = schemaVersion,
            updatedAt = racine.string("updatedAt"),
            minAppVersion = racine.int("minAppVersion") ?: 0,
            defaultPlaylistId = racine.string("defaultPlaylistId"),
            playlists = lirePlaylists(racine),
            bandwidth = lireDebit(racine.obj("bandwidth")),
        )
    }

    /** Sources de playlists ; les entrées incomplètes sont écartées. */
    private fun lirePlaylists(racine: JsonObject): List<PlaylistSource> =
        racine.array("playlists").mapNotNull { element ->
            val objet = element.asObjectOrNull() ?: return@mapNotNull null
            val id = objet.string("id")?.takeIf { ID_VALIDE.matches(it) } ?: return@mapNotNull null
            val nom = objet.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = objet.string("url")?.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val epgUrl = objet.string("epgUrl")?.takeIf { it.startsWith("http") }

            PlaylistSource(
                id = id,
                name = nom,
                url = url,
                epgUrl = epgUrl,
                enabled = objet.boolean("enabled") ?: true,
            )
        }.take(MAX_PLAYLISTS)

    /** Réglages de débit, avec contrôle des bornes. */
    private fun lireDebit(bloc: JsonObject?): BandwidthSettings {
        if (bloc == null) return BandwidthSettings()

        val seuil = bloc.int("lowBandwidthThresholdKbps")
        val mode = QualityMode.fromNameOrNull(bloc.string("defaultMode"))

        return BandwidthSettings(
            lowBandwidthThresholdKbps = seuil?.takeIf { it in MIN_THRESHOLD_KBPS..MAX_THRESHOLD_KBPS }
                ?: BandwidthSettings.DEFAULT_LOW_THRESHOLD_KBPS,
            defaultMode = mode ?: QualityMode.DEFAULT,
            maxVideoHeightByMode = lireEntiers(bloc.obj("maxVideoHeightByMode"), MIN_HEIGHT, MAX_HEIGHT),
            maxVideoBitrateByMode = lireEntiers(
                bloc.obj("maxVideoBitrateByMode"),
                MIN_BITRATE,
                MAX_BITRATE,
            ),
            buffer = lireTampon(bloc.obj("buffer")),
        )
    }

    /**
     * Table mode → valeur entière.
     *
     * Les modes inconnus et les valeurs hors bornes sont ignorés : la valeur par
     * défaut du modèle reste alors appliquée. `0` est accepté, il signifie
     * « aucune limite » (mode audio seul ou qualité maximale).
     */
    private fun lireEntiers(bloc: JsonObject?, min: Int, max: Int): Map<QualityMode, Int> {
        if (bloc == null) return emptyMap()
        return buildMap {
            for ((cle, valeur) in bloc) {
                val mode = QualityMode.fromNameOrNull(cle) ?: continue
                val nombre = valeur.asIntOrNull() ?: continue
                if (nombre == 0 || nombre in min..max) put(mode, nombre)
            }
        }
    }

    /**
     * Réglages du tampon.
     *
     * Un tampon incohérent (maximum inférieur au minimum) ferait saccader la
     * lecture : dans ce cas, l'intégralité du bloc est remplacée par les valeurs
     * par défaut plutôt que d'appliquer un réglage douteux.
     */
    private fun lireTampon(bloc: JsonObject?): BufferSettings {
        if (bloc == null) return BufferSettings()

        val min = bloc.int("minMs") ?: return BufferSettings()
        val max = bloc.int("maxMs") ?: return BufferSettings()
        val playback = bloc.int("playbackMs") ?: return BufferSettings()
        val afterRebuffer = bloc.int("afterRebufferMs") ?: return BufferSettings()

        val coherent = min in MIN_BUFFER_MS..MAX_BUFFER_MS &&
            max in MIN_BUFFER_MS..MAX_BUFFER_MS &&
            playback in 0..MAX_BUFFER_MS &&
            afterRebuffer in 0..MAX_BUFFER_MS &&
            max >= min

        if (!coherent) {
            MissaLog.w("Configuration distante : réglages de tampon incohérents, ignorés")
            return BufferSettings()
        }

        return BufferSettings(
            minMs = min,
            maxMs = max,
            playbackMs = playback,
            afterRebufferMs = afterRebuffer,
        )
    }

    private companion object {
        /** Doit correspondre au schéma : `^[A-Za-z0-9_-]+$`. */
        val ID_VALIDE = Regex("^[A-Za-z0-9_-]+$")

        const val MAX_PLAYLISTS = 16
        const val MIN_THRESHOLD_KBPS = 64
        const val MAX_THRESHOLD_KBPS = 100_000
        const val MIN_HEIGHT = 144
        const val MAX_HEIGHT = 4_320
        const val MIN_BITRATE = 50_000
        const val MAX_BITRATE = 120_000_000
        const val MIN_BUFFER_MS = 1_000
        const val MAX_BUFFER_MS = 120_000
    }
}
