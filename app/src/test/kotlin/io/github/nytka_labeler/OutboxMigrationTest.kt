package io.github.nytka_labeler

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.nytka_labeler.outbox.LabelDatabase
import io.github.nytka_labeler.outbox.Outbox
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** An unsent answer cannot be given again: version 1 of the outbox opens as version 2 with its rows intact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = "migrate-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `a queued answer of version 1 survives the migration and is due at once`() {
        val v1 =
            Json
                .parseToJsonElement(File("schemas/io.github.nytka_labeler.outbox.LabelDatabase/1.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        val create =
            v1
                .getValue("entities")
                .jsonArray
                .single()
                .jsonObject
                .getValue("createSql")
                .jsonPrimitive.content
                .replace("\${TABLE_NAME}", "outbox")
        val hash = v1.getValue("identityHash").jsonPrimitive.content
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL(create)
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, '$hash')")
            db.execSQL("INSERT INTO outbox VALUES ('c-1', 7, 's-1', '{}', 'pending', NULL, 123)")
            db.version = 1
        }

        val db =
            Room
                .databaseBuilder(context, LabelDatabase::class.java, name)
                .addMigrations(LabelDatabase.MIGRATION_1_2)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .build()
        try {
            val row = runBlocking { Outbox(db.outbox()).due() }.single()
            assertEquals("c-1", row.clientId)
            assertEquals(7L, row.itemId)
            assertEquals(0L, row.notBefore)
        } finally {
            db.close()
        }
    }
}
