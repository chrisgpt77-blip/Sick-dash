package com.sickdash.data

/**
 * Every value the dash can show. Internal units: km/h, °C, %, volts, degrees, deg/s, g, kPa
 * (tyres), g/s (MAF), km (odometer). On/off states are 0 or 1 (headlights: 0 off, 1 parking,
 * 2 low, 3 high).
 */
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
    TIRE_RR("tire_rr"),
    YAW_RATE("yaw_rate"),
    WHEEL_FL("wheel_fl"),
    WHEEL_FR("wheel_fr"),
    WHEEL_RL("wheel_rl"),
    WHEEL_RR("wheel_rr"),
    REVERSE("reverse"),
    NEUTRAL("neutral"),
    BRAKE_LIGHT("brake_light"),
    DOOR_LOCK("door_lock"),
    DOORS_OPEN("doors_open"),
    BELT("belt"),
    HEADLIGHTS("headlights"),
    PARK_BRAKE("park_brake"),
    VDC_OFF("vdc_off"),
    TRACK_MODE("track_mode"),
    CRUISE("cruise"),
    ODOMETER("odometer"),
    TIMING("timing"),
    MAF("maf"),
    STFT("stft"),
    LTFT("ltft"),
    LAMBDA("lambda"),
    LOAD("load");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): Channel? = byKey[key.lowercase()]
    }
}
