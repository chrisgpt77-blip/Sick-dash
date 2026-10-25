package com.sickdash.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.sickdash.data.Navigation
import com.sickdash.data.NowPlaying

/**
 * Holds notification access, which is what lets the dash see the phone's media sessions (for
 * Spotify and other players) and Google Maps' turn-by-turn notification. Every other app's
 * notifications are ignored, and nothing is stored.
 */
class NotificationWatcher : NotificationListenerService() {
    override fun onListenerConnected() {
        NowPlaying.attach(this, ComponentName(this, NotificationWatcher::class.java))
        runCatching { activeNotifications }.getOrNull()?.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        NowPlaying.detach()
        Navigation.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != Navigation.MAPS_PACKAGE) return
        val n = sbn.notification
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)
        val text = e.getCharSequence(Notification.EXTRA_TEXT)
        val navigating = n.category == Notification.CATEGORY_NAVIGATION ||
            (sbn.isOngoing && (Navigation.looksLikeDistance(title?.toString()) || Navigation.looksLikeDistance(text?.toString())))
        if (!navigating) return
        navKey = sbn.key
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: e.getCharSequence(Notification.EXTRA_INFO_TEXT)
        val icon = runCatching { n.getLargeIcon()?.loadDrawable(this)?.let { toBitmap(it) } }.getOrNull()
        Navigation.parse(title, text, sub, icon, System.currentTimeMillis())?.let { Navigation.update(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == navKey) { navKey = null; Navigation.clear() }
    }

    private var navKey: String? = null

    private fun toBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(b))
        }
    }

    companion object {
        fun allowed(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
