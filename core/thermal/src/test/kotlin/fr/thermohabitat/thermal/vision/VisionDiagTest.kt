package fr.thermohabitat.thermal.vision

import org.junit.Test

class VisionDiagTest {
    private fun detail(sb: StringBuilder, n: Int, pitch: Double, height: Double, yaw: Double, tilt: Double, seed: Int) {
        val sc = SyntheticRadiatorScene(n, pitchMm = pitch, heightMm = height, yawDeg = yaw, tiltDeg = tilt, seed = seed)
        val img = sc.image
        val t0 = System.nanoTime()
        val r = PhotoIdentifier.identify(img, sc.pointedCorners)
        val ms = (System.nanoTime() - t0) / 1e6
        val trueX = (0..n).map { k -> r.imageToFace.map(sc.project(k * pitch, height / 2)).x }
        val P = r.faceWidth / (r.counting.spectralCycles ?: 1.0)
        sb.append(String.format("N=%d yaw=%.0f tilt=%.0f seed=%d -> %s (%.0f ms) P=%.1f W=%d\n", n, yaw, tilt, seed, r.count, ms, P, r.faceWidth))
        sb.append("  true : " + trueX.joinToString { String.format("%.1f", it) } + "\n")
        sb.append("  found: " + r.boundariesFacePx.joinToString { String.format("%.1f", it) } + "\n")
        sb.append("  alt=" + r.alternatives + "\n")
    }

    @Test
    fun diag() {
        val sb = StringBuilder()
        detail(sb, 13, 60.0, 600.0, 20.0, 0.0, 1 + 130 + 20)
        detail(sb, 20, 50.0, 500.0, 45.0, 0.0, 2 + 200 + 45)
        detail(sb, 30, 60.0, 450.0, 20.0, 0.0, 2 + 300 + 20)
        detail(sb, 30, 60.0, 450.0, -35.0, 0.0, 2 + 300 - 35)
        detail(sb, 30, 60.0, 450.0, 35.0, 0.0, 2 + 300 + 35)
        detail(sb, 30, 60.0, 450.0, 35.0, 0.0, 1 + 300 + 35)
        println(sb)
    }
}
