# Röktest SNC Camera

Enhet: sdk_gphone64_x86_64, Android 16 (API 36)

### Installation
- ✅ APK installeras

### Kamera
- ✅ Appen startar och lever
- ✅ Kameravyn visas
- ✅ Kameran startar utan felmeddelande
- ✅ Foto tas med volymknappen och sparas (0 → 1)

### Omstart tre gånger (fångar startproblem)
- ✅ Omstart 1: foto sparas (1 → 2)
- ✅ Omstart 2: foto sparas (2 → 3)
- ✅ Omstart 3: foto sparas (3 → 4)

### Bildformat, rutnät och självutlösare
- ✅ Självutlösare 3 s tar bild (4 → 5)

### Bildvisare och redigerare
- ✅ Bildvisaren öppnas
- ✅ Redigeraren öppnas med filter
- ✅ Redigerad kopia sparas (5 → 6)
- ✅ Tillbaka i bildvisaren efter sparande

### Video
- ✅ Appen lever efter byte till 60 fps
- ✅ Video spelas in och sparas (0 → 1)

### Natt, Pro och Porträtt
- ✅ Nattbild sparas (6 → 7)
- ✅ Pro-läget visar EV-reglage
- ✅ Porträttbild sparas (7 → 8)
- ✅ Appen lever efter porträtt

### Svep mellan lägen
- ✅ Foto är valt läge
- ✅ Svep åt vänster på lägesraden byter till Porträtt
- ✅ Svep åt höger på lägesraden byter tillbaka till Foto
- ✅ Långt svep på lägesraden hoppar förbi Video (Natt eller Pro)
- ✅ Svep i sökaren byter till Porträtt
- ✅ Svep i sökaren byter tillbaka till Foto
- ✅ Appen lever efter svepen

### Live-filter (RGB)
- ✅ Filterknappen finns i fotoläget
  Sökaren utan filter: R=243 G=245 B=226
- ✅ Filterpanelen öppnas med RGB-reglage
- ✅ Rött-reglaget står på 0 %
  Sökaren med rött 0 %: R=0 G=245 B=226
- ✅ Sökaren visar filtret live (rött försvinner)
- ✅ Foto med filter sparas (8 → 9)
  Sparat foto (IMG_20261106_022757_908.jpg) med rött 0 %: R=0 G=208 B=173
  EXIF i fotot: Google sdk_gphone64_x86_64 2026:11:06 02:27:58
- ✅ Det sparade fotot har samma filter som sökaren (inget rött)
- ✅ Appen lever efter foto med filter
  Sökaren efter Återställ: R=242 G=245 B=225
- ✅ Återställ tar bort filtret
- ✅ Filterknappen göms i videoläget

### Framkamera
- ✅ Selfie sparas (9 → 10)

### Inställningar och Om
- ✅ Inställningar öppnas
- ✅ Om visar Developer: Claes Sundman S.N.C AB

### Text i bild (delas till appen)
- ✅ Textskärmen öppnas
- ✅ Text hittas i bilden
- ✅ Rader markeras
- ✅ Igenkänd text: 'SNC Camera'
- ✅ Igenkänd text: telefonnummer

### Galleri: textknappen
- ✅ Textigenkänning från bildvisaren

### Kameror (vad telefonen ger appen)
- ✅ Kamerasidan visar sammanfattningen
- ✅ Kamerasidan visar bakkameran
- ✅ Appen lever efter kamerasidan

### Uppdatering (som i Direct)
- ✅ Ny version hittas på GitHub
  Resultat av nedladdningen: installerare
- ✅ Nedladdning och kontroll blir klar (installerare)
- ✅ Appen lever efter uppdateringsförsöket

### Språkval (Svenska)
- ✅ Appen byter till svenska
- ✅ Om på svenska visar Developer-raden
- ✅ Appen byter tillbaka till engelska

### Avslut
- ✅ Appen lever i slutet
- ✅ Ingen krasch i loggen
- ✅ Inget ANR

**Resultat: 56 OK, 0 fel**
