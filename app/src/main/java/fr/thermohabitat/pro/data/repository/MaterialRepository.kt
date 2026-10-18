package fr.thermohabitat.pro.data.repository

import fr.thermohabitat.pro.data.db.MaterialDao
import fr.thermohabitat.pro.data.db.MaterialEntity
import fr.thermohabitat.pro.data.db.toEntity
import fr.thermohabitat.pro.data.db.toModel
import fr.thermohabitat.thermal.library.MaterialLibrary
import fr.thermohabitat.thermal.model.Material
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Bibliothèque de matériaux : matériaux intégrés (initialisés en base) et matériaux personnalisés. */
@Singleton
class MaterialRepository @Inject constructor(private val dao: MaterialDao) {

    private val seedMutex = Mutex()
    @Volatile private var seeded = false

    /** Insère les matériaux intégrés manquants (sans écraser les valeurs modifiées par l'utilisateur). */
    suspend fun ensureSeeded() {
        if (seeded) return
        seedMutex.withLock {
            if (seeded) return
            dao.insertAllIgnore(MaterialLibrary.all.map { it.toEntity(builtIn = true) })
            seeded = true
        }
    }

    fun observeAll(): Flow<List<MaterialEntity>> = dao.observeAll().onStart { ensureSeeded() }

    /** Matériaux indexés par identifiant de base, convertis pour le moteur. */
    fun observeModels(): Flow<Map<Long, Material>> = observeAll().map { list -> list.associate { it.id to it.toModel() } }

    suspend fun all(): List<MaterialEntity> { ensureSeeded(); return dao.getAll() }

    suspend fun get(id: Long): MaterialEntity? = dao.get(id)

    /** Identifiant en base d'un matériau, créé s'il n'existe pas (matériau personnalisé importé). */
    suspend fun idFor(material: Material): Long {
        ensureSeeded()
        dao.byCode(material.code)?.let { return it.id }
        return dao.insert(material.toEntity(builtIn = false))
    }

    /**
     * Matériau d'une sauvegarde importée : le matériau local de même code n'est réutilisé que si ses valeurs
     * thermiques sont identiques ; sinon une copie personnalisée est créée, pour que le projet importé garde
     * exactement les valeurs (λ, ρ, c, origine) avec lesquelles il a été étudié.
     */
    suspend fun idForImported(material: Material): Long {
        ensureSeeded()
        val local = dao.byCode(material.code)?.toModel() ?: return dao.insert(material.toEntity(builtIn = false))
        if (local.sameThermalValues(material)) return local.id
        return dao.insert(material.toEntity(builtIn = false).copy(
            code = "imp_" + UUID.randomUUID().toString().take(8),
            name = material.name + " (importé)",
        ))
    }

    private fun Material.sameThermalValues(o: Material) =
        lambda == o.lambda && density == o.density && specificHeat == o.specificHeat && origin == o.origin &&
            lambdaMin == o.lambdaMin && lambdaMax == o.lambdaMax && isInsulation == o.isInsulation && category == o.category

    suspend fun idForCode(code: String): Long? { ensureSeeded(); return dao.byCode(code)?.id }

    suspend fun createCustom(base: MaterialEntity? = null): Long = dao.insert(
        (base ?: MaterialEntity(
            code = "", name = "Nouveau matériau", category = fr.thermohabitat.thermal.model.MaterialCategory.OTHER,
            lambda = 1.0, density = 1500.0, specificHeat = 1000.0,
        )).copy(id = 0, code = "perso_" + UUID.randomUUID().toString().take(8), builtIn = false,
            name = base?.let { it.name + " (copie)" } ?: "Nouveau matériau",
            origin = fr.thermohabitat.thermal.model.ValueOrigin.ESTIMATED, source = base?.source ?: "Saisie utilisateur"),
    )

    suspend fun update(material: MaterialEntity) = dao.update(material)

    /** Supprime un matériau personnalisé non utilisé. Retourne false s'il est utilisé ou intégré. */
    suspend fun deleteIfUnused(id: Long): Boolean {
        val m = dao.get(id) ?: return false
        if (m.builtIn || dao.usageCount(id) > 0) return false
        dao.delete(id)
        return true
    }

    /** Rétablit les valeurs de la bibliothèque pour un matériau intégré. */
    suspend fun resetBuiltIn(id: Long) {
        val m = dao.get(id) ?: return
        val ref = MaterialLibrary.find(m.code) ?: return
        dao.update(ref.toEntity(id = id, builtIn = true))
    }

    suspend fun usageCount(id: Long): Int = dao.usageCount(id)
}
