package io.github.nytka_labeler.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [OutboxRow::class], version = 2, exportSchema = true)
abstract class LabelDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao

    companion object {
        /** Never a destructive fallback: an unsent answer cannot be given again. A schema change adds a migration. */
        fun open(context: Context): LabelDatabase =
            Room
                .databaseBuilder(context, LabelDatabase::class.java, "labeler.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Adds the send-not-before time; answers already queued are due at once. */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE outbox ADD COLUMN notBefore INTEGER NOT NULL DEFAULT 0")
                }
            }
    }
}
