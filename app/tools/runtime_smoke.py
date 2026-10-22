"""Exercise visible debug controls on the phone AVD and save only synthetic results."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--report-path", type=Path)
    args = parser.parse_args()
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    command = [str(adb), "-s", args.serial]
    results = []
    profile = "smoke-" + uuid.uuid4().hex[:16]

    def run(*parts):
        return subprocess.run(command + list(parts), check=True, capture_output=True, timeout=40).stdout

    def tree():
        run("shell", "uiautomator", "dump", "/sdcard/gyeote-smoke.xml")
        return ET.fromstring(run("exec-out", "cat", "/sdcard/gyeote-smoke.xml"))

    def bounds(node):
        return list(map(int, re.findall(r"\d+", node.attrib["bounds"])))

    def find(fragment, click=False):
        for _ in range(9):
            state = tree()
            for node in state.iter("node"):
                if node.attrib.get("package") != "ai.gyeote.app":
                    continue
                if fragment in node.attrib.get("text", "") and (not click or node.attrib.get("clickable") == "true"):
                    if click:
                        left, top, right, bottom = bounds(node)
                        run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                        time.sleep(0.6)
                    return
            scrolling = next(node for node in state.iter("node") if node.attrib.get("class") == "android.widget.ScrollView")
            left, top, right, bottom = bounds(scrolling)
            center = (left + right) // 2
            run("shell", "input", "swipe", str(center), str(top + (bottom - top) * 4 // 5),
                str(center), str(top + (bottom - top) // 5), "400")
            time.sleep(0.4)
        raise AssertionError(f"Visible control or text missing: {fragment}")

    def tap(fragment):
        find(fragment, click=True)

    def passed(name, expected):
        find(expected)
        results.append({"scenario": name, "expected": expected, "observed": True, "passed": True})
        print(f"PASS {name}", flush=True)

    run("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "screen", "console", "--ez", "restricted", "false", "--es", "vehicleMode", "fake", "--es", "testProfile", profile)
    time.sleep(1)
    find("문 열림")
    tap("문 닫고 잠그기 / 문 열기")
    time.sleep(2.2)
    tap("브레이크 밟기 / 놓기")
    tap("주행거리 +1 km")
    tap("오늘")
    passed("departure-from-console-signals", "출발했어요. 편안하게 다녀오세요.")

    tap("모의")
    tap("내비 도착 콜백")
    tap("오늘")
    passed("navigation-arrival-callback", "잘 다녀오셨어요. 오늘도 반가워요.")

    tap("모의")
    tap("모의 목적지 받기")
    tap("이 문장을 들려주기")
    passed("korean-acceptance-to-fake-route", "모의 길안내를 요청했어요.")
    tap("받은 목적지 보기")
    passed("accepted-destination-removed-from-inbox", "아직 기다리는 길이 없어요")
    run("shell", "input", "keyevent", "4")
    time.sleep(0.8)
    passed("android-36-back-dispatch", "오늘의 동행")

    tap("모의")
    tap("시연 정비 기록")
    tap("오늘")
    tap("경고 기록")
    passed("maintenance-record-visible", "참고:")

    run("shell", "am", "force-stop", "ai.gyeote.app")
    run("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "screen", "console", "--es", "vehicleMode", "fake", "--es", "testProfile", profile)
    time.sleep(2)
    passed("record-survives-process-restart", "저장한 엔진오일 2000 km")
    output = args.report_path or Path(__file__).resolve().parents[1] / "docs/runtime-smoke.json"
    output.write_text(json.dumps({"serial": args.serial, "results": results}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
