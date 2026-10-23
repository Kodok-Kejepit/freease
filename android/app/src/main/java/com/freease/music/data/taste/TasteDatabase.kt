package com.freease.music.data.taste

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** Sinyal per lagu: berapa kali dilewati / diselesaikan / diberi jempol. */
@Entity(tableName = "song_signal")
data class SongSignal(
    @PrimaryKey val songId: String,
    val skips: Int = 0,
    val completes: Int = 0,
    val ups: Int = 0,
    val downs: Int = 0,
    val lastAt: Long = 0,
)

/** Sinyal per artis (kunci sudah dinormalkan, lihat [com.freease.music.playback.smart.Taste.key]). */
@Entity(tableName = "artist_signal")
data class ArtistSignal(
    @PrimaryKey val key: String,
    val skips: Int = 0,
    val completes: Int = 0,
    val ups: Int = 0,
    val downs: Int = 0,
    val lastAt: Long = 0,
)

@Dao
interface TasteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(s: SongSignal): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtist(a: ArtistSignal): Long

    @Query(
        "UPDATE song_signal SET skips=skips+:skip, completes=completes+:complete, ups=ups+:up, downs=downs+:down, lastAt=:at WHERE songId=:id",
    )
    suspend fun bumpSong(id: String, skip: Int, complete: Int, up: Int, down: Int, at: Long)

    @Query(
        "UPDATE artist_signal SET skips=skips+:skip, completes=completes+:complete, ups=ups+:up, downs=downs+:down, lastAt=:at WHERE `key`=:key",
    )
    suspend fun bumpArtist(key: String, skip: Int, complete: Int, up: Int, down: Int, at: Long)

    @Transaction
    suspend fun bump(songId: String, artistKey: String, skip: Int, complete: Int, up: Int, down: Int, at: Long) {
        insertSong(SongSignal(songId))
        insertArtist(ArtistSignal(artistKey))
        bumpSong(songId, skip, complete, up, down, at)
        bumpArtist(artistKey, skip, complete, up, down, at)
    }

    @Query("SELECT * FROM song_signal")
    suspend fun songs(): List<SongSignal>

    @Query("SELECT * FROM artist_signal")
    suspend fun artists(): List<ArtistSignal>

    @Query("DELETE FROM song_signal")
    suspend fun clearSongs()

    @Query("DELETE FROM artist_signal")
    suspend fun clearArtists()
}

/**
 * Database terpisah dari perpustakaan: data belajar Freease Pintar bisa direset tanpa
 * menyentuh lagu/playlist, dan perpustakaan tidak butuh migrasi skema.
 */
@Database(entities = [SongSignal::class, ArtistSignal::class], version = 1, exportSchema = false)
abstract class TasteDatabase : RoomDatabase() {
    abstract fun dao(): TasteDao

    companion object {
        fun build(context: Context): TasteDatabase =
            Room.databaseBuilder(context, TasteDatabase::class.java, "taste.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
