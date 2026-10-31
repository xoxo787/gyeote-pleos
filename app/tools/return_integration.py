"""H3 emulator check: F3 return card, handover history and fleet trip table through the real Pleos SDK and Virtual Console.

Modeled on handover_integration.py / tour_integration.py. Uses only emulator-5554, a separate encrypted test profile
(existing state.enc is untouched) and PUT writes to the Virtual Console with the required /browser/session heartbeat.
No server, no authentication values. The odometer is only ever increased from its current value (it cannot go down).

Status 2026-10-27 (H4): run on emulator-5554, 12/12 passed. First run failed 4 steps because the Pleos keyboard opens
full screen and swallowed the taps after typing the note; hide_keyboard() now closes it with BACK.
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
OUTPUT = BASE / "docs/screens/return"
REPORT = BASE / "docs/return-integration.json"
LOG = BASE / "docs/return-vehicle.log"
CONSOLE = "http://127.0.0.1:18088"
PROFILE = "returnH3"
SERIAL = "emulator-5554"
RESULTS, CAPTURES, UI_TEXT = [], [], {}
# adb "input text" cannot type Korean; the note is ASCII ("%s" is a space for adb).
NOTE = "Rear tire air low"

# Code points keep the repository word scan from matching this file itself (same lists as handover_integration.py).
def _words(*codes):
    return tuple("".join(map(chr, c)) for c in codes)
BANNED = _words((44048, 49884), (52628, 51201), (50948, 52824, 32, 54869, 51064), (49892, 48260), (45432, 51064),
    (51648, 53428, 51060), (44553, 48156, 51652), (54168, 45804, 32, 50724, 51312, 51089), (52293, 51076), (51096, 47803), (51216, 49688))
HONORIFICS = _words((48512, 47784, 45784), (50500, 48260, 51648), (46384, 45784), (51088, 45376), (44032, 51313), (46392,))
MODE_EXCEPTION = "".join(map(chr, (44032, 51313, 32, 44277, 50976)))
# Text that only the return card shows.
RETURN_MARKERS = ("이번 운행을 정리했어요.", "차를 돌려줄 때 · 정차 중", "돌려줬어요", "다음 사람에게 한마디")


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
    client = "gyeote-H3-" + uuid.uuid4().hex
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
    adb("shell", "uiautomator", "dump", "/sdcard/gyeote-H3.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/gyeote-H3.xml"))


def app_nodes(state):
    return [node for node in state.iter("node") if node.get("package") == "ai.gyeote.app"]


def texts():
    return [n.get("text", "") for n in app_nodes(tree()) if n.get("text")]


def return_texts(seen):
    return [t for t in seen if any(marker in t for marker in RETURN_MARKERS)]


def find(fragment, click=False, attempts=10, scroll=True, cls=None):
    for _ in range(attempts):
        nodes = app_nodes(tree())
        for node in nodes:
            if fragment in node.get("text", "") and (not click or node.get("clickable") == "true") and (cls is None or node.get("class") == cls):
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


def hide_keyboard():
    """The Pleos keyboard opens full screen (extract mode) and swallows taps meant for the app; close it with BACK."""
    for _ in range(3):
        shown = adb("shell", "dumpsys", "input_method").decode("utf-8", errors="replace")
        if "mInputShown=true" not in shown:
            return True
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.8)
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
    CAPTURES.append({"file": "screens/return/" + name + ".png", "sha256": hashlib.sha256(raw).hexdigest(),
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


def start_app(screen=None, extra=()):
    args = ["shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "vehicleMode", "pleos", "--es", "testProfile", PROFILE]
    if screen:
        args += ["--es", "screen", screen]
    args += list(extra)
    adb(*args)
    time.sleep(1.5)


def current_km():
    seen = [float(v) for v in re.findall(r"odometerKm=([0-9.]+)", logs())]
    return seen[-1] if seen else None


def take_over(mark):
    """P + driver door open -> closed -> handover card -> [인수했어요]. Returns the snapshot log line."""
    console("/vehicle/body?door_fl=true", wait=1.2)
    # An open takeover from an earlier run may show its return card first; [나중에] leaves no record.
    if wait_log(r"returnCard shown", mark, seconds=2):
        find("나중에", click=True, scroll=True)
    console("/vehicle/body?door_fl=false", wait=1.2)
    wait_log(r"handoverCard shown", mark)
    find("인수했어요", click=True, scroll=True)
    return wait_log(r"handoverSnapshot saved .*restored=true via=accept", mark)


def drive(add_km, soc=None, tpms=False):
    """D, then soc / odometer +add_km / TPMS on while driving. Returns the odometer written."""
    console("/vehicle/gear?gear=Drive", wait=2)
    if soc is not None:
        console("/vehicle/ev-battery?field=soc&value=%d" % soc, wait=1.0)
    start = current_km() or 0.0
    target = int(round(start)) + add_km
    console("/vehicle/odometer?traveled_distance_km=%d" % target, wait=1.5)
    if tpms:
        console("/vehicle/telltales/simulator?type=TPMS&state=On&color=Yellow", wait=4.5)
    return start, target


def scenario():
    # Baseline: driving, doors and window closed, no telltale, battery 54 %, rental mode, fresh records.
    console("/vehicle/gear?gear=Drive")
    console("/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false&trunk=false")
    console("/vehicle/body?window_fl=0")
    console("/vehicle/ev-battery?field=charge_port_open&value=false")
    console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow")
    console("/vehicle/ev-battery?field=soc&value=54", wait=1.0)
    adb("shell", "am", "force-stop", "ai.gyeote.app")
    adb("logcat", "-c")
    start_app("home", ["--ez", "clearRecords", "true"])
    cleared = wait_log(r"records cleared via=debugIntent pairs=0", 0, seconds=4)
    time.sleep(2)

    # Takeover in P (54 %).
    mark = log_mark()
    console("/vehicle/gear?gear=Park", wait=1.5)
    snapshot = take_over(mark)
    check("takeover", "Handover card -> [인수했어요] stores the takeover snapshot (soc=54)", {"cleared": cleared, "snapshot": snapshot},
        bool(snapshot and "soc=54" in snapshot))
    door_only = wait_log(r"returnCard shown", mark, seconds=1)
    check("no-drive-no-card", "Takeover without a drive: no return card", door_only, door_only is None)

    # Drive: soc 41, odometer +32, TPMS on. No return card in D, even with the driver door opening.
    mark = log_mark()
    start_km, end_km = drive(32, soc=41, tpms=True)
    console("/vehicle/body?door_fl=true", wait=1.2)
    seen_d = texts()
    ok_d, _ = capture("06-driving-no-return", [])
    console("/vehicle/body?door_fl=false", wait=1.0)
    check("d-no-return", "In D (door opened too): return card never shown, no input",
        {"log": all_logs(r"returnCard shown", mark), "text": return_texts(seen_d), "odometer": [start_km, end_km]},
        not all_logs(r"returnCard shown", mark) and not return_texts(seen_d))

    # P + driver door open -> return card: 54 -> 41 %, +32 km, 1 new warning.
    mark = log_mark()
    console("/vehicle/gear?gear=Park", wait=1.5)
    early = wait_log(r"returnCard shown", mark, seconds=1)
    console("/vehicle/body?door_fl=true", wait=1.5)
    shown = wait_log(r"returnCard shown .*soc=54->41 distanceKm=32\.0 newWarnings=1 names=TPMS", mark)
    find("돌려줬어요", attempts=6, scroll=False)
    scroll_top()
    ok1, seen1 = capture("01-return-card", ["이번 운행을 정리했어요.", "인수 때 → 지금", "54% → 41%", "+32km", "1개: 타이어 바람",
        "닫혀 있어요", "돌려줬어요", "나중에"])
    check("return-card", "P alone: nothing; P + driver door open: card with 54% -> 41%, +32km, 1 new warning",
        {"beforeDoor": early, "shown": shown, "ui": seen1}, early is None and bool(shown) and ok1)
    # H7: rental/car-share mode has no note for the next driver. Scrolled to the buttons, where the note card was.
    for _ in range(3):
        adb("shell", "input", "swipe", "1400", "1300", "1400", "500", "250")
    time.sleep(0.5)
    ok7, seen7 = capture("07-rental-no-note", ["이번 운행을 정리했어요.", "돌려줬어요", "나중에"])
    note_texts = [t for t in seen1 + seen7 if "한마디" in t]
    check("rental-no-note", "Rental mode return card: no [다음 사람에게 한마디] card or button", {"noteTexts": note_texts, "ui": seen7},
        ok7 and not note_texts)
    window = wait_log(r"windowOpen=", 0, seconds=1)
    check("window-read", "Window line appears only when windows are reported (CAR_WINDOWS)",
        {"windowLog": window, "windowLine": any("창문" == t or "모두 닫혀 있어요." in t for t in seen1)},
        True if window is None else any("모두 닫혀 있어요." in t for t in seen1))

    # [돌려줬어요] -> pair stored; history tab shows 1 pair.
    mark = log_mark()
    find("돌려줬어요", click=True, scroll=True)
    pair = wait_log(r"returnPair saved soc=54->41 .*newWarnings=1 pairs=1 restored=true", mark)
    no_trip = wait_log(r"tripLog added", mark, seconds=1)
    check("return-pair", "[돌려줬어요] stores the return snapshot as a pair (rental: no trip line)", {"pair": pair, "trip": no_trip},
        bool(pair) and no_trip is None)
    start_app("history", ["--es", "historyTab", "pairs"])
    find("인계 이력", click=True, scroll=False, cls="android.widget.Button")
    scroll_top()
    ok3, seen3 = capture("03-history-pairs", ["경고등", "인계 이력", "인수 ", "→ 반납", "배터리 54% → 41%", "누적 +32km", "새로 켜진 경고등 1개: 타이어 바람"])
    pairs_shown = sum(1 for t in seen3 if "→ 반납" in t)
    fleet_tab = any(t == "운행 표" for t in seen3)
    check("history-pairs", "Record screen: tabs 경고등/인계 이력 (운행 표 hidden outside fleet mode), 1 pair, vehicle state only",
        {"ui": seen3, "pairs": pairs_shown, "tripTab": fleet_tab}, ok3 and pairs_shown == 1 and not fleet_tab)

    # Door closes -> next boarding -> handover card. Re-opening keeps it and brings no return card.
    mark = log_mark()
    console("/vehicle/body?door_fl=false", wait=1.5)
    card = wait_log(r"handoverCard shown", mark)
    console("/vehicle/body?door_fl=true", wait=1.2)
    again = wait_log(r"returnCard shown", mark, seconds=1)
    check("next-card", "Door closed then opened: new handover card; no second return card", {"card": card, "returnAgain": again},
        bool(card) and again is None)

    # Fleet (법인·택시) mode: take over, switch mode, drive +5 km, return with a note -> one trip line in the trip table.
    mark = log_mark()
    console("/vehicle/body?door_fl=false", wait=1.0)
    find("인수했어요", click=True, scroll=True)
    second = wait_log(r"handoverSnapshot saved .*via=accept", mark)
    start_app("settings")
    find("법인·택시", click=True, scroll=True, cls="android.widget.RadioButton")
    fleet = wait_log(r"handoverSettings mode=FLEET", mark)
    trip_start, trip_end = drive(5)
    console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow", wait=1.0)
    console("/vehicle/gear?gear=Park", wait=1.5)
    console("/vehicle/body?door_fl=true", wait=1.5)
    wait_log(r"returnCard shown", mark)

    # Note for the next driver (ASCII: adb cannot type Korean). Fleet mode keeps the note.
    note_mark = log_mark()
    find("다음 사람에게 한마디", click=True, scroll=True)
    field = find("글 한 줄", attempts=4, scroll=True, cls="android.widget.EditText")
    if field is not None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", field.get("bounds")))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        adb("shell", "input", "text", NOTE.replace(" ", "%s"))
        time.sleep(0.8)
        hide_keyboard()
    ok2, seen2 = capture("02-note", [NOTE, "한마디 저장"])
    find("한마디 저장", click=True, scroll=True)
    note = wait_log(r"returnNote saved chars=%d" % len(NOTE), note_mark)
    check("note-saved", "Fleet mode: [다음 사람에게 한마디] -> one line -> [한마디 저장]", {"ui": seen2, "log": note}, ok2 and bool(note))

    find("돌려줬어요", click=True, scroll=True)
    trip = wait_log(r"tripLog added .*distanceKm=5\.0 .*trips=1", mark)
    start_app("history", ["--es", "historyTab", "trips"])
    find("운행 표", click=True, scroll=False, cls="android.widget.Button")
    scroll_top()
    ok5, seen5 = capture("05-trip-table", ["운행 표", "1건 · 합계 5km", "날짜", "출발 km", "도착 km", "거리", "목적지", "인수 → 반납", "5km"])
    check("fleet-trip", "Fleet mode: [돌려줬어요] writes one trip line; trip table tab shows month, 1 entry, total km",
        {"takeover": second, "mode": fleet, "trip": trip, "odometer": [trip_start, trip_end], "ui": seen5}, bool(second and fleet and trip) and ok5)

    # Door closes -> next boarding -> handover card with the note (fleet mode shows it).
    mark = log_mark()
    console("/vehicle/body?door_fl=false", wait=1.5)
    card = wait_log(r"handoverCard shown", mark)
    find("이전 운전자 한마디", attempts=6, scroll=True)
    ok4, seen4 = capture("04-next-card-note", ["안녕하세요. 지금 제 상태를 말씀드릴게요.", "이전 운전자 한마디", NOTE, "배터리 41%"])
    check("next-card-note", "Fleet mode: the next handover card shows the note", {"card": card, "ui": seen4}, bool(card) and ok4)

    # Clear all (settings) empties the records; back to rental mode for the other scripts.
    mark = log_mark()
    if card:
        find("인수했어요", click=True, scroll=True)
    start_app("settings")
    find("모두 지우기", click=True, scroll=True, cls="android.widget.Button")
    find("네, 모두 지울게요", click=True, scroll=True)
    cleared = wait_log(r"records cleared warnings=0 pairs=0 trips=0 snapshot=false note=false", mark)
    find("렌터카·카셰어", click=True, scroll=True, cls="android.widget.RadioButton")
    check("clear-all", "Settings 모두 지우기 removes warning history, pairs, trips, snapshot and note", cleared, bool(cleared))

    offending = {}
    for name, items in UI_TEXT.items():
        for text in items:
            hits = [w for w in BANNED + HONORIFICS if w in text.replace(MODE_EXCEPTION, "")]
            if hits:
                offending.setdefault(name, []).append(text)
    check("ui-wording", "No banned words or honorifics on the captured screens", {"screens": sorted(UI_TEXT), "offending": offending}, not offending)


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
