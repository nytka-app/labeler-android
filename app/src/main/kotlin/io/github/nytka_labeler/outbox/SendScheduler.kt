package io.github.nytka_labeler.outbox

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Asks for a send run; a fake in tests. */
fun interface SendScheduler {
    fun schedule()
}

class WorkManagerSendScheduler(
    private val context: Context,
) : SendScheduler {
    override fun schedule() {
        val request =
            OneTimeWorkRequestBuilder<SendWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        // KEEP: a run already queued will also send what was added after it was queued.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val UNIQUE_NAME = "outbox-send"
        private const val BACKOFF_SECONDS = 30L
    }
}

@HiltWorker
class SendWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sender: OutboxSender,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            while (true) {
                when (sender.drain()) {
                    DrainResult.Done -> delay(sender.millisToNextDue() ?: return Result.success())
                    DrainResult.Retry -> return Result.retry()
                    // Retrying cannot help until the owner saves a new token, which schedules a run.
                    DrainResult.TokenRefused -> return Result.success()
                }
            }
        }
    }
