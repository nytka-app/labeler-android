package io.github.nytka_labeler.remind

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class ReminderWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val reminder: Reminder,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            reminder.check()
            return Result.success()
        }

        companion object {
            private const val UNIQUE_NAME = "reminder"
            private const val EVERY_HOURS = 6L

            /** On: every 6 hours with a network; off: cancelled. KEEP makes a second call a no-op. */
            fun apply(
                context: Context,
                enabled: Boolean,
            ) {
                val work = WorkManager.getInstance(context)
                if (!enabled) {
                    work.cancelUniqueWork(UNIQUE_NAME)
                    return
                }
                val request =
                    PeriodicWorkRequestBuilder<ReminderWorker>(EVERY_HOURS, TimeUnit.HOURS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                work.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
