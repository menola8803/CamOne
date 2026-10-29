#!/usr/bin/env python3
"""Små UI-hjälpare för röktestet i emulatorn (uiautomator via adb).

  ui.py tap --text "Edit"            tryck på ett element med exakt text
  ui.py tap --desc "Switch camera"   tryck på ett element med innehållsbeskrivning
  ui.py exists --contains "SNC"      finns det något som innehåller texten?
  ui.py dump                         skriv ut allt som syns på skärmen
  ui.py text-image fil.png           skapa en testbild med text (för textigenkänning)
"""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args, timeout=60):
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)


def screen_size():
    out = adb("shell", "wm", "size").stdout
    sizes = re.findall(r"(\d+)x(\d+)", out)
    if not sizes:
        return 1080, 2400
    w, h = sizes[-1]
    return int(w), int(h)


def dump():
    for _ in range(4):
        r = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        if "dumped" in (r.stdout + r.stderr).lower():
            xml = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
            try:
                return ET.fromstring(xml)
            except ET.ParseError:
                pass
        time.sleep(1)
    return None


def bounds(node):
    nums = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
    return nums if len(nums) == 4 else None


def visible(node, w, h):
    b = bounds(node)
    if not b:
        return False
    x1, y1, x2, y2 = b
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    return x2 > x1 and y2 > y1 and 0 <= cx < w and 0 <= cy < h


def find(root, args, w, h):
    for n in root.iter("node"):
        t = n.get("text", "") or ""
        d = n.get("content-desc", "") or ""
        hit = (
            (args.text is not None and t == args.text)
            or (args.desc is not None and d == args.desc)
            or (args.contains is not None and (args.contains in t or args.contains in d))
        )
        if hit and visible(n, w, h):
            return n
    return None


def describe(root):
    items = []
    if root is None:
        return "(uiautomator dump misslyckades)"
    for n in root.iter("node"):
        t = n.get("text", "")
        d = n.get("content-desc", "")
        if t or d:
            items.append(f"text={t!r} desc={d!r} bounds={n.get('bounds')}")
    return "\n".join(items)


def wait_for(args):
    w, h = screen_size()
    deadline = time.time() + args.timeout
    root = None
    attempt = 0
    while True:
        root = dump()
        if root is not None:
            node = find(root, args, w, h)
            if node is not None:
                return node, root
        if time.time() > deadline:
            return None, root
        attempt += 1
        if args.scroll:
            adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.3)), "300")
            time.sleep(0.8)
        else:
            time.sleep(1)


def target(args):
    return args.text if args.text is not None else args.desc if args.desc is not None else args.contains


def main():
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["tap", "exists", "dump", "text-image"])
    p.add_argument("path", nargs="?")
    p.add_argument("--text")
    p.add_argument("--desc")
    p.add_argument("--contains")
    p.add_argument("--timeout", type=float, default=8)
    p.add_argument("--scroll", action="store_true")
    args = p.parse_args()

    if args.cmd == "dump":
        print(describe(dump()))
        return 0

    if args.cmd == "text-image":
        from PIL import Image, ImageDraw, ImageFont
        img = Image.new("RGB", (1500, 1100), "white")
        draw = ImageDraw.Draw(img)
        try:
            font = ImageFont.truetype("DejaVuSans.ttf", 76)
        except OSError:
            font = ImageFont.load_default(size=76)
        lines = ["Hello from SNC Camera", "www.example.com", "+46 70 123 45 67", "Developer: Claes Sundman"]
        for i, line in enumerate(lines):
            draw.text((90, 140 + i * 220), line, fill="black", font=font)
        img.save(args.path)
        return 0

    node, root = wait_for(args)
    if node is None:
        print(f"[ui] HITTADE INTE: {target(args)!r}. Synligt på skärmen:\n{describe(root)}\n")
        return 1
    if args.cmd == "tap":
        x1, y1, x2, y2 = bounds(node)
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        print(f"[ui] tryckte på {target(args)!r} vid {node.get('bounds')}")
    else:
        print(f"[ui] hittade {target(args)!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
