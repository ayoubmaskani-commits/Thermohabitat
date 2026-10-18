package fr.thermohabitat.thermal.calc

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Loi d'eau (courbe de chauffe) linéaire entre le point de base et le pied de courbe,
 * bornée par les températures de départ minimale et maximale.
 */
@Serializable
data class HeatingCurve(
    /** Température extérieure du point de base (en général θe), °C. */
    val designOutdoorC: Double = -7.0,
    /** Température de départ au point de base, °C. */
    val designSupplyC: Double = 55.0,
    /** Température extérieure du pied de courbe (non-chauffage), °C. */
    val footOutdoorC: Double = 20.0,
    /** Température de départ au pied de courbe, °C. */
    val footSupplyC: Double = 20.0,
    val minSupplyC: Double = 25.0,
    val maxSupplyC: Double = 65.0,
    /** Écart départ/retour au point de base, K (diminue proportionnellement à la charge). */
    val designDeltaK: Double = 7.0,
) {
    fun supplyAt(outdoorC: Double): Double {
        val span = footOutdoorC - designOutdoorC
        val t = if (abs(span) < 1e-9) 0.0 else (outdoorC - designOutdoorC) / span
        val raw = designSupplyC + t * (footSupplyC - designSupplyC)
        // Bornes saisies dans le désordre (saisie en cours, import) : jamais d'exception.
        return raw.coerceIn(minOf(minSupplyC, maxSupplyC), maxOf(minSupplyC, maxSupplyC))
    }

    /** Pente de la courbe (K d'eau par K extérieur). */
    val slope: Double get() = (designSupplyC - footSupplyC) / (footOutdoorC - designOutdoorC)
}

/** Point de performance constructeur d'une PAC air/eau. */
@Serializable
data class HeatPumpPoint(
    val outdoorC: Double,
    val waterC: Double,
    /** Puissance calorifique disponible, kW. */
    val capacityKw: Double? = null,
    /** Coefficient de performance. */
    val cop: Double? = null,
)

@Serializable
data class HeatPumpModel(
    val id: Long = 0,
    val name: String = "PAC air/eau",
    val manufacturer: String = "",
    val reference: String = "",
    /** Puissance « commerciale » annoncée (pour information : ce n'est PAS le besoin du bâtiment), kW. */
    val nominalCapacityKw: Double? = null,
    /** Conditions de la puissance nominale (souvent A7/W35). */
    val nominalOutdoorC: Double = 7.0,
    val nominalWaterC: Double = 35.0,
    val points: List<HeatPumpPoint> = emptyList(),
)

/** Performance interpolée en un point de fonctionnement. */
data class HeatPumpOperatingPoint(
    val outdoorC: Double,
    val waterC: Double,
    val capacityKw: Double?,
    val cop: Double,
    /** Vrai si la valeur provient du modèle générique et non des données constructeur. */
    val generic: Boolean,
    /** Vrai si le point est hors de la plage des données constructeur (valeur transposée ou bornée). */
    val extrapolated: Boolean,
)

/**
 * Performances d'une PAC : interpolation des données constructeur, sinon modèle générique
 * fondé sur le rendement de Carnot (clairement identifié comme estimation).
 */
class HeatPumpPerformance(
    private val model: HeatPumpModel,
    /** Fraction du COP de Carnot atteinte par une PAC air/eau courante (≈ 0,45–0,55). */
    private val carnotEfficiency: Double = 0.50,
) {
    private val capacityPoints = model.points.filter { it.capacityKw != null }
    private val copPoints = model.points.filter { it.cop != null }

    val hasManufacturerCop: Boolean get() = copPoints.isNotEmpty()
    val hasManufacturerCapacity: Boolean get() = capacityPoints.isNotEmpty()

    fun at(outdoorC: Double, waterC: Double): HeatPumpOperatingPoint {
        var extrapolated = false
        val cop = if (copPoints.isNotEmpty()) {
            val (v, ex) = interpolate(copPoints, outdoorC, waterC, Companion::carnotRatio) { it.cop!! }
            extrapolated = extrapolated || ex
            v.coerceAtLeast(1.0)
        } else genericCop(outdoorC, waterC, carnotEfficiency)

        val capacity: Double? = when {
            capacityPoints.size >= 2 -> {
                val (v, ex) = interpolate(capacityPoints, outdoorC, waterC, Companion::capacityRatio) { it.capacityKw!! }
                extrapolated = extrapolated || ex
                v
            }
            capacityPoints.size == 1 -> {
                val p = capacityPoints.first()
                genericCapacity(p.capacityKw!!, p.outdoorC, p.waterC, outdoorC, waterC)
            }
            model.nominalCapacityKw != null ->
                genericCapacity(model.nominalCapacityKw, model.nominalOutdoorC, model.nominalWaterC, outdoorC, waterC)
            else -> null
        }
        return HeatPumpOperatingPoint(outdoorC, waterC, capacity, cop, copPoints.isEmpty(), extrapolated)
    }

    companion object {
        /**
         * COP générique : η · Tchaud / (Tchaud − Tfroid), avec un pincement de 3 K au condenseur
         * et de 8 K à l'évaporateur. Borné entre 1 et 7.
         */
        fun genericCop(outdoorC: Double, waterC: Double, efficiency: Double = 0.50): Double =
            (efficiency * carnotCop(outdoorC, waterC)).coerceIn(1.0, 7.0)

        /** COP de Carnot avec les pincements du modèle générique (sans rendement ni bornes). */
        internal fun carnotCop(outdoorC: Double, waterC: Double): Double {
            val th = waterC + 273.15 + 3.0
            val tc = outdoorC + 273.15 - 8.0
            return th / (th - tc).coerceAtLeast(1.0)
        }

        /** Transposition d'un COP constructeur hors plage : rapport des COP de Carnot. */
        internal fun carnotRatio(refOutdoorC: Double, refWaterC: Double, outdoorC: Double, waterC: Double): Double =
            carnotCop(outdoorC, waterC) / carnotCop(refOutdoorC, refWaterC)

        /** Transposition d'une puissance constructeur hors plage : tendances du modèle générique. */
        internal fun capacityRatio(refOutdoorC: Double, refWaterC: Double, outdoorC: Double, waterC: Double): Double =
            genericCapacity(1.0, refOutdoorC, refWaterC, outdoorC, waterC)

        /**
         * Puissance générique à partir d'un point connu : −2,5 %/K lorsque la température extérieure baisse
         * et −0,5 %/K lorsque la température d'eau augmente (ordre de grandeur, à remplacer par les courbes constructeur).
         */
        fun genericCapacity(refKw: Double, refOutdoorC: Double, refWaterC: Double, outdoorC: Double, waterC: Double): Double {
            val f = (1.0 + 0.025 * (outdoorC - refOutdoorC)) * (1.0 - 0.005 * (waterC - refWaterC))
            return (refKw * f).coerceAtLeast(0.0)
        }

        /**
         * Interpolation sur des points dispersés : linéaire en température extérieure pour chaque
         * température d'eau, puis linéaire entre températures d'eau. Hors plage, dans le sens
         * défavorable (plus froid dehors, eau plus chaude), la valeur extrême est transposée par
         * [beyond] (rapport appliqué depuis le point de référence) ; dans le sens favorable elle est
         * simplement bornée, ce qui reste prudent.
         */
        internal fun interpolate(
            points: List<HeatPumpPoint>,
            outdoorC: Double,
            waterC: Double,
            beyond: ((refOutdoorC: Double, refWaterC: Double, outdoorC: Double, waterC: Double) -> Double)? = null,
            value: (HeatPumpPoint) -> Double,
        ): Pair<Double, Boolean> {
            var extrapolated = false
            val byWater = points.groupBy { it.waterC }.toSortedMap()
            fun alongOutdoor(water: Double): Double {
                val sorted = byWater.getValue(water).sortedBy { it.outdoorC }
                if (outdoorC < sorted.first().outdoorC || outdoorC > sorted.last().outdoorC) extrapolated = true
                if (outdoorC <= sorted.first().outdoorC) {
                    val p = sorted.first()
                    return value(p) * (beyond?.invoke(p.outdoorC, water, outdoorC, water) ?: 1.0)
                }
                if (outdoorC >= sorted.last().outdoorC) return value(sorted.last())
                for (i in 1 until sorted.size) {
                    val a = sorted[i - 1]; val b = sorted[i]
                    if (outdoorC <= b.outdoorC) {
                        val t = (outdoorC - a.outdoorC) / (b.outdoorC - a.outdoorC)
                        return value(a) + t * (value(b) - value(a))
                    }
                }
                return value(sorted.last())
            }
            val waters = byWater.keys.toList()
            if (waterC < waters.first() || waterC > waters.last()) extrapolated = true
            val result = when {
                waterC <= waters.first() -> alongOutdoor(waters.first())
                waterC >= waters.last() -> {
                    val w = waters.last()
                    alongOutdoor(w) * (beyond?.invoke(outdoorC, w, outdoorC, waterC) ?: 1.0)
                }
                else -> {
                    val i = waters.indexOfFirst { it >= waterC }
                    val w0 = waters[i - 1]; val w1 = waters[i]
                    val v0 = alongOutdoor(w0); val v1 = alongOutdoor(w1)
                    v0 + (waterC - w0) / (w1 - w0) * (v1 - v0)
                }
            }
            return result to extrapolated
        }
    }
}

data class BivalencePoint(
    val outdoorC: Double,
    val loadKw: Double,
    val capacityKw: Double?,
    val supplyC: Double,
    val cop: Double,
)

data class BivalenceResult(
    /** Température de bivalence (°C) : en dessous, la PAC seule ne couvre plus la charge. Null : couverture totale jusqu'à θe. */
    val bivalentTempC: Double?,
    val designLoadKw: Double,
    val capacityAtDesignKw: Double?,
    /** Appoint nécessaire à θe, kW. */
    val backupAtDesignKw: Double?,
    val points: List<BivalencePoint>,
    /** Puissance à θe hors de la plage des données constructeur (transposée). */
    val capacityExtrapolated: Boolean = false,
) {
    val coversDesignLoad: Boolean get() = capacityAtDesignKw != null && capacityAtDesignKw >= designLoadKw
}

/** Résultat du calcul saisonnier par classes de température (méthode des « bins »). */
data class SeasonalPerformance(
    val scop: Double,
    val heatDemandKwh: Double,
    val heatPumpHeatKwh: Double,
    val backupHeatKwh: Double,
    val electricityKwh: Double,
    val climateLabel: String,
    val generic: Boolean,
    /** Au moins une classe hors de la plage des données constructeur (valeurs transposées). */
    val extrapolated: Boolean = false,
    /** Faux si la puissance de la PAC est inconnue : l'appoint ne peut alors pas être évalué. */
    val capacityKnown: Boolean = true,
) {
    /** Part de la chaleur fournie par l'appoint (0–1). */
    val backupShare: Double get() = if (heatDemandKwh > 0) backupHeatKwh / heatDemandKwh else 0.0
}

object HeatPumpAnalysis {

    /**
     * Répartition horaire des températures de la saison de chauffage « climat moyen » de la
     * NF EN 14825 (température de dimensionnement −10 °C, 4 910 h).
     */
    val EN14825_AVERAGE_BINS: List<Pair<Int, Int>> = listOf(
        -10 to 1, -9 to 25, -8 to 23, -7 to 24, -6 to 27, -5 to 68, -4 to 91, -3 to 89, -2 to 165,
        -1 to 173, 0 to 240, 1 to 280, 2 to 320, 3 to 357, 4 to 356, 5 to 303, 6 to 330, 7 to 326,
        8 to 348, 9 to 335, 10 to 315, 11 to 215, 12 to 169, 13 to 151, 14 to 105, 15 to 74,
    )

    /** Charge du bâtiment (kW) à une température extérieure, proportionnelle à l'écart de température. */
    fun loadAt(designLoadKw: Double, interiorC: Double, designOutdoorC: Double, outdoorC: Double): Double {
        val denom = interiorC - designOutdoorC
        if (denom <= 0) return 0.0
        return (designLoadKw * (interiorC - outdoorC) / denom).coerceAtLeast(0.0)
    }

    fun bivalence(
        designLoadKw: Double,
        interiorC: Double,
        designOutdoorC: Double,
        curve: HeatingCurve,
        performance: HeatPumpPerformance,
        stepK: Double = 0.5,
    ): BivalenceResult {
        val points = mutableListOf<BivalencePoint>()
        var designExtrapolated = false
        var t = designOutdoorC
        while (t <= interiorC - 1.0 + 1e-9) {
            val supply = curve.supplyAt(t)
            val op = performance.at(t, supply)
            if (points.isEmpty()) designExtrapolated = op.extrapolated
            points += BivalencePoint(t, loadAt(designLoadKw, interiorC, designOutdoorC, t), op.capacityKw, supply, op.cop)
            t += stepK
        }
        val atDesign = points.firstOrNull()
        val capDesign = atDesign?.capacityKw
        var bivalent: Double? = null
        if (capDesign != null && capDesign < designLoadKw) {
            // première température où la capacité rejoint la charge
            for (i in 1 until points.size) {
                val a = points[i - 1]; val b = points[i]
                val ca = a.capacityKw ?: continue; val cb = b.capacityKw ?: continue
                val da = ca - a.loadKw; val db = cb - b.loadKw
                if (da < 0 && db >= 0) {
                    bivalent = a.outdoorC + (b.outdoorC - a.outdoorC) * (-da) / (db - da)
                    break
                }
            }
            if (bivalent == null) bivalent = points.lastOrNull()?.outdoorC
        }
        return BivalenceResult(
            bivalentTempC = bivalent,
            designLoadKw = designLoadKw,
            capacityAtDesignKw = capDesign,
            backupAtDesignKw = capDesign?.let { (designLoadKw - it).coerceAtLeast(0.0) },
            points = points,
            capacityExtrapolated = capDesign != null && designExtrapolated,
        )
    }

    /**
     * SCOP estimé par la méthode des classes de température de la NF EN 14825 (climat moyen),
     * charge linéaire nulle à [balanceC] et égale à la charge de conception à θe. La chaleur non
     * couverte par la PAC est fournie par un appoint électrique (COP = 1).
     *
     * @param binShiftK décalage optionnel des classes pour approcher un climat local
     */
    fun seasonal(
        designLoadKw: Double,
        designOutdoorC: Double,
        curve: HeatingCurve,
        performance: HeatPumpPerformance,
        balanceC: Double = 16.0,
        binShiftK: Double = 0.0,
    ): SeasonalPerformance {
        var demand = 0.0
        var hpHeat = 0.0
        var backup = 0.0
        var elec = 0.0
        var generic = false
        var extrapolated = false
        var capacityKnown = true
        for ((tj, hours) in EN14825_AVERAGE_BINS) {
            val t = tj + binShiftK
            if (t >= balanceC) continue
            val load = designLoadKw * (balanceC - t) / (balanceC - designOutdoorC)
            if (load <= 0) continue
            val op = performance.at(t, curve.supplyAt(t))
            generic = generic || op.generic
            extrapolated = extrapolated || op.extrapolated
            if (op.capacityKw == null) capacityKnown = false
            val cap = op.capacityKw ?: Double.POSITIVE_INFINITY
            val fromHp = minOf(load, cap)
            val fromBackup = load - fromHp
            demand += load * hours
            hpHeat += fromHp * hours
            backup += fromBackup * hours
            elec += fromHp * hours / op.cop + fromBackup * hours
        }
        val scop = if (elec > 0) demand / elec else 0.0
        return SeasonalPerformance(scop, demand, hpHeat, backup, elec, "Climat moyen NF EN 14825", generic, extrapolated, capacityKnown)
    }
}
