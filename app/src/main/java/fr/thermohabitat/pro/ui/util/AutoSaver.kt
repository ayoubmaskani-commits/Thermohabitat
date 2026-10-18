package fr.thermohabitat.pro.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Sauvegarde automatique des formulaires : chaque modification est enregistrée après une courte
 * pause de saisie, et la dernière valeur est toujours écrite à la fermeture de l'écran ([flush]).
 */
@OptIn(FlowPreview::class)
class AutoSaver<T : Any>(
    scope: CoroutineScope,
    private val appScope: CoroutineScope,
    private val delayMs: Long = 400,
    private val save: suspend (T) -> Unit,
) {
    private val pending = MutableStateFlow<T?>(null)
    @Volatile private var lastSaved: T? = null

    init {
        scope.launch {
            pending.filterNotNull().debounce(delayMs).collect { value ->
                if (value != lastSaved) { save(value); lastSaved = value }
            }
        }
    }

    fun submit(value: T) { pending.value = value }

    /** Écrit immédiatement la dernière valeur non sauvegardée (à appeler dans onCleared). */
    fun flush() {
        val v = pending.value ?: return
        if (v != lastSaved) { lastSaved = v; appScope.launch { save(v) } }
    }
}
