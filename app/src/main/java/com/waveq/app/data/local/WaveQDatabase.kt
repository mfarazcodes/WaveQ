package com.waveq.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Incident store. Deliberately separate from the mesh MessageStore database:
 * the two have different lifetimes and eviction rules - mesh envelopes expire
 * within hours, incident reports are a record that should persist.
 */
@Database(entities = [IncidentEntity::class], version = 2, exportSchema = true)
abstract class WaveQDatabase : RoomDatabase() {
    abstract fun incidentDao(): IncidentDao

    companion object {
        @Volatile
        private var INSTANCE: WaveQDatabase? = null

        fun getDatabase(context: Context): WaveQDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WaveQDatabase::class.java,
                    "waveq_incidents",
                )
                    // A real migration, and no destructive fallback. Incident
                    // reports are the record of what people reported; dropping
                    // every one of them on a schema bump - which is what
                    // fallbackToDestructiveMigration() did - is not an
                    // acceptable upgrade path for them.
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }

        /** Adds operator review columns: who confirmed or dismissed a report, when, and which. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE incidents ADD COLUMN verifiedBy TEXT")
                db.execSQL("ALTER TABLE incidents ADD COLUMN verifiedAtMillis INTEGER")
                db.execSQL("ALTER TABLE incidents ADD COLUMN isRejected INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
