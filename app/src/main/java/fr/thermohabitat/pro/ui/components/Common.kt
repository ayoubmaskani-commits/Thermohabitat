package fr.thermohabitat.pro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.thermohabitat.pro.ui.theme.LocalStatusColors
import fr.thermohabitat.thermal.calc.CoverageStatus
import fr.thermohabitat.thermal.model.ValueOrigin

/** Barre supérieure standard avec retour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
            }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                action?.invoke()
            }
            content()
        }
    }
}

/** Carte indicateur du tableau de bord. */
@Composable
fun KpiCard(title: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null, detail: String? = null, accent: Color? = null) {
    Card(
        modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, tint = accent ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent ?: MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun statusColor(status: CoverageStatus): Color {
    val c = LocalStatusColors.current
    return when (status) {
        CoverageStatus.SUFFICIENT -> c.sufficient
        CoverageStatus.LIMIT -> c.limit
        CoverageStatus.INSUFFICIENT, CoverageStatus.NO_EMITTER -> c.insufficient
        CoverageStatus.NO_NEED -> c.neutral
    }
}

@Composable
fun StatusChip(status: CoverageStatus, modifier: Modifier = Modifier, text: String = status.label) {
    val color = statusColor(status)
    Surface(modifier, color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Étiquette d'origine d'une donnée (mesurée / déclarée / estimée / inconnue). */
@Composable
fun OriginBadge(origin: ValueOrigin, modifier: Modifier = Modifier) {
    val color = when (origin) {
        ValueOrigin.MEASURED -> LocalStatusColors.current.sufficient
        ValueOrigin.DECLARED -> MaterialTheme.colorScheme.primary
        ValueOrigin.ESTIMATED -> LocalStatusColors.current.limit
        ValueOrigin.UNKNOWN -> LocalStatusColors.current.insufficient
    }
    Surface(modifier, color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(origin.label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

enum class BannerKind { INFO, WARNING }

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, kind: BannerKind = BannerKind.INFO) {
    val bg = if (kind == BannerKind.WARNING) LocalStatusColors.current.limit.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    Surface(modifier.fillMaxWidth(), color = bg, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (kind == BannerKind.WARNING) Icons.Filled.Warning else Icons.Filled.Info, null,
                tint = if (kind == BannerKind.WARNING) LocalStatusColors.current.limit else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirmer",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Ligne libellé / valeur pour les tableaux de résultats. */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, emphasize: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium)
        trailing?.let { Spacer(Modifier.width(6.dp)); it() }
    }
}

@Composable
fun LargeActionButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, outlined: Boolean = false) {
    val content: @Composable () -> Unit = {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
    if (outlined) {
        OutlinedButton(onClick, modifier.fillMaxWidth().height(56.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { content() }
    } else {
        Button(onClick, modifier.fillMaxWidth().height(56.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { content() }
    }
}
