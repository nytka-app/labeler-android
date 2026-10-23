package io.github.nytka_labeler.session

import java.io.File

/**
 * The clips of the sessions on this phone, under the app's private no-backup storage. A session folder holds
 * `<itemId>_<context>.ogg`. It is deleted when the session has ended and none of its answers is still waiting to
 * be sent; a parked (refused) answer does not hold it, since that answer will not be sent.
 */
class ClipStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun folder(sessionId: String): File = File(root, safe(sessionId)).also { it.mkdirs() }

    fun file(
        sessionId: String,
        itemId: Long,
        context: Int,
    ): File = File(folder(sessionId), "${itemId}_$context.ogg")

    /** The session was left or finished; its folder goes once nothing is pending. */
    fun markEnded(sessionId: String) {
        File(folder(sessionId), ENDED).writeText("")
    }

    fun exists(sessionId: String) = File(root, safe(sessionId)).isDirectory

    suspend fun sweep(pendingCount: suspend (sessionId: String) -> Int) {
        root.listFiles { f -> f.isDirectory }?.forEach { folder ->
            val ended = File(folder, ENDED)
            val abandoned = !ended.exists() && now() - folder.lastModified() > ABANDONED_MS
            if ((ended.exists() || abandoned) && pendingCount(folder.name) == 0) folder.deleteRecursively()
        }
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    // Session ids are UUIDs from the server; keep a stray character out of a path.
    private fun safe(sessionId: String) = sessionId.filter { it.isLetterOrDigit() || it == '-' }

    private companion object {
        const val ENDED = ".ended"
        const val ABANDONED_MS = 24L * 60 * 60 * 1000
    }
}
