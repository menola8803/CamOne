#!/usr/bin/env bash
# Kör Gradle och gör om kompileringsfel till anteckningar i GitHub Actions (fil, rad och meddelande),
# så att felen syns utan att man behöver ladda ner loggen.
set -uo pipefail
LOG="${RUNNER_TEMP:-/tmp}/gradle-$$.log"
./gradlew --no-daemon "$@" 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  # Kotlin: "e: file:///…/app/src/…/X.kt:12:34 meddelande"
  re='^file://[^ ]*/(app/[^:]+):([0-9]+):([0-9]+) (.*)$'
  grep -E '^e: ' "$LOG" | head -40 | while IFS= read -r line; do
    msg="${line#e: }"
    if [[ "$msg" =~ $re ]]; then
      echo "::error file=${BASH_REMATCH[1]},line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]}::${BASH_REMATCH[4]}"
    else
      echo "::error::${msg}"
    fi
  done
  # Samlat: alla felrader i en anteckning (GitHub visar högst tio anteckningar per steg)
  summary=$(grep -E '^e: |ERROR:|error:|What went wrong' -A1 "$LOG" | grep -v '^--$' | head -60 \
    | sed -E 's#file://[^ ]*/(app/)#\1#' | sed ':a;N;$!ba;s/%/%25/g;s/\r//g;s/\n/%0A/g')
  [ -n "$summary" ] && echo "::error title=Gradle-fel (samlat)::${summary}"
fi
exit "$status"
