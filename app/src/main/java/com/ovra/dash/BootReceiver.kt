package com.ovra.dash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** After a restart Android won't let the dashcam use the camera until the app is opened once - remind the user. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("status", "Recording status", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(3, NotificationCompat.Builder(context, "status")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Tap to switch on Ovra Dash")
            .setContentText("Needed once after the phone restarts so the dashcam can start by itself")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }
}
