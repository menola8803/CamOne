#!/usr/bin/env python3
"""Telefonens APK får bara innehålla arm64-kod (OnePlus 13R). Stoppar bygget om det finns x86- eller
annan kod: både mappnamnen under lib/ och själva ELF-filerna kontrolleras."""
import struct
import sys
import zipfile

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
bad = []
libs = 0
for info in z.infolist():
    name = info.filename
    if name.startswith("lib/"):
        abi = name.split("/")[1]
        if abi != "arm64-v8a":
            bad.append(f"{name} (mapp {abi})")
    with z.open(info) as f:
        head = f.read(20)
    if head[:4] == b"\x7fELF":
        libs += 1
        machine = struct.unpack("<H", head[18:20])[0]
        if machine != 183:  # EM_AARCH64
            arch = {3: "x86", 62: "x86_64", 40: "arm"}.get(machine, f"maskin {machine}")
            bad.append(f"{name} ({arch})")
if bad:
    for b in bad:
        print(f"::error title=Arkitektur::{b} – telefonens APK får bara innehålla arm64-kod")
    sys.exit(1)
print(f"::notice title=Arkitektur::OK – {libs} inbyggda bibliotek, alla arm64, ingen x86-kod")
