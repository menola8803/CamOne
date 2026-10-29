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

step "Kamera"
adb shell am start -W -n "$PKG/.MainActivity" >> "$OUT/ui.log" 2>&1
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
for n in 1 2 3; do
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >> "$OUT/ui.log" 2>&1
  sleep 7
  before=$(count '\.jpg')
  key KEYCODE_VOLUME_UP
  sleep 5
  after=$(count '\.jpg')
  check "Omstart $n: foto sparas ($before → $after)" [ "$after" -gt "$before" ]
done
shot 02b-efter-omstarter

step "Bildformat, rutnät och självutlösare"
tap --desc "Aspect ratio"; sleep 3; shot 03-format-1-1
tap --desc "Aspect ratio"; sleep 3; shot 04-format-16-9
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
before=$(count '\.mp4')
key KEYCODE_VOLUME_UP
sleep 6
shot 14-spelar-in
key KEYCODE_VOLUME_UP
sleep 6
after=$(count '\.mp4')
check "Video spelas in och sparas ($before → $after)" [ "$after" -gt "$before" ]

step "Natt, Pro och Porträtt"
tap --text "NIGHT"
sleep 5
shot 15-natt
before=$(count '\.jpg')
key KEYCODE_VOLUME_UP
sleep 8
after=$(count '\.jpg')
check "Nattbild sparas ($before → $after)" [ "$after" -gt "$before" ]
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
$UI tap --text "About" --scroll --timeout 15 >> "$OUT/ui.log" 2>&1 || bad "Hittade inte Om-raden"
sleep 3
shot 22-om
check "Om visar Developer" $UI exists --text "Developer"
check "Om visar Claes Sundman S.N.C AB" $UI exists --text "Claes Sundman S.N.C AB"
key KEYCODE_BACK
sleep 2
key KEYCODE_BACK
sleep 4

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
