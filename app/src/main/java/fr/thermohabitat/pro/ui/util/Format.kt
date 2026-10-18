package fr.thermohabitat.pro.ui.util

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Formatage des nombres à la française (espace insécable pour les milliers, virgule décimale). */
object Fmt {
    private val locale = Locale.FRANCE

    fun num(value: Double?, decimals: Int = 1): String {
        if (value == null || value.isNaN()) return "–"
        val nf = NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = decimals
        nf.maximumFractionDigits = decimals
        return nf.format(value)
    }

    fun watts(w: Double?): String = when {
        w == null || w.isNaN() -> "–"
        kotlin.math.abs(w) >= 10_000 -> "${num(w / 1000.0, 1)} kW"
        else -> "${num(w, 0)} W"
    }

    fun kw(w: Double?): String = if (w == null) "–" else "${num(w / 1000.0, 2)} kW"

    fun temp(c: Double?, decimals: Int = 1): String = if (c == null) "–" else "${num(c, decimals)} °C"

    fun pct(fraction: Double?, decimals: Int = 0): String = if (fraction == null || fraction.isNaN() || fraction.isInfinite()) "–" else "${num(fraction * 100.0, decimals)} %"

    fun area(m2: Double?): String = if (m2 == null) "–" else "${num(m2, 1)} m²"

    fun volume(m3: Double?): String = if (m3 == null) "–" else "${num(m3, 0)} m³"

    fun eur(v: Double?): String = if (v == null) "–" else "${num(v, 0)} €"

    fun mm(v: Double?): String = if (v == null) "–" else "${num(v, 0)} mm"

    fun date(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))

    fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(millis))

    /** Lecture tolérante d'un nombre saisi (virgule ou point, espaces). */
    fun parse(text: String): Double? {
        val t = text.trim().replace(' ', ' ').replace(" ", "").replace(',', '.')
        if (t.isEmpty() || t == "-" || t == ".") return null
        return t.toDoubleOrNull()
    }
}
