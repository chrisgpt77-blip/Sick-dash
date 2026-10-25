package com.sickdash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sickdash.MainActivity
import com.sickdash.R

/** Keeps the CAN/OBD connection running while the phone screen is off or the app is in the background. */
class CanService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dash)
            .setContentTitle("Sick Dash is connected to your car")
            .setContentText("Reading CAN / OBD data")
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, 42, n, type)
        ConnectionManager.acquire(this, CLIENT)
        return START_STICKY
    }

    override fun onDestroy() {
        ConnectionManager.release(CLIENT)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val CLIENT = "service"
        private const val ACTION_STOP = "com.sickdash.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CanService::class.java))
        }
    }
}
