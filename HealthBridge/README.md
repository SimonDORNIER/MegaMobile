# Health Bridge 0.3

Application Android locale destinée au suivi santé personnel :

Fitbit → Santé Connect → Health Bridge → Google Drive → analyses ChatGPT

Aucun serveur Health Bridge, aucun abonnement externe.

## Architecture

Health Bridge utilise quatre fichiers Drive, dont un seul est obligatoire :

- `health_live.json` : obligatoire, données détaillées des 72 dernières heures.
- `health_status.json` : état de la dernière synchronisation et diagnostic.
- `health_history.json` : résumé longitudinal quotidien compact qui s'enrichit avec le temps.
- `health_command.json` : canal de commande distante facultatif.

L'ancien `health_live.json` configuré par une version précédente est conservé automatiquement.

## Synchronisations

Exports prévus à :

- 07:45
- 08:20, juste avant le bilan santé de 08:30
- 11:00
- 13:30
- 16:00
- 18:30, juste avant le bilan du soir de 19:00
- 21:00
- 23:30

Android WorkManager peut retarder un travail de fond de quelques minutes si le téléphone est en veille profonde, hors connexion ou soumis à des restrictions de batterie.

Un bouton permet aussi une synchronisation immédiate.

## Commandes distantes

L'application vérifie environ toutes les 15 minutes si ChatGPT a demandé un rafraîchissement.

Deux méthodes sont comprises :

1. fichier `health_command.json` avec un `requestId` unique ;
2. mécanisme de secours sans fichier de commande : renommer temporairement le fichier live sous la forme `health_live_request_<identifiant>.json`.

Le dernier identifiant de commande traité est mémorisé pour empêcher les doublons.

Après une commande distante, le bloc `bridge` de `health_live.json` indique le déclencheur, l'heure de synchronisation et le dernier identifiant de commande.

## Historique

`health_history.json` ne duplique pas toutes les mesures seconde par seconde. Il conserve des résumés quotidiens pour les tendances longues : sommeil, FC au repos, HRV, respiration, SpO2, VO2 max, poids, masse grasse, pas, distance, calories, dénivelé, étages, hydratation et séances.

Pour les métriques cumulatives provenant de plusieurs applications, le fichier garde le détail des sources et privilégie Fitbit lorsqu'il est présent afin de limiter les doubles comptages.

L'export quotidien natif `Santé Connect.zip` reste la sauvegarde de référence pour l'historique antérieur à l'installation de Health Bridge et pour les analyses rétrospectives complètes.

## Données détaillées

Health Bridge lit, lorsque les autorisations sont accordées :

- sommeil et phases ;
- fréquence cardiaque ;
- fréquence cardiaque au repos ;
- HRV RMSSD ;
- fréquence respiratoire ;
- saturation en oxygène ;
- pas et distance ;
- calories actives et totales ;
- exercices ;
- dénivelé et étages ;
- poids et masse grasse ;
- VO2 max ;
- hydratation.

Les métadonnées Santé Connect, notamment `originPackage`, `metadata.id` et `lastModifiedTime`, sont conservées dans le fichier live pour faciliter la déduplication.

## Première configuration recommandée

1. Installer ou mettre à jour l'APK.
2. Autoriser Santé Connect, y compris la lecture en arrière-plan et l'historique lorsqu'elles sont proposées.
3. Vérifier que `health_live.json` est toujours configuré.
4. Créer `health_status.json`.
5. Créer `health_command.json`.
6. Créer `health_history.json`.
7. Appuyer sur « Synchroniser maintenant ».
8. Vérifier dans Drive que les fichiers sont modifiés.

## Confidentialité

Les données sont lues depuis Santé Connect sur le téléphone et écrites uniquement dans les fichiers Drive choisis par l'utilisateur. Health Bridge n'envoie pas les données vers un serveur propriétaire et ne contient ni publicité ni SDK publicitaire.

Les fichiers Drive peuvent ensuite être lus par les intégrations que l'utilisateur a lui-même connectées à ChatGPT.
