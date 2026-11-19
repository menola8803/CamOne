# Ändringar

Varje version beskrivs här och under **Releases** på GitHub. Punktlistan för versionen visas också i appen under **Inställningar → Uppdateringar** när en ny version finns.

## Version 1.16.0 – 2026-11-19

- Nytt läge Panorama (mellan Porträtt och Dubbel): tryck på avtryckaren och svep sakta åt höger eller vänster med telefonen stående; tryck igen för att avsluta, eller svep ett helt varv.
- En guldlinje med en prick i sökaren visar om telefonen lutar; Långsammare om man sveper för fort. Överst syns det som hittills sytts ihop, nederst grader och antal bilder.
- Gyrot väljer en bild ungefär var tredjedel av bildbredden. Bilderna projiceras på en cylinder och finjusteras mot varandra (ljusstyrkan i överlappet, tre upplösningar), med en mjuk skarv mitt i överlappet. Allt görs i telefonen, i ren Kotlin – inga extra bibliotek.
- Exponering och vitbalans låses under svepet. Panoramat sparas som PANO_….jpg med live-filter, vattenstämpel och plats om de är på.

## Version 1.15.0 – 2026-11-19

- Bildvisaren har Alla, Favoriter och Papperskorg överst. Hjärtat gör en bild eller film till favorit (Androids egen favoritmarkering, syns även i andra galleriappar).
- Ta bort flyttar till papperskorgen, med Ångra en stund efteråt. I Papperskorg finns Återställ, Radera (för gott) och Töm; Android tar bort det som legat där i 30 dagar.
- Beskärning i redigeraren (fliken Beskär): dra i hörnen eller flytta ramen, fritt format eller 1:1, 4:3, 3:2 och 16:9 (Vänd för stående/liggande), tillsammans med rotera och spegla.
- Vattenstämpel under Inställningar › Foto (av från början): egen text med en tunn guldlinje och – valfritt – brännvidd, bländare, slutartid, ISO och datum. Förhandsvisning i Inställningar och i sökaren där den hamnar på fotot. Gäller foton från kameran (även natt, HDR, porträtt, serie och dubbelfoto), inte film eller RAW.
- Spara plats under Inställningar › Allmänt (av från början): GPS-position i foton och filmer (även slow motion och dubbelvideo). Behörigheten frågas först när den slås på; appen lyssnar bara medan kameran är öppen. Detaljer i bildvisaren visar platsen med Visa på karta.
- Sidan Kameror och kamerarapporten visar nu vad appen använder: slow motion (val och väg – Camera2 eller CameraX), vilket kamerapar Dubbel använder, kodaren för dubbelvideo och platstjänsterna. Slow motion och fram och bak samtidigt står inte längre som "inte byggt än".

## Version 1.14.0 – 2026-11-19

- Dubbelvideo: läget Dubbel har nu Foto och Video under sökaren. I Video spelas båda kamerorna in samtidigt, i var sin halva som i sökaren, med guldlinjen emellan – 1080 × 1440 i 30 bilder per sekund med ljud från mikrofonen.
- Byt plats under inspelningen (knappen eller håll in och dra): halvorna glider om varandra direkt i filmen. Live-filtret följer med och kan ändras under inspelningen.
- Liggande telefon när inspelningen börjar ger en film med halvorna bredvid varandra, som fotot. Filmerna heter DUAL_….mp4.
- Båda sökarna går genom ett eget OpenGL-steg (en CameraX-effekt per kamera, samma kamerauppsättning som dubbelfotot) som också ritar filmen till en H.264-kodare; ljudet kodas som AAC. Går det inte på telefonen visas ett meddelande och fotot fungerar som vanligt.
- Filmen sparas klart även om appen går till bakgrunden mitt i en inspelning.

## Version 1.13.0 – 2026-11-19

- Nytt läge Skanna (längst till höger i lägesmenyn) med QR-kod och Dokument.
- QR- och streckkoder läses direkt i sökaren (ML Kit med inbäddad modell – utan Google Play-tjänster). En guldram visar koden och ett kort visar innehållet med knappar efter typ: öppna länk, anslut till Wi-Fi, lägg till kontakt, ring, mejla, skicka sms, visa på karta, sök produktkod – och alltid kopiera och dela. Ficklampa överst.
- Koder i bilder som delas till appen visas också på textskärmen.
- Dokumentskanner: en guldram visar papperet i sökaren. Efter bilden kan hörnen dras till sidans kanter; sidan rätas (perspektiv), belysningen jämnas ut och den blir Svartvit, Färg eller Original. Flera sidor blir en PDF (A4) i Dokument/SNC Camera, som kan öppnas eller delas direkt.
- Snabbinställningsrutan Skanna QR öppnar kameran i läget Skanna.
- Appen startar aldrig i läget Skanna (modellen laddas först när läget öppnas).

## Version 1.12.0 – 2026-11-19

- Nytt läge Dubbel (längst till höger i lägesmenyn): bakkameran och frontkameran samtidigt, i var sin halva av sökaren med en tunn guldlinje emellan.
- Byt plats: håll in en halva och dra den förbi mitten, eller tryck på knappen till höger om avtryckaren.
- Fotot sätts ihop av båda kamerornas bilder (CameraX samtidig kamera, en ImageCapture per kamera) i samma ordning som i sökaren; liggande telefon ger bilderna bredvid varandra. Live-filter och självutlösare fungerar. Bilderna heter DUAL_….jpg.
- Klarar telefonen inte båda kamerorna samtidigt står det i sökaren. Går kamerorna bara att köra med sökarna tas bilden från sökarna.
- Appen startar aldrig i läget Dubbel (två kameror tar längre tid att starta).

## Version 1.11.0 – 2026-11-19

- Videoläget har tre val i glas ovanför zoomen: Video, Slow motion och Tidsförlopp.
- Slow motion: 120, 240 eller 480 fps (det kameran klarar visas). Filmen sparas i 30 fps och spelas upp 4, 8 eller 16 gånger långsammare, utan ljud. Appen använder CameraX höghastighetsvideo när telefonen har profilerna för det, annars öppnar den kameran själv (Camera2) i en höghastighetssession med egen videokodare. Klarar kameran det inte står det tydligt.
- Tidsförlopp: 0,5, 1, 2 eller 5 s mellan bilderna – filmen spelas upp 15–150 gånger snabbare. En OpenGL-effekt på videon släpper igenom en bild per intervall; sökaren är som vanligt och live-filtret följer med.
- Klockan visar inspelad tid och filmens längd i slow motion och tidsförlopp. Paus finns bara i vanlig video; bildfrekvensknappen visas bara i vanlig video.

## Version 1.10.0 – 2026-11-18

- Pro-hjälpmedel: tryck på Hjälp i Pro-panelen och slå på Histogram, Fokusmarkering och Zebra var för sig. Histogrammet visar ljusstyrkan uppe till höger, med röd kant när en del av bilden är helt svart eller utfrätt. Fokusmarkeringen gör skarpa kanter röda (bra med manuell fokus) och zebran visar sneda ränder där bilden är nästan utfrätt. Den sparade bilden påverkas inte.
- Hjälpmedlen räknas på en liten extra bild från kameran (640 × 480) och kopplas bara in när något av dem är på. Får den inte plats med filformatet (t.ex. RAW + JPEG) körs kameran utan dem, med ett meddelande.
- Serietagning: håll in avtryckaren i Foto och Pro så tas bilder i följd tills du släpper (högst 50). Antalet syns i knappen och bilderna heter …_BURST001 osv. Serien tas utan blixt; beskärning, filter och filformat görs i bakgrunden. Fungerar inte med RAW eller när appen lägger ihop HDR.

## Version 1.9.0 – 2026-11-18

- Live-filtret i video: filtren och reglagen för rött, grönt och blått syns i sökaren och spelas in i filmen (OpenGL). Färgerna kan ändras medan filmen spelas in. Går det inte på telefonen spelas videon in utan filter, med ett meddelande.
- IR-filter: IR svartvit (mörk himmel, ljus grönska) och IR färg (falsk färg som infraröd diafilm, grönska blir röd) – i live-filtret och i redigeraren.
- Knapparna överst är lite mindre i Video, så att filterknappen får plats bredvid kvalitet och bildfrekvens.

## Version 1.8.1 – 2026-11-18

- 16:9 och Video: sökaren går ut till skärmens kanter och upp till överkanten, utan rundade hörn. Knapparna överst och avtryckarraden ligger i glas ovanpå bilden, som i Helskärm. Det sparade fotot är oförändrat 16:9.
- Etiketterna överst i sökaren (natt, HDR, meddelanden) hamnar under knapparna i Helskärm och 16:9.

## Version 1.8.0 – 2026-11-18

- HDR i appen: HDR-knappen i Foto tar 3 exponeringar (vald, −2 och +2 EV) i handen och 5 på stativ (0, ±1,5, ±3 EV) och lägger ihop dem, så att både ljusa fönster och mörka hörn syns. Ljusskillnaden mäts i bilderna själva.
- Bilderna flyttas på plats (samma flytt som nattläget, efter att ljuset jämnats ut) och det som rör sig tas från den första bilden – inga spökbilder. Mjuka vikter efter hur väl exponerat varje område är, utfrätta pixlar räknas inte.
- Ultra HDR: HDR-bilden sparas med en gain map, så att HDR-skärmar visar ljusa partier upp till 4× ljusare. Kan stängas av under Inställningar › Foto.
- Etiketten överst visar förloppet ("HDR · 2/3", sedan "Lägger ihop bilderna…"). Blixten används inte med HDR.

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
