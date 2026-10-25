package com.sickdash.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A procedurally lofted GR86 (ZN8) in metres: 4.265 m long, 2.575 m wheelbase, 1.31 m tall.
 * x is forward from the car's centre, y up from the ground, z to the driver's right.
 * Beyond the body loft it carries the details that make it read as a GR86: the wide trapezoid
 * grille and corner fangs, slim swept headlights, the vertical air outlet behind the front wheel
 * with its rising rocker line, door-mounted mirrors, the pointed rear side glass, shark fin,
 * wraparound L tail lights joined by a black bar, ducktail, diffuser and twin round exhausts.
 */
object CarWireframe {
    private const val LENGTH = 4.265f
    private const val FRONT_OVERHANG = 0.83f
    private const val WHEELBASE = 2.575f
    const val FRONT_AXLE = LENGTH / 2 - FRONT_OVERHANG
    const val REAR_AXLE = FRONT_AXLE - WHEELBASE
    const val WHEEL_R = 0.315f      // 215/40R18
    const val TRACK_HALF = 0.77f

    /** Each section: x from the front bumper, sill y, shoulder y, top y, shoulder half-width, top half-width. */
    private val sections = arrayOf(
        floatArrayOf(0.00f, 0.30f, 0.50f, 0.54f, 0.64f, 0.48f),   // nose
        floatArrayOf(0.15f, 0.20f, 0.66f, 0.68f, 0.82f, 0.62f),   // hood leading edge
        floatArrayOf(0.85f, 0.20f, 0.84f, 0.78f, 0.89f, 0.60f),   // over the front wheel: fenders above the hood
        floatArrayOf(1.45f, 0.20f, 0.90f, 0.93f, 0.88f, 0.70f),   // cowl / windscreen base
        floatArrayOf(2.22f, 0.20f, 0.93f, 1.29f, 0.87f, 0.56f),   // top of the windscreen
        floatArrayOf(2.78f, 0.20f, 0.95f, 1.30f, 0.87f, 0.55f),   // end of the roof
        floatArrayOf(3.30f, 0.21f, 0.98f, 1.13f, 0.90f, 0.58f),   // fastback over the rear haunches
        floatArrayOf(3.72f, 0.23f, 1.00f, 1.02f, 0.89f, 0.66f),   // base of the rear glass
        floatArrayOf(4.10f, 0.28f, 1.02f, 1.05f, 0.80f, 0.64f),   // ducktail lip
        floatArrayOf(4.265f, 0.40f, 0.86f, 0.98f, 0.72f, 0.56f),  // tail
    )

    /** How far a lowered car's body sits below stock, relative to the wheels. */
    private const val LOWERED_DROP = 0.03f

    private val bodies = HashMap<Boolean, FloatArray>()
    private val heads = HashMap<Boolean, FloatArray>()
    private val tails = HashMap<Boolean, FloatArray>()

    /**
     * Body shell lines as x0,y0,z0,x1,y1,z1 runs. With [kit] the car is lowered and wears a
     * front lip splitter, side skirts and a raised ducktail.
     */
    @Synchronized fun body(kit: Boolean): FloatArray = bodies.getOrPut(kit) { buildBody(kit) }

    /** Front light units. */
    @Synchronized fun headLights(kit: Boolean): FloatArray = heads.getOrPut(kit) { buildHeadLights(kit) }

    /** Tail lights and the bar between them (lit when braking). */
    @Synchronized fun tailLights(kit: Boolean): FloatArray = tails.getOrPut(kit) { buildTailLights(kit) }

    // ---------------------------------------------------------------------------------------

    private class Lines(private val drop: Float = 0f) {
        val out = ArrayList<Float>()

        /** A segment, with x measured from the front bumper. */
        fun seg(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            out += LENGTH / 2 - x0; out += y0 - drop; out += z0
            out += LENGTH / 2 - x1; out += y1 - drop; out += z1
        }

        /** A polyline through (xFront, y, z) triples, optionally closed. */
        fun poly(vararg p: Float, closed: Boolean = false) {
            val n = p.size / 3
            for (i in 0 until n - 1) seg(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], p[i * 3 + 3], p[i * 3 + 4], p[i * 3 + 5])
            if (closed && n > 2) seg(p[(n - 1) * 3], p[(n - 1) * 3 + 1], p[(n - 1) * 3 + 2], p[0], p[1], p[2])
        }

        /** The same polyline on both sides of the car (z mirrored). */
        fun both(vararg p: Float, closed: Boolean = false) {
            poly(*p, closed = closed)
            val m = p.copyOf()
            for (i in 2 until m.size step 3) m[i] = -m[i]
            poly(*m, closed = closed)
        }

        /** A circle in the plane facing along x (exhaust tips), centre given from the front. */
        fun ringX(xFront: Float, cy: Float, cz: Float, r: Float, n: Int = 12) {
            for (i in 0 until n) {
                val a0 = 2 * PI * i / n
                val a1 = 2 * PI * (i + 1) / n
                seg(
                    xFront, cy + r * sin(a0).toFloat(), cz + r * cos(a0).toFloat(),
                    xFront, cy + r * sin(a1).toFloat(), cz + r * cos(a1).toFloat(),
                )
            }
        }

        fun array() = out.toFloatArray()
    }

    private fun ring(s: FloatArray): Array<FloatArray> {
        val (x, sill, shoulder, top, w) = arrayOf(s[0], s[1], s[2], s[3], s[4])
        val tw = s[5]
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

    private fun buildBody(kit: Boolean): FloatArray {
        val l = Lines(if (kit) LOWERED_DROP else 0f)
        fun seg(a: FloatArray, b: FloatArray) = l.seg(a[0], a[1], a[2], b[0], b[1], b[2])
        val rings = sections.map { ring(it) }
        for ((i, r) in rings.withIndex()) {
            // full cross-section at the ends and pillars, just the upper half elsewhere
            val full = i == 0 || i == rings.size - 1 || i == 3 || i == 7
            for (k in 0 until r.size - 1) if (full || k in 1..4) seg(r[k], r[k + 1])
            if (i < rings.size - 1) for (k in r.indices) seg(r[k], rings[i + 1][k])
        }
        seg(rings.first()[0], rings.first()[6])
        seg(rings.last()[0], rings.last()[6])

        val fa = FRONT_OVERHANG           // axle positions measured from the front
        val ra = FRONT_OVERHANG + WHEELBASE
        for (side in floatArrayOf(-1f, 1f)) {
            // wheel arches, the rear one flared wider
            for ((ax, z) in listOf(fa to 0.875f, ra to 0.90f)) {
                val n = 12
                for (i in 0 until n) {
                    val a0 = PI * i / n
                    val a1 = PI * (i + 1) / n
                    l.seg(
                        ax - 0.38f * cos(a0).toFloat(), WHEEL_R + 0.38f * sin(a0).toFloat(), side * z,
                        ax - 0.38f * cos(a1).toFloat(), WHEEL_R + 0.38f * sin(a1).toFloat(), side * z,
                    )
                }
            }
        }

        // ---- front: trapezoid grille with mesh, corner fangs, hood creases
        l.poly(0.02f, 0.45f, -0.40f, 0.02f, 0.45f, 0.40f, 0.06f, 0.25f, 0.50f, 0.06f, 0.25f, -0.50f, closed = true)
        for (k in 1..3) {
            val t = k / 4f
            val y = 0.45f - 0.20f * t
            val zz = 0.40f + 0.10f * t
            l.seg(0.02f + 0.04f * t, y, -zz, 0.02f + 0.04f * t, y, zz)
        }
        for (k in -3..3) {
            val z = k * 0.12f
            l.seg(0.02f, 0.45f, z, 0.06f, 0.25f, z * 1.2f)
        }
        l.both(0.05f, 0.47f, 0.56f, 0.12f, 0.27f, 0.62f, 0.16f, 0.27f, 0.70f, 0.09f, 0.47f, 0.62f, closed = true)
        l.both(0.30f, 0.69f, 0.36f, 1.40f, 0.86f, 0.46f)   // hood creases running up to the cowl
        // front lip
        l.poly(0.04f, 0.21f, -0.66f, 0.03f, 0.20f, 0.0f, 0.04f, 0.21f, 0.66f)

        // ---- side: air outlet behind the front wheel and the rocker line rising from it
        l.both(1.24f, 0.62f, 0.885f, 1.30f, 0.62f, 0.885f, 1.22f, 0.31f, 0.885f, 1.17f, 0.33f, 0.885f, closed = true)
        l.both(1.22f, 0.31f, 0.885f, 2.20f, 0.30f, 0.88f, 2.80f, 0.38f, 0.88f, 3.02f, 0.47f, 0.89f)
        // door shut lines, handle
        l.both(1.47f, 0.24f, 0.86f, 1.45f, 0.92f, 0.875f)
        l.both(2.74f, 0.25f, 0.87f, 2.80f, 0.94f, 0.88f)
        l.both(2.36f, 0.80f, 0.885f, 2.48f, 0.80f, 0.885f)
        // side glass: A-pillar, roof rail, pointed rear quarter, with the B-pillar
        l.both(
            1.52f, 0.95f, 0.84f, 2.20f, 1.24f, 0.62f, 2.80f, 1.25f, 0.60f, 3.12f, 1.12f, 0.70f,
            3.42f, 0.99f, 0.82f, closed = false,
        )
        l.both(1.52f, 0.95f, 0.84f, 3.42f, 0.99f, 0.82f)
        l.both(2.70f, 0.96f, 0.835f, 2.74f, 1.24f, 0.61f)
        // mirrors on the doors
        l.both(1.58f, 0.92f, 0.86f, 1.64f, 1.02f, 1.02f, 1.80f, 1.01f, 1.03f, 1.76f, 0.92f, 0.87f, closed = true)
        // fuel door on the right rear haunch
        l.poly(3.30f, 0.84f, 0.895f, 3.44f, 0.84f, 0.895f, 3.44f, 0.72f, 0.90f, 3.30f, 0.72f, 0.90f, closed = true)

        // ---- roof: shark fin
        l.poly(2.90f, 1.27f, 0f, 3.06f, 1.33f, 0f, 3.12f, 1.22f, 0f, closed = true)

        // ---- rear: ducktail lip edge, diffuser, twin round exhausts, reflector
        l.seg(4.16f, 1.06f, -0.62f, 4.16f, 1.06f, 0.62f)
        l.poly(4.265f, 0.50f, -0.72f, 4.265f, 0.50f, 0.72f)
        l.poly(4.24f, 0.30f, -0.70f, 4.20f, 0.24f, -0.40f, 4.20f, 0.24f, 0.40f, 4.24f, 0.30f, 0.70f)
        for (z in floatArrayOf(-0.20f, 0f, 0.20f)) l.seg(4.21f, 0.40f, z, 4.12f, 0.24f, z)
        for (side in floatArrayOf(-1f, 1f)) {
            l.ringX(4.27f, 0.34f, side * 0.56f, 0.055f)
            l.ringX(4.27f, 0.34f, side * 0.56f, 0.035f, 10)
        }
        l.poly(4.265f, 0.42f, -0.12f, 4.265f, 0.42f, 0.12f, 4.265f, 0.38f, 0.12f, 4.265f, 0.38f, -0.12f, closed = true)

        if (kit) {
            // front lip splitter, sticking out ahead of the bumper
            l.poly(0.16f, 0.17f, -0.82f, -0.03f, 0.16f, -0.64f, -0.05f, 0.16f, 0f, -0.03f, 0.16f, 0.64f, 0.16f, 0.17f, 0.82f)
            l.poly(0.12f, 0.20f, -0.80f, -0.02f, 0.19f, -0.62f, -0.03f, 0.19f, 0f, -0.02f, 0.19f, 0.62f, 0.12f, 0.20f, 0.80f)
            l.both(-0.03f, 0.16f, 0.64f, -0.02f, 0.19f, 0.62f)
            // side skirts
            l.both(1.24f, 0.20f, 0.89f, 1.30f, 0.15f, 0.93f, 2.98f, 0.15f, 0.93f, 3.03f, 0.21f, 0.91f)
            l.both(1.30f, 0.15f, 0.93f, 1.32f, 0.20f, 0.90f)
            // raised ducktail: the lip kicks up behind the boot
            l.seg(4.21f, 1.13f, -0.60f, 4.21f, 1.13f, 0.60f)
            l.both(4.04f, 1.05f, 0.62f, 4.21f, 1.13f, 0.60f, 4.24f, 1.03f, 0.58f)
            l.seg(4.13f, 1.09f, -0.61f, 4.13f, 1.09f, 0.61f)
        }
        return l.array()
    }

    private fun buildHeadLights(kit: Boolean): FloatArray {
        val l = Lines(if (kit) LOWERED_DROP else 0f)
        // slim, swept-back lamps that wrap up onto the fender
        l.both(
            0.08f, 0.60f, 0.42f, 0.16f, 0.66f, 0.58f, 0.42f, 0.76f, 0.80f, 0.48f, 0.74f, 0.84f,
            0.34f, 0.67f, 0.80f, 0.12f, 0.57f, 0.50f, closed = true,
        )
        // the L-shaped running light inside
        l.both(0.14f, 0.61f, 0.50f, 0.36f, 0.71f, 0.76f, 0.40f, 0.68f, 0.80f)
        return l.array()
    }

    private fun buildTailLights(kit: Boolean): FloatArray {
        val l = Lines(if (kit) LOWERED_DROP else 0f)
        // L-shaped lamps wrapping round the rear corners
        l.both(
            4.255f, 0.86f, 0.34f, 4.24f, 0.88f, 0.70f, 4.10f, 0.88f, 0.84f, 4.08f, 0.82f, 0.85f,
            4.22f, 0.81f, 0.70f, 4.26f, 0.80f, 0.36f, closed = true,
        )
        l.both(4.25f, 0.84f, 0.40f, 4.18f, 0.85f, 0.78f)
        // the black bar joining them across the boot
        l.seg(4.258f, 0.84f, -0.34f, 4.258f, 0.84f, 0.34f)
        l.seg(4.262f, 0.82f, -0.34f, 4.262f, 0.82f, 0.34f)
        return l.array()
    }

    /**
     * One wheel as segments: tyre edges, a big 18" rim, turned by [spin]. Either the stock ten
     * spokes in pairs, or [fiveSpoke]: five broad, outlined spokes like a forged track wheel.
     */
    fun wheel(cx: Float, cz: Float, spin: Float, fiveSpoke: Boolean = false): FloatArray {
        val out = ArrayList<Float>()
        fun seg(a: FloatArray, b: FloatArray) { out += a.toList(); out += b.toList() }
        fun pt(r: Float, a: Double, z: Float) = floatArrayOf(cx + r * cos(a).toFloat(), WHEEL_R + r * sin(a).toFloat(), z)
        val n = 20
        val outerZ = cz + if (cz > 0) 0.11f else -0.11f
        val rim = WHEEL_R * 0.73f
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n
            val a1 = 2 * PI * (i + 1) / n
            seg(pt(WHEEL_R, a0, cz), pt(WHEEL_R, a1, cz))
            seg(pt(WHEEL_R, a0, outerZ), pt(WHEEL_R, a1, outerZ))
            seg(pt(rim, a0, outerZ), pt(rim, a1, outerZ))
        }
        for (i in 0 until 8) seg(pt(rim * 0.2f, 2 * PI * i / 8, outerZ), pt(rim * 0.2f, 2 * PI * (i + 1) / 8, outerZ))
        if (fiveSpoke) {
            // five broad spokes, each outlined as a wedge from the hub to the lip, and the lug nuts
            for (k in 0 until 5) {
                val base = spin + 2 * PI * k / 5
                seg(pt(rim * 0.22f, base - 0.28, outerZ), pt(rim * 0.96f, base - 0.20, outerZ))
                seg(pt(rim * 0.22f, base + 0.28, outerZ), pt(rim * 0.96f, base + 0.20, outerZ))
                seg(pt(rim * 0.30f, base + PI / 5, outerZ), pt(rim * 0.36f, base + PI / 5, outerZ))
            }
        } else {
            // five split spokes (ten arms)
            for (k in 0 until 5) {
                val base = spin + 2 * PI * k / 5
                for (d in doubleArrayOf(-0.13, 0.13)) seg(pt(rim * 0.2f, base, outerZ), pt(rim, base + d * 1.6, outerZ))
            }
        }
        return out.toFloatArray()
    }
}
