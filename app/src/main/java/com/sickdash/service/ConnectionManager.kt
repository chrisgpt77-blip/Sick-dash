package com.sickdash.service

import android.content.Context
import android.util.Log
import com.sickdash.can.BleSerialTransport
import com.sickdash.can.BluetoothSppTransport
import com.sickdash.can.CanProfile
import com.sickdash.can.Elm327Session
import com.sickdash.can.SlcanSession
import com.sickdash.can.TcpTransport
import com.sickdash.can.Transport
import com.sickdash.can.UsbSerialTransport
import com.sickdash.data.DashConfig
import com.sickdash.data.GSensor
import com.sickdash.data.LinkState
import com.sickdash.data.Navigation
import com.sickdash.data.NowPlaying
import com.sickdash.data.Simulator
import com.sickdash.data.Source
import com.sickdash.data.VehicleData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the one live connection to the car. The phone UI, the Android Auto session and the
 * foreground service each [acquire] it; it disconnects when the last one [release]s.
 */
object ConnectionManager {
    private const val TAG = "SickDash"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val clients = HashSet<String>()
    private lateinit var appContext: Context

    @Synchronized
    fun acquire(context: Context, client: String) {
        appContext = context.applicationContext
        clients += client
        if (job == null) start()
    }

    @Synchronized
    fun release(client: String) {
        clients -= client
        if (clients.isEmpty()) stop()
    }

    @Synchronized
    fun restart() {
        if (job == null) return
        stop()
        start()
    }

    private fun start() {
        val ctx = appContext
        GSensor.start(ctx)
        job = scope.launch { loop(ctx) }
    }

    private fun stop() {
        job?.cancel()
        job = null
        GSensor.stop()
        VehicleData.setStatus(LinkState.IDLE, "Not connected")
    }

    private suspend fun loop(ctx: Context) {
        while (scope.isActive) {
            val cfg = DashConfig.get(ctx)
            VehicleData.clearAll()
            VehicleData.sniffer.clear()
            VehicleData.linkMode = if (cfg.source == Source.DEMO) "DEMO" else "—"
            VehicleData.nodes = if (cfg.source == Source.DEMO) emptyMap() else runCatching { loadProfile(ctx).nodes }.getOrDefault(emptyMap())
            VehicleData.setStatus(LinkState.CONNECTING, "Connecting: ${cfg.source.label}…")
            var transport: Transport? = null
            try {
                if (cfg.source == Source.DEMO) {
                    try { Simulator.run { DashConfig.get(ctx) } } finally { Navigation.clearDemo(); NowPlaying.clearDemo() }
                } else {
                    val t = createTransport(ctx, cfg).also { transport = it }
                    t.open()
                    coroutineScope {
                        try {
                            when {
                                cfg.source.usesProfile -> SlcanSession(t, this, loadProfile(ctx)).run()
                                else -> Elm327Session(t, this, runCatching { loadProfile(ctx) }.getOrNull()).run(cfg.elmMode)
                            }
                        } finally {
                            // Closing unblocks the reader thread so this scope can finish.
                            t.close()
                            coroutineContext.cancelChildren()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed", e)
                VehicleData.setStatus(LinkState.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                transport?.close()
            }
            delay(3000)
        }
    }

    private fun createTransport(ctx: Context, cfg: DashConfig): Transport = when (cfg.source) {
        Source.BT_ELM -> BluetoothSppTransport(ctx, cfg.btAddress)
        Source.BLE_ELM -> BleSerialTransport(ctx, cfg.btAddress)
        Source.WIFI_ELM, Source.TCP_SLCAN -> TcpTransport(cfg.host, cfg.port)
        Source.USB_ELM, Source.USB_SLCAN -> UsbSerialTransport(ctx, cfg.usbBaud)
        Source.DEMO -> error("demo has no transport")
    }

    /** The user's edited profile for the selected car, if they saved one. */
    fun profileFile(ctx: Context): File = File(ctx.filesDir, "can_profile_${DashConfig.get(ctx).car.key}.json")

    fun defaultProfileText(ctx: Context): String =
        ctx.assets.open(DashConfig.get(ctx).car.profileAsset).bufferedReader().use { it.readText() }

    fun profileText(ctx: Context): String {
        val f = profileFile(ctx)
        return if (f.exists()) f.readText() else defaultProfileText(ctx)
    }

    fun loadProfile(ctx: Context): CanProfile = CanProfile.parse(profileText(ctx))
}
