# SchemaCraft (Minecraft 26.3, Fabric)

Mini-Litematica côté client : sélection, schémas, hologramme, affichage couche par couche et printer.

## Installation (pas besoin de compiler)

1. **Installer Fabric** pour Minecraft **26.3** : télécharger l'installeur sur <https://fabricmc.net/use/installer/>,
   le lancer, choisir la version `26.3`, puis *Install*. Un profil « fabric-loader-26.3 » apparaît dans le launcher.
2. **Télécharger les deux mods** :
   - `schemacraft-1.4.0.jar` : onglet **Releases** de ce dépôt (à droite sur la page GitHub) → *Assets*.
   - **Fabric API** pour 26.3 : <https://modrinth.com/mod/fabric-api> (filtrer sur la version 26.3).
3. **Les copier dans le dossier `mods`** :
   - Launcher officiel : `Win + R` → taper `%appdata%\.minecraft` → ouvrir (ou créer) le dossier `mods`.
   - CurseForge / Modrinth / Prism : clic droit sur l'instance → *Ouvrir le dossier* → `mods`.
4. **Lancer le jeu** avec le profil Fabric 26.3. En jeu, appuyer sur `,` pour ouvrir le menu.

Pour utiliser des `.litematic`, `.schem` ou `.schematic` : les mettre dans le dossier `schematics` (ou `schemas`)
à côté du dossier `mods`. Les schémas de WorldEdit (`config/worldedit/schematics`) sont aussi listés.
Pas besoin d'avoir Litematica ni WorldEdit installé.

## Contrôles

| Action | Contrôle |
|---|---|
| Position 1 | Bâton : clic droit sur un bloc |
| Position 2 | Bâton : shift + clic droit |
| Menu | `,` |
| Poser le schéma chargé (son coin min sur la face cliquée) | Épée en bois : clic droit |
| Tout afficher / couche par couche | `=` |
| Monter / descendre d'une couche | `PgUp` / `PgDn` |
| Ressources à avoir | `)` (AZERTY) |

Touches modifiables dans Options → Commandes → SchemaCraft.

## Menu (onglets)

- **Sélection** : coordonnées des 2 coins, taille, sauvegarde sous un nom.
- **Schémas** : liste des schémas, clic = charger :
  `<instance>/schemas/*.schema`, et les `.litematic`, `.schem` et `.schematic` de `schemas/`, `schematics/`
  (dossier de Litematica) et `config/worldedit/schematics/` (WorldEdit), sous-dossiers compris.
  - `.litematic` : toutes les régions sont fusionnées.
  - `.schem` / `.schematic` : format Sponge v1-v3 (WorldEdit 7+, FAWE...) et ancien format MCEdit
    d'avant 1.13 (WorldEdit 6, MCEdit, Schematica), détecté automatiquement. Les ids numériques
    des anciens fichiers sont convertis avec `assets/schemacraft/legacy_blocks.txt` ; les connexions
    des barrières / vitres et la forme des escaliers peuvent apparaître en cadre rouge (non stockées
    à l'époque), le printer pose quand même le bon bloc.
  - Dans tous les cas, le contenu des coffres et les entités sont ignorés.
- **Placement** : coin du schéma, déplacement X/Y/Z (shift = 10 blocs), rotation ±90°, miroir X/Z (comme Litematica), mode d'affichage, couches, retirer.
- **Ressources** : objets nécessaires (total, à poser, dans l'inventaire), filtre « à chercher ».
- **Paramètres** : printer (on/off, délai, blocs par pose, portée, couche affichée seulement).
  Sauvegardés dans `config/schemacraft.properties`.

## Hologramme

Bloc manquant = fantôme semi-transparent, bloc faux = cadre rouge,
bloc sans modèle simple (coffre, liquide...) = petit cadre bleu.

## Printer

Pose automatiquement les blocs manquants à portée, du bas vers le haut, avec les blocs de l'inventaire
(créés en créatif). Comme litematica-printer, il simule le placement vanilla avec différentes faces,
hauteurs de clic et rotations pour obtenir le bon état (escaliers, dalles, bûches, fours...).

## Build

- `./build-local.sh` (ou `./build-local.sh install` pour copier dans l'instance Jules) :
  compile avec le JDK 25 de CurseForge, sans Gradle (classpath dans `.buildlib/cp.txt`).
- `./gradlew build` : build Fabric Loom standard.
