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
│   ├── vision/              Redressement de la façade, comptage des éléments, échelle, plaque (OCR)
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
| `measurement_series` | températures mesurées pour le calage, avec leurs conditions (extérieur, température initiale, durée) |

La base est en version 2 ; la migration 1 → 2 ajoute les conditions des séries de mesures sans
perte de données.

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
  - « Identifier sur la photo » : contour à 4 coins sur une photo de face ou un peu de biais,
    référence d'échelle, nombre d'éléments, pas, hauteur et longueur avec leur origine et leur
    incertitude, types compatibles de la base avec leurs raisons (voir la section 6) ;
  - bibliothèque illustrée (fonte 2 à 6 colonnes, fonte DIN, ornée, à plaques, acier, panneaux,
    tubulaire, aluminium…) avec plages de pas et de hauteurs, classée selon les mesures ;
  - lecture de plaque (OCR embarqué) ;
  - mesure d'une cote isolée (profondeur…) avec objet de référence, sur une photo prise bien en
    face de cette cote.
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
| Terre-plein | U équivalent EN ISO 13370 (B' = A/0,5P sur les seuls planchers, épaisseur équivalente dt), puis f_g1 = 1,45, f_g2 et G_w de l'EN 12831-1. Un périmètre exposé manquant est signalé. Un mur ou un rampant déclaré « terre-plein » est traité comme extérieur (prudent), avec un avertissement. |
| Ponts thermiques | Forfait ΔU_TB sur l'enveloppe et/ou ψ·l détaillés. |
| Ventilation, infiltrations | q_inf = 2·V·n50·e·ε et q_min = n_min·V. Naturelle, simple flux ou double flux (rendement). Au niveau du bâtiment : 0,5·Σq_inf. Φ_V = 0,34·q·ΔT. |
| Radiateurs | Φ = Φn·(ΔT_ln/ΔT_ln,n)^n (NF EN 442), conditions 75/65/20 ou 90/70/20, mode débit imposé par bilan ṁ·cp·ΔT. |
| Estimation sans référence | Corrélations géométriques (hauteur, profondeur, éléments ; type de panneau × longueur…) avec plage ±15 à ±40 %. Une photo ne fournit des dimensions mesurées qu'avec une échelle ; avec un pas standard supposé, les dimensions et la puissance qui en découle sont signalées comme estimées (simple ordre de grandeur). |
| Analyse de photo | Homographie 4 points (DLT), rapport d'aspect du rectangle vu en perspective (Zhang et He), comptage spectral par bandes, loi projective des joints ajustée de façon robuste, incertitudes par perturbation déterministe des points pointés (±3 px). Détails en section 6. |
| Couleurs | Vert si la borne basse couvre le besoin, rouge sous 90 %, orange entre les deux. |
| Température de départ | Recherche par dichotomie, pièce par pièce, à charge de conception ou partielle. |
| PAC | COP et puissance constructeur interpolés. Hors de la plage de la fiche, dans le sens défavorable (plus froid, eau plus chaude) : COP transposé par le rapport des COP de Carnot, puissance dégradée de 2,5 %/K extérieur et 0,5 %/K d'eau ; ces valeurs sont signalées comme transposées. Sans données : modèle générique η·T_ch/(T_ch − T_fr). Bivalence. SCOP par classes NF EN 14825 (climat moyen) ; sans puissance connue, l'appoint n'est pas évalué et le SCOP est signalé comme optimiste. |
| Énergie | Besoins = H·DJU·24/1000·i, DJU corrigés selon la consigne moyenne (pondérée par le H des pièces) : ±1 DJU par kelvin et par jour de la saison de chauffage (≈ 205 j). Consommation = besoins / rendement (ou SCOP). Calage sur la consommation réelle : le rapport réel/modèle de l'état actuel est appliqué au besoin modélisé des scénarios. |
| Dynamique | 2 nœuds par pièce (air + masse), H_ms = 9,1·A_m, inertie par la règle des 10 cm (EN ISO 13786), Euler implicite, chauffage borné à la puissance des radiateurs. Ventilation ramenée au débit du bâtiment (0,5·Σq_inf) ; la « maison entière » a le même H permanent que le modèle multizone. Calage capacité + déperditions par simplexe de Nelder-Mead. |

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
présentée comme fonctionnelle : chaque résultat est une **proposition** issue d'un calcul géométrique
ou d'un traitement du signal, affichée avec son origine, son incertitude et ses raisons, que
l'utilisateur vérifie et valide. L'identification combine :

1. **Identifier sur la photo** (`PhotoIdentifyScreen`, moteur `vision/PhotoIdentification.kt`) :
   analyse géométrique décrite ci-dessous.
2. **Bibliothèque illustrée** : catégories dessinées en vue de face et de dessus, avec indices
   visuels, plages de pas et de hauteurs, et mesures à relever. Elles sont classées selon les
   mesures disponibles.
3. **Lecture de plaque** : OCR ML Kit exécuté sur le téléphone. `NameplateParser` extrait le
   fabricant (y compris approchant), la puissance (W, kW ou kcal/h converties), les conditions
   (75/65/20, ΔT 50/60), le type de panneau et les dimensions. Chaque champ se coche avant d'être
   appliqué.
4. **Mesure avec référence** (outil « Mesurer ») : une cote isolée, la profondeur sur une vue de
   côté par exemple, sur une photo prise bien en face de cette cote, à partir d'une carte bancaire,
   d'une feuille A4 ou d'une longueur connue posée dans le même plan. L'incertitude est affichée.

Après toute proposition, la fiche repasse « à valider ».

### Analyse géométrique d'une photo

Le parcours compte trois étapes :

1. **Contour** : l'utilisateur fait glisser 4 coins sur les angles du corps du radiateur (sans pieds
   ni tuyaux), avec une loupe pendant le glissé. La photo peut être prise de biais : le radiateur y
   apparaît comme un quadrilatère en perspective, pas comme un rectangle. Un objet posé devant
   (chaise, pot) ne change pas le contour ; un coin caché se place dans le prolongement des bords
   visibles.
2. **Échelle** : facultative pour compter, nécessaire pour mesurer (tableau ci-dessous).
3. **Analyse** sur le téléphone, puis vérification et report sur la fiche.

Le calcul est déterministe (mêmes entrées, même résultat) :

- **Redressement de la façade** : homographie entre le contour et un rectangle (DLT à 4 points).
  Le rapport largeur / hauteur réel est estimé par la méthode de Zhang et He (« whiteboard
  scanning ») avec la focale déduite des deux points de fuite du contour quand elle est plausible,
  sinon la focale d'un téléphone courant (≈ 26 mm équivalent 24×36). Sur la façade redressée, les
  éléments sont équidistants.
- **Sélection des bandes visibles** : la hauteur est découpée en bandes horizontales ; seules les
  zones où le motif est nettement périodique sont retenues. Les parties masquées par un objet sont
  écartées et les bandes analysées sont montrées sur l'aperçu.
- **Pas commun** : spectre combiné des bandes retenues ; les candidats N, N/2, N/3 et 2N (reflets,
  colonnes arrondies, rainures) sont départagés par leurs harmoniques et par la plage de pas
  plausible, convertie en pixels quand l'échelle est connue.
- **Loi projective des joints** : les éléments étant identiques, le joint d'indice k coupe une
  rangée en x = (a·k + b) / (c·k + 1), avec un dévers linéaire d'une rangée à l'autre. L'ajustement
  robuste de cette loi rejette les faux joints (pieds de chaise, tiges, bords d'objets), comble les
  joints masqués et corrige la perspective résiduelle due à un coin caché placé approximativement.
- **Bords et nombre** : les éléments extrêmes sont comparés au gabarit des éléments bien visibles ;
  un bord masqué, peu net ou hors du contour donne un doute de ± 1 et une alternative. Les
  ambiguïtés restantes (motif deux fois plus fin, joints alternativement marqués) sont proposées
  comme alternatives, avec leur raison.
- **Haut et bas du corps** détectés sur la façade redressée, pour des coins pointés un peu trop haut
  ou trop bas.
- **Dimensions** : pas, hauteur et longueur avec leur incertitude (perturbation déterministe de
  ±3 px de chaque point pointé, rapport d'aspect, bords détectés) et leur origine : *mesurée* avec
  une référence métrique, *estimée* avec un pas standard, aucune sans échelle.
- **Indices de confiance** : celui du comptage repose surtout sur les joints observés qui suivent
  la loi ; celui de l'analyse y ajoute la précision des dimensions et de la prise de vue.

À l'écran, l'aperçu redressé en couleur et la photo d'origine montrent les limites d'éléments ; le
nombre se règle par − / + et les traits se recalculent pour être comparés aux joints. « Reporter sur
la fiche » écrit le nombre, la hauteur et la longueur (des cotes relevées au mètre sont conservées
par défaut), la profondeur et les colonnes saisies, le type choisi, la méthode « Analyse
géométrique de la photo » et l'indice de confiance.

**Références d'échelle**, posées à plat contre la façade (pas sur le mur derrière) :

| Référence | Pointage | Dimensions | Résultat |
|---|---|---|---|
| Carte au format ISO/IEC 7810 ID-1 (carte bancaire) | 4 coins | 85,60 × 53,98 mm | Mesure |
| Feuille A4 ou A5 (ISO 216) | 4 coins | 297 × 210 mm, 210 × 148 mm | Mesure |
| Billets en euros, série Europe | 4 coins | 5 € 120 × 62, 10 € 127 × 67, 20 € 133 × 72, 50 € 140 × 77, 100 € 147 × 77 mm | Mesure |
| Pièces de 1 €, 2 € et 50 centimes | 2 points (diamètre) | Ø 23,25, 25,75 et 24,25 mm | Mesure, précision limitée |
| Mètre ruban ou cote connue | 2 points et longueur lue | saisie | Mesure |
| Hauteur ou longueur du corps relevée au mètre | saisie | saisie | Mesure ; l'autre dimension suit le rapport d'aspect |
| Pas standard d'une catégorie de la bibliothèque | choix dans une liste | plage documentée | **Estimation**, incertitude d'une demi-plage (au moins 6 %) |
| Aucune | — | — | Nombre d'éléments seul |

Un objet à 4 coins donne les échelles horizontale et verticale lues sur la façade redressée, sans
hypothèse de focale ; sa propre homographie sert de contrôle (avertissement si l'objet n'est pas
dans le plan de la façade ou si les coins ne concordent pas).

**Base de données et rapprochement** (`RadiatorLibrary`, `RadiatorMatcher`) : chaque catégorie
documente ses plages de pas d'élément, ses hauteurs usuelles ou normalisées (DIN 4703), ses
profondeurs (selon le nombre de colonnes quand elles sont connues), ses colonnes possibles, son
motif vu de face (éléments verticaux, cannelures d'un panneau qui ne sont pas des éléments,
barreaux, façade lisse), son époque, ses indices visuels, des puissances d'éléments documentées à
ΔT 50 K (ordre de grandeur de comparaison), la fiabilité des données et leurs sources. Les
catégories couvrent la fonte (2, 3, 4 et 5-6 colonnes, moderne à colonnes fines, DIN 4703, ornée,
à face lisse, à plaques), l'acier (embouti, à lamelles, panneaux, tubulaire), l'aluminium, les
sèche-serviettes, les convecteurs et les radiateurs décoratifs. Le rapprochement compare le pas, les
proportions (rapport pas / hauteur, utilisable même sans échelle), la hauteur, la profondeur, les
colonnes, le motif et le type : chaque catégorie reçoit un verdict (compatible, possible, peu
probable, non départagé) et des **raisons lisibles**, par exemple « Pas de 61 ± 4 mm compatible
(usuel 55 à 66 mm) » pour une fonte à colonnes. Les nombres d'éléments alternatifs sont aussi
confrontés à la base. Le type n'est appliqué que si l'utilisateur le choisit.

**Limites** :
- aucun modèle d'IA : le type n'est pas reconnu, il est rapproché de la base à partir des mesures ;
- le résultat est à vérifier sur l'aperçu (traits sur les joints) avant le report, et la fiche doit
  ensuite être validée ;
- sans référence d'échelle, aucune dimension n'est mesurée ; avec un pas standard, les dimensions
  sont estimées et doivent être confirmées au mètre ;
- la précision dépend de la taille de la référence dans l'image (une référence de quelques
  dizaines de pixels est signalée ; une feuille A4 est plus précise qu'une carte, une pièce l'est
  moins), de sa planéité contre la façade et du placement des coins ;
- sans objet à 4 coins, les proportions dépendent de la focale supposée ; une vue très oblique
  (au-delà de 50° environ) réduit la précision ;
- un coin caché placé approximativement est toléré par le comptage, mais les dimensions en
  dépendent ;
- le comptage vaut pour les radiateurs à éléments ; les cannelures d'un panneau ne sont pas des
  éléments : si un panneau est retenu, le nombre compté n'est pas reporté.

**Ajouter un modèle entraîné** : l'interface `RadiatorClassifier` est déjà implémentée avec
TensorFlow Lite (`TfLiteRadiatorClassifier`). Déposez dans `app/src/main/assets/models/` :
- `radiator_classifier.tflite` : entrée image RGB, en float ou uint8, sortie en probabilités ;
- `radiator_labels.txt` : un code de catégorie de `RadiatorLibrary` par ligne, par exemple
  `fonte_4col`.

L'application affiche alors les 3 meilleures propositions avec leur confiance ; elles doivent toujours
être confirmées.

## 7. Tests

**Moteur (`core/thermal`, 158 tests JVM)**
- surfaces et volumes, résistances et U ;
- terre-plein, déperditions, ventilation, infiltrations, ponts thermiques ;
- correction des régimes de radiateurs, débit imposé, estimations ;
- couverture et température de départ ;
- loi d'eau, COP, bivalence, SCOP, degrés-jours ;
- simulation RC (solution analytique, équilibre, couplages) et calage ;
- scénarios, sérialisation et sauvegardes ;
- vision : homographie aller-retour, redressement et rapport d'aspect (à ±8 % jusqu'à 35° avec une
  focale fausse de 15 %), comptage sur images synthétiques de radiateurs en fonte vus de biais
  (5 à 30 éléments jusqu'à 35°, bon nombre ou alternative à 45°), objets devant le radiateur et coin
  caché, faux joints rejetés, carte bancaire à 35° (hauteur et longueur à ±3 %), images uniformes
  ou bruit sans faux comptage, mesure par référence, analyse de plaque (« 1 500 W », « 1,5 kW »,
  kcal/h, références) ;
- rapprochement avec la base : pas, proportions, profondeur, colonnes, cannelures de panneau,
  nombres alternatifs, raisons en français ;
- cas limites relevés en revue : bornes de loi d'eau inversées, données PAC hors plage, calage
  appliqué aux scénarios, consigne et DJU, radiateur validé sans puissance, terre-plein ;
- cas d'usage 1870.

**Application (Robolectric)**
- base Room ;
- association radiateurs / pièces, clé étrangère `SET NULL` ;
- aller-retour base ⇄ moteur sans écart de calcul ;
- duplication de pièce, scénarios ;
- sauvegarde ZIP avec photo puis réimport : scénarios renumérotés, matériaux et conditions de
  mesure conservés ;
- suppression complète, exports générés compris ;
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
- Suppression complète d'un projet (données, photos et exports générés) depuis l'onglet Rapports ;
  les exports d'une session précédente sont effacés au démarrage.

## 9. Limites connues et suites

- **Mesure ARCore non implémentée** : la précision attendue sur des cotes de radiateur au
  centimètre n'est pas garantie et nécessiterait les services AR de Google. La mesure sur photo
  avec une référence d'échelle et la saisie manuelle sont proposées.
- **Pas de modèle d'IA de classification entraîné** : voir la section 6 pour en ajouter un.
- **Identification sur photo** : analyse géométrique dont les résultats restent à vérifier ; sans
  référence métrique, les dimensions sont absentes ou seulement estimées (pas standard) ; la
  précision dépend de la taille de la référence dans l'image (section 6).
- **Nombre de colonnes non détecté automatiquement** : il se déduit de la vue de dessus, de la
  profondeur et de la bibliothèque illustrée.
- **Données climatiques indicatives** : une vingtaine de stations de référence, corrigées de
  l'altitude, à vérifier pour la commune (annexe nationale NF EN 12831-1/CN, Météo-France).
- **Prix de l'énergie** : des exemples à remplacer par les tarifs réels.
- **SCOP** : calculé sur le climat moyen NF EN 14825.
- **Profil solaire simplifié** pour la simulation dynamique.
- **Tests instrumentés** : compilés en CI, à exécuter sur un appareil.
