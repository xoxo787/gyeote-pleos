"""Inspect the actual release UI using a locally debug-signed copy; retain all app data."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from car_integration import adb
from edge_integration import find, tree, nodes

BASE = Path(__file__).resolve().parents[1]
SDK = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
OUT = BASE / "docs/screens/polish"


def main():
    data = Path(tempfile.mkdtemp(prefix="gyeote-polish-release-"))
    unsigned = BASE / "build/outputs/apk/release/app-release-unsigned.apk"
    signed = data / "app-release-local-check.apk"
    java = Path("C:/Program Files/Android/Android Studio/jbr/bin/java.exe")
    subprocess.run([str(java), "-jar", str(SDK / "build-tools/36.1.0/lib/apksigner.jar"), "sign",
        "--ks", str(Path.home() / ".android/debug.keystore"), "--ks-key-alias", "androiddebugkey",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", str(signed), str(unsigned)],
        check=True, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
    results = []
    report = {}
    with ZipFile(unsigned) as before, ZipFile(signed) as after:
        unchanged = all(before.read(name) == after.read(name) for name in before.namelist() if not name.startswith("META-INF/"))
    assert unchanged, "Local signing changed the release payload"
    results.append(dict(scenario="release-payload-preserved", expected="Local signing preserves all unsigned release payload entries",
        observed=dict(payload_equal=unchanged), passed=unchanged))
    try:
        adb("shell", "am", "force-stop", "ai.gyeote.app")
        adb("install", "-r", str(signed))
        adb("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity")
        find("설정", click=True)
        texts, captures = [], []
        OUT.mkdir(parents=True, exist_ok=True)
        for index in range(4):
            state = tree()
            text = "\n".join(node.get("text", "") for node in nodes(state))
            assert not re.search(r"연결 번호\s+\d{6}", text)
            name = "release-settings-" + str(index + 1) + ".xml"
            (OUT / name).write_bytes(ET.tostring(state, encoding="utf-8"))
            captures.append(name)
            texts.append(text)
            if "가족과 이어지기" in text:
                break
            container = next(node for node in nodes(state) if node.get("class") == "android.widget.ScrollView")
            left, top, right, bottom = map(int, re.findall(r"\d+", container.get("bounds")))
            adb("shell", "input", "swipe", str((left + right) // 2), str(bottom - 80),
                str((left + right) // 2), str(top + 80), "350")
            time.sleep(0.5)
        observed = "\n".join(texts)
        debug_tabs = sum(line == "모의" for line in observed.splitlines())
        missing = not any(fragment in observed for fragment in ("서버 주소", "개발 연결 설정")) and debug_tabs == 0
        complete = "내가 고른 소식만" in observed and "가족과 이어지기" in observed
        results.append(dict(scenario="release-settings-no-editor", expected="Actual release settings from top to family card has no server editor or debug tab",
            observed=dict(server_editor_matches=observed.count("서버 주소"), debug_matches=debug_tabs,
                full_settings=complete, xml=captures), passed=missing and complete))
        adb("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "screen", "console",
            "--es", "serverUrl", "http://10.0.2.2:8787")
        state = tree()
        text = "\n".join(node.get("text", "") for node in nodes(state))
        (OUT / "release-debug-intent-ignored.xml").write_bytes(ET.tostring(state, encoding="utf-8"))
        results.append(dict(scenario="release-debug-intent-ignored", expected="Debug screen/server URL intents cannot reveal developer UI in release",
            observed=dict(developer_ui=any(part in text for part in ("개발 연결 설정", "모의 콘솔", "서버 주소"))),
            passed=not any(part in text for part in ("개발 연결 설정", "모의 콘솔", "서버 주소"))))
        # FLAG_SECURE remains enabled. The real view hierarchy provides the UI evidence.
        report = dict(release_apk_sha256=hashlib.sha256(unsigned.read_bytes()).hexdigest(),
            locally_signed_apk_sha256=hashlib.sha256(signed.read_bytes()).hexdigest(),
            local_signed_apk=str(signed), secure_capture_policy_preserved=True, results=results)
        (BASE / "docs/polish-release-ui.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for result in results:
            print(("PASS " if result["passed"] else "FAIL ") + result["scenario"], flush=True)
        assert all(result["passed"] for result in results)
    finally:
        adb("shell", "am", "force-stop", "ai.gyeote.app")
        adb("install", "-r", str(BASE / "build/outputs/apk/debug/app-debug.apk"))
        adb("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "vehicleMode", "pleos")
        package = adb("shell", "dumpsys", "package", "ai.gyeote.app").decode("utf-8", errors="replace")
        report["debug_restored"] = "DEBUGGABLE" in package
        (BASE / "docs/polish-release-ui.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        assert report["debug_restored"], "Debug APK restoration not confirmed"


if __name__ == "__main__":
    main()
