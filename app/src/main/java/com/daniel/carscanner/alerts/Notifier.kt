package com.daniel.carscanner.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.daniel.carscanner.MainActivity
import com.daniel.carscanner.R
import com.daniel.carscanner.data.ReturnedCode
import com.daniel.carscanner.net.Recall

/*
 * WHAT THIS FILE DOES
 * Shows phone notifications:
 *   - "New recall for your 2016 Lexus RC" (from the daily recall check)
 *   - "P0420 came back" (when a cleared code returns)
 * Tapping one opens the app on the right car and screen.
 * Android 13+ asks you once to allow notifications; if you say no, nothing shows.
 */
object Notifier {
    private const val CH_RECALLS = "recalls"
    private const val CH_CODES = "codes"

    /** Notification categories you can turn on/off in Android settings. */
    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_RECALLS, "Recalls", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "New safety recalls for cars in your garage" })
        nm.createNotificationChannel(NotificationChannel(CH_CODES, "Returning codes", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A trouble code you cleared has come back" })
    }

    /** True if the app is allowed to show notifications. */
    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the app on a given car and screen when the notification is tapped. */
    private fun open(ctx: Context, vin: String, screen: String, requestCode: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_VIN, vin)
            putExtra(MainActivity.EXTRA_SCREEN, screen)
        }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission")   // checked by allowed()
    fun newRecalls(ctx: Context, vin: String, carName: String, recalls: List<Recall>) {
        if (!allowed(ctx) || recalls.isEmpty()) return
        ensureChannels(ctx)
        val first = recalls.first()
        val title = if (recalls.size == 1) "New recall for your $carName" else "${recalls.size} new recalls for your $carName"
        val text = first.component.substringBefore(':').lowercase().replaceFirstChar { it.uppercase() } +
            (if (first.parkIt) ". NHTSA says don't drive until fixed." else ". Tap for details.")
        val n = NotificationCompat.Builder(ctx, CH_RECALLS)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n${first.summary.take(300)}"))
            .setContentIntent(open(ctx, vin, "Vehicle", vin.hashCode()))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(("recall$vin").hashCode(), n)
    }

    @SuppressLint("MissingPermission")
    fun codesReturned(ctx: Context, vin: String, carName: String, codes: List<ReturnedCode>) {
        if (!allowed(ctx) || codes.isEmpty()) return
        ensureChannels(ctx)
        val list = codes.joinToString(", ") { it.code }
        val n = NotificationCompat.Builder(ctx, CH_CODES)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(if (codes.size == 1) "${codes[0].code} came back on your $carName" else "$list came back on your $carName")
            .setContentText("You cleared ${if (codes.size == 1) "it" else "them"} earlier. The cause is probably still there.")
            .setContentIntent(open(ctx, vin, "Codes", vin.hashCode() + 1))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(("codes$vin").hashCode(), n)
    }
}
