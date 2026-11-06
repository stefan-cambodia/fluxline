# FLUXLINE

Jeu de tuyaux pour Android, original, dans la lignée des casse-tête d'arcade de
la fin des années 1980 : on construit un réseau avec les pièces imposées par le
distributeur avant que le plasma ne s'élance.

## Compiler

```sh
export JAVA_HOME=/opt/android-studio/jbr      # Gradle 9.7 refuse le JDK 27
./gradlew :core:test :game:test                # tests (règles, disposition)
./gradlew :android:assembleDebug               # APK de debug
./gradlew :desktop:run -Pargs="--size 540x1170"   # version PC de développement
```

Captures sur PC : `-Pargs="--screen game --mode levels --level 3 --autoplay --shot /chemin/capture.png --delay 10"`.

## Ressources (toutes générées, aucune importée)

```sh
python3 tools/gen_textures.py   # atlas du plateau (tuyaux, cartes d'écoulement, verre)
python3 tools/gen_ui.py         # atlas de l'interface (logo, boutons, icônes)
python3 tools/gen_icon.py       # icônes Android
python3 tools/gen_audio.py      # bruitages et musiques (OGG, via ffmpeg)
python3 tools/gen_fonts.py      # polices russe, chinoise, khmère (fontTools, uharfbuzz)
python3 tools/gen_store.py      # image de présentation Google Play, icône de la fiche
```

Polices : Chakra Petch et Orbitron (logo) ; Exo 2 (cyrillique), Noto Sans SC (chinois) et Noto Sans Khmer, fusionnées par `tools/gen_fonts.py`. Toutes SIL OFL 1.1. Moteur : libGDX (Apache 2.0).

## Modules

- `core` : règles en Kotlin pur (grille, ports, écoulement, score, niveaux, sauvegarde, robot de test).
- `game` : rendu libGDX (shader du plasma, bloom, particules), interface, audio, sauvegarde.
- `android` : lanceur (plein écran, encoche, vibrations, 120 Hz).
- `desktop` : lanceur PC de développement.

## Livrables

```sh
./gradlew :android:assembleRelease   # android/build/outputs/apk/release/android-release.apk
./gradlew :android:bundleRelease     # android/build/outputs/bundle/release/android-release.aab
```

Sans `keystore.properties` (voir `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) ou les variables `FLUXLINE_KEYSTORE_*`, les paquets de release
sont signés avec la clé de debug : installables pour tester, pas publiables.

Documentation technique et état des fonctionnalités : [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
Publication sur Google Play (fiche en 5 langues, déclarations) : [docs/PLAY-STORE.md](docs/PLAY-STORE.md).
Politique de confidentialité : [docs/privacy-policy.md](docs/privacy-policy.md).
