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
bad()  {
  FAIL=$((FAIL + 1)); log "- ❌ $*"; echo "::error title=Röktest::$*"
  # Det som syntes på skärmen när felet kom (ui.py skriver ut det) – i jobbloggen för felsökning
  echo "::group::ui.log vid felet: $*"; tail -n 30 "$OUT/ui.log" | cut -c1-240; echo "::endgroup::"
}
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

step "Omstart fem gånger (fångar startproblem)"
COLD=()
for n in 1 2 3 4 5; do
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
# Medianen av fem kallstarter (emulatorn i GitHub Actions varierar mycket mellan enskilda starter)
MEDIAN=$(printf '%s\n' "${COLD[@]}" | sort -n | sed -n '3p')
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
# Pro-hjälpmedel: histogram, fokusmarkering och zebra (en liten analysbild kopplas till sökaren)
tap --text "ASSIST"
sleep 2
check "Pro-panelen visar hjälpmedlen" $UI exists --text "Focus peaking"
tap --text "Histogram"; sleep 5
tap --text "Focus peaking"; sleep 1
tap --text "Zebra"; sleep 4
shot 17b-pro-hjalpmedel
check "Histogrammet visas i sökaren" $UI exists --desc "Live histogram" --timeout 10
check "Hjälpmedlen räknas på sökarens bild" bash -c "adb logcat -d -s SncCamera:V | grep -qE 'Hj.lpmedel: bildruta'"
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Hj.lpmedel: bildruta" | tail -1 | while IFS= read -r l; do
  echo "::notice title=Hjälpmedel-logg::$(echo "$l" | cut -c1-300)"
done
FPA=$(snap jpg pro-hjalpmedel.jpg)
check "Foto i Pro med hjälpmedlen på sparas (${FPA:-ingen fil})" [ -n "$FPA" ]
check "Appen lever med Pro-hjälpmedlen" alive
tap --text "Histogram"; sleep 1
tap --text "Focus peaking"; sleep 1
tap --text "Zebra"; sleep 5
check "Histogrammet försvinner när hjälpmedlen stängs av" bash -c "! $UI exists --desc 'Live histogram' --timeout 2"
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

step "Avtryckaren: tryck och serietagning (håll in)"
check "Avtryckaren finns" $UI exists --desc "Shutter"
before=$(count '\.jpg')
tap --desc "Shutter"
after=$before
for w in $(seq 1 15); do sleep 1; after=$(count '\.jpg'); [ "$after" -gt "$before" ] && break; done
check "Ett tryck på avtryckaren tar en bild ($before → $after)" [ "$after" -gt "$before" ]
sleep 2
BB=$(count '_BURST')
( $UI hold --desc "Shutter" --ms 4500 >> "$OUT/ui.log" 2>&1 ) &
HOLD=$!
sleep 5
shot 19d-serietagning
wait $HOLD
sleep 6
BA=$(count '_BURST')
log "  Serietagning: $((BA - BB)) bilder ($BB → $BA)"
check "Håll in avtryckaren tar en serie (minst 3 bilder: $((BA - BB)))" [ $((BA - BB)) -ge 3 ]
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Serie:" | tail -2 | while IFS= read -r l; do
  echo "::notice title=Serie-logg::$(echo "$l" | cut -c1-300)"
done
check "Appen lever efter serietagningen" alive

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
# Video: live-filtret läggs på sökaren och filmen (OpenGL). Rött 0 % igen, sedan Video.
$UI swipe --desc "Red" --dx=-0.45 --ms=500 >> "$OUT/ui.log" 2>&1
sleep 1
check "Rött-reglaget står på 0 % igen" $UI exists --text "0 %"
tap --desc "Filter"
sleep 1
tap --text "VIDEO"
sleep 7
shot 19g-video-filter
check "Filterknappen finns i videoläget" $UI exists --desc "Filter" --timeout 3
read -r vr vg vb < <($UI rgb --region "$VF" 2>> "$OUT/ui.log")
log "  Videons sökare med rött 0 %: R=${vr:-?} G=${vg:-?} B=${vb:-?}"
if [ "$bright" = 1 ]; then
  check "Videons sökare visar filtret (rött försvinner)" [ "${vr:-999}" -le $(( r0 / 3 )) ]
fi
VB=$(count '\.mp4')
key KEYCODE_VOLUME_UP
sleep 5
key KEYCODE_VOLUME_UP
for w in $(seq 1 15); do sleep 1; [ "$(count '\.mp4')" -gt "$VB" ] && break; done
sleep 3
VA=$(count '\.mp4')
VID=$(adb shell "ls -t '$DIR' | grep '\.mp4$' | head -1" | tr -d '\r')
check "Video med filter sparas ($VB → $VA)" [ "$VA" -gt "$VB" ]
if [ -n "$VID" ] && command -v ffmpeg > /dev/null; then
  adb pull "$DIR/$VID" "$OUT/filter-video.mp4" >> "$OUT/ui.log" 2>&1
  ffmpeg -loglevel error -y -ss 1 -i "$OUT/filter-video.mp4" -frames:v 1 "$OUT/filter-video.png" >> "$OUT/ui.log" 2>&1
  read -r fr fg fb < <($UI rgb "$OUT/filter-video.png" 2>> "$OUT/ui.log")
  log "  Bild ur videon ($VID) med rött 0 %: R=${fr:-?} G=${fg:-?} B=${fb:-?}"
  if [ "$bright" = 1 ]; then
    check "Filmen har filtret (inget rött)" [ "${fr:-999}" -le 25 ]
  fi
else
  log "  (ffmpeg saknas – bilden ur videon kontrolleras inte)"
fi
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Videofilter: f" | tail -1 | while IFS= read -r l; do
  echo "::notice title=Videofilter-logg::$(echo "$l" | cut -c1-300)"
done
check "Appen lever efter video med filter" alive
tap --desc "Filter"
sleep 1
tap --text "Reset"
sleep 1
tap --desc "Filter"
sleep 1
tap --text "PHOTO"
sleep 4

step "Tidsförlopp och slow motion"
tap --text "VIDEO"
sleep 5
check "Videoläget visar valen Video, Slow motion och Tidsförlopp" $UI exists --text "Time-lapse" --timeout 8
tap --text "Time-lapse"
sleep 5
tap --text "0.5 s"
sleep 1
shot 19h-tidsforlopp
check "Tidsförlopp visar hur mycket snabbare filmen blir" $UI exists --text "Plays 15× faster"
TB=$(count '_TIMELAPSE')
key KEYCODE_VOLUME_UP
sleep 8
shot 19i-tidsforlopp-spelar-in
key KEYCODE_VOLUME_UP
for w in $(seq 1 15); do sleep 1; [ "$(count '_TIMELAPSE')" -gt "$TB" ] && break; done
sleep 2
TA=$(count '_TIMELAPSE')
check "Tidsförlopp sparas ($TB → $TA)" [ "$TA" -gt "$TB" ]
TL=$(adb shell "ls -t '$DIR' | grep '_TIMELAPSE.mp4' | head -1" | tr -d '\r')
if [ -n "$TL" ] && command -v ffprobe > /dev/null; then
  adb pull "$DIR/$TL" "$OUT/tidsforlopp.mp4" >> "$OUT/ui.log" 2>&1
  TDUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/tidsforlopp.mp4" 2>> "$OUT/ui.log" | head -1)
  TFR=$(ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$OUT/tidsforlopp.mp4" 2>> "$OUT/ui.log" | head -1)
  log "  Tidsförlopp: $TL – ${TDUR:-?} s, ${TFR:-?} bildrutor (8 s inspelning, 0,5 s mellan bilderna)"
  check "Tidsförloppet går fortare än verklig tid (${TDUR:-?} s under 3 s, ${TFR:-?} bildrutor, minst 5)" python3 -c "import sys; d=float('${TDUR:-99}' or 99); f=int('${TFR:-0}' or 0); sys.exit(0 if d < 3 and f >= 5 else 1)"
fi
check "Appen lever efter tidsförloppet" alive
tap --text "Slow motion"
sleep 8
shot 19j-slow-motion
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Slowmo:" | tail -2 | while IFS= read -r l; do
  echo "::notice title=Slowmo-logg::$(echo "$l" | cut -c1-300)"
done
if $UI exists --text "Slow motion isn't supported by this camera" --timeout 3 >> "$OUT/ui.log" 2>&1; then
  log "  Slow motion stöds inte av emulatorns kamera – appen säger det"
  ok "Slow motion: tydligt besked när kameran inte klarar det"
else
  SB=$(count '_SLOMO')
  key KEYCODE_VOLUME_UP
  sleep 4
  shot 19k-slow-motion-spelar-in
  key KEYCODE_VOLUME_UP
  for w in $(seq 1 20); do sleep 1; [ "$(count '_SLOMO')" -gt "$SB" ] && break; done
  sleep 2
  SA=$(count '_SLOMO')
  check "Slow motion sparas ($SB → $SA)" [ "$SA" -gt "$SB" ]
  SL=$(adb shell "ls -t '$DIR' | grep '_SLOMO' | head -1" | tr -d '\r')
  if [ -n "$SL" ] && command -v ffprobe > /dev/null; then
    adb pull "$DIR/$SL" "$OUT/slowmotion.mp4" >> "$OUT/ui.log" 2>&1
    SDUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/slowmotion.mp4" 2>> "$OUT/ui.log" | head -1)
    log "  Slow motion: $SL – ${SDUR:-?} s (4 s inspelning)"
    check "Slow motion är långsammare än verklig tid (${SDUR:-?} s, minst 7 s)" python3 -c "import sys; sys.exit(0 if float('${SDUR:-0}' or 0) >= 7 else 1)"
  fi
  adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Slowmo: (inspelning|fel)" | tail -1 | while IFS= read -r l; do
    echo "::notice title=Slowmo-inspelning::$(echo "$l" | cut -c1-300)"
  done
fi
check "Appen lever efter slow motion" alive
tap --text "Video"
sleep 4
# Bildfrekvensknappen visas bara i vanlig video
check "Tillbaka till vanlig video" $UI exists --desc "Frame rate" --timeout 6
tap --text "PHOTO"
sleep 4

step "Fram och bak samtidigt (Dubbel)"
tap --text "PORTRAIT"
sleep 4
tap --text "DUAL"
sleep 8
shot 19l-dubbel
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Dubbel: (par|bunden)" | tail -2 | while IFS= read -r l; do
  echo "::notice title=Dubbel-logg::$(echo "$l" | cut -c1-300)"
done
if $UI exists --text "Front and back cameras can't be used at the same time on this phone" --timeout 3 >> "$OUT/ui.log" 2>&1; then
  log "  Fram och bak samtidigt stöds inte av emulatorn – appen säger det"
  ok "Dubbel: tydligt besked när telefonen inte klarar båda kamerorna samtidigt"
else
  check "Knappen Byt plats finns" $UI exists --desc "Swap places"
  DB=$(count 'DUAL_')
  key KEYCODE_VOLUME_UP
  for w in $(seq 1 15); do sleep 1; [ "$(count 'DUAL_')" -gt "$DB" ] && break; done
  DA=$(count 'DUAL_')
  check "Dubbelbilden sparas ($DB → $DA)" [ "$DA" -gt "$DB" ]
  DF=$(adb shell "ls -t '$DIR' | grep 'DUAL_' | head -1" | tr -d '\r')
  if [ -n "$DF" ]; then
    adb pull "$DIR/$DF" "$OUT/dubbel.jpg" >> "$OUT/ui.log" 2>&1
    DIM=$(python3 - "$OUT/dubbel.jpg" <<'PY'
import struct, sys
d = open(sys.argv[1], 'rb').read()
i = 2
while i < len(d) - 9:
    if d[i] != 0xFF:
        i += 1
        continue
    m = d[i + 1]
    if m in (0xC0, 0xC1, 0xC2):
        h, w = struct.unpack('>HH', d[i + 5:i + 9])
        print(f"{w}x{h}")
        break
    i += 2 + struct.unpack('>H', d[i + 2:i + 4])[0]
PY
)
    log "  Dubbelbild: $DF ${DIM:-?}"
    check "Dubbelbilden är stående 3:4 (två halvor 3:2): ${DIM:-?}" python3 -c "import sys; w,h=map(int,'${DIM:-0x1}'.split('x')); sys.exit(0 if h and 0.72 < w/h < 0.78 else 1)"
  fi
  tap --desc "Swap places"
  sleep 2
  # Håll in den övre halvan i 1 s och dra den till den undre (egna pekarhändelser: input draganddrop
  # börjar röra sig precis när tiden för långt tryck går ut, och då hinner det bli ett svep)
  read -r SW SH < <(adb shell wm size | tail -1 | sed 's/.*: //; s/x/ /')
  DX=$((SW / 2)); DY1=$((SH * 24 / 100)); DY2=$((SH * 52 / 100))
  adb shell "input motionevent DOWN $DX $DY1; sleep 1; for f in 1 2 3 4 5; do input motionevent MOVE $DX \$(( $DY1 + ($DY2 - $DY1) * f / 5 )); done; input motionevent UP $DX $DY2" >> "$OUT/ui.log" 2>&1
  sleep 3
  shot 19m-dubbel-bytt
  SWAPS=$(adb logcat -d -s SncCamera:V 2>/dev/null | grep -c "Dubbel: bytt plats")
  check "Byt plats med knappen och genom att hålla in och dra ($SWAPS byten, minst 2)" [ "$SWAPS" -ge 2 ]
  DB=$(count 'DUAL_')
  key KEYCODE_VOLUME_UP
  for w in $(seq 1 15); do sleep 1; [ "$(count 'DUAL_')" -gt "$DB" ] && break; done
  check "Dubbelbild sparas även efter bytet" [ "$(count 'DUAL_')" -gt "$DB" ]
fi
check "Appen lever efter Dubbel" alive
tap --text "PORTRAIT"
sleep 3
tap --text "PHOTO"
sleep 4

step "Skanna: snabbinställningsrutan, QR-kod och dokument till PDF"
# Snabbinställningsrutan öppnar kameran i läget Skanna (QR-kod)
adb shell cmd statusbar add-tile "$PKG/.scan.ScanTileService" >> "$OUT/ui.log" 2>&1
sleep 2
adb shell cmd statusbar click-tile "$PKG/.scan.ScanTileService" >> "$OUT/ui.log" 2>&1
sleep 3
adb shell cmd statusbar collapse >> "$OUT/ui.log" 2>&1
sleep 5
shot 19n-skanna-qr
check "Snabbinställningsrutan öppnar läget Skanna (QR-kod)" $UI exists --text "Point the camera at a QR code or barcode" --timeout 10
check "Skanna visar valen QR-kod och Dokument" $UI exists --text "Document"
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Skanna: (CODE|första)" | tail -1 | while IFS= read -r l; do
  echo "::notice title=Skanna-logg::$(echo "$l" | cut -c1-300)"
done
check "Appen lever i läget Skanna" alive
tap --text "Document"
sleep 5
check "Dokumentläget visar uppmaningen" $UI exists --text "Hold the phone over the page and tap the shutter" --timeout 8
tap --desc "Shutter"
sleep 6
shot 19o-dokument-horn
check "Dokumentet öppnas för att justera hörnen" $UI exists --text "Adjust the corners" --timeout 12
tap --text "Add page"
sleep 6
check "Sidan läggs till" $UI exists --text "Pages: 1" --timeout 15
PB=$(adb shell "ls '/sdcard/Documents/SNC Camera' 2>/dev/null | grep -c '\.pdf$'" | tr -d '\r')
tap --text "Save PDF"
sleep 6
shot 19p-pdf-sparad
check "PDF:en sparas" $UI exists --text "PDF saved · 1 pages" --timeout 15
PDF=$(adb shell "ls -t '/sdcard/Documents/SNC Camera' 2>/dev/null | grep '\.pdf$' | head -1" | tr -d '\r')
if [ -n "$PDF" ]; then
  adb pull "/sdcard/Documents/SNC Camera/$PDF" "$OUT/dokument.pdf" >> "$OUT/ui.log" 2>&1
  PAGES=$(python3 -c "import re,sys; d=open(sys.argv[1],'rb').read(); print(len(re.findall(rb'/Type\s*/Page[^s]', d)) if d[:4]==b'%PDF' else -1)" "$OUT/dokument.pdf" 2>> "$OUT/ui.log")
  SIZE=$(stat -c %s "$OUT/dokument.pdf" 2>/dev/null)
  log "  PDF: $PDF – ${PAGES:-?} sida, $((${SIZE:-0} / 1024)) kB"
  check "PDF-filen är en riktig PDF med en sida (${PAGES:-?})" [ "${PAGES:-0}" -eq 1 ]
else
  bad "Ingen PDF i Dokument/SNC Camera (${PB:-0} före)"
fi
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "Dokument: (bild|sida|PDF)" | tail -2 | while IFS= read -r l; do
  echo "::notice title=Dokument-logg::$(echo "$l" | cut -c1-300)"
done
check "Appen lever efter dokumentet" alive
# Tillbaka till Foto (kamerans egen avsikt väljer Foto)
adb shell am start -a android.media.action.STILL_IMAGE_CAMERA -n "$PKG/.MainActivity" >> "$OUT/ui.log" 2>&1
sleep 5
check "Tillbaka i Foto" $UI exists --text "PHOTO" --selected --timeout 8

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
# Inställningarna öppnas på nytt från kameran (överst), så att sökningen alltid börjar på samma ställe
key KEYCODE_BACK
sleep 3
tap --desc "Settings"
sleep 3
$UI tap --text "Manual" --scroll --timeout 20 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte Manual i inställningarna"
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
adb logcat -d -s SncCamera:V 2>/dev/null | grep -E "HDR: gain|HDR/Natt: sparad" | tail -1 | while IFS= read -r l; do
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

step "QR-kod i en delad bild"
if python3 -c "import qrcode" 2> /dev/null; then
  python3 -c "import qrcode; qrcode.make('https://example.com/snc-camera-test').save('$OUT/qr-testbild.png')"
  adb push "$OUT/qr-testbild.png" "$DIR/QR_TEST.png" >> "$OUT/ui.log" 2>&1
  sleep 4
  QID=$(adb shell "content query --uri content://media/external/images/media --projection _id:_display_name" \
    | grep "QR_TEST.png" | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2)
  if [ -n "$QID" ]; then
    adb shell am start -a android.intent.action.SEND -t image/png \
      --eu android.intent.extra.STREAM "content://media/external/images/media/$QID" \
      -n "$PKG/.MainActivity" >> "$OUT/ui.log" 2>&1
    sleep 10
    shot 23c-qr-i-bild
    check "QR-koden i bilden läses (länk)" $UI exists --text "https://example.com/snc-camera-test" --timeout 15
    check "Kortet har knappen Öppna länk" $UI exists --text "Open link"
    key KEYCODE_BACK
    sleep 3
  else
    bad "QR-testbilden hittades inte i MediaStore"
  fi
  adb shell rm -f "$DIR/QR_TEST.png"
else
  log "  qrcode saknas – steget hoppas över"
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
# Bakkamerans rubrik kommer direkt efter sammanfattningen – leta innan sidan rullas långt ned
# (--scroll rullar bara nedåt)
check "Kamerasidan visar bakkameran" $UI exists --contains "BACK CAMERA" --scroll --timeout 25
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1); H=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 5)) 400; sleep 1
shot 34-kameror-linser
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
