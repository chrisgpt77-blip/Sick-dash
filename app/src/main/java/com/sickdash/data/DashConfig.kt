package com.sickdash.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.sickdash.can.ElmMode

enum class Source(val key: String, val label: String) {
    DEMO("demo", "Demo (simulated drive)"),
    BLE_ELM("ble_elm", "Bluetooth LE OBD (DauntlessOBD, OBDLink CX, Vgate BLE)"),
    BT_ELM("bt_elm", "Bluetooth Classic OBD (ELM327 / OBDLink MX+)"),
    WIFI_ELM("wifi_elm", "Wi-Fi OBD (ELM327 / WiCAN)"),
    USB_ELM("usb_elm", "USB OBD (ELM327 USB)"),
    USB_SLCAN("usb_slcan", "USB CAN adapter (CANable / slcan)"),
    TCP_SLCAN("tcp_slcan", "Wi-Fi CAN adapter (WiCAN / ESP32 slcan)");

    val usesProfile get() = this == USB_SLCAN || this == TCP_SLCAN

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEMO
    }
}

/** Factory numbers for each supported car; picking one in Settings copies these into the prefs. */
enum class CarPreset(
    val key: String,
    val label: String,
    val wordmark: String,
    val chassis: String,
    val engine: String,
    val profileAsset: String,
    val theme: String,
    val redline: Int,
    val shiftRpm: Int,
    val maxRpm: Int,
    val mtRatios: String,
    val mtFinal: String,
    val atRatios: String,
    val atFinal: String,
    val tire: String,
) {
    GR86(
        "gr86", "Toyota GR86 (2022–2026)", "GR86", "ZN8", "FA24D", GEN2_PROFILE, "akasaka",
        7500, 7000, 8000, "3.626,2.189,1.541,1.213,1.000,0.767", "4.100",
        "3.538,2.060,1.405,1.000,0.713,0.582", "3.909", "215/40R18",
    ),
    BRZ(
        "brz", "Subaru BRZ (2022–2026)", "BRZ", "ZD8", "FA24D", GEN2_PROFILE, "wrblue",
        7500, 7000, 8000, "3.626,2.189,1.541,1.213,1.000,0.767", "4.100",
        "3.538,2.060,1.405,1.000,0.713,0.582", "3.909", "215/40R18",
    ),
    Z370(
        "370z", "Nissan 370Z (2009–2020)", "370Z", "Z34", "VQ37VHR", "profiles/nissan_370z.json", "akasaka",
        7500, 7000, 8000, "3.794,2.324,1.624,1.271,1.000,0.794", "3.692",
        "4.923,3.193,2.042,1.411,1.000,0.862,0.771", "3.357", "275/35R19",
    );

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: GR86
    }
}

private const val GEN2_PROFILE = "profiles/toyota_gr86_brz_gen2.json"

/** Everything the app reads from Settings, parsed once per change. */
data class DashConfig(
    val source: Source,
    val btAddress: String,
    val host: String,
    val port: Int,
    val usbBaud: Int,
    val elmMode: ElmMode,
    val imperial: Boolean,
    val themeKey: String,
    val redline: Int,
    val shiftRpm: Int,
    val maxRpm: Int,
    val gearRatios: FloatArray,
    val finalDrive: Float,
    val tireCircumferenceM: Float,
    val phoneGSensor: Boolean,
    val car: CarPreset = CarPreset.GR86,
    val automatic: Boolean = false,
    /** Wireframe: five-spoke aftermarket wheels instead of the stock ten-spoke. */
    val fiveSpoke: Boolean = true,
    /** Wireframe: lowered, with lip splitter, side skirts and raised ducktail. */
    val bodyKit: Boolean = true,
) {
    /** "FA24D · 6MT" under the wordmark. */
    val subtitle: String get() = "${car.engine} · ${gearRatios.size}${if (automatic) "AT" else "MT"}"

    companion object {
        /** Keys whose change requires reconnecting to the adapter. */
        val CONNECTION_KEYS = setOf("source", "bt_device", "tcp_host", "tcp_port", "usb_baud", "elm_mode", "car")

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
            val car = CarPreset.of(p.getString("car", null))
            val automatic = p.getString("transmission", "mt") == "at"
            return DashConfig(
                source = source,
                btAddress = p.getString("bt_device", "") ?: "",
                host = p.getString("tcp_host", null)?.takeIf { it.isNotBlank() }
                    ?: if (source == Source.TCP_SLCAN) "192.168.80.1" else "192.168.0.10",
                port = p.getString("tcp_port", null)?.toIntOrNull()
                    ?: if (source == Source.TCP_SLCAN) 3333 else 35000,
                usbBaud = p.getString("usb_baud", "115200")?.toIntOrNull() ?: 115200,
                elmMode = when (p.getString("elm_mode", "auto")) {
                    "obd" -> ElmMode.OBD
                    "monitor" -> ElmMode.RAW
                    else -> ElmMode.AUTO
                },
                imperial = p.getString("units", "imperial") == "imperial",
                themeKey = p.getString("theme", null) ?: car.theme,
                redline = p.getString("redline", null)?.toIntOrNull() ?: car.redline,
                shiftRpm = p.getString("shift_rpm", null)?.toIntOrNull() ?: car.shiftRpm,
                maxRpm = p.getString("max_rpm", null)?.toIntOrNull() ?: car.maxRpm,
                gearRatios = parseRatios(p.getString("gear_ratios", null) ?: if (automatic) car.atRatios else car.mtRatios),
                finalDrive = (p.getString("final_drive", null) ?: if (automatic) car.atFinal else car.mtFinal)
                    .toFloatOrNull() ?: 4.1f,
                tireCircumferenceM = tireCircumference(p.getString("tire_size", null) ?: car.tire),
                phoneGSensor = p.getBoolean("phone_g", true),
                car = car,
                automatic = automatic,
                fiveSpoke = p.getString("wheels", "5spoke") == "5spoke",
                bodyKit = p.getBoolean("body_kit", true),
            )
        }

        /** Writes [car]'s factory numbers over the user-editable ones. */
        fun applyPreset(p: SharedPreferences, car: CarPreset, automatic: Boolean) {
            p.edit()
                .putString("theme", car.theme)
                .putString("redline", car.redline.toString())
                .putString("shift_rpm", car.shiftRpm.toString())
                .putString("max_rpm", car.maxRpm.toString())
                .putString("gear_ratios", if (automatic) car.atRatios else car.mtRatios)
                .putString("final_drive", if (automatic) car.atFinal else car.mtFinal)
                .putString("tire_size", car.tire)
                .apply()
        }

        const val DEFAULT_RATIOS = "3.626,2.189,1.541,1.213,1.000,0.767"

        fun parseRatios(s: String?): FloatArray =
            (s ?: DEFAULT_RATIOS).split(',', ' ', ';').mapNotNull { it.trim().toFloatOrNull() }
                .filter { it > 0f }.toFloatArray()
                .let { if (it.isEmpty()) parseRatios(DEFAULT_RATIOS) else it }

        /** "215/40R18" -> rolling circumference in metres (falls back to a GR86 18" tyre). */
        fun tireCircumference(size: String?): Float {
            val m = Regex("""(\d{3})\s*/\s*(\d{2})\s*Z?R\s*(\d{2})""", RegexOption.IGNORE_CASE)
                .find(size ?: "") ?: return 1.93f
            val width = m.groupValues[1].toFloat()
            val aspect = m.groupValues[2].toFloat()
            val rim = m.groupValues[3].toFloat()
            val diameterMm = rim * 25.4f + 2f * width * aspect / 100f
            return (Math.PI * diameterMm / 1000.0).toFloat() * 0.97f // ~3 % squat under load
        }
    }
}
