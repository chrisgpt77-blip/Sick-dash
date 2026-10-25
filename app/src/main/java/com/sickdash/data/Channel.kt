package com.sickdash.data

/** Every value the dash can show. Internal units: km/h, °C, %, volts, degrees, g, kPa (tyres). */
enum class Channel(val key: String) {
    RPM("rpm"),
    SPEED("speed"),
    COOLANT("coolant"),
    OIL_TEMP("oil_temp"),
    INTAKE_TEMP("intake_temp"),
    THROTTLE("throttle"),
    BATTERY("battery"),
    FUEL("fuel"),
    GEAR("gear"),
    STEERING("steering"),
    BRAKE("brake"),
    CLUTCH("clutch"),
    LAT_G("lat_g"),
    LON_G("lon_g"),
    TIRE_FL("tire_fl"),
    TIRE_FR("tire_fr"),
    TIRE_RL("tire_rl"),
    TIRE_RR("tire_rr");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): Channel? = byKey[key.lowercase()]
    }
}
