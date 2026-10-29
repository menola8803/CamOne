# Ändringar

Varje version beskrivs här och under **Releases** på GitHub. Punktlistan för versionen visas också i appen under **Inställningar → Uppdateringar** när en ny version finns.

## Version 1.2.0 – 2026-10-29

- Nytt: uppdateringar direkt i appen, som i Direct-appen. Under Inställningar → Uppdateringar söker appen efter ny version på GitHub, laddar ner den, kontrollerar den och lämnar den till Android för installation ovanpå den gamla.
- Nedladdningen kontrolleras innan den installeras: kontrollsumma (SHA-256) från GitHub, att det är SNC Camera, att versionen är nyare och att den är signerad med samma nyckel.
- Sök automatiskt (kan stängas av): appen söker när den startar, högst var sjätte timme. En guldprick på kugghjulet i kameran visar att en ny version finns.
- Release publiceras bara när appen är signerad med appens egen nyckel, så att varje version kan installeras ovanpå den förra.

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
