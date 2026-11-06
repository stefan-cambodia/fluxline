# FLUXLINE — Publication sur Google Play

Tout ce qu'il faut pour remplir la fiche et les déclarations de la Console Play.
Les fichiers suivent l'arborescence de fastlane (`fastlane supply` sait les
envoyer tels quels), mais on peut aussi les copier à la main dans la Console.

## Fiche du magasin (`fastlane/metadata/android/<langue>/`)

| Langue | Dossier |
|---|---|
| Français | `fr-FR` |
| Anglais | `en-US` |
| Russe | `ru-RU` |
| Chinois simplifié | `zh-CN` |
| Khmer | `km-KH` |

Dans chaque dossier :

- `title.txt` — nom (30 caractères au plus) : FLUXLINE ;
- `short_description.txt` — description courte (80 au plus) ;
- `full_description.txt` — description complète (4 000 au plus) ;
- `images/icon.png` — icône 512 × 512 (`tools/gen_icon.py`) ;
- `images/featureGraphic.png` — image de présentation 1024 × 500 (`tools/gen_store.py`) ;
- `images/phoneScreenshots/1.jpg` à `4.jpg` — captures 1080 × 1920 dans la
  langue de la fiche : partie en cours, bilan à trois étoiles, tutoriel, menu.

Régénérer l'image de présentation et l'icône : `python3 tools/gen_store.py`.

Les captures viennent du jeu lui-même (émulateur réglé en 1080 × 1920 : le Play
Store refuse un rapport supérieur à 2:1, donc pas de 1080 × 2400). La version de
debug accepte des options de lancement (`--es lang`, `--es screen`,
`--es mode`, `--es level`, `--es autoplay 1`) qui ouvrent directement l'écran voulu.

## Paramètres de l'application

- **Catégorie** : Jeux › Éducatif (jeu éducatif de réflexion). Étiquettes
  suggérées : Réflexion, Casse-tête, Logique. Alternative : Jeux › Réflexion.
- **Prix** : gratuit. **Publicités** : non. **Achats intégrés** : non.
- **Accès à l'application** : aucun identifiant nécessaire.
- **Adresse e-mail de contact** : à fournir (obligatoire, visible sur la fiche).
- **Politique de confidentialité** : `docs/privacy-policy.md`, publiée par
  exemple à l'adresse
  https://github.com/stefan-cambodia/fluxline/blob/main/docs/privacy-policy.md
  (le dépôt est public).

## Déclarations

- **Sécurité des données** : aucune donnée collectée, aucune donnée partagée.
  L'application n'a pas accès à Internet ; réglages et progression restent sur
  l'appareil. Seule permission : `VIBRATE`.
- **Classification du contenu (questionnaire IARC)** : pas de violence, de sang,
  de peur, de langage grossier, de jeux d'argent, de contenu sexuel, de drogue ;
  pas d'interaction entre utilisateurs, pas de partage de position, pas d'achat
  numérique. Résultat attendu : tous publics (PEGI 3 / ESRB Everyone).
- **Public cible : tous âges, enfants compris** (décision du 6 novembre 2026).
  Tranches d'âge proposées : 6-8, 9-12, 13-15, 16-17 et 18 ans et plus (le
  tutoriel demande de savoir lire ; le compte à rebours et les vies conviennent
  dès 6 ans). Les moins de 5 ans ne sont pas visés.
  Cibler des enfants soumet l'application au **programme Familles** de Google
  Play. FLUXLINE en remplit les exigences principales :
  - aucune publicité, aucun achat intégré, aucun lien vers l'extérieur ;
  - aucune donnée collectée ni partagée, aucun identifiant d'appareil lu ;
  - une seule permission, `VIBRATE` (aucune localisation, caméra, micro, contacts) ;
  - contenu tous publics, politique de confidentialité publiée ;
  - aucune bibliothèque tierce de publicité, d'analyse ou de connexion.
  À cocher dans la Console : « L'application cible des enfants » et la
  conformité au programme Familles. Le label « Approuvé par les enseignants »
  est attribué par Google après examen ; il ne se demande pas.
- **Application d'actualité, application gouvernementale, fonctionnalités
  financières, santé** : non.

## Paquet à envoyer

```sh
export JAVA_HOME=/opt/android-studio/jbr
./gradlew :android:bundleRelease   # android/build/outputs/bundle/release/android-release.aab
```

Avant le premier envoi : créer la clé d'envoi et le fichier `keystore.properties`
(voir le README). Sans eux, l'AAB est signé avec la clé de debug et la Console
le refuse. Activer la signature d'application par Google Play (Play App Signing) :
Google conserve la clé de signature, et la clé d'envoi peut être remplacée si
elle est perdue.

À chaque nouvelle version : augmenter `versionCode` (et `versionName`) dans
`android/build.gradle.kts`.
