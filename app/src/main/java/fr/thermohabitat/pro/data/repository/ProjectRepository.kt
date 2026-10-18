package fr.thermohabitat.pro.data.repository

import androidx.room.withTransaction
import fr.thermohabitat.pro.data.db.AppDatabase
import fr.thermohabitat.pro.data.db.BuildingEntity
import fr.thermohabitat.pro.data.db.LayerEntity
import fr.thermohabitat.pro.data.db.MeasurementSeriesEntity
import fr.thermohabitat.pro.data.db.OpeningEntity
import fr.thermohabitat.pro.data.db.PhotoEntity
import fr.thermohabitat.pro.data.db.PhotoOwner
import fr.thermohabitat.pro.data.db.RadiatorEntity
import fr.thermohabitat.pro.data.db.RoomEntity
import fr.thermohabitat.pro.data.db.RoomWithDetails
import fr.thermohabitat.pro.data.db.ScenarioEntity
import fr.thermohabitat.pro.data.db.SurfaceEntity
import fr.thermohabitat.pro.data.db.ThermalBridgeEntity
import fr.thermohabitat.pro.data.db.toEntity
import fr.thermohabitat.pro.data.db.toModel
import fr.thermohabitat.pro.data.files.PhotoStorage
import fr.thermohabitat.thermal.StudySettings
import fr.thermohabitat.thermal.backup.BackupJson
import fr.thermohabitat.thermal.backup.MeasurementSeries
import fr.thermohabitat.thermal.calc.EnergyCarrier
import fr.thermohabitat.thermal.calc.EnergySettings
import fr.thermohabitat.thermal.calc.HeatingCurve
import fr.thermohabitat.thermal.library.CompositionTemplates
import fr.thermohabitat.thermal.library.OpeningPresets
import fr.thermohabitat.thermal.model.Boundary
import fr.thermohabitat.thermal.model.Building
import fr.thermohabitat.thermal.model.HeatingSystem
import fr.thermohabitat.thermal.model.Material
import fr.thermohabitat.thermal.model.OpeningKind
import fr.thermohabitat.thermal.model.Orientation
import fr.thermohabitat.thermal.model.PhotoKind
import fr.thermohabitat.thermal.model.RoomUsage
import fr.thermohabitat.thermal.model.SurfaceKind
import fr.thermohabitat.thermal.model.ValueOrigin
import fr.thermohabitat.thermal.scenario.Scenario
import fr.thermohabitat.thermal.sim.MeasuredPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Projet complet : entité, graphe du moteur, paramètres d'étude et photos. */
data class Project(
    val entity: BuildingEntity,
    val building: Building,
    val settings: StudySettings,
    val rooms: List<RoomWithDetails>,
    val radiators: List<RadiatorEntity>,
    val photos: List<PhotoEntity>,
) {
    val id: Long get() = entity.id
    fun photosOf(owner: PhotoOwner, ownerId: Long) = photos.filter { it.ownerType == owner && it.ownerId == ownerId }
}

/**
 * Accès aux projets : création, modification et assemblage du graphe de calcul.
 * Toutes les écritures déclenchent la mise à jour automatique des écrans (Flow Room) :
 * c'est la base de la sauvegarde automatique.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ProjectRepository @Inject constructor(
    private val db: AppDatabase,
    private val materials: MaterialRepository,
    private val photoStorage: PhotoStorage,
) {
    private val buildingDao = db.buildingDao()
    private val roomDao = db.roomDao()
    private val surfaceDao = db.surfaceDao()
    private val bridgeDao = db.thermalBridgeDao()
    private val radiatorDao = db.radiatorDao()
    private val photoDao = db.photoDao()
    private val scenarioDao = db.scenarioDao()
    private val measurementDao = db.measurementDao()
    private val json = BackupJson.json

    // ------------------------------------------------------------------ Lecture

    fun observeBuildings(): Flow<List<BuildingEntity>> = buildingDao.observeAll()

    fun observeProject(id: Long): Flow<Project?> = combine(
        buildingDao.observe(id),
        roomDao.observeWithDetails(id),
        radiatorDao.observeForBuilding(id),
        materials.observeModels(),
        photoDao.observeForBuilding(id),
    ) { b, rooms, radiators, mats, photos ->
        b?.let { assemble(it, rooms, radiators, mats, photos) }
    }

    suspend fun loadProject(id: Long): Project? {
        val b = buildingDao.get(id) ?: return null
        val mats = materials.all().associate { it.id to it.toModel() }
        return assemble(b, roomDao.getWithDetails(id), radiatorDao.getForBuilding(id), mats, photoDao.getForBuilding(id))
    }

    private fun assemble(
        b: BuildingEntity,
        rooms: List<RoomWithDetails>,
        radiators: List<RadiatorEntity>,
        mats: Map<Long, Material>,
        photos: List<PhotoEntity>,
    ): Project {
        val roomModels = rooms.map { it.toModel(mats) }
        val radModels = radiators.map { r -> r.toModel(photos.filter { it.ownerType == PhotoOwner.RADIATOR && it.ownerId == r.id }) }
        return Project(b, b.toModel(roomModels, radModels), decodeSettings(b), rooms, radiators, photos)
    }

    fun decodeSettings(b: BuildingEntity): StudySettings =
        b.settingsJson.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(StudySettings.serializer(), it) }.getOrNull() }
            ?: defaultSettings(b)

    fun defaultSettings(b: BuildingEntity) = StudySettings(
        heatingCurve = HeatingCurve(designOutdoorC = b.designOutdoorTempC, designSupplyC = 55.0, designDeltaK = 7.0),
        energy = EnergySettings(currentCarrier = carrierFor(b.existingHeating)),
    )

    private fun carrierFor(h: HeatingSystem) = when (h) {
        HeatingSystem.OIL_BOILER -> EnergyCarrier.FUEL_OIL
        HeatingSystem.GAS_BOILER, HeatingSystem.CONDENSING_GAS_BOILER -> EnergyCarrier.NATURAL_GAS
        HeatingSystem.WOOD_BOILER -> EnergyCarrier.WOOD_LOGS
        HeatingSystem.ELECTRIC -> EnergyCarrier.ELECTRICITY
        HeatingSystem.HEAT_PUMP -> EnergyCarrier.HEAT_PUMP
        else -> EnergyCarrier.FUEL_OIL
    }

    // ------------------------------------------------------------------ Bâtiments

    suspend fun createBuilding(name: String = "Nouvelle étude"): Long {
        materials.ensureSeeded()
        return buildingDao.insert(BuildingEntity(name = name))
    }

    suspend fun updateBuilding(entity: BuildingEntity) = buildingDao.update(entity.copy(updatedAt = System.currentTimeMillis()))

    suspend fun updateSettings(buildingId: Long, transform: (StudySettings) -> StudySettings) {
        val b = buildingDao.get(buildingId) ?: return
        val s = transform(decodeSettings(b))
        buildingDao.update(b.copy(settingsJson = json.encodeToString(StudySettings.serializer(), s), updatedAt = System.currentTimeMillis()))
    }

    /** Suppression complète d'un projet : base de données ET fichiers photo. */
    suspend fun deleteBuilding(id: Long) {
        photoDao.getForBuilding(id).forEach { photoStorage.delete(it.fileName) }
        buildingDao.delete(id)
    }

    private suspend fun touch(buildingId: Long) = buildingDao.touch(buildingId)

    // ------------------------------------------------------------------ Pièces

    suspend fun addRoom(
        buildingId: Long,
        name: String,
        floorLevel: Int = 0,
        lengthM: Double = 4.0,
        widthM: Double = 3.5,
        heightM: Double? = null,
        usage: RoomUsage = RoomUsage.LIVING,
    ): Long {
        val b = buildingDao.get(buildingId)
        val id = roomDao.insert(RoomEntity(
            buildingId = buildingId, name = name, floorLevel = floorLevel, lengthM = lengthM, widthM = widthM,
            heightM = heightM ?: b?.ceilingHeightM ?: 2.5, usage = usage, setpointC = b?.setpointC ?: usage.usualSetpointC,
            sortOrder = roomDao.maxSortOrder(buildingId) + 1,
        ))
        touch(buildingId)
        return id
    }

    suspend fun updateRoom(room: RoomEntity) { roomDao.update(room); touch(room.buildingId) }

    suspend fun getRoom(id: Long): RoomEntity? = roomDao.get(id)

    fun observeRoom(id: Long): Flow<RoomEntity?> = roomDao.observe(id)

    fun observeSurface(id: Long) = surfaceDao.observeWithDetails(id)

    suspend fun getSurface(id: Long): SurfaceEntity? = surfaceDao.get(id)

    suspend fun deleteRoom(id: Long) {
        val room = roomDao.get(id) ?: return
        photoDao.getFor(PhotoOwner.ROOM, id).forEach { photoStorage.delete(it.fileName); photoDao.delete(it.id) }
        roomDao.delete(id)
        touch(room.buildingId)
    }

    /** Duplique une pièce avec ses parois, couches, baies et ponts thermiques (sans radiateurs). */
    suspend fun duplicateRoom(id: Long): Long? = db.withTransaction {
        val room = roomDao.getWithDetails(roomDao.get(id)?.buildingId ?: return@withTransaction null).firstOrNull { it.room.id == id }
            ?: return@withTransaction null
        val newId = roomDao.insert(room.room.copy(id = 0, name = room.room.name + " (copie)", sortOrder = roomDao.maxSortOrder(room.room.buildingId) + 1,
            planX = null, planY = null))
        for (s in room.surfaces) {
            val sid = surfaceDao.insert(s.surface.copy(id = 0, roomId = newId))
            surfaceDao.insertLayers(s.layers.map { it.copy(id = 0, surfaceId = sid) })
            s.openings.forEach { surfaceDao.insertOpening(it.copy(id = 0, surfaceId = sid)) }
        }
        room.bridges.forEach { bridgeDao.insert(it.copy(id = 0, roomId = newId)) }
        touch(room.room.buildingId)
        newId
    }

    /**
     * Assistant de création des parois : murs selon les dimensions de la pièce (extérieurs pour les
     * orientations cochées, sinon cloisons vers pièces chauffées), plancher et plafond.
     */
    suspend fun addStandardSurfaces(
        roomId: Long,
        exteriorWalls: Set<Orientation>,
        wallTemplateCode: String?,
        floorBoundary: Boundary?,
        floorTemplateCode: String?,
        ceilingBoundary: Boundary?,
        ceilingTemplateCode: String?,
        interiorWalls: Boolean,
    ) = db.withTransaction {
        val room = roomDao.get(roomId) ?: return@withTransaction
        var order = surfaceDao.maxSortOrder(roomId)
        val walls = listOf(Orientation.N to room.lengthM, Orientation.E to room.widthM, Orientation.S to room.lengthM, Orientation.W to room.widthM)
        for ((o, length) in walls) {
            val exterior = o in exteriorWalls
            if (!exterior && !interiorWalls) continue
            val sid = surfaceDao.insert(SurfaceEntity(
                roomId = roomId, label = if (exterior) "Mur ${o.label.lowercase()}" else "Cloison ${o.label.lowercase()}",
                kind = SurfaceKind.WALL, boundary = if (exterior) Boundary.EXTERIOR else Boundary.HEATED_ROOM,
                orientation = o, lengthM = length, heightM = room.heightM, sortOrder = ++order,
            ))
            val template = if (exterior) wallTemplateCode else "cloison_ba13"
            template?.let { applyTemplate(sid, it) }
        }
        floorBoundary?.let { boundary ->
            val sid = surfaceDao.insert(SurfaceEntity(
                roomId = roomId, label = "Plancher bas", kind = SurfaceKind.FLOOR, boundary = boundary, sortOrder = ++order,
                unheatedPresetCode = if (boundary == Boundary.UNHEATED) "vide_sanitaire" else null,
                groundExposedPerimeterM = if (boundary == Boundary.GROUND) exteriorPerimeter(room, exteriorWalls) else null,
            ))
            floorTemplateCode?.let { applyTemplate(sid, it) }
        }
        ceilingBoundary?.let { boundary ->
            val sid = surfaceDao.insert(SurfaceEntity(
                roomId = roomId, label = if (boundary == Boundary.UNHEATED) "Plafond sous combles" else "Plafond",
                kind = SurfaceKind.CEILING, boundary = boundary, sortOrder = ++order,
                unheatedPresetCode = if (boundary == Boundary.UNHEATED) "combles_non_isoles" else null,
            ))
            ceilingTemplateCode?.let { applyTemplate(sid, it) }
        }
        touch(room.buildingId)
    }

    private fun exteriorPerimeter(room: RoomEntity, exterior: Set<Orientation>): Double =
        exterior.sumOf { if (it == Orientation.N || it == Orientation.S) room.lengthM else room.widthM }

    // ------------------------------------------------------------------ Parois

    suspend fun addSurface(roomId: Long, kind: SurfaceKind, boundary: Boundary, templateCode: String? = null): Long {
        val room = roomDao.get(roomId) ?: error("Pièce introuvable")
        val id = surfaceDao.insert(SurfaceEntity(
            roomId = roomId, label = when (kind) {
                SurfaceKind.WALL -> "Mur"
                SurfaceKind.FLOOR -> "Plancher bas"
                SurfaceKind.CEILING -> "Plafond"
                SurfaceKind.ROOF -> "Rampant"
            },
            kind = kind, boundary = boundary,
            lengthM = if (kind == SurfaceKind.WALL) room.lengthM else null,
            heightM = if (kind == SurfaceKind.WALL) room.heightM else null,
            sortOrder = surfaceDao.maxSortOrder(roomId) + 1,
        ))
        templateCode?.let { applyTemplate(id, it) }
        touch(room.buildingId)
        return id
    }

    suspend fun updateSurface(surface: SurfaceEntity) {
        surfaceDao.update(surface)
        roomDao.get(surface.roomId)?.let { touch(it.buildingId) }
    }

    suspend fun deleteSurface(id: Long) {
        val s = surfaceDao.get(id) ?: return
        surfaceDao.delete(id)
        roomDao.get(s.roomId)?.let { touch(it.buildingId) }
    }

    /** Remplace les couches d'une paroi par une composition type. */
    suspend fun applyTemplate(surfaceId: Long, templateCode: String) {
        val template = CompositionTemplates.find(templateCode) ?: return
        surfaceDao.deleteLayersOf(surfaceId)
        val layers = template.layers.mapIndexed { i, (code, thickness) ->
            LayerEntity(surfaceId = surfaceId, position = i, materialId = materials.idForCode(code) ?: return,
                thicknessM = thickness, origin = ValueOrigin.ESTIMATED)
        }
        surfaceDao.insertLayers(layers)
        val s = surfaceDao.get(surfaceId) ?: return
        if (s.uValueOverride != null) surfaceDao.update(s.copy(uValueOverride = null))
    }

    suspend fun addLayer(surfaceId: Long, materialId: Long, thicknessM: Double, atInteriorSide: Boolean = false) {
        val existing = surfaceDao.layers(surfaceId)
        if (atInteriorSide) {
            surfaceDao.updateLayers(existing.map { it.copy(position = it.position + 1) })
            surfaceDao.insertLayer(LayerEntity(surfaceId = surfaceId, position = 0, materialId = materialId, thicknessM = thicknessM))
        } else {
            surfaceDao.insertLayer(LayerEntity(surfaceId = surfaceId, position = (existing.maxOfOrNull { it.position } ?: -1) + 1,
                materialId = materialId, thicknessM = thicknessM))
        }
    }

    suspend fun updateLayer(layer: LayerEntity) = surfaceDao.updateLayer(layer)

    suspend fun deleteLayer(surfaceId: Long, layerId: Long) {
        surfaceDao.deleteLayer(layerId)
        val rest = surfaceDao.layers(surfaceId).sortedBy { it.position }
        surfaceDao.updateLayers(rest.mapIndexed { i, l -> l.copy(position = i) })
    }

    /** Déplace une couche vers l'intérieur (delta = −1) ou l'extérieur (delta = +1). */
    suspend fun moveLayer(surfaceId: Long, layerId: Long, delta: Int) {
        val list = surfaceDao.layers(surfaceId).sortedBy { it.position }.toMutableList()
        val i = list.indexOfFirst { it.id == layerId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val a = list[i]; list[i] = list[j]; list[j] = a
        surfaceDao.updateLayers(list.mapIndexed { k, l -> l.copy(position = k) })
    }

    suspend fun addOpening(surfaceId: Long, presetCode: String?, kind: OpeningKind = OpeningKind.WINDOW): Long {
        val preset = OpeningPresets.find(presetCode)
        return surfaceDao.insertOpening(OpeningEntity(
            surfaceId = surfaceId,
            kind = preset?.kind ?: kind,
            label = (preset?.kind ?: kind).label,
            widthM = if ((preset?.kind ?: kind) == OpeningKind.DOOR) 0.9 else 1.0,
            heightM = if ((preset?.kind ?: kind) == OpeningKind.DOOR) 2.15 else 1.25,
            uValue = preset?.uValue ?: 3.0,
            gValue = preset?.gValue ?: 0.6,
            frameFraction = preset?.frameFraction ?: 0.3,
            presetCode = preset?.code,
        ))
    }

    suspend fun updateOpening(opening: OpeningEntity) = surfaceDao.updateOpening(opening)
    suspend fun deleteOpening(id: Long) = surfaceDao.deleteOpening(id)

    // ------------------------------------------------------------------ Ponts thermiques

    fun observeBridges(roomId: Long) = bridgeDao.observeForRoom(roomId)
    suspend fun addBridge(roomId: Long, label: String, lengthM: Double, psi: Double) =
        bridgeDao.insert(ThermalBridgeEntity(roomId = roomId, label = label, lengthM = lengthM, psi = psi))
    suspend fun updateBridge(b: ThermalBridgeEntity) = bridgeDao.update(b)
    suspend fun deleteBridge(id: Long) = bridgeDao.delete(id)

    // ------------------------------------------------------------------ Radiateurs

    fun observeRadiator(id: Long) = radiatorDao.observe(id)
    suspend fun getRadiator(id: Long) = radiatorDao.get(id)

    suspend fun nextRadiatorCode(buildingId: Long): String {
        val used = radiatorDao.codes(buildingId).mapNotNull { Regex("""R(\d+)""").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        var n = 1
        while (n in used) n++
        return "R%02d".format(n)
    }

    /** Crée une fiche radiateur NON validée (brouillon issu du scanner ou d'une saisie). */
    suspend fun createRadiatorDraft(buildingId: Long, roomId: Long? = null): Long {
        val id = radiatorDao.insert(RadiatorEntity(buildingId = buildingId, roomId = roomId, code = nextRadiatorCode(buildingId)))
        touch(buildingId)
        return id
    }

    /** Enregistre la fiche telle quelle ([RadiatorEntity.updatedAt] est fixé par l'appelant). */
    suspend fun updateRadiator(r: RadiatorEntity) {
        radiatorDao.update(r)
        touch(r.buildingId)
    }

    /** Duplique une fiche et ses photos ; la copie doit être revalidée. */
    suspend fun duplicateRadiator(id: Long): Long? {
        val r = radiatorDao.get(id) ?: return null
        val newId = radiatorDao.insert(r.copy(id = 0, code = nextRadiatorCode(r.buildingId), validated = false, validatedAt = null,
            label = r.label, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        photoDao.getFor(PhotoOwner.RADIATOR, id).forEach { p ->
            val copy = photoStorage.newPhotoFile("radiateur")
            runCatching { photoStorage.file(p.fileName).copyTo(copy, overwrite = true) }.onSuccess {
                photoDao.insert(p.copy(id = 0, ownerId = newId, fileName = copy.name))
            }
        }
        touch(r.buildingId)
        return newId
    }

    suspend fun deleteRadiator(id: Long) {
        val r = radiatorDao.get(id) ?: return
        photoDao.getFor(PhotoOwner.RADIATOR, id).forEach { photoStorage.delete(it.fileName); photoDao.delete(it.id) }
        radiatorDao.delete(id)
        touch(r.buildingId)
    }

    // ------------------------------------------------------------------ Photos

    fun observePhotos(owner: PhotoOwner, ownerId: Long) = photoDao.observeFor(owner, ownerId)
    fun observePhoto(id: Long) = photoDao.observe(id)
    suspend fun getPhoto(id: Long) = photoDao.get(id)

    suspend fun addPhoto(buildingId: Long, owner: PhotoOwner, ownerId: Long, file: File, kind: PhotoKind): Long {
        val (w, h) = photoStorage.dimensions(file.name)
        return photoDao.insert(PhotoEntity(buildingId = buildingId, ownerType = owner, ownerId = ownerId, fileName = file.name,
            kind = kind, width = w, height = h))
    }

    suspend fun updatePhoto(p: PhotoEntity) = photoDao.update(p)

    /** Importe une image de la galerie (copie privée redressée) et l'associe au propriétaire. */
    suspend fun importPhoto(buildingId: Long, owner: PhotoOwner, ownerId: Long, uri: android.net.Uri, kind: PhotoKind): Long {
        val file = photoStorage.importFromUri(uri, owner.name.lowercase())
        return addPhoto(buildingId, owner, ownerId, file, kind)
    }

    suspend fun deletePhoto(id: Long) {
        val p = photoDao.get(id) ?: return
        photoStorage.delete(p.fileName)
        photoDao.delete(id)
    }

    // ------------------------------------------------------------------ Scénarios et mesures

    fun observeScenarios(buildingId: Long): Flow<List<Pair<ScenarioEntity, Scenario>>> =
        scenarioDao.observeForBuilding(buildingId).map { list ->
            list.mapNotNull { e -> runCatching { e to json.decodeFromString(Scenario.serializer(), e.json) }.getOrNull() }
        }

    suspend fun saveScenario(buildingId: Long, scenario: Scenario, id: Long? = null): Long {
        val text = json.encodeToString(Scenario.serializer(), scenario)
        return if (id == null || id == 0L) {
            scenarioDao.insert(ScenarioEntity(buildingId = buildingId, name = scenario.name, json = text))
        } else {
            scenarioDao.update(ScenarioEntity(id = id, buildingId = buildingId, name = scenario.name, json = text, updatedAt = System.currentTimeMillis()))
            id
        }
    }

    fun observeScenario(id: Long): Flow<Pair<ScenarioEntity, Scenario>?> = scenarioDao.observe(id).map { e ->
        e?.let { runCatching { it to json.decodeFromString(Scenario.serializer(), it.json) }.getOrNull() }
    }

    suspend fun deleteScenario(id: Long) = scenarioDao.delete(id)

    private val pointsSerializer = ListSerializer(MeasuredPoint.serializer())

    fun observeMeasurements(buildingId: Long): Flow<List<Pair<MeasurementSeriesEntity, List<MeasuredPoint>>>> =
        measurementDao.observeForBuilding(buildingId).map { list ->
            list.map { it to (runCatching { json.decodeFromString(pointsSerializer, it.pointsJson) }.getOrDefault(emptyList())) }
        }

    suspend fun saveMeasurements(entity: MeasurementSeriesEntity, points: List<MeasuredPoint>): Long {
        val e = entity.copy(pointsJson = json.encodeToString(pointsSerializer, points.sortedBy { it.timeH }))
        return if (e.id == 0L) measurementDao.insert(e) else { measurementDao.update(e); e.id }
    }

    suspend fun deleteMeasurements(id: Long) = measurementDao.delete(id)

    // ------------------------------------------------------------------ Import d'un graphe complet

    /**
     * Insère un bâtiment complet (projet d'exemple ou sauvegarde importée) en réattribuant les identifiants.
     * @param photoFiles correspondance nom de fichier d'origine → fichier copié dans le stockage privé
     */
    suspend fun insertGraph(
        building: Building,
        settings: StudySettings,
        scenarios: List<Scenario> = emptyList(),
        measurements: List<MeasurementSeries> = emptyList(),
        photos: List<Triple<String, Long, PhotoKind>> = emptyList(),
        photoOwnerTypes: Map<String, PhotoOwner> = emptyMap(),
        photoFiles: Map<String, File> = emptyMap(),
        nameSuffix: String = "",
    ): Long {
        materials.ensureSeeded()
        val materialIds = mutableMapOf<String, Long>()
        building.rooms.flatMap { r -> r.surfaces.flatMap { s -> s.layers.map { it.material } } }.distinctBy { it.code }.forEach {
            materialIds[it.code] = materials.idFor(it)
        }
        return db.withTransaction {
            val settingsJson = json.encodeToString(StudySettings.serializer(), settings)
            val bId = buildingDao.insert(building.copy(name = building.name + nameSuffix).toEntity(settingsJson))
            val roomIds = mutableMapOf<Long, Long>()
            building.rooms.forEachIndexed { i, r -> roomIds[r.id] = roomDao.insert(r.toEntity(bId, i)) }
            building.rooms.forEach { r ->
                val newRoomId = roomIds.getValue(r.id)
                r.surfaces.forEachIndexed { i, s ->
                    val sid = surfaceDao.insert(s.toEntity(newRoomId, i, s.adjacentRoomId?.let { roomIds[it] }))
                    surfaceDao.insertLayers(s.layers.mapIndexed { k, l ->
                        LayerEntity(surfaceId = sid, position = k, materialId = materialIds.getValue(l.material.code),
                            thicknessM = l.thicknessM, lambdaOverride = l.lambdaOverride, origin = l.origin)
                    })
                    s.openings.forEach { surfaceDao.insertOpening(it.toEntity(sid)) }
                }
                r.thermalBridges.forEach { bridgeDao.insert(ThermalBridgeEntity(roomId = newRoomId, label = it.label, lengthM = it.lengthM, psi = it.psi, origin = it.origin)) }
            }
            val radiatorIds = mutableMapOf<Long, Long>()
            building.radiators.forEach { r -> radiatorIds[r.id] = radiatorDao.insert(r.toEntity(bId, r.roomId?.let { roomIds[it] })) }
            scenarios.forEach { saveScenario(bId, it) }
            measurements.forEach { m ->
                saveMeasurements(MeasurementSeriesEntity(buildingId = bId, name = m.name, roomId = m.roomId?.let { roomIds[it] },
                    outdoorC = m.outdoorC, startClockHour = m.startClockHour), m.points)
            }
            photos.forEach { (fileName, ownerId, kind) ->
                val file = photoFiles[fileName] ?: return@forEach
                val owner = photoOwnerTypes[fileName] ?: PhotoOwner.RADIATOR
                val newOwner = when (owner) {
                    PhotoOwner.BUILDING -> bId
                    PhotoOwner.ROOM -> roomIds[ownerId]
                    PhotoOwner.RADIATOR -> radiatorIds[ownerId]
                } ?: return@forEach
                val (w, h) = photoStorage.dimensions(file.name)
                photoDao.insert(PhotoEntity(buildingId = bId, ownerType = owner, ownerId = newOwner, fileName = file.name, kind = kind, width = w, height = h))
            }
            bId
        }
    }

    suspend fun scenariosOf(buildingId: Long): List<Scenario> = observeScenarios(buildingId).first().map { it.second }

    suspend fun measurementsOf(buildingId: Long): List<MeasurementSeries> =
        observeMeasurements(buildingId).first().map { (e, pts) -> MeasurementSeries(e.name, e.roomId, e.outdoorC, e.startClockHour, pts) }
}
