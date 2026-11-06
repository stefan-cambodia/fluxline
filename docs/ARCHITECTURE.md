# FLUXLINE — documentation technique

## Vue d'ensemble

```
core     Kotlin pur, aucune dépendance. Règles, simulation, niveaux, sauvegarde, robot.
game     libGDX. Rendu, interface, entrées, audio, persistance. Multiplateforme.
android  Lanceur Android (activité unique, vibrations, plein écran, 120 Hz).
desktop  Lanceur PC de développement (captures, démonstration). Non distribué.
tools    Scripts Python qui génèrent toutes les images et tous les sons.
assets   Ressources générées, partagées par Android et le PC.
```

La logique ne connaît rien du rendu : `GameSession` émet des `GameEvent`
(pièce posée, conduit rempli, fuite…) que l'écran de jeu relaie au rendu, à
l'audio et aux vibrations.

## Règles (`core`)

- **Pièces** (`Piece`) : chaque pièce déclare ses conduits (`Channel`, paire de
  ports). La croix a deux conduits indépendants. Sources et arrivées ont un port
  au centre (`null`). Un sens unique refuse le fluide à contre-courant.
- **Plateau** (`Board`) : tableaux plats (pièce, fixe, posée par le joueur,
  remplissage et état de chaque conduit, port d'entrée). Aucune allocation pendant la simulation.
- **Écoulement** (`GameSession.update`) : pas fixe (1/120 s, côté écran), donc
  déterministe. Le fluide remplit le conduit de tête, puis sort par l'autre port.
  Il faut qu'une pièce voisine accepte ce port, dans un conduit encore vide ;
  sinon c'est une fuite et la manche se termine. Elle est réussie si l'objectif
  est atteint (et l'arrivée rejointe si elle est exigée).
- **Barème** (`Scoring`) : tuyau +50 (+100 au-delà de l'objectif), réservoir
  +150, croisement +500, arrivée +1000, remplacement −50 (et 0,4 s de blocage),
  pièce posée jamais traversée −50, accélération ×2.
- **Distributeur** (`PieceQueue`) : tirage pondéré, graine SplitMix64, jamais
  trois pièces identiques de suite, limite optionnelle (défis).
- **Modes** (`Run`) : Classique (pas de rotation, une manche ratée = fin),
  Campagne (20 niveaux, rotation de la prochaine pièce, étoiles), Arcade
  (rotation, rythme rapide, 3 vies), Défi du jour (graine = date).
- **Génération** (`LevelGenerator`) : source jamais collée au bord, sortie
  dégagée, espace accessible ≥ objectif + 8. Difficulté croissante (objectif,
  compte à rebours, vitesse, blocs, réservoirs, sens uniques, arrivée).
- **Sauvegarde** (`SaveCodec`) : texte « clé=valeur » versionné, plateau complet
  inclus ; le décodage refuse tout état incohérent et tolère les blancs parasites
  (Android indente le XML des préférences).
- **Équilibrage** : mesuré avec des joueurs humains simulés (`HumanBalanceTest`).
  Le robot y joue plus lentement, perd du temps à chaque rotation et choisit
  parfois une option plausible au lieu de la meilleure. Débutant : 2,2 s par
  pose, 30 % d'erreurs ; occasionnel : 1,6 s, 18 %. Garanties : le débutant
  réussit au moins 75 % du temps les niveaux 1 à 9 de la campagne et 70 % les
  deux premières manches du Classique ; l'occasionnel réussit chaque niveau de
  la campagne au moins 65 % du temps. Le robot expert (0,75 s, sans erreur)
  doit réussir les 20 niveaux.
- **Robot** (`AutoPlayer`) : ne voit que ce que voit le joueur, agit au plus toutes
  les `thinkTime` secondes. Glouton (simule chaque pose et note le réseau obtenu)
  ou planificateur (itinéraire par recherche en profondeur). Il sert aux tests
  d'équilibrage, à la démonstration du menu et au mode `autoplay`.

## Rendu (`game/render`)

- **Textures** : chaque pièce a trois images de 208 px (case de 176 px plus une
  marge pour l'ombre) :
  - `body` : céramique, laiton et ombre portée ;
  - `flow` : carte d'écoulement (R = position le long du conduit, G = masque de
    la fenêtre, B = profil transversal) ;
  - `glass` : reflets, dessinés par-dessus le fluide.
- **Plasma** (`fluid.frag`) : un seul lot pour tout le plateau. Les paramètres
  par case passent dans la couleur du sprite (r = remplissage, g = sens,
  b = intensité). Le front de fluide suit exactement la forme du conduit, coudes compris.
- **Bloom** (`Bloom`) : le cœur du plasma et les étincelles sont redessinés au
  quart de la résolution, floutés (deux passes séparables), puis ajoutés.
  Désactivable dans les options.
- **Particules** : tableaux de taille fixe, sans allocation.
- **Fond** : shader plein écran (dégradé, grille, lueurs dérivantes, grain).

## Interface et gestes (`game/screens`, `game/ui`)

- Toucher le plateau montre l'aperçu et les lignes de visée ; relâcher pose.
  Sortir du plateau annule. Glisser depuis la prochaine pièce fonctionne aussi.
- « Accélérer » se maintient 0,4 s (action irréversible).
- Menu principal : l'icône « quitter » demande un second toucher dans les 3 s
  (un effleurement ne ferme pas le jeu) ; la touche retour quitte directement.
- Une pose refusée pendant le blocage d'un remplacement est rejouée automatiquement.
- `GameLayout` (pur, testé) place le HUD, le plateau et le distributeur pour
  tout format, en respectant les zones sûres.
- Polices rastérisées par FreeType à la taille exacte de l'écran.

## Langues (`game/i18n`, `assets/i18n`, `tools/gen_fonts.py`)

- Cinq langues : français (référence), anglais, russe, chinois simplifié, khmer.
  Par défaut, celle du système (anglais pour toute autre langue) ; la ligne
  « Langue » des options l'impose, avec effet immédiat. Android 13+ la propose
  aussi dans « Langue de l'application » (`locales_config.xml`).
- Textes : `assets/i18n/<code>.properties` (UTF-8, `clé = valeur`). Paramètres
  nommés (`{n}`, `{title}`) ; pluriels `clé.one/few/many/other` selon les règles
  CLDR de la langue ; séparateurs de milliers et décimal propres à chaque langue.
  Une clé absente se rabat sur l'anglais, puis s'affiche telle quelle.
- Textes trop longs (le russe dépasse souvent le français de 20 à 30 %) :
  réduits automatiquement à la largeur disponible.
- Polices : FreeType (libGDX) dessine un glyphe par caractère, sans moteur de
  mise en forme. `tools/gen_fonts.py` fusionne donc dans Chakra Petch le
  cyrillique d'Exo 2 et les seuls idéogrammes utilisés de Noto Sans SC. Le khmer
  (consonnes souscrites, voyelles réordonnées) est mis en forme d'avance par
  HarfBuzz : chaque syllabe devient un glyphe en zone d'usage privé, et le jeu lit
  `km.shaped.properties`. Dans `km.properties`, `|` marque les coupures de ligne
  possibles (le khmer n'a pas d'espaces entre les mots).
- Après toute modification de `ru`, `zh` ou `km.properties` :
  `python3 tools/gen_fonts.py` (un test le rappelle si le khmer est périmé).

## Cycle de vie Android

- Mise en arrière-plan : la partie passe en pause et est sauvegardée.
- Arrêt du processus : « Continuer » au menu restaure la partie (vérifié sur émulateur).
- Touche retour : pause, reprise, navigation entre écrans
  (`enableOnBackInvokedCallback="false"`).
- Portrait verrouillé, y compris sur grand écran (`appCategory="game"`).
- Rendu sous l'encoche ; fréquence d'affichage maximale demandée.

## Tests

| Module | Couverture |
|---|---|
| `core` (51 tests) | ports, rotation, connexions, écoulement par type de pièce, fuites (bord, vide, pièce incompatible, bloc, conduit plein), croix, réservoir, sens unique, arrivée, remplacement et blocage, accélération, score, étoiles, distributeur, sauvegarde (reprise identique, fichiers abîmés), génération, faisabilité des 20 niveaux, équilibrage, équilibrage par joueurs humains simulés (campagne, premières manches Classique et Arcade) |
| `game` (34 tests) | disposition sur 11 formats, correspondance pixel → case ; traductions : mêmes clés et paramètres que le français, formes plurielles, chaque caractère présent dans la police de sa langue, khmer pré-mis en forme à jour, chaque clé utilisée par le code existe, nombres |

## État des fonctionnalités

**Terminé et vérifié sur émulateur Android** (téléphones 15 et 8.0, version de
production minifiée ; tablette 15) :
- les quatre modes joués jusqu'au bilan (Arcade : vie perdue puis reprise) ;
- menu avec démonstration ;
- campagne avec étoiles et déblocage ;
- options (volumes séparés, vibrations, bloom, aide, lignes rétro, réinitialisation) ;
- statistiques et crédits ;
- pause, reprise, sauvegarde et restauration ;
- 16:9 et 20:9 ;
- interface en cinq langues (français, anglais, russe, chinois, khmer) sur tous
  les écrans, y compris la langue par application d'Android 13+ sur la version
  de production ;
- tablette 10 pouces en portrait (plein écran, poses au toucher) et tenue en
  paysage (Android affiche alors le jeu en 3:4 entre deux bandes noires).

**Vérifié sur un appareil réel** (Samsung Galaxy A56, Android 16, Exynos 1580,
1080×2340, 6 novembre 2026), versions debug et de production :
- 60 images/s stables en partie, menu compris : 905 images en 15 s, aucune
  perdue ni saccadée selon les statistiques du compositeur Android (`timestats`) ;
- 120 Hz réels quand le système l'autorise : 1 792 images en 15 s, aucune perdue
  ni saccadée. L'économie d'énergie ou la fluidité Samsung « Standard » ramènent
  l'écran à 60 Hz, quelle que soit la demande du jeu ;
- boucle de rendu sans allocation (tas Java stable hors robot de démonstration) ;
- vibrations émises (effets prédéfinis Samsung : tick à la rotation, clic à la pose) ;
- disposition autour de la caméra perforée, langue choisie selon le système.

**Implémenté mais non vérifiable ici :**
- le ressenti des vibrations ;
- la qualité sonore (sons générés et lus, jamais écoutés).

**Non fait :**
- clé de signature de production (le paquet de release est signé avec la clé de debug) ;
- test sur tablette réelle et sur un téléphone d'entrée de gamme ;
- relecture des traductions russe, chinoise et khmère par des locuteurs natifs.
