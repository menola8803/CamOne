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
  (svep i sidled på lägesraden eller i sökaren, eller tryck på läget – raden följer fingret)
- Övre fältet: blixt (av/auto/på, skärmblixt på frontkameran), HDR, självutlösare 3/5/10 s,
  bildformat 4:3 / 1:1 / 16:9, rutnät och inställningar
- **Riktiga linser**: appen väljer den bakkamera som har flest linser (på OnePlus 13R kamera 5).
  0,6× = ultravidvinkel 16 mm, 1× = huvudkamera 24 mm, 2× = tele 47 mm – en etikett visar vilken lins som tar över
- Zoomknappar (.6 / 1× / 2× / 5×) och nyp-zoom upp till 10×
- Tryck för fokus, dra sedan upp/ned för exponering (solikonen)
- Vattenpass, rutnät (tredjedelar, gyllene snittet, kvadrat)
- Ikonerna roterar med telefonen; bilder sparas rättvända även i liggande läge
- Video 720p / 1080p / 4K i **30 eller 60 fps**, med paus, ficklampa och stabilisering
- Pro-läge: EV, ISO, slutartid **upp till 30 s** (lång exponering), vitbalans och manuell fokus
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
- **Kameror** visar vad telefonen ger appen tillgång till: varje lins med brännvidd, megapixlar,
  bildfrekvens, slow motion, RAW och Pro-värden. Rapporten kan kopieras eller delas
- **Uppdateringar** direkt i appen (se nedan)
- **Om**-sidan visar *Developer: Claes Sundman S.N.C AB*, version, enhet och licenser
- Svenska och engelska (följer telefonens språk)

---

## Ladda ner och installera

**Från version 1.2.0** uppdaterar appen sig själv under *Inställningar → Uppdateringar*.

**Första installationen:** öppna [Releases](https://github.com/menola8803/CamOne/releases/latest) på telefonen,
ladda ner `SNC-Camera-x.y.z.apk` och öppna filen. Tillåt "Installera okända appar" för
webbläsaren eller filhanteraren om telefonen frågar.

Varje gång kod skickas till `main` gör GitHub Actions följande automatiskt:
1. bygger APK:n,
2. installerar den i en Android 16-emulator och klickar igenom kamera, foto, video, alla lägen,
   redigerare, textigenkänning, inställningar och Om (skärmbilder sparas under *Actions → körningen →
   Artifacts → skarmbilder-och-testrapport*),
3. publicerar APK:n som en ny release om testet gick igenom och appen är signerad med den fasta nyckeln.

### Uppdatera direkt i appen (som i Direct-appen)
**Inställningar → Uppdateringar** visar installerad version och senaste release här på GitHub.
Finns en ny version visas den med storlek, ändringar och knappen **Ladda ner och installera**.
Appen kontrollerar filen innan den lämnas till Android: SHA-256 mot GitHub, att det är SNC Camera,
att versionen är nyare och att den är signerad med samma nyckel. Med **Sök automatiskt** söker appen
när den startar (högst var sjätte timme) och visar en guldprick på kugghjulet i kameran.
Ändringarna för varje version skrivs i [CHANGELOG.md](CHANGELOG.md).

Ny release: höj `sncAppVersionName` och `sncAppVersionCode` i `gradle.properties`, skriv ändringarna
under en ny rubrik i `CHANGELOG.md` och skicka till `main`.

### Samma signeringsnyckel för alla versioner
För att en ny version ska kunna installeras **ovanpå** den gamla måste alla byggen signeras med
samma nyckel. Lägg in nyckeln som en hemlighet en gång:

1. GitHub → repot → **Settings → Secrets and variables → Actions → New repository secret**
2. Name: `SNC_KEYSTORE_BASE64`
3. Secret: hela innehållet i filen `SNC_KEYSTORE_BASE64.txt`
4. Spara. Nästa bygge använder den fasta nyckeln.

Utan hemligheten byggs och testas appen med en tillfällig nyckel, men **ingen release publiceras**
(som i Direct-appen) – annars skulle uppdateringen i appen inte kunna installeras ovanpå. Bygget kontrollerar
också att fingeravtrycket stämmer med `signing/cert-sha256.txt`. Spara nyckelfilen på ett säkert ställe
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
- Så exponerar LineageOS 23.2 kamerorna på OnePlus 13R (CPH2645), enligt appens kamerarapport:

  | ID | Typ | Lins |
  |---|---|---|
  | 0 | logisk bak [2, 3] | vidvinkel + ultravid, zoom 0,6–10× |
  | **5** | **logisk bak [2, 3, 4]** | **vidvinkel + ultravid + tele, zoom 0,6–10× – används av appen** |
  | 2 | fysisk | huvudkamera 24 mm f/1.88, 12,6 MP, 4K/1080p 60 fps, slow motion 1080p 480 fps |
  | 3 | fysisk | ultravid 16 mm f/2.2, 107°, 8 MP |
  | 4 | fysisk | tele 47 mm f/2.0, 12,6 MP, slow motion 240 fps |
  | 1 | fram | 24 mm f/2.4, 15,9 MP, 1080p 60 fps |

  Alla kameror har manuell sensor och RAW (LEVEL_3). Fram + bak samtidigt stöds ([0, 1] och [1, 5]).
  Inga CameraX-tillägg och inget 50 MP-läge exponeras; OIS styrs av hårdvaran och syns inte för appen.
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
├─ update/                   uppdateringar från GitHub (som i Direct)
├─ info/                     Kameror-sidan och kamerarapporten
├─ data/                     inställningar (DataStore) och mediabibliotek (MediaStore)
└─ ui/, util/                tema, zoombar bild, orientering m.m.
```

Bilder och videor sparas i `DCIM/SNC Camera`.

© 2026 Claes Sundman S.N.C AB
