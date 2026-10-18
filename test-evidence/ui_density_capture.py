"""ADB UI inspection/capture helper. Never accesses credentials or clears app data."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
ADB = "C:/Dev/Env/Android/Sdk/platform-tools/adb.exe"
OUT = Path(__file__).parent

def adb(*args):
    return subprocess.check_output([ADB, "-s", "emulator-5554", *args])

def nodes():
    for _ in range(3):
        result = adb("shell", "uiautomator", "dump", "/sdcard/cet6-ui.xml")
        if b"dumped to" in result:
            return list(ET.fromstring(adb("shell", "cat", "/sdcard/cet6-ui.xml")).iter("node"))
    raise RuntimeError("No fresh UI hierarchy; refusing to use stale nodes")

def dump():
    for n in nodes():
        if n.get("text"):
            print(n.get("text"), n.get("bounds"))

def tap(text):
    matches = [n for n in nodes() if n.get("text") == text]
    assert len(matches) == 1, (text, len(matches))
    n = matches[0]
    assert n.get("enabled") == "true", text
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

def capture(name):
    nodes()  # Wait for an idle, fresh hierarchy before capturing IME/layout transitions.
    adb("shell", "screencap", "-p", "/sdcard/cet6-shot.png")
    adb("pull", "/sdcard/cet6-shot.png", str(OUT / f"ui-density-{name}.png"))

if __name__ == "__main__":
    if sys.argv[1] == "tap": tap(sys.argv[2])
    elif sys.argv[1] == "capture": capture(sys.argv[2])
    else: dump()
