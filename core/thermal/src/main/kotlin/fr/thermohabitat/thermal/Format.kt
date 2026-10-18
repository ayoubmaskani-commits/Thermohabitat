package fr.thermohabitat.thermal

import java.util.Locale

/** Formatage numérique à la française (virgule décimale), utilisé dans les messages du moteur. */
fun Double.fr(decimals: Int = 1): String = String.format(Locale.FRANCE, "%.${decimals}f", this)
