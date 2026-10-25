package com.sickdash.data

/** Every value the dash can show. Internal units: km/h, °C, %, volts, degrees, g. */
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
    LAT_G("lat_g"),
    LON_G("lon_g");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): Channel? = byKey[key.lowercase()]
    }
}
