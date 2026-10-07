package com.missa.tv.data.di

import android.content.Context
import androidx.room.Room
import com.missa.tv.data.local.db.CatalogDao
import com.missa.tv.data.local.db.MissaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Base locale.
 *
 * Une seule instance pour toute l'application : Room sérialise lui-même les accès
 * concurrents, ouvrir plusieurs bases sur le même fichier ne ferait que
 * gaspiller de la mémoire.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MissaDatabase =
        Room.databaseBuilder(context, MissaDatabase::class.java, MissaDatabase.NAME)
            // Cache reconstructible : en cas de changement de schéma, on repart
            // d'une base vide plutôt que d'échouer au démarrage sur l'appareil
            // de l'utilisateur.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun catalogDao(database: MissaDatabase): CatalogDao = database.catalogDao()
}
