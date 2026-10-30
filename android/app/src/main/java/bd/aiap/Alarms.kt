package bd.aiap

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import bd.aiap.core.AlarmScheduler
import bd.aiap.core.Reminder
import bd.aiap.core.Repeat
import bd.aiap.core.nextFutureOccurrence
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private const val ACTION_REMINDER = "bd.aiap.REMINDER"
private const val ACTION_BRIEFING = "bd.aiap.BRIEFING"
private const val ACTION_EVENING = "bd.aiap.EVENING"
private const val ACTION_SNOOZE = "bd.aiap.SNOOZE"
private const val ACTION_DONE = "bd.aiap.DONE"
private const val EXTRA_ID = "id"
private const val EXTRA_TEXT = "text"
private const val BRIEFING_REQUEST_CODE = -1
private const val EVENING_REQUEST_CODE = -2
private const val BRIEFING_NOTIFICATION_ID = -1
private const val EVENING_NOTIFICATION_ID = -2
private const val SNOOZE_MINUTES = 10L

/** Schedules reminders and the daily briefing with AlarmManager so they fire even when the app is closed. */
class AndroidAlarms(private val context: Context) : AlarmScheduler {
    private val manager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    fun pendingIntent(action: String, requestCode: Int, id: Long = 0, text: String? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun setAt(millis: Long, intent: PendingIntent, exact: Boolean) {
        try {
            if (exact && canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    override fun schedule(reminder: Reminder) {
        val at = maxOf(reminder.dueAtMillis, System.currentTimeMillis() + 2_000)
        setAt(at, pendingIntent(ACTION_REMINDER, reminder.id.toInt(), reminder.id), exact = true)
    }

    override fun cancel(reminderId: Long) {
        manager.cancel(pendingIntent(ACTION_REMINDER, reminderId.toInt(), reminderId))
    }

    /** Next morning briefing at [time] ("HH:MM"); empty turns it off. */
    fun scheduleBriefing(time: String) = scheduleDaily(time, pendingIntent(ACTION_BRIEFING, BRIEFING_REQUEST_CODE))

    /** Next evening review at [time] ("HH:MM"); empty turns it off. */
    fun scheduleEvening(time: String) = scheduleDaily(time, pendingIntent(ACTION_EVENING, EVENING_REQUEST_CODE))

    private fun scheduleDaily(time: String, intent: PendingIntent) {
        val localTime = runCatching { LocalTime.parse(time) }.getOrNull()
        if (localTime == null) {
            manager.cancel(intent)
            return
        }
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        var next = now.with(localTime).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        setAt(next.toInstant().toEpochMilli(), intent, exact = false)
    }

    /** Alarms are wiped on reboot, so put every active reminder back. */
    fun rescheduleAll() {
        val app = AiApp.from(context)
        app.store.activeReminders().forEach(::schedule)
        scheduleBriefing(app.settings.briefingTime)
        scheduleEvening(app.settings.eveningTime)
    }
}

object Notifications {
    fun show(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        reminderId: Long? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply {
                if (reminderId != null) {
                    val alarms = AiApp.from(context).alarms
                    addAction(0, "✅ হয়ে গেছে", alarms.pendingIntent(ACTION_DONE, id, reminderId))
                    addAction(0, "⏰ $SNOOZE_MINUTES মিনিট পরে", alarms.pendingIntent(ACTION_SNOOZE, id, reminderId, text))
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = AiApp.from(context)
        when (intent.action) {
            ACTION_REMINDER -> {
                val reminder = app.store.getReminder(intent.getLongExtra(EXTRA_ID, -1)) ?: return
                Notifications.show(context, reminder.id.toInt(), AiApp.CHANNEL_REMINDERS, "⏰ রিমাইন্ডার", reminder.text, reminder.id)
                if (reminder.repeat == Repeat.NONE) {
                    app.store.deactivateReminder(reminder.id)
                } else {
                    val next = nextFutureOccurrence(reminder.dueAtMillis, reminder.repeat, ZoneId.systemDefault(), System.currentTimeMillis())
                    app.store.rescheduleReminder(reminder.id, next)
                    app.alarms.schedule(reminder.copy(dueAtMillis = next))
                }
            }
            ACTION_DONE -> {
                NotificationManagerCompat.from(context).cancel(intent.getLongExtra(EXTRA_ID, -1).toInt())
            }
            ACTION_SNOOZE -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                NotificationManagerCompat.from(context).cancel(id.toInt())
                // The reminder itself may already be finished, so the text travels with the button.
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                // A one-off copy, so a repeating reminder keeps its own schedule.
                val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000
                val snoozeId = app.store.addReminder(text, at, Repeat.NONE)
                app.alarms.schedule(Reminder(snoozeId, text, at, Repeat.NONE))
            }
            ACTION_BRIEFING, ACTION_EVENING -> {
                val evening = intent.action == ACTION_EVENING
                val request = OneTimeWorkRequestBuilder<BriefingWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(BriefingWorker.KIND to if (evening) "evening" else "morning"))
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(if (evening) "evening" else "briefing", ExistingWorkPolicy.REPLACE, request)
                if (evening) app.alarms.scheduleEvening(app.settings.eveningTime) else app.alarms.scheduleBriefing(app.settings.briefingTime)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AiApp.from(context).alarms.rescheduleAll()
    }
}

/** Asks the AI for today's plan in the background and posts it as a notification. */
class BriefingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val assistant = AiApp.from(applicationContext).assistant() ?: return Result.success()
        val evening = inputData.getString(KIND) == "evening"
        return try {
            val text = assistant.briefing(if (evening) "evening" else "morning")
            if (text.isNotBlank()) {
                val (id, title) = if (evening) EVENING_NOTIFICATION_ID to "🌙 আজকের হিসাব" else BRIEFING_NOTIFICATION_ID to "🌅 আজকের পরিকল্পনা"
                Notifications.show(applicationContext, id, AiApp.CHANNEL_BRIEFING, title, text)
                AiApp.from(applicationContext).store.addChat(fromUser = false, text = "$title\n$text")
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KIND = "kind"
    }
}
