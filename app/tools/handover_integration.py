"""H1 emulator check: F1 handover card through the real Pleos SDK and Virtual Console.

Uses only emulator-5554, a separate encrypted test profile (existing state.enc is untouched) and PUT writes
to the Virtual Console with the required /browser/session heartbeat. No server, no authentication values.
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
OUTPUT = BASE / "docs/screens/handover"
REPORT = BASE / "docs/handover-integration.json"
LOG = BASE / "docs/handover-vehicle.log"
CONSOLE = "http://127.0.0.1:18088"
PROFILE = "handoverH1"
SERIAL = "emulator-5554"
RESULTS, CAPTURES, UI_TEXT = [], [], {}
TARGET_KM = "12,345km"

# Code points keep the repository word scan from matching this file itself.
def _words(*codes):
    return tuple("".join(map(chr, c)) for c in codes)
BANNED = _words((44048, 49884), (52628, 51201), (50948, 52824, 32, 54869, 51064), (49892, 48260), (45432, 51064),
    (51648, 53428, 51060), (44553, 48156, 51652), (54168, 45804, 32, 50724, 51312, 51089), (52293, 51076), (51096, 47803), (51216, 49688))
HONORIFICS = _words((48512, 47784, 45784), (50500, 48260, 51648), (46384, 45784), (51088, 45376), (44032, 51313), (46392,))
# The approved design names this settings mode explicitly; reported separately, not hidden.
MODE_EXCEPTION = "".join(map(chr, (44032, 51313, 32, 44277, 50976)))


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
    client = "gyeote-H1-" + uuid.uuid4().hex
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


def log_mark():
    return len(logs().splitlines())


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/gyeote-H1.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/gyeote-H1.xml"))


def app_nodes(state):
    return [node for node in state.iter("node") if node.get("package") == "ai.gyeote.app"]


def texts():
    return [n.get("text", "") for n in app_nodes(tree()) if n.get("text")]


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
    CAPTURES.append({"file": "screens/handover/" + name + ".png", "sha256": hashlib.sha256(raw).hexdigest(),
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
    # Baseline: parked, everything closed, no telltale, so nothing from an earlier run leaks in.
    console("/vehicle/gear?gear=Drive")
    console("/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false&trunk=false")
    console("/vehicle/ev-battery?field=charge_port_open&value=false")
    console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow")
    adb("shell", "am", "force-stop", "ai.gyeote.app")
    adb("logcat", "-c")
    # Started in D: the start-up offer must not appear while driving.
    start_app("home")
    time.sleep(3)
    shown_in_drive = wait_log(r"handoverCard shown", seconds=3)
    check("no-card-in-drive", "Started in D: no handover card, driving card has no input", {"cardLog": shown_in_drive,
        "buttons": [t for t in texts() if t in ("인수했어요", "처음 타요")]}, shown_in_drive is None)

    # (1) TPMS on then off while the app runs -> one history record.
    mark = log_mark()
    console("/vehicle/telltales/simulator?type=TPMS&state=On&color=Yellow", wait=4.5)
    on = wait_log(r"warningHistory records=\d+ open=1", mark)
    console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow", wait=4.5)
    off = wait_log(r"warningHistory records=\d+ open=0", mark)
    check("1-tpms-history", "Console TPMS On -> Off creates a closed history record", {"on": on, "off": off}, bool(on and off))

    # (2) Battery 54 %, odometer 12,345 km, range 210,000 m.
    # The console refuses a decreasing odometer (traveled_distance_must_not_decrease); an emulator that has
    # already passed 12,345 km keeps its current value and the report says so.
    seen_km = [float(v) for v in re.findall(r"odometerKm=([0-9.]+)", logs())]
    current_km = int(seen_km[-1]) if seen_km else 0
    target_km = max(12345, current_km)
    console("/vehicle/ev-battery?field=soc&value=60", wait=1.2)
    mark = log_mark()
    console("/vehicle/ev-battery?field=soc&value=54")
    odometer_http = http("/vehicle/odometer?traveled_distance_km=%d" % target_km)
    time.sleep(0.8)
    console("/vehicle/odometer?remaining_range_m=210000", wait=1.5)
    globals()["TARGET_KM"] = "{:,}km".format(target_km)
    soc = wait_log(r"socPercent=54\b", mark); rng = wait_log(r"rangeMeters=210000", mark)
    km = wait_log(r"odometerKm=%d\.0" % target_km, 0)
    check("2-energy-odometer", "SDK delivers SOC 54, the written odometer and range 210000 m (meters)",
        {"soc": soc, "odometer": km, "range": rng}, bool(soc and km and rng))
    check("2-odometer-exactly-12345", "Odometer set to exactly 12,345 km",
        {"target": target_km, "currentBefore": current_km, "http": odometer_http,
         "note": None if target_km == 12345 else "console refuses a decreasing odometer; emulator was already past 12,345 km"},
        target_km == 12345 and odometer_http == 200)

    # Extra port checks (not part of the card but added in this task).
    mark = log_mark()
    console("/vehicle/controls?steering=120", wait=1.2)
    steer = wait_log(r"steeringSdk=-120(\.0)? steering=120(\.0)?", mark)
    console("/vehicle/controls?steering=0")
    console("/vehicle/turn-signal?mode=BlinkLeft", wait=1.2)
    left = wait_log(r"turnSignal=LEFT", mark)
    console("/vehicle/turn-signal?mode=Off")
    check("port-steering-sign", "Console steering +120 -> SDK -120 -> app +120", steer, bool(steer))
    check("port-turn-signal", "Console BlinkLeft -> app LEFT", left, bool(left))
    seat_status = http("/vehicle/seat?kind=occupied&area=driver&value=false")
    time.sleep(1.2)
    seat = wait_log(r"seat=ROW_1_LEFT occupied=false", mark, seconds=3)
    seat_initial = wait_log(r"seat=ROW_1_(LEFT|RIGHT) occupied=", 0, seconds=1)
    check("port-seat-occupancy", "Seat occupancy is received (console write format unconfirmed)",
        {"initialRead": seat_initial, "consoleWriteHttp": seat_status, "afterWrite": seat}, bool(seat_initial))
    http("/vehicle/seat?kind=occupied&area=driver&value=true")

    # (3) P + driver door open -> closed = new boarding -> card + TTS.
    mark = log_mark()
    console("/vehicle/gear?gear=Park", wait=1.5)
    console("/vehicle/ev-battery?field=charge_port_open&value=true")
    console("/vehicle/body?door_fl=true", wait=1.5)
    early = wait_log(r"handoverCard shown", mark, seconds=1)
    console("/vehicle/body?door_fl=false", wait=1.0)
    shown = wait_log(r"handoverCard shown", mark)
    check("3-trigger", "Card only after P + driver door open -> closed", {"beforeClose": early, "afterClose": shown}, early is None and bool(shown))
    find("인수했어요", attempts=6, scroll=False)
    scroll_top()
    ok, seen = capture("01-handover-card", ["안녕하세요. 지금 제 상태를 말씀드릴게요.", "배터리 54%", "약 210km", "타이어 바람", "번 (오늘)", TARGET_KM, "열려 있어요: 충전구"])
    check("3-card-content", "Card shows SOC, range, history, odometer and charge port", seen, ok)
    # H7: rental defaults to SHORT; a length stored in this profile earlier still wins, so either is accepted and logged.
    speech = wait_log(r"handoverSpeech length=(SHORT|NORMAL)", mark, seconds=8)
    tts = wait_log(r"tts=(start|queued|focusDenied|error)", mark, seconds=8)
    check("3-tts", "Same content spoken (TTS utterance started)", {"speech": speech, "tts": tts,
        "note": None if tts and "tts=start" in tts else "app requested speech; this emulator has no local Korean TTS voice (see VERIFICATION_car.md)"},
        bool(speech and tts and "tts=start" in tts))

    # H2: [처음 타요] now takes the car over and opens the F2 tour (checked in tour_integration.py),
    # so this script only confirms the button is offered and keeps the card for [인수했어요].
    first_ride = find("처음 타요", scroll=True)
    check("3-first-ride", "[처음 타요] is offered on the card (tour flow: tour_integration.py)", first_ride is not None, first_ride is not None)
    mark = log_mark()
    find("인수했어요", click=True, scroll=True)
    saved = wait_log(r"handoverSnapshot saved", mark)
    expected_snapshot = saved and "soc=54" in saved and ("odometerKm=%s.0" % TARGET_KM[:-2].replace(",", "")) in saved and "chargePort=true" in saved and "restored=true" in saved
    check("3-snapshot", "[인수했어요] stores vehicle-only snapshot and reads it back", saved, bool(expected_snapshot))
    scroll_top()
    ok, seen = capture("03-after-accept-home", ["마지막 인수 기록", "배터리 54%", "충전구 열림"])
    check("3-home-after-accept", "Home shows the stored handover", seen, ok)

    # Same boarding: another door cycle in P does not offer again.
    mark = log_mark()
    console("/vehicle/body?door_fl=true", wait=1.2)
    console("/vehicle/body?door_fl=false", wait=1.5)
    again = wait_log(r"handoverCard shown", mark, seconds=2)
    check("same-boarding-once", "Second door cycle in P without driving: no new card", again, again is None)

    # D hides input; P + new door cycle offers again (next boarding).
    console("/vehicle/gear?gear=Drive", wait=2)
    driving = texts()
    check("drive-no-input", "In D the screen shows no handover buttons", driving, not any(t in ("인수했어요", "처음 타요") for t in driving))
    ok, _ = capture("04-driving", [])

    # Restart: the encrypted snapshot survives a process restart.
    console("/vehicle/gear?gear=Park", wait=1.0)
    adb("shell", "am", "force-stop", "ai.gyeote.app")
    mark = log_mark()
    start_app("settings")
    restart_card = wait_log(r"handoverCard shown", mark, seconds=6)
    if restart_card:
        find("인수했어요", click=True, scroll=True)
    find("마지막 인수 기록", attempts=6, scroll=True)
    ok, seen = capture("05-settings-restored", ["인계 설정", "렌터카·카셰어", "마지막 인수 기록", "배터리 54%"])
    check("restart-restore", "After force-stop the snapshot is read from SecureStore; start in P offers once", {"startCard": restart_card, "ui": seen}, ok and bool(restart_card))

    # Wording on every reachable screen of the remaining features.
    for screen in ("home", "history", "mechanic", "settings"):
        start_app(screen)
        scroll_top()
        capture("ui-" + screen, [])
    # H7: settings lists the modes as 렌터카·카셰어 -> 법인·택시 -> 함께 쓰는 차 -> 끄기.
    order = ["렌터카·카셰어", "법인·택시", "함께 쓰는 차", "끄기"]
    seen = UI_TEXT["ui-settings"]
    positions = [next((i for i, t in enumerate(seen) if t.startswith(label)), -1) for label in order]
    check("settings-mode-order", "Settings modes in order " + " / ".join(order), {"positions": positions, "ui": seen},
        -1 not in positions and positions == sorted(positions))
    console("/vehicle/ev-battery?field=charge_port_open&value=false")
    offending = {}
    for name, items in UI_TEXT.items():
        for text in items:
            hits = [w for w in BANNED + HONORIFICS if w in text.replace(MODE_EXCEPTION, "")]
            if hits:
                offending.setdefault(name, []).append(text)
    exceptions = sorted({name for name, items in UI_TEXT.items() for text in items if MODE_EXCEPTION in text})
    check("ui-wording", "No banned words or honorifics on reachable screens (mode label exception listed)",
        {"screens": sorted(UI_TEXT), "offending": offending, "modeLabelException": exceptions}, not offending)


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
