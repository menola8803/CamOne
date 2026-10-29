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

**Utseende – samma stil som USB-appen**
- Mörk petrolgrön botten, guld som huvudfärg och teal för rubriker
- Typsnitten Space Grotesk, IBM Plex Sans och Cormorant Garamond (ordmärket "SNC" i guld)
- Rundade kort, guldval och reglage, emblem med guldring – även som appikon

**Inställningar och Om**
- Bildformat, kvalitet, JPEG-kvalitet, spegling av frontkamera, videoupplösning,
  stabilisering, ljud, rutnät, vattenpass, slutarljud, vibration, volymknappar
- Språkval Svenska/English direkt i appen (Android 13+), som i USB-appen
- **Om**-sidan visar *Developer: Claes Sundman S.N.C AB*, version, enhet och licenser
- Svenska och engelska (följer telefonens språk)

---

## Ladda ner och installera

**Enklast:** öppna [Releases](https://github.com/menola8803/CamOne/releases/latest) på telefonen,
ladda ner `SNC-Camera-x.y.z.apk` och öppna filen. Tillåt "Installera okända appar" för
webbläsaren eller filhanteraren om telefonen frågar.

Varje gång kod skickas till `main` gör GitHub Actions följande automatiskt:
1. bygger APK:n,
2. installerar den i en Android 16-emulator och klickar igenom kamera, foto, video, alla lägen,
   redigerare, textigenkänning, inställningar och Om (skärmbilder sparas under *Actions → körningen →
   Artifacts → skarmbilder-och-testrapport*),
3. publicerar APK:n som en ny release om testet gick igenom.

### Samma signeringsnyckel för alla versioner
För att en ny version ska kunna installeras **ovanpå** den gamla måste alla byggen signeras med
samma nyckel. Lägg in nyckeln som en hemlighet en gång:

1. GitHub → repot → **Settings → Secrets and variables → Actions → New repository secret**
2. Name: `SNC_KEYSTORE_BASE64`
3. Secret: hela innehållet i filen `SNC_KEYSTORE_BASE64.txt`
4. Spara. Nästa bygge använder den fasta nyckeln.

Utan hemligheten får varje bygge en tillfällig nyckel – då måste den gamla versionen avinstalleras
före uppdatering (bilderna i `DCIM/SNC Camera` ligger kvar). Spara nyckelfilen på ett säkert ställe
och lägg den aldrig i repot.

### Bygga själv
**Android Studio:** *File → Open* → välj projektmappen, vänta på Gradle-synken,
*Build → Generate App Bundles or APKs → Build APK(s)*.

**Kommandorad:**
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/release/app-release.apk
```

Egen nyckel lokalt: sätt miljövariablerna `SNC_KEYSTORE`, `SNC_KEYSTORE_PASSWORD`, `SNC_KEY_ALIAS`, `SNC_KEY_PASSWORD`.

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
