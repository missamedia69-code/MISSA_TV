package com.missa.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.log.MissaLog
import com.missa.tv.domain.model.QualityMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Préférences de lecture de l'utilisateur. */
data class PlaybackPreferences(
    /**
     * Mode de qualité choisi.
     *
     * `null` signifie « aucune préférence exprimée » : c'est alors le mode
     * recommandé par la configuration distante qui s'applique, et l'adaptation
     * automatique qui décide.
     */
    val qualityMode: QualityMode? = null,
    /**
     * Vrai si l'utilisateur a verrouillé son choix : plus aucune dégradation
     * automatique n'a lieu, même en cas de coupures répétées.
     */
    val qualityLocked: Boolean = false,
)

/**
 * Préférences de lecture conservées sur l'appareil.
 *
 * Le mode choisi survit au redémarrage : un utilisateur qui a réglé sa qualité
 * pour son débit n'a pas à le refaire à chaque ouverture de l'application.
 */
interface SettingsStore {

    fun observePlayback(): Flow<PlaybackPreferences>

    suspend fun playback(): PlaybackPreferences

    /** Enregistre le mode choisi. `null` rétablit l'adaptation automatique. */
    suspend fun setQualityMode(mode: QualityMode?, locked: Boolean)
}

@Singleton
class DataStoreSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : SettingsStore {

    private val dataStore: DataStore<Preferences> get() = context.missaSettingsStore

    override fun observePlayback(): Flow<PlaybackPreferences> {
        val source: Flow<Preferences> = dataStore.data.catch { erreur ->
            MissaLog.e("Lecture des préférences impossible, valeurs par défaut utilisées", erreur)
            emit(emptyPreferences())
        }
        return source
            .map { preferences -> preferences.toPreferences() }
            .flowOn(dispatchers.io)
    }

    override suspend fun playback(): PlaybackPreferences = observePlayback().first()

    override suspend fun setQualityMode(mode: QualityMode?, locked: Boolean) {
        withContext(dispatchers.io) {
            dataStore.edit { preferences ->
                if (mode == null) {
                    preferences.remove(KEY_QUALITY_MODE)
                } else {
                    preferences[KEY_QUALITY_MODE] = mode.name
                }
                preferences[KEY_QUALITY_LOCKED] = locked
            }
        }
    }

    private fun Preferences.toPreferences(): PlaybackPreferences = PlaybackPreferences(
        // Un nom de mode inconnu (retour à une version antérieure, fichier
        // modifié) est ignoré : l'adaptation automatique reprend la main.
        qualityMode = QualityMode.fromNameOrNull(this[KEY_QUALITY_MODE]),
        qualityLocked = this[KEY_QUALITY_LOCKED] ?: false,
    )

    private companion object {
        val KEY_QUALITY_MODE = stringPreferencesKey("playback_quality_mode")
        val KEY_QUALITY_LOCKED = booleanPreferencesKey("playback_quality_locked")
    }
}

/** Un seul DataStore pour les préférences, partagé par toute l'application. */
private val Context.missaSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "missa_settings",
)
