package com.sickdash.data

import kotlin.math.abs
import kotlin.math.roundToInt

/** Display name, group and formatting for each channel; used by the system check and Systems page. */
object ChannelInfo {
    enum class Group(val title: String) { POWERTRAIN("POWERTRAIN · ECM"), CHASSIS("CHASSIS · ABS / VDC"), BODY("BODY · BCM / METER") }

    class Info(val channel: Channel, val name: String, val group: Group)

    val ALL: List<Info> = listOf(
        Info(Channel.RPM, "ENGINE", Group.POWERTRAIN),
        Info(Channel.THROTTLE, "PEDAL", Group.POWERTRAIN),
        Info(Channel.COOLANT, "COOLANT", Group.POWERTRAIN),
        Info(Channel.OIL_TEMP, "OIL TEMP", Group.POWERTRAIN),
        Info(Channel.INTAKE_TEMP, "AIR TEMP", Group.POWERTRAIN),
        Info(Channel.GEAR, "GEAR", Group.POWERTRAIN),
        Info(Channel.CLUTCH, "CLUTCH", Group.POWERTRAIN),
        Info(Channel.NEUTRAL, "NEUTRAL SW", Group.POWERTRAIN),
        Info(Channel.FUEL, "FUEL", Group.POWERTRAIN),
        Info(Channel.BATTERY, "BATTERY", Group.POWERTRAIN),
        Info(Channel.TIMING, "TIMING", Group.POWERTRAIN),
        Info(Channel.MAF, "MASS AIRFLOW", Group.POWERTRAIN),
        Info(Channel.LOAD, "LOAD", Group.POWERTRAIN),
        Info(Channel.STFT, "SHORT TRIM", Group.POWERTRAIN),
        Info(Channel.LTFT, "LONG TRIM", Group.POWERTRAIN),
        Info(Channel.LAMBDA, "LAMBDA", Group.POWERTRAIN),
        Info(Channel.SPEED, "SPEED", Group.CHASSIS),
        Info(Channel.WHEEL_FL, "WHEEL FL", Group.CHASSIS),
        Info(Channel.WHEEL_FR, "WHEEL FR", Group.CHASSIS),
        Info(Channel.WHEEL_RL, "WHEEL RL", Group.CHASSIS),
        Info(Channel.WHEEL_RR, "WHEEL RR", Group.CHASSIS),
        Info(Channel.STEERING, "STEERING", Group.CHASSIS),
        Info(Channel.YAW_RATE, "YAW RATE", Group.CHASSIS),
        Info(Channel.BRAKE, "BRAKE", Group.CHASSIS),
        Info(Channel.BRAKE_LIGHT, "BRAKE LIGHTS", Group.CHASSIS),
        Info(Channel.LAT_G, "LATERAL G", Group.CHASSIS),
        Info(Channel.LON_G, "LONG G", Group.CHASSIS),
        Info(Channel.VDC_OFF, "VDC", Group.CHASSIS),
        Info(Channel.TRACK_MODE, "TRACK MODE", Group.CHASSIS),
        Info(Channel.PARK_BRAKE, "PARK BRAKE", Group.CHASSIS),
        Info(Channel.CRUISE, "CRUISE", Group.CHASSIS),
        Info(Channel.DOORS_OPEN, "DOORS", Group.BODY),
        Info(Channel.DOOR_DRIVER, "DOOR DRIVER", Group.BODY),
        Info(Channel.DOOR_PASS, "DOOR PASS", Group.BODY),
        Info(Channel.HATCH, "TRUNK", Group.BODY),
        Info(Channel.TURN_SIGNAL, "TURN / HAZARD", Group.BODY),
        Info(Channel.WIPERS, "WIPERS", Group.BODY),
        Info(Channel.DOOR_LOCK, "LOCKS", Group.BODY),
        Info(Channel.BELT, "BELT", Group.BODY),
        Info(Channel.HEADLIGHTS, "LIGHTS", Group.BODY),
        Info(Channel.REVERSE, "REVERSE", Group.BODY),
        Info(Channel.ODOMETER, "ODOMETER", Group.BODY),
        Info(Channel.TIRE_FL, "TYRE FL", Group.BODY),
        Info(Channel.TIRE_FR, "TYRE FR", Group.BODY),
        Info(Channel.TIRE_RL, "TYRE RL", Group.BODY),
        Info(Channel.TIRE_RR, "TYRE RR", Group.BODY),
    )

    private val byChannel = ALL.associateBy { it.channel }
    fun of(c: Channel): Info? = byChannel[c]

    /** Short display value for a channel. */
    fun format(c: Channel, v: Float, imperial: Boolean): String {
        fun temp(x: Float) = if (imperial) "${(x * 9f / 5f + 32f).roundToInt()}°F" else "${x.roundToInt()}°C"
        fun spd(x: Float) = if (imperial) "${(x * 0.621371f).roundToInt()} MPH" else "${x.roundToInt()} KM/H"
        return when (c) {
            Channel.RPM -> "%,d RPM".format(v.roundToInt())
            Channel.SPEED, Channel.WHEEL_FL, Channel.WHEEL_FR, Channel.WHEEL_RL, Channel.WHEEL_RR -> spd(v)
            Channel.COOLANT, Channel.OIL_TEMP, Channel.INTAKE_TEMP -> temp(v)
            Channel.THROTTLE, Channel.FUEL, Channel.LOAD -> "${v.roundToInt()}%"
            Channel.BRAKE -> if (v < 2f) "OFF" else "${v.roundToInt()}%"
            Channel.BATTERY -> "%.1f V".format(v)
            Channel.GEAR -> when (v.roundToInt()) { 0 -> "N"; -1 -> "R"; else -> v.roundToInt().toString() }
            Channel.CLUTCH -> if (v >= 50f) "IN" else "OUT"
            Channel.STEERING -> "${abs(v).roundToInt()}° ${if (v > 0.5f) "L" else if (v < -0.5f) "R" else ""}".trim()
            Channel.YAW_RATE -> "%.1f°/S".format(v)
            Channel.LAT_G, Channel.LON_G -> "%.2f G".format(v)
            Channel.TIRE_FL, Channel.TIRE_FR, Channel.TIRE_RL, Channel.TIRE_RR ->
                if (imperial) "${(v / 6.8948f).roundToInt()} PSI" else "%.1f BAR".format(v / 100f)
            Channel.TIMING -> "%.1f° BTDC".format(v)
            Channel.MAF -> "%.1f G/S".format(v)
            Channel.STFT, Channel.LTFT -> "%+.1f%%".format(v)
            Channel.LAMBDA -> "%.3f λ".format(v)
            Channel.ODOMETER -> if (imperial) "%,d MI".format((v * 0.621371f).roundToInt()) else "%,d KM".format(v.roundToInt())
            Channel.DOOR_LOCK -> if (v >= 0.5f) "LOCKED" else "UNLOCKED"
            Channel.DOORS_OPEN, Channel.DOOR_DRIVER, Channel.DOOR_PASS -> if (v >= 0.5f) "OPEN" else "CLOSED"
            Channel.HATCH -> if (v >= 0.5f) "OPEN" else "SHUT"
            Channel.TURN_SIGNAL -> when (v.roundToInt()) { 0 -> "OFF"; 1 -> "LEFT"; 2 -> "RIGHT"; else -> "HAZARD" }
            Channel.WIPERS -> when (v.roundToInt()) { 0 -> "OFF"; 1 -> "INT"; 2 -> "LOW"; else -> "HIGH" }
            Channel.BELT -> if (v >= 0.5f) "BUCKLED" else "OPEN"
            Channel.HEADLIGHTS -> when (v.roundToInt()) { 0 -> "OFF"; 1 -> "PARKING"; 2 -> "LOW"; else -> "HIGH" }
            Channel.VDC_OFF -> if (v >= 0.5f) "OFF" else "ON"
            Channel.TRACK_MODE, Channel.CRUISE, Channel.REVERSE, Channel.NEUTRAL, Channel.BRAKE_LIGHT, Channel.PARK_BRAKE ->
                if (v >= 0.5f) "ON" else "OFF"
        }
    }

    /** True when a value deserves a look (the amber "TO CHECK" in the system check). */
    fun needsCheck(c: Channel, v: Float, s: Snapshot): Boolean = when (c) {
        Channel.COOLANT -> v < 60f || v > 108f
        Channel.OIL_TEMP -> v > 130f
        Channel.FUEL -> v < 15f
        Channel.BATTERY -> v < 12.0f || v > 15.2f
        Channel.BELT -> v < 0.5f
        Channel.DOORS_OPEN, Channel.DOOR_DRIVER, Channel.DOOR_PASS, Channel.HATCH -> v >= 0.5f
        Channel.PARK_BRAKE -> v >= 0.5f && (s[Channel.SPEED] ?: 0f) > 3f
        Channel.TIRE_FL, Channel.TIRE_FR, Channel.TIRE_RL, Channel.TIRE_RR -> v < 193f
        else -> false
    }
}
