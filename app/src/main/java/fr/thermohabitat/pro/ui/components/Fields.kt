package fr.thermohabitat.pro.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
    LaunchedEffect(value) {
        // Ne réécrit le texte que si la valeur externe diffère réellement (évite de perdre « 16, » en cours de saisie).
        val parsed = Fmt.parse(text)
        val same = (parsed == null && value == null) ||
            (parsed != null && value != null && kotlin.math.abs(parsed - value) <= 1e-7 * maxOf(1.0, kotlin.math.abs(value)))
        if (!same) text = value.toEditable(decimals)
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
                p != null && (min == null || p >= min) && (max == null || p <= max) -> onValueChange(p)
                t.isBlank() && nullable -> onValueChange(null)
            }
        },
        label = { Text(label) },
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
        modifier = modifier.fillMaxWidth(),
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
) {
    NumberField(
        label = label, value = value?.toDouble(), onValueChange = { onValueChange(it?.toInt()) },
        modifier = modifier, decimals = 0, supportingText = supportingText, min = min.toDouble(), max = max.toDouble(),
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
    LaunchedEffect(value) { if (value != text) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValueChange(it) },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        modifier = modifier.fillMaxWidth(),
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
            label = { Text(label) },
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
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ValueOrigin.entries.forEach { o ->
            FilterChip(selected = origin == o, onClick = { onChange(o) }, label = { Text(o.label) })
        }
    }
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

/** Rangée de puces de choix exclusif. */
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}
