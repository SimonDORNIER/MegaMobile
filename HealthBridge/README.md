# Health Bridge

Application Android gratuite et locale pour synchroniser régulièrement les données de **Santé Connect** vers un dossier choisi avec le sélecteur de fichiers Android. Google Drive fonctionne via son fournisseur de documents Android.

## Objectif

Fitbit → Santé Connect → Health Bridge → Google Drive → analyse ChatGPT

L'application ne possède aucun serveur et n'utilise aucun abonnement externe.

## Fonctionnement

- autorisations Santé Connect utiles au suivi santé ;
- lecture en arrière-plan si Santé Connect la permet ;
- choix unique d'un dossier Google Drive ;
- synchronisation WorkManager environ toutes les 30 minutes ;
- lecture d'une fenêtre glissante de 72 heures ;
- écriture de health_live.json et health_status.json ;
- conservation de originPackage, metadata.id et lastModifiedTime pour la déduplication ;
- bouton de synchronisation immédiate.

## Données exportées

Sommeil et phases, fréquence cardiaque, FC au repos, HRV RMSSD, fréquence respiratoire, SpO₂, pas, distance, calories actives et totales, exercices, dénivelé, étages, poids, masse grasse, VO₂ max et hydratation.

## Première configuration

1. Installer l'APK.
2. Ouvrir Health Bridge.
3. Appuyer sur « Autoriser Santé Connect ».
4. Accorder les catégories souhaitées et la lecture en arrière-plan.
5. Choisir le dossier Google Drive qui sera utilisé pour le suivi.
6. Appuyer une fois sur « Synchroniser maintenant ».

## Confidentialité

Les données sont lues depuis Santé Connect sur l'appareil puis écrites vers le dossier choisi. Aucun serveur applicatif n'est utilisé.
