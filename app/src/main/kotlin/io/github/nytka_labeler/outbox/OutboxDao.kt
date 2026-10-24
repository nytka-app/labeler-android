package io.github.nytka_labeler.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// One statement per use of the queue: a flat list, not a grab bag.
@Suppress("TooManyFunctions")
@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: OutboxRow)

    /** Oldest first, so answers reach the server in the order they were given. */
    @Query("SELECT * FROM outbox WHERE state = 'pending' ORDER BY createdAt, rowid")
    suspend fun pending(): List<OutboxRow>

    /** The pending answers whose undo window has passed. */
    @Query("SELECT * FROM outbox WHERE state = 'pending' AND notBefore <= :now ORDER BY createdAt, rowid")
    suspend fun due(now: Long): List<OutboxRow>

    /** The earliest send time of a pending answer that is not due yet. */
    @Query("SELECT MIN(notBefore) FROM outbox WHERE state = 'pending' AND notBefore > :now")
    suspend fun nextDue(now: Long): Long?

    /** Undo: removes an answer that has not been sent. */
    @Query("DELETE FROM outbox WHERE clientId = :clientId AND state = 'pending'")
    suspend fun cancel(clientId: String): Int

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
