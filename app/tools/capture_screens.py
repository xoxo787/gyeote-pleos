"""Capture installed debug app via ADB without text-mode binary corruption."""
import argparse
import hashlib
import json
import os
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    command = [str(adb), "-s", args.serial]
    output = Path(__file__).resolve().parents[1] / "docs/screens"
    output.mkdir(parents=True, exist_ok=True)

    def run(*parts):
        return subprocess.run(command + list(parts), capture_output=True, check=True, timeout=40).stdout

    cases = [
        ("01-home", "home", False, None, "오늘의 동행"),
        ("02-consent", "consent", False, None, "내가 고른 소식만"),
        ("03-warning", "warning", False, "TPMS", "무슨 뜻인가요"),
        ("04-driving", "home", True, None, "타이어 공기압이 낮을 수 있어요"),
        ("05-console", "console", False, None, "모의 콘솔"),
    ]
    captures = []
    for filename, screen, restricted, warning, expected in cases:
        parts = ["shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "screen", screen,
                 "--ez", "restricted", str(restricted).lower(), "--es", "vehicleMode", "fake"]
        if warning:
            parts += ["--es", "warning", warning]
        run(*parts)
        time.sleep(3 if filename == "01-home" else 1.3)
        remote = f"/sdcard/gyeote-{filename}.xml"
        run("shell", "uiautomator", "dump", remote)
        hierarchy = run("exec-out", "cat", remote)
        tree = ET.fromstring(hierarchy)
        texts = " ".join(node.attrib.get("text", "") for node in tree.iter("node"))
        assert expected in texts, (filename, "expected screen was not visible")
        if restricted:
            app_nodes = [node for node in tree.iter("node") if node.attrib.get("package") == "ai.gyeote.app"]
            assert not any(node.attrib.get("clickable") == "true" for node in app_nodes), "Driving screen contains input"
            assert not any(node.attrib.get("class", "").endswith(("Button", "EditText")) for node in app_nodes)
        time.sleep(0.5)
        image = run("exec-out", "screencap", "-p")
        assert image.startswith(b"\x89PNG\r\n\x1a\n"), "ADB returned invalid PNG"
        (output / f"{filename}.png").write_bytes(image)
        (output / f"{filename}.xml").write_bytes(hierarchy)
        captures.append({"file": f"{filename}.png", "sha256": hashlib.sha256(image).hexdigest(),
                         "bytes": len(image), "expected_visible": True, "restricted": restricted})
        print(f"PASS {filename} ({len(image)} bytes)")
    (output / "captures.json").write_text(json.dumps({
        "at": datetime.now(timezone.utc).isoformat(), "serial": args.serial,
        "capture_command": "adb exec-out screencap -p", "captures": captures,
        "apk_sha256": hashlib.sha256((Path(__file__).resolve().parents[1] / "build/outputs/apk/debug/app-debug.apk").read_bytes()).hexdigest(),
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
