package com.radiosuite.pro.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ScheduledRadioEvent(
    val id: String,
    val name: String,
    val audioUri: String,
    val audioTitle: String,
    val scheduledAtMillis: Long,
)

internal object RadioEventScheduler {
    private const val TAG = "RadioEventScheduler"
    private const val PREFS = "radio_events"
    private const val EVENTS_KEY = "scheduled_events"
    private const val ACTION_EVENT_ALARM = "com.radiosuite.pro.EVENT_ALARM"
    private const val EXTRA_EVENT_ID = "eventId"
    private const val MISSED_EVENT_TOLERANCE_MS = 5 * 60 * 1000L

    fun load(context: Context): List<ScheduledRadioEvent> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(EVENTS_KEY, "[]") ?: "[]"
        val loaded = try {
            val events = JSONArray(saved)
            (0 until events.length()).map { index ->
                val event = events.getJSONObject(index)
                ScheduledRadioEvent(
                    id = event.getString("id"),
                    name = event.getString("name"),
                    audioUri = event.getString("audioUri"),
                    audioTitle = event.getString("audioTitle"),
                    scheduledAtMillis = event.getLong("scheduledAtMillis"),
                )
            }.sortedBy(ScheduledRadioEvent::scheduledAtMillis)
        } catch (error: Exception) {
            Log.e(TAG, "No se pudieron leer los eventos programados", error)
            emptyList()
        }
        val active = loaded.filter {
            it.scheduledAtMillis >= System.currentTimeMillis() - MISSED_EVENT_TOLERANCE_MS
        }
        if (active.size != loaded.size) save(context, active)
        return active
    }

    fun save(context: Context, events: List<ScheduledRadioEvent>) {
        val json = JSONArray()
        events.forEach { event ->
            json.put(
                JSONObject()
                    .put("id", event.id)
                    .put("name", event.name)
                    .put("audioUri", event.audioUri)
                    .put("audioTitle", event.audioTitle)
                    .put("scheduledAtMillis", event.scheduledAtMillis),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(EVENTS_KEY, json.toString()).apply()
    }

    fun newEvent(name: String, audioUri: String, audioTitle: String, scheduledAtMillis: Long) =
        ScheduledRadioEvent(UUID.randomUUID().toString(), name, audioUri, audioTitle, scheduledAtMillis)

    fun schedule(context: Context, event: ScheduledRadioEvent) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            throw SecurityException("El permiso para alarmas exactas no está habilitado.")
        }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            event.scheduledAtMillis,
            pendingIntent(context, event.id),
        )
    }

    fun cancel(context: Context, eventId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, eventId))
    }

    fun restore(context: Context) {
        load(context).forEach { event ->
            try {
                schedule(context, event)
            } catch (error: SecurityException) {
                Log.e(TAG, "No se pudo restaurar la alarma del evento ${event.name}", error)
            }
        }
    }

    fun eventFor(context: Context, id: String): ScheduledRadioEvent? =
        load(context).firstOrNull { it.id == id }

    private fun pendingIntent(context: Context, eventId: String): PendingIntent {
        val intent = Intent(context, RadioEventAlarmReceiver::class.java)
            .setAction(ACTION_EVENT_ALARM)
            .setData(Uri.parse("radiosuite-event://${context.packageName}/$eventId"))
            .putExtra(EXTRA_EVENT_ID, eventId)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun isEventAlarm(action: String?): Boolean = action == ACTION_EVENT_ALARM
    internal fun eventId(intent: Intent): String? = intent.getStringExtra(EXTRA_EVENT_ID)
}

class RadioEventAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RadioEventScheduler.restore(context)
            return
        }
        if (!RadioEventScheduler.isEventAlarm(intent.action)) return
        val eventId = RadioEventScheduler.eventId(intent) ?: return
        val serviceIntent = Intent(context, RadioEngineService::class.java)
            .setAction(RadioEngineService.ACTION_PLAY_SCHEDULED_EVENT)
            .putExtra(RadioEngineService.EXTRA_EVENT_ID, eventId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (error: Exception) {
            Log.e("RadioEventScheduler", "No se pudo iniciar el reproductor para el evento programado", error)
        }
    }
}
