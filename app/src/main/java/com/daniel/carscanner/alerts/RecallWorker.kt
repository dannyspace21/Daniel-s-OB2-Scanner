package com.daniel.carscanner.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.daniel.carscanner.data.GarageJson
import com.daniel.carscanner.net.Nhtsa
import com.daniel.carscanner.net.Recall
import com.daniel.carscanner.net.VehicleInfo
import com.daniel.carscanner.widget.CarWidget
import java.util.concurrent.TimeUnit

/*
 * WHAT THIS FILE DOES
 * The daily recall check. Android runs this in the background about once a day,
 * only when you have internet, even if the app is closed. For every car in your
 * garage it asks NHTSA for recalls and compares them with the last list it saw.
 * Anything new becomes a notification. It uses very little battery or data.
 *
 * The first time a car is checked, its current recalls just become the
 * starting point, so you aren't flooded with old recalls.
 */
class RecallWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("vehicle", 0)
        if (!prefs.getBoolean("recall_alerts", true)) return Result.success()
        val garage = GarageJson.fromJson(prefs.getString("garage", null))
        var failures = 0
        for (car in garage) {
            val info = prefs.getString("info_${car.vin}", null)?.let { VehicleInfo.fromJson(it) } ?: continue
            val saved = prefs.getString("recalls_${car.vin}", null)
            val fresh = try { Nhtsa.recalls(info) } catch (_: Exception) { failures++; continue }
            prefs.edit().putString("recalls_${car.vin}", Recall.listToJson(fresh)).apply()
            if (saved != null) {
                val known = Recall.listFromJson(saved).map { it.campaign }.toSet()
                val new = fresh.filter { it.campaign !in known }
                val name = car.nickname.ifBlank { info.displayName }
                Notifier.newRecalls(applicationContext, car.vin, name, new)
            }
        }
        CarWidget.updateAll(applicationContext)
        // If every lookup failed (NHTSA down), try again later; otherwise we're done for today.
        return if (garage.isNotEmpty() && failures == garage.size) Result.retry() else Result.success()
    }

    companion object {
        /** Sets up the daily check. Safe to call every time the app starts. */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<RecallWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("recall-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
