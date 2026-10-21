package com.freease.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class,
        PlayEventEntity::class, SearchQueryEntity::class, ArtistEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FreeaseDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun playlists(): PlaylistDao
    abstract fun history(): HistoryDao
    abstract fun search(): SearchDao
    abstract fun artists(): ArtistDao

    companion object {
        fun build(context: Context): FreeaseDatabase =
            Room.databaseBuilder(context, FreeaseDatabase::class.java, "freease.db")
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
    }
}
