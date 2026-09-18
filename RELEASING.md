# Publier une version MegaMobile

La publication est assurée par le workflow GitHub Actions `Publish MegaMobile release`.
Il compile, teste, signe et vérifie l'APK, génère les fragments Base64, met à jour
`live/app.json`, puis pousse le canal de mise à jour sur `main`.

## Secrets GitHub requis

- `MEGAMOBILE_SIGNING_KEYSTORE_BASE64` : keystore de publication encodé en Base64 ;
- `MEGAMOBILE_SIGNING_STORE_PASSWORD` : mot de passe du keystore ;
- `MEGAMOBILE_SIGNING_KEY_ALIAS` : alias de la clé ;
- `MEGAMOBILE_SIGNING_KEY_PASSWORD` : mot de passe de la clé.

Le keystore doit correspondre à l'empreinte SHA-256 conservée dans
`release/signing-certificate.sha256`. Le workflow bloque la publication si la
signature diffère, afin de ne jamais produire une mise à jour qu'Android refuserait.

## Procédure

1. augmenter `versionCode` et `versionName` dans `app/build.gradle` ;
2. faire valider le build normal sur `main` ;
3. lancer manuellement `Publish MegaMobile release` avec les notes de version ;
4. attendre la réussite du workflow `Verify MegaMobile live update`.

Ne jamais ajouter le keystore ou ses mots de passe au dépôt.
