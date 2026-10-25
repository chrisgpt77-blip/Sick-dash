package com.sickdash.data

import kotlin.math.abs

/** A manual 370Z doesn't broadcast its gear, so work it out from engine vs. wheel speed. */
object GearEstimator {
    /** @return 1-based gear, 0 for neutral/clutch-in, or null when unknown. */
    fun estimate(rpm: Float, speedKph: Float, cfg: DashConfig): Int? {
        if (speedKph < 4f) return if (rpm > 300f) 0 else null
        if (rpm < 400f) return 0
        val wheelRpm = (speedKph / 3.6f) / cfg.tireCircumferenceM * 60f
        val ratio = rpm / wheelRpm / cfg.finalDrive
        var best = -1
        var bestErr = Float.MAX_VALUE
        for (i in cfg.gearRatios.indices) {
            val err = abs(ratio - cfg.gearRatios[i]) / cfg.gearRatios[i]
            if (err < bestErr) { bestErr = err; best = i }
        }
        return if (best >= 0 && bestErr < 0.12f) best + 1 else 0
    }
}
