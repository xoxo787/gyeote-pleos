"""H2 emulator check: F2 first-ride tour through the real Pleos SDK and Virtual Console.

Modeled on handover_integration.py. Uses only emulator-5554, a separate encrypted test profile (existing state.enc
is untouched) and PUT writes to the Virtual Console with the required /browser/session heartbeat.
No server, no authentication values. The odometer is never written (it cannot go down).
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET


BASE = Path(__file__).resolve().parents[1]
OUTPUT = BASE / "docs/screens/tour"
REPORT = BASE / "docs/tour-integration.json"
LOG = BASE / "docs/tour-vehicle.log"
CONSOLE = "http://127.0.0.1:18088"
PROFILE = "tourH2"
SERIAL = "emulator-5554"
RESULTS, CAPTURES, UI_TEXT = [], [], {}

# Code points keep the repository word scan from matching this file itself (same lists as handover_integration.py).
def _words(*codes):
    return tuple("".join(map(chr, c)) for c in codes)
BANNED = _words((44048, 49884), (52628, 51201), (50948, 52824, 32, 54869, 51064), (49892, 48260), (45432, 51064),
    (51648, 53428, 51060), (44553, 48156, 51652), (54168, 45804, 32, 50724, 51312, 51089), (52293, 51076), (51096, 47803), (51216, 49688))
HONORIFICS = _words((48512, 47784, 45784), (50500, 48260, 51648), (46384, 45784), (51088, 45376), (44032, 51313), (46392,))
MODE_EXCEPTION = "".join(map(chr, (44032, 51313, 32, 44277, 50976)))
# Text that only the tour screen shows.
TOUR_MARKERS = ("처음 3분 투어 · 정차 중", "건너뛰기", "준비 끝났어요", "핸들을 왼쪽 끝까지", "꾹 밟아 보세요", "켜 보세요")


def adb(*parts, timeout=40):
    executable = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    return subprocess.run([str(executable), "-s", SERIAL, *parts], check=True, capture_output=True, timeout=timeout).stdout


def http(path, method="PUT"):
    request = urllib.request.Request(CONSOLE + path, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        response.read()
        return response.status


@contextmanager
def console_session():
    client = "gyeote-H2-" + uuid.uuid4().hex
    done = threading.Event()
    def heartbeat():
        while not done.is_set():
            try:
                http("/browser/session?client=" + client, "POST")
            except (OSError, urllib.error.URLError):
                pass
            done.wait(0.8)
    worker = threading.Thread(target=heartbeat, daemon=True)
    worker.start()
    try:
        time.sleep(3)
        yield
    finally:
        done.set()
        worker.join(timeout=3)
        try:
            http("/browser/session?client=" + client + "&closing=1", "POST")
        except (OSError, urllib.error.URLError):
            pass


def console(path, wait=0.8):
    status = http(path)
    if status != 200:
        raise AssertionError("Console rejected " + path + ": " + str(status))
    time.sleep(wait)


def logs():
    return adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")


def wait_log(pattern, since=0, seconds=12):
    end = time.time() + seconds
    while time.time() < end:
        lines = logs().splitlines()[since:]
        hits = [line for line in lines if re.search(pattern, line)]
        if hits:
            return hits[-1]
        time.sleep(0.5)
    return None


def all_logs(pattern, since=0):
    return [line for line in logs().splitlines()[since:] if re.search(pattern, line)]


def log_mark():
    return len(logs().splitlines())


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/gyeote-H2.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/gyeote-H2.xml"))


def app_nodes(state):
    return [node for node in state.iter("node") if node.get("package") == "ai.gyeote.app"]


def texts():
    return [n.get("text", "") for n in app_nodes(tree()) if n.get("text")]


def tour_texts(seen):
    return [t for t in seen if any(marker in t for marker in TOUR_MARKERS)]


def find(fragment, click=False, attempts=10, scroll=True):
    for _ in range(attempts):
        nodes = app_nodes(tree())
        for node in nodes:
            if fragment in node.get("text", "") and (not click or node.get("clickable") == "true"):
                if click:
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    time.sleep(0.8)
                return node
        if scroll:
            target = next((n for n in nodes if n.get("class") == "android.widget.ScrollView"), None)
            if target is not None:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.get("bounds")))
                adb("shell", "input", "swipe", str((x1 + x2) // 2), str(y1 + (y2 - y1) * 4 // 5), str((x1 + x2) // 2), str(y1 + (y2 - y1) // 5), "350")
        time.sleep(0.6)
    return None


def wait_text(fragment, seconds=8):
    end = time.time() + seconds
    while time.time() < end:
        if any(fragment in t for t in texts()):
            return True
        time.sleep(0.4)
    return False


def scroll_top():
    for _ in range(3):
        adb("shell", "input", "swipe", "1400", "500", "1400", "1300", "250")
    time.sleep(0.5)


def capture(name, expected):
    state = tree()
    nodes = app_nodes(state)
    seen = [n.get("text", "") for n in nodes if n.get("text")]
    ok = all(any(fragment in text for text in seen) for fragment in expected)
    raw = adb("exec-out", "screencap", "-p")
    assert raw.startswith(b"\x89PNG\r\n\x1a\n")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / (name + ".png")).write_bytes(raw)
    (OUTPUT / (name + ".xml")).write_bytes(ET.tostring(state, encoding="utf-8"))
    UI_TEXT[name] = seen
    CAPTURES.append({"file": "screens/tour/" + name + ".png", "sha256": hashlib.sha256(raw).hexdigest(),
        "expected_text": expected, "passed": ok})
    return ok, seen


def check(name, expected, observed, passed):
    RESULTS.append({"scenario": name, "expected": expected, "observed": observed, "passed": bool(passed)})
    print(("PASS " if passed else "FAIL ") + name, flush=True)
    save()


def save():
    apk = BASE / "build/outputs/apk/debug/app-debug.apk"
    REPORT.write_text(json.dumps({"at": datetime.now(timezone.utc).isoformat(), "serial": SERIAL, "testProfile": PROFILE,
        "results": RESULTS, "captures": CAPTURES,
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest() if apk.exists() else None}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def start_app(screen=None):
    args = ["shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "vehicleMode", "pleos", "--es", "testProfile", PROFILE]
    if screen:
        args += ["--es", "screen", screen]
    adb(*args)
    time.sleep(1.5)


def scenario():
    # Baseline: driving, doors closed, wheel centred, brake released, signal off, no telltale.
    console("/vehicle/gear?gear=Drive")
    console("/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false&trunk=false")
    console("/vehicle/controls?steering=0")
    console("/vehicle/controls?brake=0")
    console("/vehicle/turn-signal?mode=Off")
    console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow")
    adb("shell", "am", "force-stop", "ai.gyeote.app")
    adb("logcat", "-c")
    start_app("home")
    time.sleep(2)
    seen = texts()
    check("d-start-no-tour", "Started in D: no tour log, no tour text", {"tourLog": all_logs(r"tour started"), "tourText": tour_texts(seen)},
        not all_logs(r"tour started") and not tour_texts(seen))

    # Boarding in P: driver door open -> closed -> handover card with [처음 타요].
    mark = log_mark()
    console("/vehicle/gear?gear=Park", wait=1.5)
    console("/vehicle/body?door_fl=true", wait=1.2)
    console("/vehicle/body?door_fl=false", wait=1.2)
    card = wait_log(r"handoverCard shown", mark)
    first_ride = find("처음 타요", attempts=6, scroll=True)
    check("card-offers-first-ride", "Handover card appears with [처음 타요]", {"card": card, "button": first_ride is not None}, bool(card and first_ride is not None))

    # [처음 타요] -> same vehicle-only snapshot as [인수했어요], then mission 1.
    mark = log_mark()
    find("처음 타요", click=True, scroll=True)
    started = wait_log(r"tour started from=handover mode=RENTAL missions=STEERING\+BRAKE\+TURN_SIGNAL", mark)
    snapshot = wait_log(r"handoverSnapshot saved .*restored=true via=firstRide", mark)
    on_mission_1 = wait_text("미션 1 / 3")
    check("enter-from-card", "[처음 타요] stores the snapshot and opens mission 1 of 3", {"started": started, "snapshot": snapshot, "mission1": on_mission_1},
        bool(started and snapshot and on_mission_1))

    # Mission 1: console -450 (SDK +450, app -450) then +450 (SDK -450, app +450).
    mark = log_mark()
    console("/vehicle/controls?steering=-450", wait=1.5)
    left = wait_log(r"steeringSdk=450(\.0)? steering=-450(\.0)?", mark)
    not_yet = wait_log(r"tourMission passed=STEERING", mark, seconds=1)
    wait_text("왼쪽 끝 통과")
    scroll_top()
    ok1, seen1 = capture("01-mission1-steering", ["미션 1 / 3", "핸들을 왼쪽 끝까지", "-450°", "왼쪽 끝 통과", "오른쪽 끝 남음", "통과 0 · 남음 3", "건너뛰기"])
    check("mission1-left-only", "After -450 only: gauge shows -450°, left reached, not passed yet",
        {"log": left, "passedEarly": not_yet, "ui": seen1}, bool(left) and not_yet is None and ok1)
    console("/vehicle/controls?steering=450", wait=0.3)
    right = wait_log(r"steeringSdk=-450(\.0)? steering=450(\.0)?", mark)
    passed1 = wait_log(r"tourMission passed=STEERING by=steering next=BRAKE", mark)
    check("mission1-pass", "Calibrated -450 then +450 passes mission 1 (SDK sign reversed)", {"sdk": right, "passed": passed1}, bool(right and passed1))

    wait_text("미션 2 / 3")
    console("/vehicle/controls?steering=0", wait=0.5)
    scroll_top()
    ok2, seen2 = capture("02-mission2-brake", ["미션 2 / 3", "브레이크를", "통과 1 · 남음 2", "건너뛰기"])
    check("mission2-screen", "After the check animation, mission 2 alone", seen2, ok2)

    # Mission 2: brake.
    mark = log_mark()
    console("/vehicle/controls?brake=60", wait=0.3)
    passed2 = wait_log(r"tourMission passed=BRAKE by=brake next=TURN_SIGNAL", mark)
    check("mission2-pass", "Console brake=60 passes mission 2", {"brake": wait_log(r"brakePressed=true", mark, seconds=2), "passed": passed2}, bool(passed2))
    wait_text("미션 3 / 3")
    console("/vehicle/controls?brake=0", wait=0.5)
    scroll_top()
    ok3, seen3 = capture("03-mission3-turn-signal", ["미션 3 / 3", "방향지시등을", "통과 2 · 남음 1"])
    check("mission3-screen", "Mission 3 alone", seen3, ok3)

    # Mission 3: turn signal.
    mark = log_mark()
    console("/vehicle/turn-signal?mode=BlinkLeft", wait=0.3)
    passed3 = wait_log(r"tourMission passed=TURN_SIGNAL by=turnSignal next=null", mark)
    completed = wait_log(r"tour completed passed=3", mark)
    check("mission3-pass", "BlinkLeft passes mission 3 and completes the tour", {"passed": passed3, "completed": completed}, bool(passed3 and completed))
    wait_text("준비 끝났어요")
    console("/vehicle/turn-signal?mode=Off", wait=0.5)
    scroll_top()
    ok4, seen4 = capture("04-done", ["준비 끝났어요.", "안전 운전하세요.", "통과 3 · 남음 0", "시작하기"])
    check("done-card", "Finish card with [시작하기]", seen4, ok4)

    speech = all_logs(r"tourSpeech kind=", 0)
    tts = all_logs(r"tts=(start|queued|focusDenied|error)", 0)
    check("tts-requests", "Each pass requests a short utterance (2 x PASS, 1 x PASS+DONE); real playback not possible here",
        {"speech": speech, "tts": tts[-3:], "note": "this emulator has no local Korean TTS voice; requests only"},
        [s.split("kind=")[1] for s in speech] == ["PASS", "PASS", "PASS+DONE"])

    mark = log_mark()
    find("시작하기", click=True, scroll=True)
    closed = wait_log(r"tour closed", mark)
    home = wait_text("차를 넘겨받을 때")
    check("start-goes-home", "[시작하기] closes the tour and shows home", {"closed": closed, "home": home}, bool(closed and home))

    # D: no tour at all, even after a door cycle and a restart in D.
    mark = log_mark()
    console("/vehicle/gear?gear=Drive", wait=2)
    console("/vehicle/body?door_fl=true", wait=1.0)
    console("/vehicle/body?door_fl=false", wait=1.0)
    ok5, seen5 = capture("05-driving-no-tour", [])
    adb("shell", "am", "force-stop", "ai.gyeote.app")
    start_app("settings")
    time.sleep(2)
    restart_seen = texts()
    check("d-no-tour", "In D the tour never appears (door cycle, restart in D)",
        {"tourLog": all_logs(r"tour started", mark), "tourText": tour_texts(seen5) + tour_texts(restart_seen), "driving": seen5},
        not all_logs(r"tour started", mark) and not tour_texts(seen5) and not tour_texts(restart_seen))

    # Settings [투어 다시 하기] in P; switching to D mid-tour ends it immediately.
    console("/vehicle/gear?gear=Park", wait=2)
    # A start in P offers the handover card once; take it over so the settings screen is reachable.
    if wait_log(r"handoverCard shown", mark, seconds=4):
        find("인수했어요", click=True, scroll=True)
    start_app("settings")
    mark = log_mark()
    replay = find("투어 다시 하기", click=True, scroll=True)
    restarted = wait_log(r"tour started from=settings", mark)
    console("/vehicle/controls?steering=-450", wait=1.0)
    console("/vehicle/gear?gear=Drive", wait=2)
    ended = wait_log(r"tour ended reason=gear=DRIVE passed=0 remaining=3", mark)
    mid_d = texts()
    console("/vehicle/gear?gear=Park", wait=2)
    back_p = texts()
    console("/vehicle/controls?steering=0")
    check("replay-and-d-ends", "Settings replay starts the tour; D ends it at once and P does not bring it back",
        {"button": replay is not None, "started": restarted, "ended": ended, "tourTextInD": tour_texts(mid_d), "tourTextBackInP": tour_texts(back_p)},
        replay is not None and bool(restarted and ended) and not tour_texts(mid_d) and not tour_texts(back_p))

    # Mode OFF hides the replay button; shared-car mode offers 2 missions.
    start_app("settings")
    find("끄기", click=True, scroll=True)
    time.sleep(1)
    off_seen = texts()
    hidden = not any("투어 다시 하기" in t for t in off_seen)
    find("함께 쓰는 차", click=True, scroll=True)
    time.sleep(1)
    mark = log_mark()
    find("투어 다시 하기", click=True, scroll=True)
    family = wait_log(r"tour started from=settings mode=FAMILY missions=STEERING\+BRAKE current=STEERING", mark)
    family_ui = wait_text("미션 1 / 2")
    find("건너뛰기", click=True, scroll=True)
    skipped = wait_log(r"tour skipped passed=0 remaining=2", mark)
    skip_card = wait_text("남은 미션은 건너뛰었어요")
    find("시작하기", click=True, scroll=True)
    start_app("settings")
    find("렌터카·카셰어", click=True, scroll=True)
    restored_mode = wait_log(r"handoverSettings mode=RENTAL", mark)
    check("modes-and-skip", "OFF hides the replay button; shared-car mode has 2 missions; [건너뛰기] ends with the remaining count",
        {"offHidesButton": hidden, "family": family, "familyUi": family_ui, "skipped": skipped, "skipCard": skip_card, "backToRental": restored_mode},
        hidden and bool(family and family_ui and skipped and skip_card and restored_mode))

    # Wording on every captured tour screen.
    offending = {}
    for name, items in UI_TEXT.items():
        for text in items:
            hits = [w for w in BANNED + HONORIFICS if w in text.replace(MODE_EXCEPTION, "")]
            if hits:
                offending.setdefault(name, []).append(text)
    check("ui-wording", "No banned words or honorifics on the tour screens", {"screens": sorted(UI_TEXT), "offending": offending}, not offending)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=SERIAL)
    args = parser.parse_args()
    globals()["SERIAL"] = args.serial
    adb("forward", "tcp:18088", "tcp:8088")
    with console_session():
        try:
            scenario()
        finally:
            text = logs()
            LOG.write_text("\n".join(line for line in text.splitlines() if "GyeoteVehicle" in line) + "\n", encoding="utf-8")
            save()
    failed = [r["scenario"] for r in RESULTS if not r["passed"]]
    print("failed:", failed)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
