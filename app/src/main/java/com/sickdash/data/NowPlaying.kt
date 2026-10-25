package com.sickdash.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Whatever is playing on the phone (Spotify, YouTube Music, podcasts…), from Android's media
 * sessions, with play / pause / skip. Reading other apps' sessions needs notification access,
 * which the notification listener holds; it calls [attach] once that access is live.
 */
object NowPlaying {
    class Track(
        val title: String,
        val artist: String?,
        val art: Bitmap?,
        val playing: Boolean,
        val positionMs: Long,
        val positionAt: Long,       // SystemClock.elapsedRealtime() when positionMs was true
        val speed: Float,
        val durationMs: Long,
        val app: String,
        val demo: Boolean = false,
    ) {
        /** Playback position now, extrapolated while playing. */
        fun position(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
            if (!playing) positionMs else (positionMs + (nowElapsed - positionAt) * speed).toLong()
                .let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
    }

    @Volatile var current: Track? = null
        private set

    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() { controller = null; refresh() }
    }

    fun attach(context: Context, listener: ComponentName) {
        main.post {
            val m = context.getSystemService(MediaSessionManager::class.java) ?: return@post
            manager = m
            component = listener
            runCatching {
                m.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
                choose(m.getActiveSessions(listener))
            }
        }
    }

    fun detach() {
        main.post {
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
            controller?.unregisterCallback(callback)
            controller = null
            manager = null
            if (current?.demo != true) current = null
        }
    }

    /** Follows the session that's playing, else the most recent one. */
    private fun choose(list: List<MediaController>) {
        val pick = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = pick
            pick?.registerCallback(callback, main)
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c == null || md == null || title.isNullOrBlank()) {
            if (current?.demo != true) current = null
            return
        }
        val st = c.playbackState
        val art = (md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))?.let { shrink(it) }
        val previous = current
        current = Track(
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            art = art,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            positionMs = st?.position ?: 0L,
            positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            app = c.packageName,
        )
        if (previous?.title != title && current?.playing == true) {
            Events.emit(Events.Kind.MEDIA, "NOW PLAYING", listOfNotNull(title, current?.artist).joinToString(" · ").uppercase().take(48))
        }
    }

    private fun shrink(b: Bitmap): Bitmap {
        val max = 256
        if (b.width <= max && b.height <= max) return b
        val k = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    fun playPause() {
        val c = controller
        if (c == null) { current?.takeIf { it.demo }?.let { demo(it.copyPlaying(!it.playing)) }; return }
        main.post { if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() }
    }

    fun next() { controller?.let { c -> main.post { c.transportControls.skipToNext() } } }

    fun previous() { controller?.let { c -> main.post { c.transportControls.skipToPrevious() } } }

    /** The demo drive's music; never replaces a real session. */
    fun demo(track: Track?) {
        val c = current
        if (controller == null && (c == null || c.demo)) current = track
    }

    fun clearDemo() { if (current?.demo == true) current = null }

    private fun Track.copyPlaying(p: Boolean) =
        Track(title, artist, art, p, position(), SystemClock.elapsedRealtime(), speed, durationMs, app, demo)
}
