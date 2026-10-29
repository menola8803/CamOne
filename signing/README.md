# Signering

Den här mappen innehåller bara offentlig information.

`cert-sha256.txt` är SHA-256-fingeravtrycket för certifikatet som SNC Camera signeras med.
Bygget jämför APK-filen med det och stoppar om de skiljer sig, så att en release aldrig publiceras
signerad med fel nyckel (samma upplägg som i Direct-appen). Annars skulle uppdateringen i appen inte
kunna installeras ovanpå den gamla versionen.

Den privata nyckeln finns **inte** här och läggs aldrig i repot. Den ligger som hemligheten
`SNC_KEYSTORE_BASE64` under *Settings → Secrets and variables → Actions*.
