#!/usr/bin/env bash
# Röktest i emulatorn: installerar APK:n, klickar igenom appens funktioner och tar skärmbilder.
# Användning: smoke-test.sh <apk> <utmapp>
set -u

APK="$1"
OUT="${2:-ci-out}"
PKG=se.snc.camera
DIR="/sdcard/DCIM/SNC Camera"
HERE="$(cd "$(dirname "$0")" && pwd)"
UI="python3 $HERE/ui.py"

mkdir -p "$OUT"
REPORT="$OUT/report.md"
: > "$REPORT"
: > "$OUT/ui.log"
PASS=0
FAIL=0

log()  { echo "$*" | tee -a "$REPORT"; }
ok()   { PASS=$((PASS + 1)); log "- ✅ $*"; }
bad()  { FAIL=$((FAIL + 1)); log "- ❌ $*"; echo "::error title=Röktest::$*"; }
check() { local d="$1"; shift; if "$@" >> "$OUT/ui.log" 2>&1; then ok "$d"; else bad "$d"; fi; }
tap()  { $UI tap "$@" >> "$OUT/ui.log" 2>&1 || bad "Hittade inte $* på skärmen"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
key()  { adb shell input keyevent "$1"; }
alive() { adb shell pidof "$PKG" > /dev/null; }
count() { adb shell "ls '$DIR' 2>/dev/null" | grep -c "$1" || true; }
step() { log ""; log "### $*"; echo "[steg] $*" >> "$OUT/ui.log"; }

log "# Röktest SNC Camera"
log ""
log "Enhet: $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

key KEYCODE_WAKEUP
key 82
adb shell settings put system screen_off_timeout 1800000

step "Installation"
check "APK installeras" adb install -r -g "$APK"
adb logcat -c

# Starttid i ms (am start -W: tills första bilden är ritad)
start_ms() { adb shell am start -W -n "$PKG/.MainActivity" 2>&1 | tee -a "$OUT/ui.log" | grep -oE 'TotalTime: [0-9]+' | grep -oE '[0-9]+' | head -1; }

step "Kamera"
FIRST_MS=$(start_ms)
log "  Första starten efter installationen: ${FIRST_MS:-?} ms"
sleep 8
shot 01-kamera
check "Appen startar och lever" alive
check "Kameravyn visas" $UI exists --desc "Switch camera"
check "Kameran startar utan felmeddelande" bash -c "! $UI exists --text 'Could not start the camera' --timeout 1"

before=$(count '\.jpg')
key KEYCODE_VOLUME_UP
sleep 6
shot 02-efter-foto
after=$(count '\.jpg')
check "Foto tas med volymknappen och sparas ($before → $after)" [ "$after" -gt "$before" ]

step "Omstart tre gånger (fångar startproblem)"
COLD=()
for n in 1 2 3; do
  adb shell am force-stop "$PKG"
  sleep 1
  T=$(start_ms)
  COLD+=("${T:-99999}")
  log "  Kallstart $n: ${T:-?} ms"
  # Vänta tills kameran är uppe (emulatorn kan vara långsam), ta sedan bild och vänta på filen
  $UI exists --desc "Switch camera" --timeout 15 >> "$OUT/ui.log" 2>&1
  sleep 4
  check "Omstart $n: kameran startar utan felmeddelande" bash -c "! $UI exists --text 'Could not start the camera' --timeout 1"
  before=$(count '\.jpg')
  key KEYCODE_VOLUME_UP
  after=$before
  for w in $(seq 1 15); do
    sleep 1
    after=$(count '\.jpg')
    [ "$after" -gt "$before" ] && break
  done
  check "Omstart $n: foto sparas ($before → $after)" [ "$after" -gt "$before" ]
done
shot 02b-efter-omstarter

step "Starttid (kameran först – allt annat laddas efteråt)"
# Gränser för emulatorn i GitHub Actions (långsammare än telefonen). Blir starten långsammare stoppas bygget.
FIRST_LIMIT=8000
COLD_LIMIT=3500
MEDIAN=$(printf '%s\n' "${COLD[@]}" | sort -n | sed -n '2p')
log "  Kallstart median: ${MEDIAN:-?} ms (gräns $COLD_LIMIT ms), första starten: ${FIRST_MS:-?} ms (gräns $FIRST_LIMIT ms)"
echo "::notice title=Starttid::Kallstart median ${MEDIAN:-?} ms · första starten ${FIRST_MS:-?} ms (emulator)"
check "Första starten går fort (${FIRST_MS:-?} ms ≤ $FIRST_LIMIT ms)" [ "${FIRST_MS:-99999}" -le "$FIRST_LIMIT" ]
check "Kallstart går fort (median ${MEDIAN:-?} ms ≤ $COLD_LIMIT ms)" [ "${MEDIAN:-99999}" -le "$COLD_LIMIT" ]

# Tar en bild, väntar (högst $3 s, annars 20) på den nya filen med ändelsen $1 och hämtar den till $OUT/$2.
# Skriver filnamnet.
snap() {
  local ext="$1" dest="$2" wait="${3:-20}" before after latest
  before=$(count "\\.$ext")
  key KEYCODE_VOLUME_UP
  for w in $(seq 1 "$wait"); do
    sleep 1
    after=$(count "\\.$ext")
    [ "$after" -gt "$before" ] && break
  done
  # vänta på efterbehandlingen (beskärning, filter, filformat) innan filen hämtas
  sleep 4
  latest=$(adb shell "ls -t '$DIR' | grep '\\.$ext\$' | head -1" | tr -d '\r')
  [ -n "$latest" ] && adb pull "$DIR/$latest" "$OUT/$dest" >> "$OUT/ui.log" 2>&1
  [ "$after" -gt "$before" ] && echo "$latest"
}
# Bildbeskrivningen i EXIF (ImageDescription) – nattbilderna skriver hur många bilder som lades ihop
desc() { python3 -c "from PIL import Image; print(Image.open('$1').getexif().get(270, ''))" 2>> "$OUT/ui.log"; }
# Bildens form: lång sida / kort sida (två decimaler)
ratio() { python3 -c "from PIL import Image; w,h=Image.open('$1').size; print(f'{max(w,h)/min(w,h):.2f}')" 2>> "$OUT/ui.log"; }

step "Bildformat (4:3 → 1:1 → 16:9 → 3:2 → Helskärm), rutnät och självutlösare"
tap --desc "Aspect ratio"; sleep 3; shot 03-format-1-1
tap --desc "Aspect ratio"; sleep 3; shot 04-format-16-9
# 16:9 tas i 4:3 och beskärs efteråt (kamerans eget 16:9-läge gav en randig remsa på OnePlus 13R)
F169=$(snap jpg format-16-9.jpg)
R169=$(ratio "$OUT/format-16-9.jpg")
log "  16:9-bild: ${F169:-?} form ${R169:-?}"
check "16:9 ger en bild i formatet 16:9 (${R169:-?})" python3 -c "import sys; sys.exit(0 if abs(float('${R169:-0}')-16/9)<0.03 else 1)"
tap --desc "Aspect ratio"; sleep 3; shot 04b-format-3-2
F32=$(snap jpg format-3-2.jpg)
R32=$(ratio "$OUT/format-3-2.jpg")
log "  3:2-bild: ${F32:-?} form ${R32:-?}"
check "3:2 ger en bild i formatet 3:2 (${R32:-?})" python3 -c "import sys; sys.exit(0 if abs(float('${R32:-0}')-1.5)<0.03 else 1)"
tap --desc "Aspect ratio"; sleep 4; shot 04c-format-helskarm
FFULL=$(snap jpg format-full.jpg)
RFULL=$(ratio "$OUT/format-full.jpg")
SCREEN=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
SR=$(python3 -c "w,h=map(int,'${SCREEN:-1080x2400}'.split('x')); print(f'{max(w,h)/min(w,h):.2f}')")
log "  Helskärmsbild: ${FFULL:-?} form ${RFULL:-?} (skärmen ${SCREEN:-?} = ${SR})"
check "Helskärm ger en bild i skärmens form (${RFULL:-?} ≈ ${SR})" python3 -c "import sys; sys.exit(0 if abs(float('${RFULL:-0}')-float('${SR}'))<0.08 else 1)"
check "Appen lever efter 3:2 och helskärm" alive
tap --desc "Aspect ratio"; sleep 3
tap --desc "Grid"; sleep 1; shot 05-rutnat
tap --desc "Grid"; tap --desc "Grid"; tap --desc "Grid"
tap --desc "Timer"; sleep 1
before=$(count '\.jpg')
key KEYCODE_VOLUME_UP
sleep 1.5
shot 06-nedrakning
sleep 6
after=$(count '\.jpg')
check "Självutlösare 3 s tar bild ($before → $after)" [ "$after" -gt "$before" ]
tap --desc "Timer"; tap --desc "Timer"; tap --desc "Timer"

step "Bildvisare och redigerare"
tap --desc "Open last photo"
sleep 4
shot 07-bildvisare
check "Bildvisaren öppnas" $UI exists --text "Details"
tap --text "Edit"
sleep 4
shot 08-redigerare
check "Redigeraren öppnas med filter" $UI exists --text "Filters"
tap --text "Warm"
sleep 2
shot 09-filter-varm
tap --text "Adjust"
sleep 2
shot 10-justera
before=$(count '\.jpg')
tap --text "Save copy"
sleep 8
shot 11-sparad-kopia
after=$(count '\.jpg')
check "Redigerad kopia sparas ($before → $after)" [ "$after" -gt "$before" ]
check "Tillbaka i bildvisaren efter sparande" $UI exists --text "Details"
tap --text "Details"
sleep 2
shot 12-detaljer
key KEYCODE_BACK
sleep 1
key KEYCODE_BACK
sleep 4

step "Video"
tap --text "VIDEO"
sleep 5
shot 13-video
tap --desc "Frame rate"
sleep 5
shot 13b-video-60fps
check "Appen lever efter byte till 60 fps" alive
before=$(count '\.mp4')
key KEYCODE_VOLUME_UP
sleep 6
shot 14-spelar-in
key KEYCODE_VOLUME_UP
sleep 6
after=$(count '\.mp4')
check "Video spelas in och sparas ($before → $after)" [ "$after" -gt "$before" ]
tap --desc "Frame rate"
sleep 4

step "Natt (flera bilder läggs ihop), Ljusspår, Silkesvatten, Pro och Porträtt"
tap --text "NIGHT"
sleep 5
shot 15-natt
check "Nattläget visar valen Natt, Ljusspår och Silkesvatten" $UI exists --text "Light trails" --timeout 8
FN=$(snap jpg natt.jpg 45)
DN=$(desc "$OUT/natt.jpg")
log "  Nattbild: ${FN:-?} – ${DN:-?}"
check "Nattbild sparas (${FN:-ingen fil})" [ -n "$FN" ]
check "Nattbilden är ihoplagd av flera bilder (${DN:-?})" bash -c "echo '$DN' | grep -qE 'Night: ([2-9]|1[0-9]) frames'"
tap --text "Silky water"; sleep 1
tap --text "1 s"; sleep 1
shot 15b-silkesvatten
FS=$(snap jpg silke.jpg 30)
DS=$(desc "$OUT/silke.jpg")
log "  Silkesvatten: ${FS:-?} – ${DS:-?}"
check "Silkesvatten 1 s sparas (${DS:-?})" bash -c "echo '$DS' | grep -q 'Silky water: 1 s'"
tap --text "Light trails"; sleep 1
FT=$(snap jpg ljusspar.jpg 30)
DT=$(desc "$OUT/ljusspar.jpg")
log "  Ljusspår: ${FT:-?} – ${DT:-?}"
check "Ljusspår 1 s sparas (${DT:-?})" bash -c "echo '$DT' | grep -q 'Light trails: 1 s'"
tap --text "Night"; sleep 1
check "Appen lever efter nattbilderna" alive
# Nattloggen i resultatet bara om något gick fel (högst 10 anteckningar per steg syns)
if [ -z "$FN" ] || [ -z "$FS" ] || [ -z "$FT" ]; then
  adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Natt" | tail -6 | while IFS= read -r l; do
    echo "::notice title=Natt-logg::$(echo "$l" | cut -c1-300)"
  done
fi
tap --text "PRO"
sleep 5
shot 16-pro
check "Pro-läget visar EV-reglage" $UI exists --text "EV"
if $UI exists --text "ISO" --timeout 2 >> "$OUT/ui.log" 2>&1; then
  tap --text "ISO"
  sleep 2
  shot 17-pro-iso
fi
tap --text "NIGHT"; sleep 2
tap --text "VIDEO"; sleep 2
tap --text "PHOTO"; sleep 2
tap --text "PORTRAIT"
sleep 5
shot 18-portratt
before=$(count '\.jpg')
key KEYCODE_VOLUME_UP
sleep 14
shot 19-portratt-klar
after=$(count '\.jpg')
check "Porträttbild sparas ($before → $after)" [ "$after" -gt "$before" ]
check "Appen lever efter porträtt" alive
tap --text "PHOTO"
sleep 4

step "Svep mellan lägen"
check "Foto är valt läge" $UI exists --text "PHOTO" --selected
$UI swipe --text "PHOTO" --dx=-170 >> "$OUT/ui.log" 2>&1
sleep 4
shot 19b-svep-till-portratt
check "Svep åt vänster på lägesraden byter till Porträtt" $UI exists --text "PORTRAIT" --selected
$UI swipe --text "PORTRAIT" --dx=170 >> "$OUT/ui.log" 2>&1
sleep 4
check "Svep åt höger på lägesraden byter tillbaka till Foto" $UI exists --text "PHOTO" --selected
$UI swipe --text "PHOTO" --dx=640 --ms=400 >> "$OUT/ui.log" 2>&1
sleep 4
shot 19c-langt-svep
check "Långt svep på lägesraden hoppar förbi Video (Natt eller Pro)" bash -c "$UI exists --text NIGHT --selected --timeout 2 || $UI exists --text PRO --selected --timeout 2"
# Foto syns inte i menyn när Natt eller Pro är valt – gå tillbaka via kamerans fotostart (som dubbeltryck på strömknappen)
adb shell am start -a android.media.action.STILL_IMAGE_CAMERA -n "$PKG/.MainActivity" >> "$OUT/ui.log" 2>&1
sleep 4
check "Tillbaka i Foto" $UI exists --text "PHOTO" --selected --timeout 6
$UI swipe --y 0.4 --dx=-0.3 >> "$OUT/ui.log" 2>&1
sleep 4
check "Svep i sökaren byter till Porträtt" $UI exists --text "PORTRAIT" --selected
$UI swipe --y 0.4 --dx=0.3 >> "$OUT/ui.log" 2>&1
sleep 4
check "Svep i sökaren byter tillbaka till Foto" $UI exists --text "PHOTO" --selected
check "Appen lever efter svepen" alive

step "Live-filter (RGB)"
VF="0.1,0.18,0.9,0.40"   # ett område mitt i sökaren
check "Filterknappen finns i fotoläget" $UI exists --desc "Filter"
read -r r0 g0 b0 < <($UI rgb --region "$VF" 2>> "$OUT/ui.log")
log "  Sökaren utan filter: R=${r0:-?} G=${g0:-?} B=${b0:-?}"
tap --desc "Filter"
sleep 2
shot 19d-filterpanel
check "Filterpanelen öppnas med RGB-reglage" $UI exists --desc "Red"
tap --text "Warm"
sleep 2
shot 19e-filter-varm
tap --text "Original"
sleep 1
# Dra reglaget från mitten (100 %) ända till vänster (0 %)
$UI swipe --desc "Red" --dx=-0.45 --ms=500 >> "$OUT/ui.log" 2>&1
sleep 2
$UI dump >> "$OUT/ui.log" 2>&1
shot 19f-rott-0-procent
check "Rött-reglaget står på 0 %" $UI exists --text "0 %"
read -r r1 g1 b1 < <($UI rgb --region "$VF" 2>> "$OUT/ui.log")
log "  Sökaren med rött 0 %: R=${r1:-?} G=${g1:-?} B=${b1:-?}"
bright=0
[ "${r0:-0}" -ge 30 ] 2>/dev/null && bright=1
if [ "$bright" = 1 ]; then
  check "Sökaren visar filtret live (rött försvinner)" [ "${r1:-999}" -le $(( r0 / 3 )) ]
else
  log "  (scenen i emulatorn har för lite rött för att mäta sökaren)"
fi
before=$(count '\.jpg')
key KEYCODE_VOLUME_UP
sleep 8
after=$(count '\.jpg')
check "Foto med filter sparas ($before → $after)" [ "$after" -gt "$before" ]
latest=$(adb shell "ls -t '$DIR' | grep 'jpg' | head -1" | tr -d '\r')
adb pull "$DIR/$latest" "$OUT/filter-foto.jpg" >> "$OUT/ui.log" 2>&1
read -r pr pg pb < <($UI rgb "$OUT/filter-foto.jpg" 2>> "$OUT/ui.log")
log "  Sparat foto ($latest) med rött 0 %: R=${pr:-?} G=${pg:-?} B=${pb:-?}"
log "  EXIF i fotot: $(python3 -c "from PIL import Image; e=Image.open('$OUT/filter-foto.jpg').getexif(); print(e.get(271, '-'), e.get(272, '-'), e.get(306, '-'))" 2>&1)"
if [ "$bright" = 1 ]; then
  check "Det sparade fotot har samma filter som sökaren (inget rött)" [ "${pr:-999}" -le 12 ]
fi
check "Appen lever efter foto med filter" alive
tap --text "Reset"
sleep 2
read -r r2 g2 b2 < <($UI rgb --region "$VF" 2>> "$OUT/ui.log")
log "  Sökaren efter Återställ: R=${r2:-?} G=${g2:-?} B=${b2:-?}"
if [ "$bright" = 1 ]; then
  check "Återställ tar bort filtret" [ "${r2:-0}" -ge $(( r0 / 2 )) ]
fi
tap --desc "Filter"
sleep 1
tap --text "VIDEO"
sleep 4
check "Filterknappen göms i videoläget" bash -c "! $UI exists --desc Filter --timeout 1"
tap --text "PHOTO"
sleep 4

step "Framkamera"
tap --desc "Switch camera"
sleep 6
shot 20-framkamera
before=$(count '\.jpg')
key KEYCODE_VOLUME_UP
sleep 6
after=$(count '\.jpg')
check "Selfie sparas ($before → $after)" [ "$after" -gt "$before" ]
tap --desc "Switch camera"
sleep 5

step "Inställningar och Om"
tap --desc "Settings"
sleep 3
shot 21-installningar
check "Inställningar öppnas" $UI exists --text "Settings"
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1); H=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 400; sleep 1
shot 21b-installningar-mitten
tap --text "Rule of thirds" --scroll
sleep 1
shot 21c-val-rutnat
tap --text "Off" --scroll
$UI tap --text "About" --scroll --timeout 15 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte Om-raden"
sleep 3
shot 22-om
check "Om visar Developer: Claes Sundman S.N.C AB" $UI exists --text "Developer: Claes Sundman S.N.C AB"
key KEYCODE_BACK
sleep 2

step "Manual och tema"
$UI tap --text "Manual" --scroll --timeout 15 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte Manual i inställningarna"
sleep 3
shot 22b-manual
check "Manualen öppnas med sina avsnitt" $UI exists --text "THE CAMERA" --timeout 10
check "Manualen visar vilken version den gäller" $UI exists --contains "For version"
check "Manualen har avsnittet om uppdateringar" $UI exists --text "UPDATES" --scroll --timeout 40
key KEYCODE_BACK
sleep 2
$UI tap --text "Beige" --scroll --timeout 10 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte temat Beige"
sleep 2
shot 22c-tema-beige
read -r tr tg tb < <($UI rgb --region 0.0,0.0,1.0,0.05 2>> "$OUT/ui.log")
log "  Inställningar i Beige: R=${tr:-?} G=${tg:-?} B=${tb:-?}"
check "Temat Beige gör inställningarna ljusa" [ "${tr:-0}" -ge 180 ]
tap --text "Dark"
sleep 2
read -r tr tg tb < <($UI rgb --region 0.0,0.0,1.0,0.05 2>> "$OUT/ui.log")
check "Temat Mörk gör dem mörka igen" [ "${tr:-255}" -le 60 ]
key KEYCODE_BACK
sleep 4
check "Kameran är mörk även efter temabytet" $UI exists --desc "Switch camera"

step "Filformat (PNG, HEIF, RAW)"
# Väljer filformat i Inställningar › Foto och går tillbaka till kameran
set_format() {
  tap --desc "Settings"; sleep 3
  $UI tap --text "$1" --scroll --timeout 15 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte filformatet $1"
  sleep 1
  key KEYCODE_BACK; sleep 4
}
set_format "PNG"
FPNG=$(snap png format.png)
KIND=$(python3 -c "from PIL import Image; print(Image.open('$OUT/format.png').format)" 2>> "$OUT/ui.log")
log "  PNG: ${FPNG:-?} ($KIND)"
check "PNG sparas som en riktig PNG-fil" [ "$KIND" = "PNG" ]
check "Ingen JPEG ligger kvar bredvid PNG-bilden" bash -c "! adb shell \"ls '$DIR'\" | grep -q \"${FPNG%.png}.jpg\""
set_format "HEIF"
FHEIC=$(snap heic format.heic)
BRAND=$(python3 -c "d=open('$OUT/format.heic','rb').read(12); print(d[4:12].decode('latin1'))" 2>> "$OUT/ui.log")
log "  HEIF: ${FHEIC:-?} (${BRAND:-?})"
check "HEIF sparas som en riktig HEIF-fil (${BRAND:-?})" bash -c "echo '${BRAND}' | grep -qE 'ftyp(heic|mif1|heix|msf1)'"
set_format "RAW"
JB=$(count '\.jpg')
FDNG=$(snap dng format.dng)
if [ -n "$FDNG" ]; then
  log "  RAW: ${FDNG}"
  check "RAW sparas som DNG" bash -c "head -c 4 '$OUT/format.dng' | od -An -c | grep -qE 'I +I|M +M'"
else
  JA=$(count '\.jpg')
  log "  RAW finns inte i emulatorns kamera – appen sparade JPEG ($JB → $JA)"
  check "Utan RAW sparas en vanlig JPEG i stället" [ "$JA" -gt "$JB" ]
fi
check "Appen lever efter filformaten" alive
set_format "JPEG"

step "HDR (3–5 exponeringar läggs ihop) och Ultra HDR"
check "HDR-knappen finns i Foto" $UI exists --desc "HDR" --timeout 8
tap --desc "HDR"; sleep 5
shot 36-hdr
FH=$(snap jpg hdr.jpg 45)
DH=$(desc "$OUT/hdr.jpg")
log "  HDR: ${FH:-?} – ${DH:-?}"
check "HDR-bild sparas (${FH:-ingen fil})" [ -n "$FH" ]
check "HDR-bilden är ihoplagd av flera exponeringar (${DH:-?})" bash -c "echo '$DH' | grep -qE 'HDR: [2-5] exposures'"
check "HDR-bilden är Ultra HDR (gain map i filen)" python3 -c "import sys; d=open('$OUT/hdr.jpg','rb').read(); sys.exit(0 if b'hdrgm' in d and d.count(b'\\xff\\xd8\\xff') >= 2 else 1)"
check "Appen lever efter HDR-bilden" alive
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "HDR" | tail -3 | while IFS= read -r l; do
  echo "::notice title=HDR-logg::$(echo "$l" | cut -c1-300)"
done
# Krasch (Java eller inbyggd kod) i resultatet
adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|Fatal signal|DEBUG   : (Abort|backtrace)" | head -3 | while IFS= read -r l; do
  echo "::notice title=Krasch::$(echo "$l" | cut -c1-300)"
done
alive || { adb shell am start -n "$PKG/.MainActivity" > /dev/null 2>&1; sleep 6; }
tap --desc "HDR"; sleep 4
check "Appen lever efter HDR" alive

step "Text i bild (delas till appen)"
if python3 -c "import PIL" 2> /dev/null || pip install --quiet pillow > /dev/null 2>&1; then
  $UI text-image "$OUT/ocr-testbild.png"
  adb push "$OUT/ocr-testbild.png" "$DIR/OCR_TEST.png" >> "$OUT/ui.log" 2>&1
  sleep 4
  ID=$(adb shell "content query --uri content://media/external/images/media --projection _id:_display_name" \
    | grep "OCR_TEST.png" | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2)
  if [ -n "$ID" ]; then
    adb shell am start -a android.intent.action.SEND -t image/png \
      --eu android.intent.extra.STREAM "content://media/external/images/media/$ID" \
      -n "$PKG/.MainActivity" >> "$OUT/ui.log" 2>&1
    sleep 10
    shot 23-text
    check "Textskärmen öppnas" $UI exists --text "Select all"
    check "Text hittas i bilden" $UI exists --contains "Tap text to select"
    tap --text "Select all"
    sleep 2
    shot 24-text-markerad
    check "Rader markeras" $UI exists --contains "selected"
    tap --text "Show text"
    sleep 3
    shot 25-text-visa
    check "Igenkänd text: 'SNC Camera'" $UI exists --contains "SNC Camera"
    check "Igenkänd text: telefonnummer" $UI exists --contains "123 45 67"
    if $UI exists --text "Copy" --timeout 2 >> "$OUT/ui.log" 2>&1; then
      tap --text "Copy"
      sleep 1
    fi
    key KEYCODE_BACK
    sleep 2
    key KEYCODE_BACK
    sleep 3
  else
    bad "Testbilden hittades inte i MediaStore"
  fi
else
  log "- ⚠️ Pillow saknas, hoppar över texttestet"
fi

step "Galleri: textknappen"
tap --desc "Open last photo"
sleep 4
tap --text "Text"
sleep 8
shot 26-text-fran-galleri
check "Textigenkänning från bildvisaren" $UI exists --text "Select all"
key KEYCODE_BACK
sleep 2
key KEYCODE_BACK
sleep 3

step "Kameror (vad telefonen ger appen)"
tap --desc "Settings"
sleep 3
$UI tap --text "Cameras" --scroll --timeout 15 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte Kameror i inställningarna"
check "Kamerasidan visar sammanfattningen" $UI exists --text "AVAILABLE TO THE APP" --timeout 20
shot 33-kameror
# Video använder standardkameran (kamera 0), foto den logiska kameran med flest linser
check "Kamerasidan visar kameran för video" $UI exists --contains "Back camera for video" --scroll --timeout 15
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1); H=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 5)) 400; sleep 1
shot 34-kameror-linser
check "Kamerasidan visar bakkameran" $UI exists --contains "BACK CAMERA" --scroll --timeout 25
tap --text "Copy report"
sleep 1
check "Appen lever efter kamerasidan" alive
key KEYCODE_BACK
sleep 2
key KEYCODE_BACK
sleep 3

step "Uppdatering (som i Direct)"
# Testappen har version 0.9.0, så senaste release på GitHub ska visas som en ny version
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
tap --desc "Settings"
sleep 3
$UI tap --text "Updates" --scroll --timeout 15 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte Uppdateringar i inställningarna"
sleep 2
if $UI exists --text "NEW VERSION" --timeout 25 >> "$OUT/ui.log" 2>&1; then
  ok "Ny version hittas på GitHub"
  shot 29-uppdatering-ny-version
  tap --text "Download and install" --scroll
  RESULT=""
  for i in $(seq 1 60); do
    if $UI exists --contains "verified (SHA-256)" --timeout 1 >> "$OUT/ui.log" 2>&1; then RESULT="verifierad"; break; fi
    if $UI exists --contains "different key" --timeout 1 >> "$OUT/ui.log" 2>&1; then RESULT="annan nyckel"; break; fi
    if $UI exists --contains "download failed" --timeout 1 >> "$OUT/ui.log" 2>&1; then RESULT="nätverksfel"; break; fi
    # Androids installerare kan ha öppnats ovanpå appen
    if ! adb shell dumpsys window | grep -q "mCurrentFocus.*$PKG"; then RESULT="installerare"; break; fi
    sleep 2
  done
  shot 30-uppdatering-efter-nedladdning
  log "  Resultat av nedladdningen: ${RESULT:-inget}"
  check "Nedladdning och kontroll blir klar (${RESULT:-timeout})" bash -c "[ -n '$RESULT' ] && [ '$RESULT' != 'nätverksfel' ]"
  if [ "$RESULT" = "installerare" ]; then
    adb exec-out screencap -p > "$OUT/31-androids-installerare.png" 2>/dev/null || true
    key KEYCODE_BACK
    sleep 2
  fi
  check "Appen lever efter uppdateringsförsöket" alive
else
  if $UI exists --text "You have the latest version" --timeout 2 >> "$OUT/ui.log" 2>&1; then
    bad "Testappen (0.9.0) borde se en nyare release"
  else
    log "- ⚠️ Kunde inte nå GitHub från emulatorn – hoppar över nedladdningen"
  fi
  shot 29-uppdatering
fi
key KEYCODE_BACK
sleep 2
key KEYCODE_BACK
sleep 3
shot 32-kamera-med-prick

step "Språkval (Svenska)"
tap --desc "Settings"
sleep 3
tap --text "Svenska"
sleep 4
shot 27-installningar-svenska
check "Appen byter till svenska" $UI exists --text "Inställningar"
$UI tap --text "Om" --scroll --timeout 15 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte Om (svenska)"
sleep 3
shot 28-om-svenska
check "Om på svenska visar Developer-raden" $UI exists --text "Developer: Claes Sundman S.N.C AB"
key KEYCODE_BACK
sleep 2
key KEYCODE_BACK
sleep 3
tap --desc "Inställningar"
sleep 3
tap --text "English"
sleep 4
check "Appen byter tillbaka till engelska" $UI exists --text "Settings"
key KEYCODE_BACK
sleep 3

step "Avslut"
shot 99-slut
check "Appen lever i slutet" alive
adb logcat -d -v time > "$OUT/logcat-full.txt" 2>&1
grep -E "AndroidRuntime|SncCamera|se\.snc\.camera|CameraX|Camera2CameraImpl|MLKit|mlkit" "$OUT/logcat-full.txt" > "$OUT/logcat-app.txt" || true

if grep -q "FATAL EXCEPTION" "$OUT/logcat-full.txt"; then
  CRASH=$(grep -A 25 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | head -40)
  bad "Krasch i loggen"
  echo "$CRASH" >> "$REPORT"
  ENC=$(echo "$CRASH" | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
  echo "::error title=Krasch::$ENC"
else
  ok "Ingen krasch i loggen"
fi
if grep -q "ANR in $PKG" "$OUT/logcat-full.txt"; then bad "Appen slutade svara (ANR)"; else ok "Inget ANR"; fi

log ""
log "**Resultat: $PASS OK, $FAIL fel**"
echo "::notice title=Röktest::$PASS OK, $FAIL fel"
[ "$FAIL" -eq 0 ]
