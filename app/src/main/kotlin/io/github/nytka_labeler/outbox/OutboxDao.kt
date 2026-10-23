package io.github.nytka_labeler.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: OutboxRow)

    /** Oldest first, so answers reach the server in the order they were given. */
    @Query("SELECT * FROM outbox WHERE state = 'pending' ORDER BY createdAt, rowid")
    suspend fun pending(): List<OutboxRow>

    @Query("SELECT * FROM outbox ORDER BY createdAt, rowid")
    fun observeAll(): Flow<List<OutboxRow>>

    @Query("SELECT * FROM outbox ORDER BY createdAt, rowid")
    suspend fun all(): List<OutboxRow>

    @Query("DELETE FROM outbox WHERE clientId = :clientId")
    suspend fun delete(clientId: String)

    /** A new answer to an item replaces its unsent one; a parked one stays for the owner to see. */
    @Query("DELETE FROM outbox WHERE itemId = :itemId AND state = 'pending'")
    suspend fun deletePendingForItem(itemId: Long)

    @Query("DELETE FROM outbox WHERE clientId = :clientId AND state = 'parked'")
    suspend fun dismissParked(clientId: String)

    @Query("UPDATE outbox SET state = 'parked', error = :error WHERE clientId = :clientId")
    suspend fun park(
        clientId: String,
        error: String,
    )

    @Query("SELECT COUNT(*) FROM outbox WHERE sessionId = :sessionId AND state = 'pending'")
    suspend fun pendingCount(sessionId: String): Int
}
