package io.github.nytka_labeler.remind

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.nytka_labeler.MainActivity
import io.github.nytka_labeler.R

class AndroidReminderNotifier(
    private val context: Context,
) : ReminderNotifier {
    override fun notifyOpen(open: Int) {
        // Before Android 13 there is no runtime permission.
        val allowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        createChannel(context)
        val tap =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(Reminder.text(open))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, notification)
    }

    companion object {
        const val CHANNEL = "reminders"
        private const val ID = 1

        fun createChannel(context: Context) {
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.channel_reminders),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
        }
    }
}
