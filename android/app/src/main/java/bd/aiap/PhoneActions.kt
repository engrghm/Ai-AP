package bd.aiap

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import bd.aiap.core.PhoneAction
import java.time.LocalDateTime
import java.time.ZoneId

/** Opens the right app for a [PhoneAction]. Nothing is sent or called without the owner confirming there. */
object PhoneActions {
    fun intentFor(action: PhoneAction): Intent {
        val target = action.target.trim()
        val number = target.filter { it.isDigit() || it == '+' }
        return when (action.type) {
            "call" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            "sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", action.text.orEmpty())
            "whatsapp" -> {
                // wa.me wants the international number without "+"; assume Bangladesh for local 01XXXXXXXXX numbers.
                val digits = number.removePrefix("+").let { if (it.startsWith("01") && it.length == 11) "88$it" else it }
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(action.text.orEmpty())))
            }
            "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(target))
                .putExtra(Intent.EXTRA_SUBJECT, action.label)
                .putExtra(Intent.EXTRA_TEXT, action.text.orEmpty())
            "map" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(target)))
            "calendar" -> {
                val start = action.time?.let { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, target)
                    .putExtra(CalendarContract.Events.DESCRIPTION, action.text.orEmpty())
                    .apply {
                        if (start != null) {
                            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 60 * 60 * 1000)
                        }
                    }
            }
            "alarm" -> {
                val time = action.time?.let { LocalDateTime.parse(it) }
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, target)
                    .apply {
                        if (time != null) {
                            putExtra(AlarmClock.EXTRA_HOUR, time.hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
                        }
                    }
            }
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(target)))
        }
    }

    /** Returns an error message to show, or null on success. */
    fun run(context: Context, action: PhoneAction): String? = try {
        context.startActivity(intentFor(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        "এই কাজের জন্য ফোনে উপযুক্ত অ্যাপ পাওয়া যায়নি।"
    } catch (e: Exception) {
        "কাজটি করা যায়নি: ${e.message}"
    }
}
