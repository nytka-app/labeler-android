package io.github.nytka_labeler.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [OutboxRow::class], version = 1, exportSchema = true)
abstract class LabelDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao

    companion object {
        /** Never a destructive fallback: an unsent answer cannot be given again. A schema change adds a migration. */
        fun open(context: Context): LabelDatabase =
            Room.databaseBuilder(context, LabelDatabase::class.java, "labeler.db").build()
    }
}
