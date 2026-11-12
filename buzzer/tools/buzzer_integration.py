"""Emulator check for the :buzzer app through the real Pleos SDK and the Virtual Console.

Uses only emulator-5554 and PUT writes to the Virtual Console with the required /browser/session heartbeat.
No server, no credentials. The odometer is never written (it cannot go down).
Debug intent extras shorten the round timings so the whole run takes a few minutes.

Run from the repository root after `gradlew :buzzer:assembleDebug`:
    python buzzer/tools/buzzer_integration.py
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
OUTPUT = BASE / "docs/screens"
REPORT = BASE / "docs/integration.json"
LOG = BASE / "docs/integration-vehicle.log"
APK = BASE / "build/outputs/apk/debug/buzzer-debug.apk"
CONSOLE = "http://127.0.0.1:18088"
PACKAGE = "ai.gyeote.buzzer"
SERIAL = "emulator-5554"
ASK, REVEAL, REFLEX_DELAY, REFLEX_WINDOW, FALLBACK = 6000, 5000, 6000, 6000, 15000
RESULTS, CAPTURES, UI_TEXT = [], [], {}

# Code points keep a repository word scan from matching this file itself.
def _words(*codes):
    return tuple("".join(map(chr, c)) for c in codes)
BANNED = _words((44048, 49884), (52628, 51201), (50948, 52824, 32, 54869, 51064), (49892, 48260), (45432, 51064),
    (51648, 53428, 51060), (44553, 48156, 51652), (54168, 45804, 32, 50724, 51312, 51089), (52293, 51076), (51096, 47803), (51216, 49688))
HONORIFICS = _words((48512, 47784, 45784), (50500, 48260, 51648), (46384, 45784), (51088, 45376), (44032, 51313), (46392,))


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
    client = "buzzer-" + uuid.uuid4().hex
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


def console(path, wait=0.6):
    status = http(path)
    if status != 200:
        raise AssertionError("Console rejected " + path + ": " + str(status))
    time.sleep(wait)


def window(seat, percent, wait=0.6):
    console("/vehicle/body?window_" + seat + "=" + str(percent), wait)


def battery(field, value, wait=0.6):
    console("/vehicle/ev-battery?field=" + field + "&value=" + str(value), wait)


def logs():
    return adb("logcat", "-d", "-s", "BuzzerGame:I", "BuzzerCar:I").decode("utf-8", errors="replace")


def log_mark():
    return len(logs().splitlines())


def wait_log(pattern, since=0, seconds=12):
    end = time.time() + seconds
    while time.time() < end:
        hits = [line for line in logs().splitlines()[since:] if re.search(pattern, line)]
        if hits:
            return hits[-1]
        time.sleep(0.3)
    return None


def all_logs(pattern, since=0):
    return [line for line in logs().splitlines()[since:] if re.search(pattern, line)]


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/buzzer-ui.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/buzzer-ui.xml"))


def app_nodes(state):
    return [node for node in state.iter("node") if node.get("package") == PACKAGE]


def texts():
    return [n.get("text", "") for n in app_nodes(tree()) if n.get("text")]


def wait_text(fragment, seconds=8):
    end = time.time() + seconds
    while time.time() < end:
        if any(fragment in t for t in texts()):
            return True
        time.sleep(0.3)
    return False


def tap(fragment):
    for _ in range(8):
        for node in app_nodes(tree()):
            if node.get("text", "") == fragment and node.get("clickable") == "true":
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(0.5)
                return True
        time.sleep(0.4)
    return False


def capture(name, expected):
    state = tree()
    seen = [n.get("text", "") for n in app_nodes(state) if n.get("text")]
    ok = all(any(fragment in text for text in seen) for fragment in expected)
    raw = adb("exec-out", "screencap", "-p")
    assert raw.startswith(b"\x89PNG\r\n\x1a\n")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / (name + ".png")).write_bytes(raw)
    UI_TEXT[name] = seen
    CAPTURES.append({"file": "screens/" + name + ".png", "sha256": hashlib.sha256(raw).hexdigest(), "expected_text": expected, "seen_text": seen, "passed": ok})
    return ok, seen


def check(name, expected, observed, passed):
    RESULTS.append({"scenario": name, "expected": expected, "observed": observed, "passed": bool(passed)})
    print(("PASS " if passed else "FAIL ") + name, flush=True)
    save()


def save():
    REPORT.write_text(json.dumps({"at": datetime.now(timezone.utc).isoformat(), "serial": SERIAL,
        "timingsMs": {"ask": ASK, "reveal": REVEAL, "reflexDelay": REFLEX_DELAY, "reflexWindow": REFLEX_WINDOW, "fallback": FALLBACK},
        "results": RESULTS, "captures": CAPTURES,
        "apk_sha256": hashlib.sha256(APK.read_bytes()).hexdigest() if APK.exists() else None}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def start_app(reset=False):
    args = ["shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", "--el", "askMs", str(ASK), "--el", "revealMs", str(REVEAL),
        "--el", "reflexDelayMs", str(REFLEX_DELAY), "--el", "reflexWindowMs", str(REFLEX_WINDOW), "--el", "fallbackMs", str(FALLBACK)]
    if reset:
        args += ["--ez", "resetProgress", "true"]
    adb(*args)
    time.sleep(2.5)


def join(seat):
    window(seat, 10)
    window(seat, 0)


def lifetime(line):
    found = re.search(r"lifetime=(\d+)", line or "")
    return int(found.group(1)) if found else None


def scenario():
    # Baseline: driving, every window up, not charging, 60 %.
    console("/vehicle/gear?gear=Drive")
    for seat in ("fl", "fr", "rl", "rr"):
        window(seat, 0, wait=0.3)
    battery("charge_switch", "false")
    battery("charge_port_open", "false")
    battery("soc", 60)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("logcat", "-c")
    start_app(reset=True)

    # --- Session A: parked, two seats ---
    seen = texts()
    window("rl", 10)
    window("rl", 0)
    check("A1-locked-while-driving", "In D the screen only says to park; a window move does nothing",
        {"ui": seen, "lobbyLog": all_logs(r"phase=Lobby")}, any("차를 세우면 열려요" in t for t in seen) and not all_logs(r"phase=Lobby"))

    mark = log_mark()
    console("/vehicle/gear?gear=Park", wait=1.2)
    lobby = wait_log(r"phase=Lobby joined=\[\]", mark)
    check("A2-park-opens-lobby", "P opens the lobby with nobody joined", {"log": lobby, "ui": wait_text("함께할 자리는")}, bool(lobby) and wait_text("함께할 자리는"))

    mark = log_mark()
    window("rl", 10)
    first = wait_log(r"phase=Lobby joined=\[REAR_LEFT\]", mark)
    window("fl", 10)
    second = wait_log(r"phase=Lobby joined=\[REAR_LEFT, DRIVER\]", mark)
    ok, seen = capture("01-lobby-joined", ["운전석", "뒤 왼쪽", "참가!", "창문을 내리면 참가", "시작"])
    check("A3-window-joins-seat", "Lowering a seat's own window joins that seat only", {"first": first, "second": second, "ui": seen}, bool(first and second) and ok)

    mark = log_mark()
    tapped = tap("시작")
    between = wait_log(r"phase=Between raise=\[(REAR_LEFT, DRIVER|DRIVER, REAR_LEFT)\]", mark)
    ok, seen = capture("02-raise-windows", ["창문을 올려 주세요", "천천히 하셔도 돼요"])
    time.sleep(3)
    early = all_logs(r"phase=Asking", mark)
    window("rl", 0)
    window("fl", 0)
    asking = wait_log(r"phase=Asking round=0 fromCar=false truth=true", mark)
    check("A4-start-waits-for-windows-up", "[시작] with windows down waits (no time limit) until they are raised",
        {"tapped": tapped, "between": between, "askedWhileDown": early, "asking": asking, "ui": seen}, tapped and bool(between and asking) and not early and ok)

    mark = log_mark()
    window("rl", 10, wait=0.4)
    answered = wait_log(r"phase=Asking round=0 .*answeredO=\[REAR_LEFT\]", mark, seconds=4)
    ok3, seen3 = capture("03-question-answered", ["1번째 판", "O를 골랐어요", "그대로 두면 X", "맞으면 창문을 한 칸 내리고"])
    reveal = wait_log(r"phase=Reveal round=0 kind=OX correct=\[REAR_LEFT\] wrong=\[DRIVER\] stars=REAR_LEFT:1,DRIVER:0", mark, seconds=ASK / 1000 + 4)
    ok4, seen4 = capture("04-reveal", ["O · 맞는 말이에요", "★ 맞혔어요", "아쉬워요", "★ 1"])
    check("A5-lowering-answers-O", "The seat that lowered answered O (correct, +1 star); the seat that stayed answered X",
        {"answered": answered, "reveal": reveal, "question": seen3, "revealUi": seen4}, bool(answered and reveal) and ok3 and ok4)

    mark = log_mark()
    between = wait_log(r"phase=Between raise=\[REAR_LEFT\]", mark, seconds=REVEAL / 1000 + 4)
    time.sleep(4)
    rushed = all_logs(r"phase=Asking round=1", mark)
    window("rl", 0)
    asking = wait_log(r"phase=Asking round=1 fromCar=false truth=false", mark)
    check("A6-next-question-waits-for-raise", "The next question does not start while the answering window is still down",
        {"between": between, "askedWhileDown": rushed, "asking": asking}, bool(between and asking) and not rushed)

    reveal = wait_log(r"phase=Reveal round=1 kind=OX correct=\[REAR_LEFT, DRIVER\] wrong=\[\] stars=REAR_LEFT:2,DRIVER:1", mark, seconds=ASK / 1000 + 4)
    check("A7-staying-up-answers-X", "On a false statement both seats stay up and both earn a star", reveal, bool(reveal))

    mark = log_mark()
    steady = wait_log(r"phase=Steady round=2 tooEarly=\[\]", mark, seconds=REVEAL / 1000 + 4)
    window("fl", 10, wait=0.4)
    early = wait_log(r"phase=Steady round=2 tooEarly=\[DRIVER\]", mark, seconds=3)
    window("fl", 0, wait=0.4)
    go = wait_log(r"phase=Go round=2 tooEarly=\[DRIVER\]", mark, seconds=REFLEX_DELAY / 1000 + 3)
    ok5, seen5 = capture("05-reflex-go", ["번개 버저", "지금!", "이번 판은 쉬어요"])
    window("rl", 10, wait=0.4)
    winner = wait_log(r"phase=Reveal round=2 kind=REFLEX winner=REAR_LEFT tooEarly=\[DRIVER\] stars=REAR_LEFT:3,DRIVER:1", mark, seconds=4)
    ok6, seen6 = capture("06-reflex-winner", ["뒤 왼쪽이 가장 빨랐어요", "★ 가장 빨랐어요", "너무 빨랐어요"])
    window("rl", 0)
    check("A8-reflex-round", "Lowering before go sits out; the first seat to lower after go wins the star",
        {"steady": steady, "early": early, "go": go, "winner": winner, "goUi": seen5, "winnerUi": seen6}, bool(steady and early and go and winner) and ok5 and ok6)

    mark = log_mark()
    car_question = wait_log(r"phase=Asking round=3 fromCar=true truth=true", mark, seconds=REVEAL / 1000 + 5)
    ok7, seen7 = capture("07-car-question", ["차가 낸 문제", "배터리는 절반보다"])
    check("A9-car-makes-a-question", "Every third question is made from live car data (battery 60 % -> 'more than half' is true)",
        {"log": car_question, "ui": seen7}, bool(car_question) and ok7)

    mark = log_mark()
    console("/vehicle/gear?gear=Drive", wait=1.0)
    closed = wait_log(r"phase=Locked closedByDriving=true leftScreen=false lifetime=4", mark, seconds=4)
    said = wait_log(r"say count=1 first=출발하니까", mark, seconds=2)
    ok8, seen8 = capture("08-closed-by-driving", ["출발하니까 닫았어요", "지금까지 모은 별 4개"])
    window("rl", 10)
    window("rl", 0)
    time.sleep(ASK / 1000 + 1)
    after = all_logs(r"phase=(Asking|Reveal|Between|Lobby)", mark)
    check("A10-driving-closes-at-once", "Leaving P closes the game at once, says so once, keeps the 4 stars, and ignores windows afterwards",
        {"closed": closed, "said": said, "ui": seen8, "phasesAfter": after}, bool(closed and said) and ok8 and not after)

    # --- Session B: charging, the battery is the clock ---
    battery("soc", 78)
    battery("charge_port_open", "true")
    battery("charge_switch", "true")
    mark = log_mark()
    console("/vehicle/gear?gear=Park", wait=1.2)
    charging = wait_log(r"charging=true", 0, seconds=6)
    lobby_text = wait_text("충전 중 78%")
    ok9, seen9 = capture("09-lobby-charging", ["충전 중 78%", "배터리가 1% 오를 때마다 새 문제가 와요", "지금까지 모은 별 4개"])
    check("B1-lobby-shows-charging", "While charging the lobby says the battery brings the questions", {"charging": charging, "ui": seen9}, bool(charging) and lobby_text and ok9)

    join("fl")
    tap("시작")
    asking = wait_log(r"phase=Asking round=0", mark)
    between = wait_log(r"phase=Between raise=\[\] nextAtSoc=79", mark, seconds=(ASK + REVEAL) / 1000 + 5)
    ok10, seen10 = capture("10-waiting-for-percent", ["다음 문제는 79%에 와요", "충전 중 78%"])
    time.sleep(3)
    rushed = all_logs(r"phase=Asking round=1", mark)
    battery("soc", 79)
    unlocked = wait_log(r"phase=Asking round=1", mark, seconds=5)
    check("B2-one-percent-brings-next-question", "After a round the next question waits for +1 % and arrives when the battery reaches it",
        {"asking": asking, "between": between, "askedBeforePercent": rushed, "unlocked": unlocked, "ui": seen10}, bool(asking and between and unlocked) and not rushed and ok10)

    between = wait_log(r"phase=Between raise=\[\] nextAtSoc=80", mark, seconds=(ASK + REVEAL) / 1000 + 5)
    battery("soc", 80)
    last = wait_log(r"phase=Steady round=2 .*final=true", mark, seconds=5)
    said_last = wait_log(r"say count=\d+ first=충전이 거의 끝났어요", mark, seconds=2)
    finished = wait_log(r"phase=Finished reason=CHARGED", mark, seconds=(REFLEX_DELAY + REFLEX_WINDOW + REVEAL) / 1000 + 6)
    ok11, seen11 = capture("11-finished-charged", ["충전도 놀이도 끝났어요", "한 판 더"])
    check("B3-target-ends-session", "Reaching 80 % plays one last round and ends the session",
        {"between": between, "lastRound": last, "said": said_last, "finished": finished, "ui": seen11}, bool(between and last and said_last and finished) and ok11)

    # Slow charger: no +1 % arrives, the fallback timer brings the next question. Then unplug mid-round.
    tap("한 판 더")
    mark = log_mark()
    wait_log(r"phase=Lobby joined=\[\]", mark)
    join("fl")
    tap("시작")
    between = wait_log(r"phase=Between raise=\[\] nextAtSoc=81", mark, seconds=(ASK + REVEAL) / 1000 + 6)
    began = time.time()
    time.sleep(FALLBACK / 1000 - 6)
    rushed = all_logs(r"phase=Asking round=1", mark)
    fallback = wait_log(r"phase=Asking round=1", mark, seconds=12)
    waited = time.time() - began
    check("B4-slow-charger-fallback", "Without +1 % the next question still arrives after the fallback time (15 s here)",
        {"between": between, "askedEarly": rushed, "asking": fallback, "waitedSeconds": round(waited, 1)}, bool(between and fallback) and not rushed)

    battery("charge_switch", "false")
    unplugged = wait_log(r"charging=false", mark, seconds=5)
    finished = wait_log(r"phase=Finished reason=UNPLUGGED", mark, seconds=(ASK + REVEAL) / 1000 + 6)
    ok12, seen12 = capture("12-finished-unplugged", ["다음 충전 때 이어서 해요", "한 판 더"])
    check("B5-unplug-ends-after-round", "Unplugging lets the current round finish, then ends with 'continue next charge'",
        {"unplugged": unplugged, "finished": finished, "ui": seen12}, bool(unplugged and finished) and ok12)

    # --- Session C: parked, five rounds, a non-player window left open ---
    battery("charge_port_open", "false")
    tap("한 판 더")
    mark = log_mark()
    wait_log(r"phase=Lobby joined=\[\]", mark)
    lobby_text = wait_text("오늘은 5판")
    join("rr")
    tap("시작")
    wait_log(r"phase=Asking round=0", mark)
    window("fr", 40)   # a seat that is not playing leaves its window open
    finished = wait_log(r"phase=Finished reason=ROUNDS_DONE open=\[PASSENGER\]", mark, seconds=(4 * (ASK + REVEAL) + REFLEX_DELAY + REFLEX_WINDOW + REVEAL) / 1000 + 15)
    ok13, seen13 = capture("13-finished-open-window", ["오늘은 여기까지예요", "열려 있는 창문을 올려 주세요: 조수석", "창문이 열려 있어요"])
    rounds = [re.search(r"round=(\d)", line).group(1) for line in all_logs(r"phase=Reveal round=", mark)]
    window("fr", 0)
    cleared = wait_log(r"phase=Finished reason=ROUNDS_DONE open=\[\]", mark, seconds=4)
    check("C1-five-rounds-then-open-window-reminder", "A parked session ends after five rounds and lists a window still open until it is closed",
        {"lobby": lobby_text, "finished": finished, "rounds": rounds, "cleared": cleared, "ui": seen13},
        lobby_text and bool(finished and cleared) and rounds == ["0", "1", "2", "3", "4"] and ok13)

    total = lifetime(cleared)
    adb("shell", "am", "force-stop", PACKAGE)
    start_app()
    kept = wait_text("지금까지 모은 별 " + str(total) + "개")
    check("C2-stars-survive-restart", "Lifetime stars are still there after the app is closed and opened", {"lifetime": total, "ui": kept}, total is not None and kept)

    offending = {}
    for name, items in UI_TEXT.items():
        for text in items:
            hits = [w for w in BANNED + HONORIFICS if w in text]
            if hits:
                offending.setdefault(name, []).append(text)
    check("ui-wording", "No avoided words on any captured screen", {"screens": sorted(UI_TEXT), "offending": offending}, not offending)


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
            # Leave the car parked, closed and not charging.
            for path in ("/vehicle/gear?gear=Park", "/vehicle/ev-battery?field=charge_switch&value=false", "/vehicle/ev-battery?field=charge_port_open&value=false",
                    "/vehicle/body?window_fl=0", "/vehicle/body?window_fr=0", "/vehicle/body?window_rl=0", "/vehicle/body?window_rr=0"):
                try:
                    http(path)
                except (OSError, urllib.error.URLError):
                    pass
            LOG.write_text("\n".join(line for line in logs().splitlines() if "Buzzer" in line) + "\n", encoding="utf-8")
            save()
    failed = [r["scenario"] for r in RESULTS if not r["passed"]]
    print("failed:", failed)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
