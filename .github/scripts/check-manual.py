#!/usr/bin/env python3
"""Varje version: manualen (assets/manual.json) ska ha appens version och samma avsnitt och punkter
på svenska och engelska. Körs i bygget innan APK:n byggs – bygget stoppas annars, som i Alif."""
import json
import re
import sys

ok = True


def bad(msg):
    global ok
    ok = False
    print(f"::error file=app/src/main/assets/manual.json::{msg}")


props = open("gradle.properties", encoding="utf-8").read()
m = re.search(r"^sncAppVersionName=(.+)$", props, re.M)
version = m.group(1).strip() if m else None

try:
    manual = json.load(open("app/src/main/assets/manual.json", encoding="utf-8"))
except Exception as e:  # noqa: BLE001
    bad(f"manual.json går inte att läsa: {e}")
    sys.exit(1)

if manual.get("version") != version:
    bad(f"Manualens version {manual.get('version')} ≠ appens version {version} – uppdatera manualen (sv + en)")

sv, en = manual.get("sv", []), manual.get("en", [])
if not sv or not en:
    bad("Manualen saknar svenska eller engelska avsnitt")
elif len(sv) != len(en):
    bad(f"Olika antal avsnitt: svenska {len(sv)}, engelska {len(en)}")
else:
    for i, (a, b) in enumerate(zip(sv, en)):
        if not a.get("title") or not b.get("title"):
            bad(f"Avsnitt {i + 1} saknar rubrik")
        if len(a.get("items", [])) != len(b.get("items", [])):
            bad(f"Avsnitt {i + 1} ({a.get('title')} / {b.get('title')}): olika antal punkter")
        for item in a.get("items", []) + b.get("items", []):
            if not item.strip():
                bad(f"Avsnitt {i + 1}: tom punkt")

if ok:
    print(f"Manualen: version {version}, {len(sv)} avsnitt på svenska och engelska – OK")
sys.exit(0 if ok else 1)
