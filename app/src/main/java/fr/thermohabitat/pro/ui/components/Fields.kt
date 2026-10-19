package fr.thermohabitat.pro.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.thermohabitat.pro.ui.util.Fmt
import fr.thermohabitat.thermal.model.ValueOrigin

/**
 * Champ numérique tolérant (virgule ou point). La saisie intermédiaire (« 2, ») est conservée ;
 * la valeur n'est transmise que lorsqu'elle est un nombre valide (ou vide → null si [nullable]).
 */
@Composable
fun NumberField(
    label: String,
    value: Double?,
    onValueChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    decimals: Int = 2,
    nullable: Boolean = true,
    supportingText: String? = null,
    enabled: Boolean = true,
    min: Double? = null,
    max: Double? = null,
) {
    var text by remember { mutableStateOf(value.toEditable(decimals)) }
    var focused by remember { mutableStateOf(false) }
    // Valeurs transmises par ce champ pendant la saisie : leur retour tardif (recalcul, base) n'écrase pas le texte.
    val sent = remember { mutableListOf<Double?>() }
    fun close(a: Double?, b: Double?) = (a == null && b == null) ||
        (a != null && b != null && kotlin.math.abs(a - b) <= 1e-7 * maxOf(1.0, kotlin.math.abs(b)))
    LaunchedEffect(value, focused) {
        if (!focused) sent.clear()
        else if (sent.any { close(it, value) }) return@LaunchedEffect
        // Ne réécrit le texte que si la valeur externe diffère réellement (évite de perdre « 16, » en cours de saisie).
        if (!close(Fmt.parse(text), value)) text = value.toEditable(decimals)
    }
    val parsed = Fmt.parse(text)
    val outOfRange = parsed != null && ((min != null && parsed < min) || (max != null && parsed > max))
    val invalid = text.isNotBlank() && parsed == null
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val p = Fmt.parse(t)
            when {
                p != null && (min == null || p >= min) && (max == null || p <= max) -> { if (focused) sent += p; onValueChange(p) }
                t.isBlank() && nullable -> { if (focused) sent += null; onValueChange(null) }
            }
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        suffix = unit?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        isError = invalid || outOfRange,
        supportingText = when {
            invalid -> ({ Text("Nombre invalide") })
            outOfRange -> ({ Text("Valeur hors plage" + rangeText(min, max)) })
            supportingText != null -> ({ Text(supportingText) })
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    )
}

private fun rangeText(min: Double?, max: Double?) = when {
    min != null && max != null -> " (${Fmt.num(min, 0)} à ${Fmt.num(max, 0)})"
    min != null -> " (≥ ${Fmt.num(min, 0)})"
    max != null -> " (≤ ${Fmt.num(max, 0)})"
    else -> ""
}

private fun Double?.toEditable(decimals: Int): String {
    if (this == null) return ""
    val s = String.format(java.util.Locale.FRANCE, "%.${decimals}f", this)
    return if (s.contains(',')) s.trimEnd('0').trimEnd(',') else s
}

@Composable
fun IntField(
    label: String,
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    min: Int = 0,
    max: Int = 10_000,
    /** Faux : un champ effacé n'est pas transmis (aucune valeur de repli réécrite pendant la saisie). */
    nullable: Boolean = true,
) {
    NumberField(
        label = label, value = value?.toDouble(), onValueChange = { onValueChange(it?.toInt()) },
        modifier = modifier, decimals = 0, nullable = nullable, supportingText = supportingText, min = min.toDouble(), max = max.toDouble(),
    )
}

@Composable
fun TextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supportingText: String? = null,
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    // Textes transmis pendant la saisie : leur retour tardif n'écrase pas les caractères tapés depuis.
    val sent = remember { mutableListOf<String>() }
    LaunchedEffect(value, focused) {
        if (!focused) sent.clear() else if (value in sent) return@LaunchedEffect
        if (value != text) text = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; if (focused) sent += it; onValueChange(it) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    )
}

/** Liste déroulante générique (sans dépendance aux API expérimentales). */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choisir…",
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { expanded = false; onSelected(option) },
                )
            }
        }
    }
}

/** Sélection de l'origine d'une donnée (mesurée, déclarée, estimée, inconnue). */
@Composable
fun OriginSelector(origin: ValueOrigin, onChange: (ValueOrigin) -> Unit, modifier: Modifier = Modifier) {
    ChoiceChips(ValueOrigin.entries, origin, { it.label }, onChange, modifier)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    Row(modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Puces de choix exclusif, réparties sur plusieurs lignes si nécessaire : toutes les options restent visibles,
 * la sélection est marquée d'une coche.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val isSelected = o == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(o) },
                label = { Text(label(o), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (isSelected) { { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
            )
        }
    }
}

/**
 * Champs côte à côte s'ils ont chacun au moins [minFieldWidth] (agrandi avec la police système), sinon les uns
 * sous les autres : sur un petit écran, chaque champ garde une largeur lisible. Les enfants occupent toute la
 * largeur qui leur est donnée (ne pas leur passer de weight).
 */
@Composable
fun FieldRow(modifier: Modifier = Modifier, minFieldWidth: Dp = 140.dp, spacing: Dp = 8.dp, content: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val count = measurables.size
        if (count == 0) return@Layout layout(0, 0) {}
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val itemWidth = (width - gap * (count - 1)) / count
        if (itemWidth >= (minFieldWidth * fontScale).roundToPx()) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(itemWidth)) }
            layout(width, placeables.maxOf { it.height }) {
                var x = 0
                placeables.forEach { it.place(x, 0); x += itemWidth + gap }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            layout(width, placeables.sumOf { it.height } + gap * (count - 1)) {
                var y = 0
                placeables.forEach { it.place(0, y); y += it.height + gap }
            }
        }
    }
}
