package fr.thermohabitat.thermal.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Scène de test : radiateur en fonte à éléments (façade plane) devant un mur texturé, photographié par une caméra
 * sténopé tournée (lacet autour de la verticale, tangage, léger roulis).
 *
 * Façade : colonnes arrondies ombrées (éclairage latéral, reflet), joints sombres, moyeux haut et bas avec lignes
 * d'assemblage, faces latérales des colonnes visibles de biais, pieds, ombre portée, plinthe et sol.
 * Image : vignetage, léger flou, bruit gaussien. Coins « pointés » avec une erreur de placement uniforme.
 * Objets posés devant (dossiers de chaises, pot, boîte, feuille, tiges) : voir [Occluder].
 *
 * Repère de la façade (mm) : x vers la droite, y vers le bas, corps du radiateur sur [0, L] × [0, H].
 */
internal class SyntheticRadiatorScene(
    val elements: Int,
    val pitchMm: Double = 60.0,
    val heightMm: Double = 600.0,
    val yawDeg: Double = 0.0,
    val tiltDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    val imageWidth: Int = 1200,
    val imageHeight: Int = 900,
    /** Focale vraie / grand côté de l'image. */
    val focalFactor: Double = 0.72,
    val noise: Double = 4.0,
    val seed: Int = 1,
    /** Radiateur sombre : le mur clair est visible entre les éléments (joints clairs). */
    val darkRadiator: Boolean = false,
    /** Position (mm, coin haut-gauche) d'une carte bancaire plaquée sur la façade, sinon null. */
    val cardAtMm: ImagePoint? = null,
    /** Erreur de placement des coins : ± fraction de la taille (largeur / hauteur) du radiateur dans l'image. */
    val cornerError: Double = 0.015,
    /** Encombrement du radiateur dans l'image vue de face. */
    val fill: Double = 0.6,
    /** Rainure au milieu de chaque colonne (0 : aucune ; 1 : aussi sombre qu'un joint) : deux motifs par élément. */
    val grooveDepth: Double = 0.0,
    /** Objets opaques devant le radiateur (repère de la façade, mm), du premier plan vers l'arrière. */
    val occluders: List<Occluder> = emptyList(),
) {
    val lengthMm: Double = elements * pitchMm
    val trueAspect: Double get() = lengthMm / heightMm
    val focalPx: Double = focalFactor * max(imageWidth, imageHeight)

    private val cx = imageWidth / 2.0
    private val cy = imageHeight / 2.0
    private val camPos: DoubleArray
    private val ax: DoubleArray
    private val ay: DoubleArray
    private val az: DoubleArray

    init {
        val yaw = Math.toRadians(yawDeg); val tilt = Math.toRadians(tiltDeg); val roll = Math.toRadians(rollDeg)
        val target = doubleArrayOf(lengthMm / 2, heightMm / 2, 0.0)
        val d = doubleArrayOf(sin(yaw) * cos(tilt), sin(tilt), cos(yaw) * cos(tilt))
        val dist = focalPx * max(lengthMm / (fill * imageWidth), heightMm / (fill * imageHeight))
        camPos = DoubleArray(3) { target[it] - dist * d[it] }
        az = d
        val x0 = unit(doubleArrayOf(d[2], 0.0, -d[0]))
        val y0 = cross(az, x0)
        ax = DoubleArray(3) { x0[it] * cos(roll) + y0[it] * sin(roll) }
        ay = DoubleArray(3) { -x0[it] * sin(roll) + y0[it] * cos(roll) }
    }

    /** Projection d'un point de la façade (mm) dans l'image (px). */
    fun project(xMm: Double, yMm: Double): ImagePoint {
        val v = doubleArrayOf(xMm - camPos[0], yMm - camPos[1], -camPos[2])
        val x = dot(v, ax); val y = dot(v, ay); val z = dot(v, az)
        return ImagePoint(focalPx * x / z + cx, focalPx * y / z + cy)
    }

    /** Coins vrais du corps du radiateur (haut-gauche, haut-droit, bas-droit, bas-gauche). */
    val trueCorners: List<ImagePoint> = listOf(project(0.0, 0.0), project(lengthMm, 0.0), project(lengthMm, heightMm), project(0.0, heightMm))

    /** Coins « pointés » par l'utilisateur : erreur uniforme ± cornerError × taille du radiateur dans l'image. */
    val pointedCorners: List<ImagePoint> = run {
        val rnd = Random(seed * 7919 + 13)
        val bw = trueCorners.maxOf { it.x } - trueCorners.minOf { it.x }
        val bh = trueCorners.maxOf { it.y } - trueCorners.minOf { it.y }
        trueCorners.map { ImagePoint(it.x + rnd.nextDouble(-1.0, 1.0) * cornerError * bw, it.y + rnd.nextDouble(-1.0, 1.0) * cornerError * bh) }
    }

    /** Coins vrais de la carte (paysage), null sans carte. */
    val trueCardCorners: List<ImagePoint>? = cardAtMm?.let { c ->
        listOf(project(c.x, c.y), project(c.x + CARD_W, c.y), project(c.x + CARD_W, c.y + CARD_H), project(c.x, c.y + CARD_H))
    }

    /** Coins de la carte pointés à ±1 px (loupe). */
    val pointedCardCorners: List<ImagePoint>? = trueCardCorners?.let { pts ->
        val rnd = Random(seed * 31 + 5)
        pts.map { ImagePoint(it.x + rnd.nextDouble(-1.0, 1.0), it.y + rnd.nextDouble(-1.0, 1.0)) }
    }

    val image: GrayImage by lazy { render() }

    private fun render(): GrayImage {
        val w = imageWidth; val h = imageHeight
        val raw = FloatArray(w * h)
        val offsets = doubleArrayOf(0.25, 0.75)
        for (py in 0 until h) for (px in 0 until w) {
            var acc = 0.0
            for (oy in offsets) for (ox in offsets) {
                val u = (px + ox - cx) / focalPx; val v = (py + oy - cy) / focalPx
                val rx = ax[0] * u + ay[0] * v + az[0]
                val ry = ax[1] * u + ay[1] * v + az[1]
                val rz = ax[2] * u + ay[2] * v + az[2]
                if (rz <= 1e-9) { acc += 40.0; continue }
                val t = -camPos[2] / rz
                acc += radiance(camPos[0] + t * rx, camPos[1] + t * ry)
            }
            val r2 = ((px - cx) * (px - cx) + (py - cy) * (py - cy)) / (cx * cx + cy * cy)
            raw[py * w + px] = (acc / 4 * (1 - 0.15 * r2)).toFloat()
        }
        // Léger flou [1 2 1] séparable puis bruit gaussien.
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = raw[y * w + max(0, x - 1)]; val b = raw[y * w + x]; val c = raw[y * w + min(w - 1, x + 1)]
            tmp[y * w + x] = (a + 2 * b + c) / 4
        }
        val rnd = java.util.Random(seed.toLong() * 104729 + 7)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = tmp[max(0, y - 1) * w + x]; val b = tmp[y * w + x]; val c = tmp[min(h - 1, y + 1) * w + x]
            out[y * w + x] = ((a + 2 * b + c) / 4 + noise * rnd.nextGaussian()).coerceIn(0.0, 255.0).toFloat()
        }
        return GrayImage(w, h, out)
    }

    /** Luminance d'un point de la façade ou du mur (mm). */
    private fun radiance(x: Double, y: Double): Double {
        // Fenêtre à gauche : éclairage décroissant vers la droite, un peu plus sombre vers le bas.
        val light = (1.12 - 0.38 * ((x + 400) / (lengthMm + 800)).coerceIn(0.0, 1.0)) * (1 - 0.08 * (y / heightMm).coerceIn(-1.0, 2.0))
        for (o in occluders) if (o.contains(x, y)) return light * o.shade(x, y)
        val c = cardAtMm
        if (c != null && x >= c.x && x <= c.x + CARD_W && y >= c.y && y <= c.y + CARD_H) {
            val chip = x - c.x in 8.0..20.0 && y - c.y in 18.0..28.0
            return light * (if (chip) 175.0 else 95.0)
        }
        if (x in 0.0..lengthMm && y in 0.0..heightMm) return light * radiator(x, y)
        // Pieds sous le corps.
        if (y > heightMm && y < heightMm + 60) {
            val inFoot = (x in pitchMm * 0.5..pitchMm * 1.5) || (x in lengthMm - pitchMm * 1.5..lengthMm - pitchMm * 0.5)
            if (inFoot) return light * albedo() * 0.4
        }
        return light * wall(x, y)
    }

    private fun albedo() = if (darkRadiator) 70.0 else 205.0

    private fun radiator(x: Double, y: Double): Double {
        val k = floor(x / pitchMm).coerceIn(0.0, elements - 1.0)
        val u = x / pitchMm - k
        val a = albedo() * (1 + 0.03 * (hash(k.toInt(), 3) - 0.5)) + 6 * (valueNoise(x / 4, y / 4, 11) - 0.5)
        val hub = 0.085 * heightMm
        val du = min(u, 1 - u)
        if (y < hub || y > heightMm - hub) {
            val t = if (y < hub) y / hub * 2 - 1 else (y - (heightMm - hub)) / hub * 2 - 1
            var shade = 0.45 + 0.55 * sqrt(max(0.0, 1 - t * t))
            shade *= 1 - 0.45 * exp(-(du * pitchMm / 1.2).let { it * it })
            return a * shade
        }
        val gapHalf = 0.11
        val s = if (u < 0.5) u else u - 1 // position relative au joint (fraction de pas)
        val column = run {
            val v = ((u - 0.5) / (0.5 - gapHalf)).coerceIn(-1.0, 1.0)
            val cyl = sqrt(max(0.0, 1 - v * v))
            val diffuse = max(0.0, 0.85 * cyl - 0.35 * v)
            val spec = 0.25 * exp(-((v + 0.35) / 0.12).let { it * it })
            val groove = 1 - grooveDepth * 0.78 * exp(-((u - 0.5) / 0.03).let { it * it })
            a * (0.25 + 0.75 * diffuse + spec) * groove
        }
        val gap = run {
            // De biais, la face latérale d'une colonne masque une partie du joint.
            val side = 2 * gapHalf * min(1.0, abs(sin(Math.toRadians(yawDeg))))
            val onSide = if (yawDeg > 0) s >= gapHalf - side else s <= -gapHalf + side
            when {
                side > 0 && onSide -> a * 0.55
                darkRadiator -> wall(x, y) * 0.9
                else -> a * 0.22
            }
        }
        val t = smoothstep(gapHalf - 0.02, gapHalf + 0.02, du)
        return gap * (1 - t) + column * t
    }

    private fun wall(x: Double, y: Double): Double {
        var v = 222.0 + 10 * (valueNoise(x / 30, y / 30, 1) - 0.5) + 6 * (valueNoise(x / 7, y / 7, 2) - 0.5)
        // Ombre portée du radiateur (éclairage venant de la gauche).
        if (x > lengthMm && x < lengthMm + 35 && y > 15 && y < heightMm + 15) v *= 0.86
        if (y > heightMm + 110 && y <= heightMm + 180) v = 238.0
        if (y > heightMm + 180) v = 120.0 + 12 * (valueNoise(x / 15, y / 15, 3) - 0.5)
        return v
    }

    private fun valueNoise(x: Double, y: Double, salt: Int): Double {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = smooth(x - x0); val fy = smooth(y - y0)
        fun h(i: Int, j: Int) = hash(i * 73_856_093 xor j * 19_349_663, salt)
        val a = h(x0, y0) * (1 - fx) + h(x0 + 1, y0) * fx
        val b = h(x0, y0 + 1) * (1 - fx) + h(x0 + 1, y0 + 1) * fx
        return a * (1 - fy) + b * fy
    }

    private fun hash(i: Int, salt: Int): Double {
        var z = (i.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor ((salt + seed * 1013).toLong() shl 32)
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 11).toDouble() / (1L shl 53).toDouble())
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)
    private fun smoothstep(a: Double, b: Double, x: Double) = smooth(((x - a) / (b - a)).coerceIn(0.0, 1.0))

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun unit(a: DoubleArray): DoubleArray { val n = sqrt(dot(a, a)); return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n) }

    /** Objet opaque posé devant la façade : zone (mm) et luminance propre (avant éclairage). */
    class Occluder(val contains: (Double, Double) -> Boolean, val shade: (Double, Double) -> Double)

    companion object {
        const val CARD_W = 85.60
        const val CARD_H = 53.98

        /** Dossier de chaise : forme arrondie (superellipse) claire, ombrée, tissu légèrement texturé, couture en biais. */
        fun chairBack(cx: Double, cy: Double, rx: Double, ry: Double, lum: Double = 185.0) = Occluder(
            contains = { x, y -> Math.pow(abs(x - cx) / rx, 3.0) + Math.pow(abs(y - cy) / ry, 3.0) <= 1 },
            shade = { x, y ->
                val u = (x - cx) / rx; val v = (y - cy) / ry
                val seam = if (abs(u + 0.55 - 0.3 * v) < 0.015) 0.6 else 1.0
                lum * (0.92 - 0.12 * u - 0.05 * v) * seam * (1 + 0.03 * sin(x * 0.9) * sin(y * 0.7))
            },
        )

        /** Objet rectangulaire : uni (pot, boîte unie) ou imprimé (pavés de luminances variées). */
        fun box(x0: Double, y0: Double, x1: Double, y1: Double, lum: Double, printed: Boolean = false) = Occluder(
            contains = { x, y -> x in x0..x1 && y in y0..y1 },
            shade = { x, y ->
                if (!printed) lum * (1 - 0.1 * (x - x0) / (x1 - x0)) else {
                    val i = floor((x - x0) / 23).toInt(); val j = floor((y - y0) / 17).toInt()
                    val h = ((i * 73_856_093) xor (j * 19_349_663)).toLong().and(0xFFFF) / 65_535.0
                    lum * (0.45 + 0.55 * h)
                }
            },
        )

        /** Feuille sombre (ellipse). */
        fun leaf(cx: Double, cy: Double, rx: Double, ry: Double, lum: Double = 45.0) = Occluder(
            contains = { x, y -> ((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it } <= 1 },
            shade = { _, _ -> lum },
        )

        /** Tige de plante sombre, presque verticale (faux joint), de [y0] à [y1], inclinée de [lean] mm par mm. */
        fun stem(x: Double, y0: Double, y1: Double, width: Double, lean: Double = 0.03, lum: Double = 40.0) = Occluder(
            contains = { px, py -> py in y0..y1 && abs(px - (x + lean * (py - y0))) <= width / 2 },
            shade = { _, _ -> lum },
        )
    }
}
