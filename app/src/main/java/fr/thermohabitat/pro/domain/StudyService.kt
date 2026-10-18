package fr.thermohabitat.pro.domain

import fr.thermohabitat.pro.data.repository.Project
import fr.thermohabitat.pro.data.repository.ProjectRepository
import fr.thermohabitat.pro.data.repository.SettingsRepository
import fr.thermohabitat.thermal.StudyAnalyzer
import fr.thermohabitat.thermal.StudyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Projet accompagné de son analyse thermique recalculée à chaque modification. */
data class AnalyzedProject(val project: Project, val result: StudyResult)

/**
 * Cas d'usage d'analyse : relie les données persistées au moteur thermique.
 * Les calculs s'exécutent hors du fil principal (Dispatchers.Default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class StudyService @Inject constructor(
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
    private val analyzer: StudyAnalyzer,
) {
    fun observe(buildingId: Long): Flow<AnalyzedProject?> =
        repository.observeProject(buildingId)
            .conflate()
            .map { p -> p?.let { AnalyzedProject(it, analyzer.analyze(it.building, it.settings)) } }
            .flowOn(Dispatchers.Default)

    /** Projet actif (choisi par l'utilisateur), ou le plus récent. */
    fun observeActive(): Flow<AnalyzedProject?> =
        combine(settings.activeBuildingId, repository.observeBuildings()) { active, all ->
            when {
                all.isEmpty() -> null
                active != null && all.any { it.id == active } -> active
                else -> all.first().id
            }
        }.flatMapLatest { id -> if (id == null) flowOf(null) else observe(id) }

    /** Tous les projets analysés (tableau de bord). */
    fun observeAll(): Flow<List<AnalyzedProject>> =
        repository.observeBuildings().flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { observe(it.id) }) { arr -> arr.filterNotNull() }
        }
}
