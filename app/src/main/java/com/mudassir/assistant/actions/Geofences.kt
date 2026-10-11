package com.mudassir.assistant.actions

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.mudassir.assistant.App
import com.mudassir.assistant.ui.MainActivity

/** "Remind me when I reach office": a 150 m circle around the saved place that fires once on arrival. */
object Geofences {

    private const val RADIUS_M = 150f

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 5100, Intent(ctx, Receiver::class.java),
        // Geofence events are added to the intent by Play services, so it must be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    @SuppressLint("MissingPermission")
    fun add(ctx: Context, r: Tracker.PlaceReminder, place: Tracker.Place) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val fence = Geofence.Builder()
            .setRequestId(r.id)
            .setCircularRegion(place.lat, place.lng, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // don't fire just because you're already there
            .addGeofence(fence)
            .build()
        LocationServices.getGeofencingClient(ctx).addGeofences(request, pending(ctx))
    }

    fun remove(ctx: Context, id: String) {
        LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(id))
    }

    /** Geofences are cleared when the phone restarts: add them again. */
    fun restoreAll(ctx: Context) {
        Tracker.placeReminders.value.forEach { r -> Tracker.place(r.place)?.let { add(ctx, r, it) } }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val event = GeofencingEvent.fromIntent(intent) ?: return
            if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            event.triggeringGeofences.orEmpty().forEach { fence ->
                val r = Tracker.placeReminders.value.firstOrNull { it.id == fence.requestId } ?: return@forEach
                nm.notify(
                    r.id.hashCode(),
                    NotificationCompat.Builder(ctx, App.CH_REMINDERS)
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle("You're at ${r.place}")
                        .setContentText(r.text.replaceFirstChar { it.uppercase() })
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(
                            PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                        )
                        .build()
                )
                Tracker.removePlaceReminder(r.id)
                remove(ctx, r.id)
            }
        }
    }
}
