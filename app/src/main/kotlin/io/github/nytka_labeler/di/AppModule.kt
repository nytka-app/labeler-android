package io.github.nytka_labeler.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.nytka_labeler.api.LabelingApi
import io.github.nytka_labeler.api.LabelingClient
import io.github.nytka_labeler.outbox.LabelDatabase
import io.github.nytka_labeler.outbox.Outbox
import io.github.nytka_labeler.outbox.OutboxSender
import io.github.nytka_labeler.outbox.SendScheduler
import io.github.nytka_labeler.outbox.WorkManagerSendScheduler
import io.github.nytka_labeler.play.AndroidHeadphoneMonitor
import io.github.nytka_labeler.play.ClipPlayer
import io.github.nytka_labeler.play.ExoClipPlayer
import io.github.nytka_labeler.play.HeadphoneMonitor
import io.github.nytka_labeler.remind.AndroidReminderNotifier
import io.github.nytka_labeler.remind.Reminder
import io.github.nytka_labeler.remind.ReminderControl
import io.github.nytka_labeler.remind.ReminderNotifier
import io.github.nytka_labeler.remind.WorkReminderControl
import io.github.nytka_labeler.session.ClipStore
import io.github.nytka_labeler.session.SessionRepository
import io.github.nytka_labeler.session.SessionSource
import io.github.nytka_labeler.settings.SettingsSource
import io.github.nytka_labeler.settings.SettingsStore
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Suppress("TooManyFunctions")
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): LabelDatabase = LabelDatabase.open(context)

    @Provides
    @Singleton
    fun settingsStore(
        @ApplicationContext context: Context,
    ): SettingsStore = SettingsStore.create(context)

    @Provides
    fun settingsSource(store: SettingsStore): SettingsSource = store

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // A redirect could carry the token or audio to plain http or another host, past the ServerUrl check.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    @Provides
    @Singleton
    fun labelingApi(
        client: OkHttpClient,
        settings: SettingsSource,
    ): LabelingApi = LabelingApi(client) { settings.current() }

    @Provides
    fun labelingClient(api: LabelingApi): LabelingClient = api

    @Provides
    @Singleton
    fun clipStore(
        @ApplicationContext context: Context,
    ): ClipStore = ClipStore(File(context.noBackupFilesDir, "clips"))

    @Provides
    @Singleton
    fun outbox(database: LabelDatabase): Outbox = Outbox(database.outbox())

    @Provides
    fun sessionSource(
        client: LabelingClient,
        clips: ClipStore,
    ): SessionSource = SessionRepository(client, clips)

    @Provides
    fun sendScheduler(
        @ApplicationContext context: Context,
    ): SendScheduler = WorkManagerSendScheduler(context)

    @Provides
    fun outboxSender(
        outbox: Outbox,
        client: LabelingClient,
        settings: SettingsSource,
        clips: ClipStore,
    ): OutboxSender = OutboxSender(outbox, client, settings) { clips.sweep { outbox.pendingCount(it) } }

    @Provides
    @Singleton
    fun clipPlayer(
        @ApplicationContext context: Context,
    ): ClipPlayer = ExoClipPlayer(context)

    @Provides
    @Singleton
    fun headphones(
        @ApplicationContext context: Context,
    ): HeadphoneMonitor = AndroidHeadphoneMonitor(context)

    @Provides
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides
    fun reminderNotifier(
        @ApplicationContext context: Context,
    ): ReminderNotifier = AndroidReminderNotifier(context)

    @Provides
    fun reminder(
        client: LabelingClient,
        settings: SettingsSource,
        notifier: ReminderNotifier,
        clock: Clock,
    ): Reminder = Reminder(client, settings, notifier, clock)

    @Provides
    fun reminderControl(
        @ApplicationContext context: Context,
    ): ReminderControl = WorkReminderControl(context)
}
