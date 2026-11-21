package com.daniel.carscanner.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.daniel.carscanner.MainActivity
import com.daniel.carscanner.R
import com.daniel.carscanner.data.GarageJson
import com.daniel.carscanner.net.Recall
import com.daniel.carscanner.net.VehicleInfo
import com.daniel.carscanner.obd.Vin

/*
 * WHAT THIS FILE DOES
 * The home screen widget. Shows your current car in its paint color, its name,
 * its latest health or code result with a colored dot, and how many recalls
 * it has. Tap it to open the app.
 *
 * Widgets can't run the app's normal screens, so this one is built from a small
 * layout file (res/layout/widget_car.xml) and filled in from saved data.
 * The app refreshes it after scans, recall checks and car changes.
 */
class CarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = build(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        /** Refreshes every copy of the widget on your home screens. */
        fun updateAll(ctx: Context) {
            try {
                val manager = AppWidgetManager.getInstance(ctx)
                val ids = manager.getAppWidgetIds(ComponentName(ctx, CarWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(ctx))
            } catch (_: Exception) { }
        }

        /** Fills in the widget from the app's saved data. */
        private fun build(ctx: Context): RemoteViews {
            val prefs = ctx.getSharedPreferences("vehicle", 0)
            val views = RemoteViews(ctx.packageName, R.layout.widget_car)
            val vin = prefs.getString("last_vin", null)

            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open)

            if (vin == null) {
                views.setTextViewText(R.id.widget_name, "Add your vehicle")
                views.setTextViewText(R.id.widget_status, "Open the app to connect")
                views.setViewVisibility(R.id.widget_recalls, View.GONE)
                views.setTextViewText(R.id.widget_detail, "")
                return views
            }

            val info = prefs.getString("info_$vin", null)?.let { VehicleInfo.fromJson(it) }
            val nickname = GarageJson.fromJson(prefs.getString("garage", null)).firstOrNull { it.vin == vin }?.nickname.orEmpty()
            views.setTextViewText(R.id.widget_name, nickname.ifBlank { info?.displayName ?: Vin.describe(vin) ?: "Your vehicle" })

            // Car icon in the chosen paint (lightened if very dark, so it shows on the dark card)
            val paint = prefs.getLong("paint_$vin", 0xFFB9BDC3).toInt()
            val r = (paint shr 16) and 0xFF; val g = (paint shr 8) and 0xFF; val b = paint and 0xFF
            val tint = if (r * 0.3 + g * 0.59 + b * 0.11 < 45) 0xFF6E737A.toInt() else paint
            views.setInt(R.id.widget_car, "setColorFilter", tint)

            // Latest result: "LEVEL|headline|time" written by the app
            val parts = prefs.getString("status_$vin", null)?.split("|")
            val (dot, status) = if (parts != null && parts.size >= 2) {
                val color = when (parts[0]) {
                    "Good" -> 0xFF2EBD6B.toInt(); "Watch" -> 0xFFF5A623.toInt(); "Problem" -> 0xFFE8443A.toInt(); else -> 0xFF8E9196.toInt()
                }
                color to parts[1]
            } else 0xFF8E9196.toInt() to "Not checked yet"
            views.setInt(R.id.widget_dot, "setColorFilter", dot)
            views.setTextViewText(R.id.widget_status, status)

            val recalls = prefs.getString("recalls_$vin", null)?.let { Recall.listFromJson(it) }.orEmpty()
            if (recalls.isEmpty()) views.setViewVisibility(R.id.widget_recalls, View.GONE)
            else {
                views.setViewVisibility(R.id.widget_recalls, View.VISIBLE)
                views.setTextViewText(R.id.widget_recalls, "${recalls.size} recall${if (recalls.size > 1) "s" else ""} on record")
            }
            val miles = GarageJson.fromJson(prefs.getString("garage", null)).firstOrNull { it.vin == vin }?.odometerMiles
            views.setTextViewText(R.id.widget_detail, miles?.let { "%,d mi".format(it) } ?: "")
            return views
        }
    }
}
