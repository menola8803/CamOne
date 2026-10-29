# SNC Camera

Kameraapp för **OnePlus 13R med LineageOS 23.2** (Android 16), med fram- och bakkamera,
menyer i samma stil som OnePlus originalkamera, bildredigering med filter och textigenkänning
där man trycker på texten för att markera, kopiera eller dela den.

**Developer: Claes Sundman S.N.C AB**

---

## Funktioner

**Kamera**
- Fram- och bakkamera (snurra-knappen till höger om avtryckaren)
- Lägen i en svepbar karusell som i OnePlus: **Pro · Natt · Video · Foto · Porträtt**
  (svep i sökaren eller tryck på läget)
- Övre fältet: blixt (av/auto/på, skärmblixt på frontkameran), HDR, självutlösare 3/5/10 s,
  bildformat 4:3 / 1:1 / 16:9, rutnät och inställningar
- Zoomknappar (.6 / 1× / 2× / 5× beroende på vad kameran exponerar) och nyp-zoom
- Tryck för fokus, dra sedan upp/ned för exponering (solikonen)
- Vattenpass, rutnät (tredjedelar, gyllene snittet, kvadrat)
- Ikonerna roterar med telefonen; bilder sparas rättvända även i liggande läge
- Video 720p / 1080p / 4K med paus, ficklampa och stabilisering
- Pro-läge: EV, ISO, slutartid, vitbalans och manuell fokus
- Volymknapparna kan ta bild eller zooma
- Appen kan väljas som standardkamera (dubbeltryck på strömknappen)

**Bildvisare** (tryck på miniatyren till vänster om avtryckaren)
- Bläddra mellan bilder och videor, nyp- och dubbeltrycks-zoom
- **Redigera**, **Text**, **Dela**, **Detaljer**, **Radera**

**Redigerare**
- 12 filter med styrkereglage (Livfull, Varm, Kall, Gyllene, Film, Blek, Retro, Dramatisk, Svartvit, Noir, Sepia …)
- Justera ljusstyrka, kontrast, mättnad, värme och vinjett
- Rotera och spegla, håll in jämför-knappen för att se originalet
- Sparas alltid som en **ny kopia** – originalet ändras aldrig

**Text i bilden**
- Texten hittas automatiskt och ramas in
- **Tryck på en rad** för att markera den, håll in och dra för att markera flera
- **Kopiera**, **Dela** till valfri app (Signal, mejl, anteckningar …), eller öppna länk / ring / mejla direkt
- "Visa text" öppnar texten där man kan markera exakt den del man vill ha
- Fungerar helt offline och **utan Google Play-tjänster**

**Inställningar och Om**
- Bildformat, kvalitet, JPEG-kvalitet, spegling av frontkamera, videoupplösning,
  stabilisering, ljud, rutnät, vattenpass, slutarljud, vibration, volymknappar, tema, dynamiska färger
- **Om**-sidan visar *Developer: Claes Sundman S.N.C AB*, version, enhet och licenser
- Svenska och engelska (följer telefonens språk)

---

## Bygga APK:n

### Alternativ 1 – GitHub (inget behöver installeras)
1. Skapa ett nytt repo på github.com och ladda upp hela mappen `SNCCamera`.
2. Gå till fliken **Actions** – bygget startar automatiskt (eller tryck *Run workflow*).
3. När det är klart (≈ 5–8 min): öppna körningen och ladda ner **SNC-Camera-apk** under *Artifacts*.
4. Packa upp zip-filen – där ligger `app-release.apk`.

### Alternativ 2 – Android Studio
1. Installera [Android Studio](https://developer.android.com/studio) (Narwhal eller senare).
2. *File → Open* → välj mappen `SNCCamera`. Vänta tills Gradle-synken är klar.
3. *Build → Generate App Bundles or APKs → Build APK(s)*.

### Alternativ 3 – kommandorad
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## Installera på telefonen
- Kopiera APK:n till telefonen och öppna den (tillåt "Installera okända appar" för filhanteraren), eller
- `adb install app/build/outputs/apk/release/app-release.apk`

Release-APK:n signeras med en debug-nyckel om ingen egen nyckel anges, så den går att installera direkt.
Egen nyckel: sätt miljövariablerna `SNC_KEYSTORE`, `SNC_KEYSTORE_PASSWORD`, `SNC_KEY_ALIAS`, `SNC_KEY_PASSWORD`.

---

## Bra att veta om LineageOS
- OnePlus egen bildbehandling (Hasselblad-färger, OnePlus nattläge/porträtt) är stängd programvara
  som inte följer med LineageOS. Appen använder därför:
  - **Natt**: tillverkarens CameraX-tillägg om det finns, annars längre exponering + högkvalitativ brusreducering.
  - **Porträtt**: tillverkarens bokeh-tillägg om det finns, annars mjukvaruporträtt där personen
    hittas och bakgrunden görs oskarp efter bilden tagits.
  - **HDR**-knappen visas bara om kameran erbjuder HDR-tillägg.
- Vilka zoomsteg som syns (.6× vidvinkel, 2× tele) beror på hur LineageOS-portningen exponerar
  kamerorna. Om bara huvudkameran syns används digital zoom.
- Textigenkänning och porträtt använder Google ML Kit med inbäddade modeller (ingen internet, inga GApps).

## Projektstruktur
```
app/src/main/java/se/snc/camera/
├─ MainActivity.kt           navigering, volymknappar, intents
├─ camera/                   CameraX, lägen, kontroller, Pro-panel, porträttprocessor
├─ gallery/ViewerScreen.kt   bildvisare
├─ editor/                   filter, justeringar, redigerare
├─ text/                     textigenkänning med tryck-för-att-markera
├─ settings/                 inställningar och Om
├─ data/                     inställningar (DataStore) och mediabibliotek (MediaStore)
└─ ui/, util/                tema, zoombar bild, orientering m.m.
```

Bilder och videor sparas i `DCIM/SNC Camera`.

© 2026 Claes Sundman S.N.C AB
