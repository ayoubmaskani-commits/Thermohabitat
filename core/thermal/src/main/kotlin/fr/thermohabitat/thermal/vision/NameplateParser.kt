package fr.thermohabitat.thermal.vision

import fr.thermohabitat.thermal.model.PanelType
import fr.thermohabitat.thermal.model.RatingCondition
import kotlin.math.min

/** Champ extrait d'une plaque constructeur, avec le texte source et un indice de confiance. */
data class ExtractedField<T>(val value: T, val sourceText: String, val confidence: Double)

data class NameplateData(
    val manufacturer: ExtractedField<String>?,
    val model: ExtractedField<String>?,
    val reference: ExtractedField<String>?,
    val powerW: ExtractedField<Double>?,
    val rating: ExtractedField<RatingCondition>?,
    val panelType: ExtractedField<PanelType>?,
    val heightMm: ExtractedField<Double>?,
    val lengthMm: ExtractedField<Double>?,
    val rawText: String,
) {
    val isEmpty: Boolean
        get() = manufacturer == null && model == null && reference == null && powerW == null &&
            rating == null && panelType == null && heightMm == null && lengthMm == null
}

/**
 * Analyse du texte reconnu (OCR) sur une plaque ou un marquage de radiateur.
 * Ne produit que des PROPOSITIONS ; l'utilisateur valide chaque champ.
 */
object NameplateParser {

    val KNOWN_MANUFACTURERS: List<String> = listOf(
        "Chappée", "Ideal Standard", "Idéal Classic", "Finimetal", "Acova", "Zehnder", "De Dietrich",
        "Brötje", "Buderus", "Purmo", "Stelrad", "Radson", "Thermor", "Atlantic", "Arbonia", "Kermi",
        "Vogel & Noot", "Henrad", "Rettig", "Global", "Fondital", "Ferroli", "Guillot", "Jaga", "Vasco",
        "Runtal", "Myson", "Quinn", "Viessmann", "Lemercier", "Compagnie Nationale des Radiateurs",
        "Saint-Roch", "Sofath", "Caleido", "Cordivari", "Irsap", "Tubes Radiatori",
    )

    private val powerRegex = Regex("""(\d{2,5}(?:[.,]\d{1,2})?)\s*(kcal\s*/?\s*h|kcal|kw|w(?:att)?s?)\b""", RegexOption.IGNORE_CASE)
    private val regimeRegex = Regex("""(\d{2})\s*/\s*(\d{2})\s*/\s*(\d{2})""")
    private val deltaTRegex = Regex("""(?:Δ\s*T|delta\s*T|dT|DT)\s*[=:]?\s*(\d{2})\s*K?""", RegexOption.IGNORE_CASE)
    private val dimsRegex = Regex("""(\d{3,4})\s*[x×*]\s*(\d{3,4})""")
    private val heightRegex = Regex("""\bH\s*[=:]?\s*(\d{3,4})\s*(?:mm)?\b""", RegexOption.IGNORE_CASE)
    private val lengthRegex = Regex("""\b(?:L|l)\s*[=:]?\s*(\d{3,4})\s*(?:mm)?\b""")
    private val panelRegex = Regex("""\b(?:type|typ|t)\s*[-:]?\s*(10|11|20|21|22|33)\b""", RegexOption.IGNORE_CASE)
    private val refRegex = Regex("""\b(?:r[ée]f(?:[ée]rence)?|art(?:icle)?|n[°o]|code)\s*[.:]?\s*([A-Z0-9][A-Z0-9\-/.]{2,24})""", RegexOption.IGNORE_CASE)
    private val modelRegex = Regex("""\b(?:mod[èe]le|model)\s*[.:]?\s*([\p{L}0-9][\p{L}0-9 \-]{1,30})""", RegexOption.IGNORE_CASE)

    fun parse(text: String): NameplateData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return NameplateData(
            manufacturer = findManufacturer(lines),
            model = modelRegex.find(text)?.let { ExtractedField(it.groupValues[1].trim(), it.value, 0.5) },
            reference = refRegex.find(text)?.let { ExtractedField(it.groupValues[1].trim(), it.value, 0.6) },
            powerW = findPower(text),
            rating = findRating(text),
            panelType = panelRegex.find(text)?.let { m ->
                PanelType.entries.firstOrNull { it.name == "T" + m.groupValues[1] }?.let { ExtractedField(it, m.value, 0.6) }
            },
            heightMm = heightRegex.find(text)?.let { ExtractedField(it.groupValues[1].toDouble(), it.value, 0.5) }
                ?: dimsRegex.find(text)?.let { ExtractedField(it.groupValues[1].toDouble(), it.value, 0.35) },
            lengthMm = lengthRegex.find(text)?.let { ExtractedField(it.groupValues[1].toDouble(), it.value, 0.5) }
                ?: dimsRegex.find(text)?.let { ExtractedField(it.groupValues[2].toDouble(), it.value, 0.35) },
            rawText = text,
        )
    }

    private fun findPower(text: String): ExtractedField<Double>? {
        val candidates = powerRegex.findAll(text).mapNotNull { m ->
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val unit = m.groupValues[2].lowercase().replace(" ", "")
            val watts = when {
                unit.startsWith("kcal") -> v * 1.163
                unit == "kw" -> v * 1000.0
                else -> v
            }
            if (watts < 50 || watts > 15000) return@mapNotNull null
            val conf = if (unit.startsWith("kcal")) 0.55 else 0.65
            ExtractedField(watts, m.value, conf)
        }.toList()
        return candidates.maxByOrNull { it.confidence }
    }

    private fun findRating(text: String): ExtractedField<RatingCondition>? {
        regimeRegex.find(text)?.let { m ->
            val s = m.groupValues[1].toDouble(); val r = m.groupValues[2].toDouble(); val a = m.groupValues[3].toDouble()
            if (s > r && r > a && a in 10.0..25.0) return ExtractedField(RatingCondition(s, r, a), m.value, 0.7)
        }
        deltaTRegex.find(text)?.let { m ->
            return when (m.groupValues[1].toInt()) {
                50 -> ExtractedField(RatingCondition.EN442, m.value, 0.6)
                60 -> ExtractedField(RatingCondition.DIN4703, m.value, 0.6)
                30 -> ExtractedField(RatingCondition(55.0, 45.0, 20.0), m.value, 0.5)
                else -> null
            }
        }
        return null
    }

    private fun findManufacturer(lines: List<String>): ExtractedField<String>? {
        var best: ExtractedField<String>? = null
        for (line in lines) {
            val normLine = normalize(line)
            val words = normLine.split(' ').filter { it.isNotEmpty() }
            for (brand in KNOWN_MANUFACTURERS) {
                val normBrand = normalize(brand)
                val k = normBrand.split(' ').size
                val tolerance = maxOf(1, normBrand.length / 7)
                val conf = when {
                    normLine.contains(normBrand) -> 0.85
                    normBrand.length >= 5 && words.windowed(k).any { w ->
                        val candidate = w.joinToString(" ")
                        candidate.length >= 4 && levenshtein(candidate, normBrand) <= tolerance
                    } -> 0.6
                    else -> 0.0
                }
                if (conf > (best?.confidence ?: 0.0)) best = ExtractedField(brand, line, conf)
            }
        }
        return best
    }

    internal fun normalize(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9& ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    internal fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
