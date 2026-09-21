# MegaMobile

MegaMobile est un jeu mobile survivor natif Android, conçu pour des parties infinies en orientation portrait.

## Version actuelle
**V1.1.0 — MOBILE WORLD**

La V1.1.0 adapte l'interface aux téléphones modernes et transforme le terrain infini en carte évolutive à explorer.

### Changements V1.1.0
- interface tactile redimensionnée pour le Pixel 7a et les écrans portrait modernes ;
- trois grands choix regroupés en bas de l'écran pour rester accessibles au pouce ;
- joystick invisible tant que le joueur ne touche pas l'écran ;
- boutons de menu agrandis et mieux espacés ;
- coffres laissés au sol par les ennemis puis récupérés en se déplaçant dessus ;
- armes garanties dans les coffres de boss ;
- chance d'obtenir un coffre d'artefact sur une élite ;
- animation d'ouverture annonçant la rareté classique, rare, épique ou légendaire ;
- carte limitée qui s'agrandit après avoir terminé ses coffres, captures, caches, secrets et zones bonus ;
- mini-carte et suivi de progression de chaque zone.

## Version précédente
**V1.0.2 — CONTRÔLES FLUIDES**

La V1.0.2 conserve le déplacement du joueur après une sélection automatique et ajoute une icône propre à l'application Android.

### Changements V1.0.2
- le joystick reste actif lorsqu'une amélioration, une arme ou un artefact est choisi automatiquement ;
- le personnage reprend immédiatement sa trajectoire après la fermeture du choix ;
- MegaMobile dispose maintenant de sa propre icône Android.

## Historique
**V1.0.1 — ENDLESS STABILITY**

La V1.0.1 renforce les parties très longues sans modifier le principe du jeu : la difficulté continue de monter jusqu'à la mort.

### Changements V1.0.1
- courbe d'XP plafonnée à haut niveau pour éviter une progression pratiquement figée ;
- population minimale de horde qui augmente avec le niveau et le temps ;
- ennemis perdus très loin replacés autour de la zone de jeu sans perdre leurs PV ni leurs récompenses ;
- relance automatique si aucune élimination n'arrive pendant trop longtemps ;
- accumulation excessive de gemmes ramenée progressivement vers le joueur afin de préserver l'XP ;
- limites de sécurité sur les particules, textes, arcs et projectiles pour réduire les blocages lors des très longues parties ;
- difficulté minimale renforcée par le niveau pour éviter qu'un build devienne définitivement intouchable.

## Gameplay
- joystick tactile dynamique récupérable presque partout sur l'écran ;
- dash avec courte invulnérabilité ;
- attaques et ciblage automatiques ;
- hordes qui montent continuellement en puissance ;
- ennemis rapides, tanks, tireurs, élites et variantes spéciales ;
- boss périodiques, boss enragés et cataclysmes ;
- XP, niveaux illimités et trois choix d'améliorations ;
- sélection automatique des améliorations ;
- raretés et montée en puissance pendant la partie ;
- Aura, Orbitales, Foudre, Roquettes, Drones et Impulsion du Vide ;
- évolutions d'armes : Halo solaire, Vortex de lames, Cœur d'orage et Essaim de siège ;
- combos, actes, frappes orbitales et balises ;
- reliques et événements de partie ;
- contrats temporisés avec récompense ou punition ;
- Némésis et primes majeures ;
- jauge de Furie et mode Surpuissance ;
- Ascension après plusieurs boss ;
- paliers de puissance tous les 10 niveaux ;
- Dernier Souffle, utilisable une fois par run ;
- pression anti-stagnation pour éviter les moments sans ennemis ni progression ;
- score, éliminations, statistiques persistantes et record de temps.

## Principes du jeu
- uniquement en **portrait** ;
- application Android native légère ;
- pas de progression obligatoire entre les parties : chaque run repart sur une base propre ;
- partie infinie jusqu'à la mort ;
- difficulté qui continue d'augmenter ;
- priorité à la lisibilité et aux performances sur téléphone.

## Mise à jour automatique
`live/app.json` est le canal de mise à jour de l'application.

Au lancement, MegaMobile :
1. vérifie la version publiée ;
2. télécharge automatiquement les morceaux de l'APK ;
3. reconstruit et vérifie l'APK avec SHA-256 ;
4. ouvre l'installateur Android.

Android demande toujours une confirmation avant de remplacer l'application. Cette confirmation est la seule étape manuelle pour une mise à jour du moteur.

Les visuels et certains paramètres de contenu restent également récupérables à distance depuis le dépôt.

## Build
Le projet est compilé automatiquement par GitHub Actions. Le package Android reste `com.megamobile.game` afin que toutes les versions puissent se mettre à jour les unes les autres avec la même signature.
