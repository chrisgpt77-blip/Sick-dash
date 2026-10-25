package com.sickdash.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A procedurally lofted low-poly coupe in the proportions of a GR86/BRZ (4.27 m long,
 * 2.575 m wheelbase), as line segments in metres: x forward from the car's centre, y up from
 * the ground, z to the driver's right. No mesh files needed; it is drawn as a hologram.
 */
object CarWireframe {
    /** Each section: x from the front bumper, sill y, shoulder y, top y, shoulder half-width, top half-width. */
    private val sections = arrayOf(
        floatArrayOf(0.00f, 0.30f, 0.52f, 0.56f, 0.66f, 0.50f),   // nose
        floatArrayOf(0.15f, 0.20f, 0.66f, 0.68f, 0.82f, 0.62f),   // hood leading edge
        floatArrayOf(0.85f, 0.20f, 0.84f, 0.78f, 0.89f, 0.60f),   // over the front wheel: fenders above the hood
        floatArrayOf(1.45f, 0.20f, 0.90f, 0.93f, 0.88f, 0.70f),   // cowl / windscreen base
        floatArrayOf(2.20f, 0.20f, 0.93f, 1.29f, 0.87f, 0.56f),   // top of the windscreen
        floatArrayOf(2.80f, 0.20f, 0.95f, 1.30f, 0.87f, 0.55f),   // end of the roof
        floatArrayOf(3.30f, 0.21f, 0.98f, 1.14f, 0.89f, 0.58f),   // fastback, rear haunches
        floatArrayOf(3.72f, 0.23f, 1.00f, 1.02f, 0.88f, 0.66f),   // base of the rear glass
        floatArrayOf(4.10f, 0.28f, 1.02f, 1.04f, 0.80f, 0.64f),   // ducktail lip
        floatArrayOf(4.265f, 0.40f, 0.86f, 0.98f, 0.72f, 0.56f),  // tail
    )
    private const val LENGTH = 4.27f
    const val FRONT_AXLE = 2.135f - 0.83f   // x of the front axle relative to the centre
    const val REAR_AXLE = FRONT_AXLE - 2.575f
    const val WHEEL_R = 0.32f
    const val TRACK_HALF = 0.77f

    /** Body lines as x0,y0,z0,x1,y1,z1 runs. */
    val body: FloatArray by lazy { buildBody() }

    /** Tail-light bars (lit when braking). */
    val tailLights: FloatArray by lazy {
        val x = -LENGTH / 2 + 0.02f
        floatArrayOf(
            x, 0.90f, -0.68f, x, 0.90f, -0.38f, x, 0.82f, -0.68f, x, 0.82f, -0.40f,
            x, 0.90f, 0.68f, x, 0.90f, 0.38f, x, 0.82f, 0.68f, x, 0.82f, 0.40f,
        )
    }

    /** Headlight outlines. */
    val headLights: FloatArray by lazy {
        val x = LENGTH / 2 - 0.10f
        floatArrayOf(
            x, 0.66f, -0.78f, x + 0.06f, 0.62f, -0.46f, x, 0.59f, -0.80f, x + 0.06f, 0.56f, -0.48f,
            x, 0.66f, 0.78f, x + 0.06f, 0.62f, 0.46f, x, 0.59f, 0.80f, x + 0.06f, 0.56f, 0.48f,
        )
    }

    private fun ring(s: FloatArray): Array<FloatArray> {
        val (sill, shoulder, top, w, tw) = arrayOf(s[1], s[2], s[3], s[4], s[5])
        val x = LENGTH / 2 - s[0]
        return arrayOf(
            floatArrayOf(x, sill, -w * 0.94f),
            floatArrayOf(x, shoulder, -w),
            floatArrayOf(x, top, -tw),
            floatArrayOf(x, top + (top - shoulder) * 0.06f, 0f),
            floatArrayOf(x, top, tw),
            floatArrayOf(x, shoulder, w),
            floatArrayOf(x, sill, w * 0.94f),
        )
    }

    private fun buildBody(): FloatArray {
        val out = ArrayList<Float>()
        fun seg(a: FloatArray, b: FloatArray) { out += a[0]; out += a[1]; out += a[2]; out += b[0]; out += b[1]; out += b[2] }
        val rings = sections.map { ring(it) }
        for ((i, r) in rings.withIndex()) {
            // full cross-section at the ends and pillars, just the upper half elsewhere
            val full = i == 0 || i == rings.size - 1 || i == 3 || i == 4 || i == 5 || i == 7
            for (k in 0 until r.size - 1) if (full || k in 1..4) seg(r[k], r[k + 1])
            if (i < rings.size - 1) for (k in r.indices) seg(r[k], rings[i + 1][k])
        }
        // closing the bumpers along the bottom
        seg(rings.first()[0], rings.first()[6])
        seg(rings.last()[0], rings.last()[6])
        for (side in floatArrayOf(-1f, 1f)) {
            // wheel arches
            for ((ax, z) in listOf(FRONT_AXLE to 0.87f, REAR_AXLE to 0.89f)) {
                val n = 10
                for (i in 0 until n) {
                    val a0 = PI * i / n
                    val a1 = PI * (i + 1) / n
                    seg(
                        floatArrayOf(ax + 0.37f * cos(a0).toFloat(), WHEEL_R + 0.37f * sin(a0).toFloat(), side * z),
                        floatArrayOf(ax + 0.37f * cos(a1).toFloat(), WHEEL_R + 0.37f * sin(a1).toFloat(), side * z),
                    )
                }
            }
            // door shut lines
            for (x in floatArrayOf(LENGTH / 2 - 1.55f, LENGTH / 2 - 2.95f)) {
                seg(floatArrayOf(x, 0.22f, side * 0.86f), floatArrayOf(x, 0.92f, side * 0.875f))
            }
        }
        return out.toFloatArray()
    }

    /** One wheel as segments: tyre outer edge, rim, and five spokes turned by [spin] radians. */
    fun wheel(cx: Float, cz: Float, spin: Float): FloatArray {
        val out = ArrayList<Float>()
        val n = 18
        val outerZ = cz + if (cz > 0) 0.12f else -0.12f
        for (face in floatArrayOf(cz, outerZ)) {
            for (i in 0 until n) {
                val a0 = 2 * PI * i / n
                val a1 = 2 * PI * (i + 1) / n
                out += floatArrayOf(
                    cx + WHEEL_R * cos(a0).toFloat(), WHEEL_R + WHEEL_R * sin(a0).toFloat(), face,
                    cx + WHEEL_R * cos(a1).toFloat(), WHEEL_R + WHEEL_R * sin(a1).toFloat(), face,
                ).toList()
            }
        }
        val rim = WHEEL_R * 0.66f
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n
            val a1 = 2 * PI * (i + 1) / n
            out += floatArrayOf(
                cx + rim * cos(a0).toFloat(), WHEEL_R + rim * sin(a0).toFloat(), outerZ,
                cx + rim * cos(a1).toFloat(), WHEEL_R + rim * sin(a1).toFloat(), outerZ,
            ).toList()
        }
        for (k in 0 until 5) {
            val a = spin + 2 * PI * k / 5
            out += floatArrayOf(
                cx + rim * 0.18f * cos(a).toFloat(), WHEEL_R + rim * 0.18f * sin(a).toFloat(), outerZ,
                cx + rim * cos(a).toFloat(), WHEEL_R + rim * sin(a).toFloat(), outerZ,
            ).toList()
        }
        return out.toFloatArray()
    }
}
