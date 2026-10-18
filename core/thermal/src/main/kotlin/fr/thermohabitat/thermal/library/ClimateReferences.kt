package fr.thermohabitat.thermal.library

import fr.thermohabitat.thermal.model.Climate
import fr.thermohabitat.thermal.model.ValueOrigin

/**
 * Station climatique de référence.
 *
 * @property designTempC température extérieure de base à l'altitude de la station (°C)
 * @property meanAnnualTempC température moyenne annuelle (°C)
 * @property degreeDays18 degrés-jours unifiés base 18 °C (ordre de grandeur des normales récentes)
 */
data class ClimateReference(
    val city: String,
    val department: String,
    val altitudeM: Double,
    val designTempC: Double,
    val meanAnnualTempC: Double,
    val degreeDays18: Double,
)

/**
 * Références climatiques indicatives pour la France métropolitaine.
 *
 * IMPORTANT : ces valeurs sont des ordres de grandeur destinés à pré-remplir l'étude.
 * La température de base doit être vérifiée dans l'annexe nationale NF EN 12831-1/CN
 * (zone climatique et altitude de la commune) et les degrés-jours auprès de Météo-France.
 * Elles sont toujours marquées « estimées » et restent modifiables.
 */
object ClimateReferences {

    /** Correction indicative de la température de base avec l'altitude : −0,5 K par 100 m. */
    const val DESIGN_TEMP_LAPSE_K_PER_100M = 0.5

    /** Gradient de la température moyenne annuelle : −0,6 K par 100 m. */
    const val MEAN_TEMP_LAPSE_K_PER_100M = 0.6

    /** Augmentation indicative des DJU avec l'altitude : ≈ +140 DJU par 100 m. */
    const val DEGREE_DAYS_PER_100M = 140.0

    const val SOURCE_NOTE =
        "Valeurs indicatives (ordre de grandeur NF EN 12831-1/CN et normales Météo-France) – à vérifier pour la commune."

    val all: List<ClimateReference> = listOf(
        ClimateReference("Paris", "75", 50.0, -7.0, 12.4, 2300.0),
        ClimateReference("Lille", "59", 50.0, -9.0, 11.1, 2700.0),
        ClimateReference("Strasbourg", "67", 150.0, -15.0, 11.2, 2900.0),
        ClimateReference("Mulhouse", "68", 240.0, -15.0, 11.2, 2800.0),
        ClimateReference("Metz", "57", 190.0, -15.0, 10.8, 2900.0),
        ClimateReference("Nancy", "54", 210.0, -15.0, 10.8, 2950.0),
        ClimateReference("Reims", "51", 90.0, -10.0, 11.0, 2750.0),
        ClimateReference("Rouen", "76", 70.0, -7.0, 11.0, 2600.0),
        ClimateReference("Caen", "14", 60.0, -7.0, 11.4, 2500.0),
        ClimateReference("Brest", "29", 90.0, -4.0, 11.6, 2350.0),
        ClimateReference("Rennes", "35", 40.0, -5.0, 12.2, 2350.0),
        ClimateReference("Nantes", "44", 30.0, -5.0, 12.6, 2200.0),
        ClimateReference("Tours", "37", 100.0, -7.0, 12.1, 2350.0),
        ClimateReference("Orléans", "45", 120.0, -7.0, 11.6, 2500.0),
        ClimateReference("Poitiers", "86", 120.0, -7.0, 12.0, 2350.0),
        ClimateReference("Limoges", "87", 280.0, -8.0, 11.5, 2600.0),
        ClimateReference("Clermont-Ferrand", "63", 330.0, -8.0, 11.9, 2600.0),
        ClimateReference("Dijon", "21", 220.0, -10.0, 11.3, 2750.0),
        ClimateReference("Besançon", "25", 310.0, -12.0, 11.2, 2850.0),
        ClimateReference("Bourg-en-Bresse", "01", 230.0, -10.0, 11.6, 2700.0),
        ClimateReference("Lyon", "69", 200.0, -10.0, 12.5, 2550.0),
        ClimateReference("Saint-Étienne", "42", 410.0, -11.0, 11.2, 2850.0),
        ClimateReference("Grenoble", "38", 210.0, -10.0, 12.0, 2650.0),
        ClimateReference("Chambéry", "73", 240.0, -10.0, 11.8, 2650.0),
        ClimateReference("Annecy", "74", 450.0, -12.0, 11.0, 2900.0),
        ClimateReference("Valence", "26", 130.0, -6.0, 13.3, 2150.0),
        ClimateReference("Bordeaux", "33", 50.0, -5.0, 13.8, 1950.0),
        ClimateReference("Toulouse", "31", 150.0, -5.0, 13.9, 1950.0),
        ClimateReference("Pau", "64", 180.0, -5.0, 13.5, 2000.0),
        ClimateReference("Montpellier", "34", 30.0, -5.0, 15.4, 1550.0),
        ClimateReference("Marseille", "13", 30.0, -5.0, 15.8, 1500.0),
        ClimateReference("Perpignan", "66", 40.0, -5.0, 16.0, 1400.0),
        ClimateReference("Nice", "06", 10.0, -2.0, 16.0, 1300.0),
        ClimateReference("Ajaccio", "2A", 10.0, -2.0, 15.6, 1400.0),
    )

    fun find(city: String): ClimateReference? = all.firstOrNull { it.city.equals(city, ignoreCase = true) }

    /**
     * Construit des données climatiques indicatives pour une commune située à [altitudeM],
     * à partir de la station [reference]. Toutes les valeurs sont marquées « estimées ».
     */
    fun climateFor(reference: ClimateReference, altitudeM: Double): Climate {
        val dAlt100 = (altitudeM - reference.altitudeM) / 100.0
        return Climate(
            referenceName = "${reference.city} (${reference.department})",
            designOutdoorTempC = round1(reference.designTempC - DESIGN_TEMP_LAPSE_K_PER_100M * dAlt100),
            designOutdoorOrigin = ValueOrigin.ESTIMATED,
            meanAnnualOutdoorTempC = round1(reference.meanAnnualTempC - MEAN_TEMP_LAPSE_K_PER_100M * dAlt100),
            degreeDays = (reference.degreeDays18 + DEGREE_DAYS_PER_100M * dAlt100).coerceAtLeast(500.0).let { Math.round(it / 10.0) * 10.0 },
            degreeDaysBase = 18.0,
            degreeDaysOrigin = ValueOrigin.ESTIMATED,
            altitudeM = altitudeM,
        )
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}
