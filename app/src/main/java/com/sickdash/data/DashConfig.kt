package com.sickdash.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.preference.PreferenceManager

enum class Source(val key: String, val label: String) {
    DEMO("demo", "Demo (simulated 370Z)"),
    BT_ELM("bt_elm", "Bluetooth OBD (ELM327 / OBDLink)"),
    WIFI_ELM("wifi_elm", "Wi-Fi OBD (ELM327 / WiCAN)"),
    USB_ELM("usb_elm", "USB OBD (ELM327 USB)"),
    USB_SLCAN("usb_slcan", "USB CAN adapter (CANable / slcan)"),
    TCP_SLCAN("tcp_slcan", "Wi-Fi CAN adapter (WiCAN / ESP32 slcan)");

    val usesProfile get() = this == USB_SLCAN || this == TCP_SLCAN

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEMO
    }
}

/** Everything the app reads from Settings, parsed once per change. */
data class DashConfig(
    val source: Source,
    val btAddress: String,
    val host: String,
    val port: Int,
    val usbBaud: Int,
    val rawCan: Boolean,
    val imperial: Boolean,
    val accent: Int,
    val redline: Int,
    val shiftRpm: Int,
    val maxRpm: Int,
    val gearRatios: FloatArray,
    val finalDrive: Float,
    val tireCircumferenceM: Float,
    val phoneGSensor: Boolean,
) {
    companion object {
        /** Keys whose change requires reconnecting to the adapter. */
        val CONNECTION_KEYS = setOf("source", "bt_device", "tcp_host", "tcp_port", "usb_baud", "elm_mode")

        @Volatile var current: DashConfig? = null
            private set

        private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

        fun get(context: Context): DashConfig = current ?: synchronized(this) {
            current ?: run {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                val l = SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> current = load(p) }
                prefs.registerOnSharedPreferenceChangeListener(l)
                listener = l
                load(prefs).also { current = it }
            }
        }

        fun load(p: SharedPreferences): DashConfig {
            val source = Source.of(p.getString("source", "demo"))
            return DashConfig(
                source = source,
                btAddress = p.getString("bt_device", "") ?: "",
                host = p.getString("tcp_host", null)?.takeIf { it.isNotBlank() }
                    ?: if (source == Source.TCP_SLCAN) "192.168.80.1" else "192.168.0.10",
                port = p.getString("tcp_port", null)?.toIntOrNull()
                    ?: if (source == Source.TCP_SLCAN) 3333 else 35000,
                usbBaud = p.getString("usb_baud", "115200")?.toIntOrNull() ?: 115200,
                rawCan = p.getString("elm_mode", "obd") == "monitor",
                imperial = p.getString("units", "imperial") == "imperial",
                accent = parseColor(p.getString("accent", "#00E5FF")),
                redline = p.getString("redline", "7500")?.toIntOrNull() ?: 7500,
                shiftRpm = p.getString("shift_rpm", "7000")?.toIntOrNull() ?: 7000,
                maxRpm = p.getString("max_rpm", "8000")?.toIntOrNull() ?: 8000,
                gearRatios = parseRatios(p.getString("gear_ratios", DEFAULT_RATIOS)),
                finalDrive = p.getString("final_drive", "3.692")?.toFloatOrNull() ?: 3.692f,
                tireCircumferenceM = tireCircumference(p.getString("tire_size", "275/35R19")),
                phoneGSensor = p.getBoolean("phone_g", true),
            )
        }

        const val DEFAULT_RATIOS = "3.794,2.324,1.624,1.271,1.000,0.794"

        fun parseRatios(s: String?): FloatArray =
            (s ?: DEFAULT_RATIOS).split(',', ' ', ';').mapNotNull { it.trim().toFloatOrNull() }
                .filter { it > 0f }.toFloatArray()
                .let { if (it.isEmpty()) parseRatios(DEFAULT_RATIOS) else it }

        /** "275/35R19" -> rolling circumference in metres (falls back to the 370Z rear tyre). */
        fun tireCircumference(size: String?): Float {
            val m = Regex("""(\d{3})\s*/\s*(\d{2})\s*Z?R\s*(\d{2})""", RegexOption.IGNORE_CASE)
                .find(size ?: "") ?: return 2.12f
            val width = m.groupValues[1].toFloat()
            val aspect = m.groupValues[2].toFloat()
            val rim = m.groupValues[3].toFloat()
            val diameterMm = rim * 25.4f + 2f * width * aspect / 100f
            return (Math.PI * diameterMm / 1000.0).toFloat() * 0.97f // ~3 % squat under load
        }

        private fun parseColor(s: String?): Int = runCatching { Color.parseColor(s) }.getOrDefault(0xFF00E5FF.toInt())
    }
}
