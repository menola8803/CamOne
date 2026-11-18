# Ändringar

Varje version beskrivs här och under **Releases** på GitHub. Punktlistan för versionen visas också i appen under **Inställningar → Uppdateringar** när en ny version finns.

## Version 1.7.1 – 2026-11-18

- Helskärm med glas: bilden syns ända ned till kanten. Avtryckaren, miniatyren och byt kamera ligger i en genomskinlig glaspanel, knapparna överst har en mörk glasbotten under guldtonen så att de syns mot både ljusa och mörka motiv, och lägesmenyn är mer genomskinlig. Övriga bildformat ser ut som förut.

## Version 1.7.0 – 2026-11-17

- Nytt nattläge: flera bilder tas efter varandra och läggs ihop till en ljusare bild med mindre brus – 8 bilder i handen (högst 1/15 s var, så att de blir skarpa) och 16 när telefonen står stilla på stativ eller bord (längre exponering). Gyrot avgör vilket, och etiketten överst visar det.
- Bilderna flyttas på plats innan de läggs ihop, även lite vridning följer med. Det som rör sig tas bara från en bild, så inga spökbilder uppstår. Den skarpaste av de första bilderna väljs som grund.
- Ljusspår och Silkesvatten i nattläget: 1, 2, 4 eller 8 sekunders exponering. Silkesvatten gör rinnande vatten mjukt, Ljusspår ritar ut lampor som rör sig.
- Bildformat, live-filter och filformat gäller även nattbilderna. Bilden får datum, kamera, exponering och antal bilder i EXIF.
- Manualen har ett nytt avsnitt om natt, ljusspår och silkesvatten.
- Om-sidan visar inte längre paketnamnet.

## Version 1.6.2 – 2026-11-17

- Remsan i 16:9 är borta även i sökaren och i video. Den kom från telefonens kamera 5 (vidvinkel + ultravid + tele), som skär bort kanten två gånger så fort den ska leverera 16:9.
- Foto, Pro, Natt och Porträtt ber aldrig kameran om 16:9: sökaren och fotot tas i 4:3 och beskärs till 16:9 eller Helskärm. Telelinsen finns kvar. Sökaren är något mindre skarp i 16:9 och Helskärm; fotona påverkas inte.
- Video använder telefonens standardkamera 0, samma som LineageOS kameraapp, som filmar utan remsa. .6 och 1× är riktiga linser; 2 och 5 är digital zoom i video.

## Version 1.6.1 – 2026-11-17

- 16:9 rättat: på OnePlus 13R gav kamerans eget 16:9-läge en randig remsa längs ena kanten av sökaren (till vänster med 1×, till höger med .6). Nu tas fotot i 4:3 och beskärs till 16:9 direkt efteråt, precis som 3:2 och Helskärm. Sökaren är lika skarp som förut och fotot lika stort.
- Testet i emulatorn kontrollerar nu att ett 16:9-foto verkligen blir 16:9.

## Version 1.6.0 – 2026-11-17

- Nya bildformat: 3:2 och Helskärm. Helskärm fyller hela skärmen och bilden får skärmens form. Båda beskärs direkt efter fotograferingen, precis som sökaren visar.
- Filformat under Inställningar › Foto: JPEG, HEIF (ungefär halva storleken), PNG (förlustfritt), RAW (DNG) och RAW + JPEG. RAW gäller i Foto och Pro.
- Live-filtret har fått en liten Återställ-knapp i menyns hörn som alltid syns, och ett tryck på procentsiffran sätter just den färgen till 100 %.
- Kameror-sidan visar nu också om telefonen klarar 1440p-video, Ultra HDR, HDR-video och om det finns dolda högupplösta inställningar (t.ex. för 50 MP).
- Nyare kamerabibliotek (CameraX 1.5.1) som grund för RAW och kommande slow motion.

## Version 1.5.1 – 2026-11-17

- Uppdateringar ser ut som i Alif: kortet "Version 1.5.1" med knappen Sök efter uppdatering direkt under, och sist valet Sök efter ny version automatiskt med en förklaring av hur det går till. Raden Källa är borta.
- Knapparna överst i kameran har Alifs utseende: guldton, guldkant och guldikoner. Påslagna knappar får starkare guld.
- Knappen Ladda ner och installera syns direkt när en ny version finns; vad som är nytt visas i ett eget kort under knappen.

## Version 1.5.0 – 2026-11-17

- Samma tema som Alif: typsnitten Plus Jakarta Sans och Cinzel för ordmärket, knappar med guldton, tunn guldkant och guldtext, val i en glasram med ett guldstreck under det valda, kort med tunn kant och rader med ›.
- Kameran har fått nytt utseende: runda glasknappar, rundad sökare, lägen i en svävande glasmeny, avtryckare med guldring, rundade knappar för senaste bilden och byt kamera, och Pro-värdena i ett glaskort.
- Temaval under Inställningar: Mörk eller Beige. Kameran, bildvisaren och redigeraren är alltid mörka.
- Uppdatering som i Alif: kortet "Ny version finns" överst i Inställningar och en lysande ring runt kugghjulet. Tryck Uppdatera – appen laddar ner, kontrollerar och öppnar Androids installationsruta, och startar sedan om av sig själv. Stoppar Android omstarten kommer en avisering att trycka på. Efteråt visar kameran "SNC Camera är uppdaterad till …".
- Ny manual under Inställningar › Manual, på svenska och engelska. Den följer med och uppdateras i varje version.
- Snabbare start: kameran startar först, och uppdateringskollen och manualen läses in efteråt. Testet mäter starttiden för varje version.
- Telefonens APK kontrolleras vid varje bygge så att den bara innehåller arm64-kod, ingen x86.

## Version 1.4.0 – 2026-11-06

- Nytt: live-filter i kameran. Tryck på filterknappen i övre fältet och välj ett av filtren (Livfull, Varm, Kall, Svartvit, Noir, Sepia …) eller ändra rött, grönt och blått var för sig från 0 till 200 %. Effekten syns direkt i sökaren.
- Bilden sparas med samma färger som du såg i sökaren, i full upplösning. Fungerar i Foto, Pro, Natt och Porträtt.
- En liten etikett i sökaren visar att ett filter är på; tryck på den för att ändra. Återställ tar bort filtret.
- Video spelas in utan filter i den här versionen, därför göms filterknappen i videoläget.
- Datum, kamera och exponering (EXIF) följer nu med när appen skriver om en bild, även i porträttläget.

## Version 1.3.1 – 2026-10-30

- Svep i sidled mellan lägena Pro, Natt, Video, Foto och Porträtt, som i OnePlus-kameran. Raden följer fingret och landar på läget i mitten när du släpper; ett kort svep byter ett läge, ett långt eller snabbt svep hoppar flera. Det går fortfarande att trycka på ett läge.
- Svep i sökaren byter också läge och kräver nu ett kortare svep än förut.

## Version 1.3.0 – 2026-10-30

- Riktiga linser: appen använder nu den bakkamera som har flest linser (på OnePlus 13R kamera 5 med vidvinkel, ultravidvinkel och tele). 0,6× är ultravidvinkeln och 2× den riktiga telelinsen i stället för digital zoom.
- En liten etikett visar vilken lins som tar över när du zoomar, t.ex. "Tele · 47 mm".
- 60 fps-video: välj 30 eller 60 fps med knappen i videoläget eller under Inställningar → Video. Fungerar i 4K och 1080p på huvudkameran och telelinsen.
- Lång exponering i Pro-läget: slutartid upp till 30 sekunder (tidigare 1 sekund).
- Kameror-sidan visar vilken bakkamera appen använder och att 60 fps nu stöds.

## Version 1.2.0 – 2026-10-29

- Nytt: uppdateringar direkt i appen, som i Direct-appen. Under Inställningar → Uppdateringar söker appen efter ny version på GitHub, laddar ner den, kontrollerar den och lämnar den till Android för installation ovanpå den gamla.
- Nedladdningen kontrolleras innan den installeras: kontrollsumma (SHA-256) från GitHub, att det är SNC Camera, att versionen är nyare och att den är signerad med samma nyckel.
- Sök automatiskt (kan stängas av): appen söker när den startar, högst var sjätte timme. En guldprick på kugghjulet i kameran visar att en ny version finns.
- Release publiceras bara när appen är signerad med appens egen nyckel, så att varje version kan installeras ovanpå den förra.
- Nytt: Inställningar → Kameror visar vad telefonen ger appen tillgång till – varje lins med brännvidd, megapixlar, högupplöst läge, bildfrekvens, slow motion, stabilisering och Pro-värden – och vilka funktioner som går att bygga. Rapporten kan kopieras eller delas.

## Version 1.1.0 – 2026-10-29

- Samma stil som USB-appen: mörk petrolgrön botten, guld som huvudfärg och teal för rubriker – inga röda menyer längre.
- Typsnitten Space Grotesk, IBM Plex Sans och Cormorant Garamond. Ordmärket SNC i guld med glans och ett runt emblem.
- Inställningar och Om som rundade kort med guldval och reglage. Språkval Svenska/English i appen.
- Ny appikon i emblemstil.
- Rättat: miniatyren bredvid avtryckaren visade ibland en ikon i stället för den senaste bilden.

## Version 1.0.0 – 2026-10-29

Första versionen av SNC Camera för OnePlus 13R med LineageOS 23.2.

- Fram- och bakkamera med lägena Pro, Natt, Video, Foto och Porträtt.
- Blixt, HDR, självutlösare, bildformat, rutnät, vattenpass, zoom och fokus med exponering.
- Bildvisare med redigering (filter och justeringar) och textigenkänning där man trycker på texten för att markera, kopiera eller dela den.
- Inställningar och Om med Developer: Claes Sundman S.N.C AB.
