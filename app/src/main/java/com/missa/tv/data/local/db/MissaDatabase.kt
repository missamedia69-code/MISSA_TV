package com.missa.tv.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base locale de l'application.
 *
 * Elle ne contient que le catalogue de chaînes et son guide : aucune adresse de
 * playlist, aucun jeton. Ces éléments-là vivent dans le stockage chiffré
 * (DataStore chiffré, voir `EncryptedPlaylistSourceStore`), et la base peut donc
 * être effacée par l'utilisateur sans conséquence sur sa configuration.
 *
 * Le schéma n'est pas exporté : le catalogue est un cache reconstructible, une
 * migration destructive reste acceptable — il se reconstruit au prochain
 * chargement réussi.
 *
 * Historique des versions :
 *  - 1 : catalogue (catégories, chaînes) ;
 *  - 2 : ajout du guide électronique (`epg_events`) ;
 *  - 3 : chaîne lue par `streamUrl` (+`tvgId`, `userAgent`, `referrer`).
 */
@Database(
    entities = [CategoryEntity::class, ChannelEntity::class, EpgEventEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class MissaDatabase : RoomDatabase() {

    abstract fun catalogDao(): CatalogDao

    abstract fun epgDao(): EpgDao

    companion object {
        /** Nom du fichier de base, dans le stockage privé de l'application. */
        const val NAME: String = "missa_tv.db"
    }
}
