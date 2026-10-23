package io.github.nytka_labeler

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.nytka_labeler.remind.AndroidReminderNotifier
import io.github.nytka_labeler.remind.ReminderWorker
import io.github.nytka_labeler.settings.SettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LabelerApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settings: SettingsSource

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        AndroidReminderNotifier.createChannel(this)
        // KEEP makes this a no-op when the job is already queued.
        CoroutineScope(Dispatchers.Default).launch {
            ReminderWorker.apply(this@LabelerApp, settings.current().remindersEnabled)
        }
    }
}
