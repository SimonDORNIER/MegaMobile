# MegaMobile

Jeu mobile survivor inspiré du rythme et de la montée en puissance de Megabonk, développé comme une application Android native légère.

## Contraintes principales
- Jeu **uniquement en orientation verticale (portrait)**.
- Rendu 2D / 2.5D natif Android, sans Unity, Godot ni navigateur à ouvrir.
- Interface pensée pour le tactile et jouable à une main autant que possible.
- Parties infinies jusqu'à la mort, difficulté en hausse continue.
- Priorité aux performances sur téléphone pendant les longues parties.

## V0.3 native
La base actuelle comprend :
- joystick tactile dynamique ;
- dash avec invulnérabilité courte et recharge ;
- tirs automatiques et ciblage automatique ;
- hordes avec plusieurs familles d'ennemis ;
- ennemis rapides, tanks et tireurs ;
- boss périodiques et coffres ;
- XP, niveaux et trois choix d'améliorations ;
- raretés Commun, Rare, Épique et Légendaire ;
- statistiques de dégâts, cadence, vitesse, portée, critique, armure, régénération, aimant et multishot ;
- quatre familles d'armes cumulables : Aura, Orbitales, Foudre et Roquettes ;
- sélection automatique des améliorations ;
- pause, recommencer et quitter ;
- difficulté infinie, compteur d'éliminations et score ;
- effets 2.5D légers : ombres, particules, flash de dégâts et effets d'armes.

## Mise à jour automatique
Le dossier `live/` contient le manifeste et la configuration distante. L'application charge `live/config.json` au démarrage avec un fallback hors-ligne. Cela permet de modifier l'équilibrage et certains paramètres sans réinstaller l'APK.

Les modifications profondes du moteur Android nécessitent encore une APK. Le workflow GitHub Actions construit automatiquement une APK de test après chaque modification du moteur.

## Orientation du projet
Le contenu, les armes, les ennemis, la progression et l'optimisation seront enrichis progressivement en conservant une architecture adaptée aux parties très longues.
