# ThermoHabitat Pro (Android)

Application Android **100 % native** (Kotlin, Jetpack Compose, Material 3) pour réaliser une étude
thermique résidentielle pièce par pièce. Elle intègre l'inventaire photographique et l'identification
assistée des radiateurs existants, la comparaison déperditions / puissances disponibles, l'étude de
compatibilité avec une pompe à chaleur, la simulation dynamique du refroidissement, des scénarios de
rénovation, des estimations de consommation et des rapports PDF.

- Gratuite, sans compte ni abonnement, **sans aucune connexion Internet** : la permission `INTERNET`
  est retirée du manifeste, les données et photos restent dans le stockage privé du téléphone.
- Calculs fondés sur les principes de la NF EN 12831-1, EN ISO 6946, EN ISO 13370, NF EN 442,
  NF EN 14825, NF EN ISO 13786 / 13790.
- Toutes les données d'entrée portent leur origine : **mesurée, déclarée, estimée ou inconnue**.

> Les résultats sont indicatifs. Ils ne remplacent ni une étude thermique réglementaire, ni un
> dimensionnement certifié par un professionnel.

---

## Sommaire

1. [Installer l'APK](#1-installer-lapk)
2. [Compiler le projet](#2-compiler-le-projet)
3. [Architecture](#3-architecture)
4. [Fonctionnalités](#4-fonctionnalités)
5. [Méthodes de calcul](#5-méthodes-de-calcul)
6. [Identification des radiateurs et IA](#6-identification-des-radiateurs-et-ia)
7. [Tests](#7-tests)
8. [Confidentialité](#8-confidentialité)
9. [Limites connues et suites](#9-limites-connues-et-suites)

---

## 1. Installer l'APK

Chaque push déclenche l'intégration continue GitHub Actions (`.github/workflows/android.yml`) :
1. tests du moteur thermique ;
2. compilation de l'APK de debug ;
3. tests unitaires de l'application ;
4. compilation des tests instrumentés ;
5. APK release optimisé par R8.

Pour récupérer l'APK :

1. Ouvrez l'onglet **Actions** du dépôt et choisissez la dernière exécution réussie de « Android CI ».
2. Téléchargez l'artefact **`thermohabitat-pro-apk`**. Il contient :
   - `app-debug.apk` ;
   - `app-release.apk`, optimisé et signé avec la clé de debug pour une installation directe.
3. Sur le téléphone (Android 8.0 ou plus), autorisez l'installation depuis cette source et ouvrez l'APK.

Pour publier sur un store, remplacez dans `app/build.gradle.kts` la signature de debug du type
`release` par votre propre configuration de signature.

## 2. Compiler le projet

Prérequis : **Android Studio** récent (compatible AGP 9.4), **JDK 17 ou 21**, SDK Android
**API 37** installé (via le SDK Manager).

```bash
git clone <ce dépôt>
cd Thermohabitat
./gradlew :core:thermal:test          # tests du moteur thermique (JVM pur)
./gradlew :app:testDebugUnitTest      # tests de l'application (Robolectric)
./gradlew :app:assembleDebug          # APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # APK optimisé R8
./gradlew :app:connectedDebugAndroidTest   # tests sur téléphone/émulateur (caméra, photos, navigation)
```

Dans Android Studio : *File › Open* sur le dossier, puis exécuter la configuration `app`.

Versions principales (`gradle/libs.versions.toml`) :

| Composant | Version |
|---|---|
| Gradle / AGP | 9.8.1 / 9.4.1 |
| Kotlin / KSP | 2.4.21 / 2.3.12 |
| Compose BOM | 2026.09.00 |
| Room / DataStore | 2.8.5 / 1.2.1 |
| Hilt | 2.60.1 |
| CameraX | 1.6.2 |
| ML Kit Text Recognition (embarqué) | 16.0.1 |
| TensorFlow Lite | 2.17.0 |

## 3. Architecture

```
Thermohabitat/
├── core/thermal/            Moteur thermique : bibliothèque Kotlin/JVM pure, sans Android
│   ├── model/               Bâtiment, pièces, parois, couches, baies, radiateurs (sérialisables)
│   ├── library/             Matériaux, baies, locaux non chauffés, climats, compositions types,
│   │                        bibliothèque illustrée des radiateurs, projet d'exemple
│   ├── calc/                R/U, terre-plein, déperditions EN 12831-1, puissance des radiateurs,
│   │                        couverture, température de départ, PAC, énergie, inertie
│   ├── sim/                 Modèle RC multizone, simulateur implicite, calage sur mesures
│   ├── scenario/            Modifications d'amélioration et application sur une copie
│   ├── vision/              Comptage d'éléments, mesure par référence, analyse de plaque (OCR)
│   ├── backup/              Format de sauvegarde JSON
│   └── StudyAnalyzer.kt     Point d'entrée : enchaîne tous les calculs d'une étude
└── app/                     Application Android (MVVM)
    ├── data/db/             Room : entités, DAO, relations, conversions vers le moteur
    ├── data/repository/     Projets, matériaux, réglages (DataStore)
    ├── data/files/          Stockage privé des photographies
    ├── data/export/         Rapport PDF, CSV, sauvegarde ZIP/JSON, import
    ├── domain/              StudyService : analyse recalculée à chaque modification (Flow)
    ├── recognition/         OCR embarqué (ML Kit) et interface de classifieur IA (TFLite)
    ├── di/                  Modules Hilt
    └── ui/                  Écrans Compose (accueil, maison, radiateurs, photo, simulations, rapports)
```

- **Présentation** : écrans Jetpack Compose et ViewModels Hilt. Les formulaires sont sauvegardés
  automatiquement (`AutoSaver`) : enregistrement après une courte pause et à la fermeture de l'écran.
- **Domaine** : le moteur `core:thermal` est indépendant de l'interface et testé sur JVM.
  `StudyService` transforme chaque modification de la base en analyse complète, calculée hors du
  fil principal.
- **Données** : base Room locale avec 11 tables et un schéma exporté dans `app/schemas`.
  Les photos sont dans `filesDir/photos`, les préférences dans DataStore.

### Schéma de la base Room

| Table | Contenu |
|---|---|
| `buildings` | projet : identité, construction, climat, ventilation, hypothèses, paramètres d'étude (JSON) |
| `rooms` | pièces : dimensions, consigne, usage, position sur le plan |
| `surfaces` | parois : type, espace adjacent, orientation, dimensions, U imposé, coefficient b, sol |
| `layers` | couches de l'intérieur vers l'extérieur (matériau, épaisseur, λ spécifique, origine) |
| `openings` | fenêtres et portes (dimensions, U, g, fraction de cadre, origine) |
| `thermal_bridges` | ponts thermiques linéiques ψ·l |
| `materials` | bibliothèque intégrée + matériaux personnalisés |
| `radiators` | fiches radiateurs (identification, dimensions, puissance, exposant, validation) |
| `photos` | photographies (bâtiment, pièce ou radiateur ; face, côté, plaque…) |
| `scenarios` | scénarios d'amélioration (JSON) |
| `measurement_series` | températures mesurées pour le calage |

## 4. Fonctionnalités

**Accueil (tableau de bord)**
- Projets enregistrés.
- Indicateurs : surface, volume, puissance nécessaire, nombre de pièces et de radiateurs, besoins
  couverts, température de départ nécessaire, compatibilité PAC.
- Graphiques interactifs : répartition des déperditions, déperditions par pièce, couverture selon le
  régime d'eau.
- Boutons « Créer une étude thermique » et « Scanner un radiateur ».
- Projet d'exemple « maison 1870 en pisé » chargeable en un geste.

**Maison**
- Fiche bâtiment complète, avec :
  - station climatique indicative corrigée de l'altitude ;
  - valeurs indicatives proposées selon l'époque et le type de bâti.
- Pièces : dimensions, surface et volume automatiques, usage, consigne, renouvellement d'air,
  apports, photos et notes.
- Plan 2D par niveau, coloré selon la couverture ; les pièces se déplacent par appui long.
- Assistant de parois : murs selon les orientations, plancher, plafond et compositions types.
- Éditeur de paroi :
  - couches de l'intérieur vers l'extérieur, épaisseur d'isolant par mur ;
  - R et U en direct, ou U imposé avec son origine ;
  - local non chauffé (coefficient b), pièce voisine, mitoyen, terre-plein ;
  - fenêtres et portes avec valeurs indicatives.
- Bibliothèque de matériaux documentée (λ, ρ, c, plage, source, origine), modifiable et extensible.

**Radiateurs**
- Inventaire photographique : miniature, identifiant, pièce, dimensions, puissance nominale ou
  estimée, puissance au régime choisi.
- Modification, duplication, suppression ; puissance totale disponible.
- Parcours photo :
  - capture CameraX avec guide de prise de vue (face, côté, plaque) ;
  - import depuis la galerie ;
  - recadrage et rotation.
- Identification :
  - bibliothèque illustrée (fonte 2, 3, 4 et 5-6 colonnes, éléments multiples, plaques, panneaux
    acier, tubulaire, aluminium…), classée selon les mesures ;
  - comptage assisté des éléments ;
  - lecture de plaque (OCR embarqué) ;
  - mesure avec objet de référence.
- Fiche « Radiateur identifié » :
  - puissance documentée, ou estimation géométrique avec plage d'incertitude ;
  - fiabilité ;
  - puissances aux 7 régimes (75/65 à 40/30 °C), avec la température ambiante en paramètre.
- Attribution à une pièce existante ou nouvelle.
- **Validation manuelle obligatoire** : seuls les radiateurs validés entrent dans le bilan.

**Simulations**
- Comparaison besoins / puissances, pièce par pièce :
  - code vert / orange / rouge, déficits et puissance à ajouter ;
  - plan coloré ;
  - régime d'eau minimal, à écart départ/retour constant ou à débit conservé.
- Pompe à chaleur :
  - loi d'eau paramétrable et ajustable sur le besoin ;
  - points constructeur (puissance, COP) ;
  - point de bivalence et appoint ;
  - SCOP par classes de température ;
  - couverture à 45, 50, 55 et 60 °C.
- Refroidissement dynamique (modèle RC) :
  - coupure du chauffage, extérieur constant ou jour/nuit, programmation ;
  - apports solaires et internes ;
  - maison entière ou pièce par pièce, avec échanges entre pièces ;
  - comparaison avec un scénario et bande d'incertitude ;
  - calage sur températures mesurées.
- Scénarios :
  - isolation intérieure ou extérieure, combles, plancher, fenêtres, portes ;
  - étanchéité, ventilation, consigne, ponts thermiques ;
  - remplacement ou ajout de radiateur, passage en basse température ;
  - comparateur avant/après.
- Énergie :
  - fioul, gaz, propane, électricité, PAC, bois bûche, granulés ;
  - prix, rendements, consommation réelle pour le calage ;
  - coûts, économies et incidence de la température d'eau sur la PAC.

**Rapports**
- Rapport PDF en 11 sections au choix, avec photos des radiateurs, tableaux colorés et graphique de
  température.
- Exports CSV de l'inventaire des radiateurs et du bilan des pièces.
- Sauvegarde complète ZIP (JSON + photos) ou JSON seul ; import.
- Ouverture, partage ou « Enregistrer sous » à l'initiative de l'utilisateur.
- Suppression complète d'un projet, photos comprises.

## 5. Méthodes de calcul

| Calcul | Méthode |
|---|---|
| Résistances et U | Rt = Rsi + Σe/λ + Rse (EN ISO 6946). Rsi = 0,13 / 0,10 / 0,17 selon le sens du flux, Rse = 0,04 vers l'extérieur. Lames d'air tabulées. |
| Transmission | Φ_T = Σ A·U·f·(θint − θe). f = 1 vers l'extérieur, b vers un local non chauffé, (θint − θj)/(θint − θe) vers une pièce voisine ou un mitoyen. |
| Terre-plein | U équivalent EN ISO 13370 (B' = A/0,5P, épaisseur équivalente dt), puis f_g1 = 1,45, f_g2 et G_w de l'EN 12831-1. |
| Ponts thermiques | Forfait ΔU_TB sur l'enveloppe et/ou ψ·l détaillés. |
| Ventilation, infiltrations | q_inf = 2·V·n50·e·ε et q_min = n_min·V. Naturelle, simple flux ou double flux (rendement). Au niveau du bâtiment : 0,5·Σq_inf. Φ_V = 0,34·q·ΔT. |
| Radiateurs | Φ = Φn·(ΔT_ln/ΔT_ln,n)^n (NF EN 442), conditions 75/65/20 ou 90/70/20, mode débit imposé par bilan ṁ·cp·ΔT. |
| Estimation sans référence | Corrélations géométriques (hauteur, profondeur, éléments ; type de panneau × longueur…) avec plage ±15 à ±40 %. Aucune puissance n'est déduite de la seule photo. |
| Couleurs | Vert si la borne basse couvre le besoin, rouge sous 90 %, orange entre les deux. |
| Température de départ | Recherche par dichotomie, pièce par pièce, à charge de conception ou partielle. |
| PAC | COP constructeur interpolé, ou modèle générique η·T_ch/(T_ch − T_fr). Bivalence. SCOP par classes NF EN 14825 (climat moyen). |
| Énergie | Besoins = H·DJU·24/1000·i. Consommation = besoins / rendement (ou SCOP). Calage sur la consommation réelle. |
| Dynamique | 2 nœuds par pièce (air + masse), H_ms = 9,1·A_m, inertie par la règle des 10 cm (EN ISO 13786), Euler implicite, chauffage borné à la puissance des radiateurs. |

Le dossier `core/thermal/src/test` contient les vérifications analytiques de chaque formule,
ainsi que le cas d'usage réel (`CaseStudy1870Test`).

**Résultats du moteur sur le projet d'exemple** (maison 1870 en pisé, 110 m², θe ≈ −10 °C).
Ces valeurs sortent du test `CaseStudy1870Test`. La géométrie et les radiateurs du projet sont
fictifs.

| Résultat | Valeur |
|---|---|
| Déperditions | ≈ 9,5 kW (87 W/m²), H ≈ 319 W/K |
| Couverture à 55/45 °C | 61 % |
| Couverture à 45/35 °C | 36 % |
| Pièces insuffisantes à 45 °C | toutes |
| Départ nécessaire | ≈ 76 °C (89 °C en hypothèse prudente) |
| Compatibilité PAC en l'état | « défavorable » |
| Scénario « fenêtres + combles + murs non isolés » | > 20 % de déperditions en moins, baisse de la température de départ nécessaire |

## 6. Identification des radiateurs et IA

Cette version **ne contient aucun modèle d'IA entraîné**. Une reconnaissance fictive ne sera jamais
présentée comme fonctionnelle. L'identification combine :

1. **Bibliothèque illustrée** : catégories dessinées en vue de face et de dessus, avec indices
   visuels et mesures à relever. Elles sont classées selon la profondeur et le nombre de colonnes
   saisis.
2. **Comptage assisté des éléments** (`ElementCounter`) : analyse de périodicité du profil de
   luminance sur la zone encadrée. Le résultat affiche un indice de confiance et superpose les
   limites détectées pour contrôle visuel.
3. **Lecture de plaque** : OCR ML Kit exécuté sur le téléphone. `NameplateParser` extrait le
   fabricant (y compris approchant), la puissance (W, kW ou kcal/h converties), les conditions
   (75/65/20, ΔT 50/60), le type de panneau et les dimensions. Chaque champ se coche avant d'être
   appliqué.
4. **Mesure avec référence** : carte bancaire, feuille A4 ou longueur connue posée dans le plan du
   radiateur. L'incertitude est affichée. Sans référence, aucune dimension n'est déduite.

Après toute proposition automatique, la fiche repasse « à valider ».

**Ajouter un modèle entraîné** : l'interface `RadiatorClassifier` est déjà implémentée avec
TensorFlow Lite (`TfLiteRadiatorClassifier`). Déposez dans `app/src/main/assets/models/` :
- `radiator_classifier.tflite` : entrée image RGB, en float ou uint8, sortie en probabilités ;
- `radiator_labels.txt` : un code de catégorie de `RadiatorLibrary` par ligne, par exemple
  `fonte_4col`.

L'application affiche alors les 3 meilleures propositions avec leur confiance ; elles doivent toujours
être confirmées.

## 7. Tests

**Moteur (`core/thermal`, 74 tests JVM)**
- surfaces et volumes, résistances et U ;
- terre-plein, déperditions, ventilation, infiltrations, ponts thermiques ;
- correction des régimes de radiateurs, débit imposé, estimations ;
- couverture et température de départ ;
- loi d'eau, COP, bivalence, SCOP, degrés-jours ;
- simulation RC (solution analytique, équilibre, couplages) et calage ;
- scénarios, sérialisation et sauvegardes ;
- vision : comptage sur images synthétiques, mesure par référence, analyse de plaque ;
- cas d'usage 1870.

**Application (Robolectric)**
- base Room ;
- association radiateurs / pièces, clé étrangère `SET NULL` ;
- aller-retour base ⇄ moteur sans écart de calcul ;
- duplication de pièce, scénarios ;
- sauvegarde ZIP avec photo puis réimport ;
- suppression complète ;
- CSV.

**Sur appareil (`androidTest`)**
- import d'image et association à un radiateur ;
- disponibilité de la caméra ;
- navigation entre les cinq sections.

## 8. Confidentialité

- Aucune permission réseau : `INTERNET` et `ACCESS_NETWORK_STATE` sont retirées par fusion du
  manifeste. Aucun service distant n'est utilisé.
- Seule permission demandée : `CAMERA`, au moment de la prise de vue. L'import passe par le
  sélecteur de photos du système, sans permission de stockage.
- Photos dans le stockage privé de l'application ; sauvegarde cloud et transfert d'appareil
  désactivés.
- Les fichiers ne quittent le téléphone que par un export choisi par l'utilisateur (partager,
  enregistrer sous).
- Suppression complète d'un projet (données et photos) depuis l'onglet Rapports.

## 9. Limites connues et suites

- **Mesure ARCore non implémentée** : la précision attendue sur des cotes de radiateur au
  centimètre n'est pas garantie et nécessiterait les services AR de Google. La mesure par objet de
  référence et la saisie manuelle sont proposées.
- **Pas de modèle d'IA de classification entraîné** : voir la section 6 pour en ajouter un.
- **Nombre de colonnes non détecté automatiquement** : il se déduit de la vue de dessus, de la
  profondeur et de la bibliothèque illustrée.
- **Données climatiques indicatives** : une vingtaine de stations de référence, corrigées de
  l'altitude, à vérifier pour la commune (annexe nationale NF EN 12831-1/CN, Météo-France).
- **Prix de l'énergie** : des exemples à remplacer par les tarifs réels.
- **SCOP** : calculé sur le climat moyen NF EN 14825.
- **Profil solaire simplifié** pour la simulation dynamique.
- **Tests instrumentés** : compilés en CI, à exécuter sur un appareil.
