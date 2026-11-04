"""H8: real Pleos SDK recording, cards and navigation while Passer is behind HOME/Maps.

Uses an isolated encrypted debug profile and the existing H3 console/UI helpers.
Never launches/restarts the emulator, changes its settings, or touches the probe app.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

import return_integration as h

BASE = Path(__file__).resolve().parents[1]
OUTPUT = BASE / "docs/screens/background"
REPORT = BASE / "docs/background-integration.json"
RESULTS, CAPTURES = [], []
NAV = "ai.umos.maps.android.navigation.app"


def save():
    apk = BASE / "build/outputs/apk/debug/app-debug.apk"
    REPORT.write_text(json.dumps({"at": datetime.now(timezone.utc).isoformat(), "serial": h.SERIAL,
        "testProfile": h.PROFILE, "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "results": RESULTS, "captures": CAPTURES}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check(name, expected, observed, passed):
    RESULTS.append({"scenario": name, "expected": expected, "observed": observed, "passed": bool(passed)})
    print(("PASS " if passed else "FAIL ") + name, flush=True)
    save()


def capture(name, expected=(), package="ai.gyeote.app"):
    state = h.tree()
    seen = [n.get("text", "") for n in state.iter("node") if n.get("package") == package and n.get("text")]
    raw = h.adb("exec-out", "screencap", "-p")
    assert raw.startswith(b"\x89PNG\r\n\x1a\n")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / (name + ".png")).write_bytes(raw)
    (OUTPUT / (name + ".xml")).write_bytes(ET.tostring(state, encoding="utf-8"))
    ok = all(any(fragment in text for text in seen) for fragment in expected)
    CAPTURES.append({"file": "screens/background/" + name + ".png", "sha256": hashlib.sha256(raw).hexdigest(),
        "expected_text": list(expected), "passed": ok})
    save()
    return ok, seen


def service_state(name):
    services = h.adb("shell", "dumpsys", "activity", "services", "ai.gyeote.app").decode("utf-8", errors="replace")
    processes = h.adb("shell", "dumpsys", "activity", "processes").decode("utf-8", errors="replace")
    # Save only this app's process block; unrelated process metadata is not evidence.
    blocks = re.split(r"(?=\s*\*APP\*)", processes)
    process = next((b for b in blocks if re.search(r"\d+:ai\.gyeote\.app/", b)), "")
    state = [line.strip() for line in process.splitlines() if "curProcState=" in line or "curRawProcState=" in line]
    (OUTPUT / (name + "-state.txt")).write_text(services + "\n" + process + "\n", encoding="utf-8")
    return {"foreground": "VehicleRecordService" in services and "isForeground=true" in services,
        "notFrozen": "isFrozen=false" in process, "processState": state,
        "service": [line.strip() for line in services.splitlines() if any(s in line for s in ("VehicleRecordService", "isForeground=", "foregroundId="))],
        "freezer": [line.strip() for line in process.splitlines() if "Frozen" in line]}


def resumed():
    text = h.adb("shell", "dumpsys", "activity", "activities").decode("utf-8", errors="replace")
    return [line.strip() for line in text.splitlines() if "mResumedActivity" in line or "topResumedActivity" in line]


def active_notifications(seconds=0):
    begin = time.monotonic()
    while True:
        notes = h.adb("shell", "dumpsys", "notification").decode("utf-8", errors="replace").split("Historical notifications", 1)[0]
        own = [line.strip() for line in notes.splitlines() if "NotificationRecord(" in line and "pkg=ai.gyeote.app " in line]
        elapsed = time.monotonic() - begin
        if own or elapsed >= seconds:
            return own, notes, elapsed
        time.sleep(0.8)


def nav_click(fragment, attempts=3):
    for _ in range(attempts):
        for node in h.tree().iter("node"):
            if node.get("package") == NAV and fragment in node.get("text", ""):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                h.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(1.5)
                return True
        time.sleep(0.8)
    return False


def open_navigation():
    h.adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", NAV)
    time.sleep(2)


def hide_app():
    mark = h.log_mark()
    h.adb("shell", "input", "keyevent", "3")
    time.sleep(1)
    # Pleos multi-window HOME can leave the app resumed in the right pane. Cover that pane too.
    covered = any("ai.gyeote.app/" in line for line in resumed())
    if covered:
        open_navigation()
    detached = h.wait_log(r"recordScreen detached", mark, seconds=8)
    top = resumed()
    assert detached and not any("ai.gyeote.app/" in line for line in top), "Passer screen did not stop"
    return {"homeThenNavigationCover": covered, "detached": detached, "resumed": top}


def navigation():
    open_navigation()
    capture("04-navigation-open", package=NAV)
    nav_click("어디로 갈까요", attempts=1)
    capture("05-navigation-search", package=NAV)
    chosen = nav_click("서울 중앙병원")
    # A recent destination opens its detail card first; [목적지로] opens route choices.
    capture("05b-navigation-destination", package=NAV)
    if chosen:
        nav_click("목적지로", attempts=1)
    capture("06-navigation-route", package=NAV)
    mark = h.log_mark()
    started = chosen and nav_click("안내 시작")
    callback = h.wait_log(r"navigation=destinationName", mark, seconds=15) if started else None
    # Read the app's callback BEFORE any command opens Passer. Device clock and logcat use the same clock.
    epoch_logs = h.adb("logcat", "-d", "-v", "epoch", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    callback_line = next((line for line in reversed(epoch_logs.splitlines()) if "navigation=destinationName" in line), None) if callback else None
    callback_epoch = float(callback_line.split()[0]) if callback_line else None
    foreground_apps = resumed()
    state = service_state("navigation")
    capture("07-navigation-started", package=NAV)
    reopen_epoch = float(h.adb("shell", "date", "+%s.%N").decode().strip())
    check("navigation-before-reopen", "Route-start name arrives before Passer is brought forward; Maps is resumed",
        {"routeStartTapped": started, "callback": callback, "callbackEpoch": callback_epoch,
         "beforeReopenEpoch": reopen_epoch, "resumed": foreground_apps, "state": state},
        bool(callback and callback_epoch is not None and callback_epoch < reopen_epoch and
             any(NAV in line for line in foreground_apps) and state["foreground"] and state["notFrozen"]))
    # Always end this guidance, and record the result, while still in Maps.
    ended = nav_click("안내 종료") if started else False
    if ended:
        # Some images have a confirmation dialog; a second matching button is safe when present.
        nav_click("안내 종료", attempts=1)
    capture("08-navigation-ended", package=NAV)
    remaining = [n.get("text", "") for n in h.tree().iter("node") if n.get("package") == NAV]
    check("navigation-ended", "Guidance is ended after measurement", {"endTapped": ended,
        "endControlStillVisible": any("안내 종료" in t for t in remaining)}, ended and not any("안내 종료" in t for t in remaining))
    h.start_app("home")


def scenario():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    h.console("/vehicle/gear?gear=Drive")
    h.console("/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false&trunk=false")
    h.console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow")
    h.adb("shell", "am", "force-stop", "ai.gyeote.app")
    h.adb("logcat", "-c")
    # Denial must not prevent FGS recording. No permission dialog is opened while driving.
    h.adb("shell", "pm", "revoke", "--user", "current", "ai.gyeote.app", "android.permission.POST_NOTIFICATIONS")
    h.start_app("home", ["--ez", "publicTelltalesOnly", "true"])
    foreground = h.wait_log(r"recordService foreground")
    hidden = hide_app()
    mark = h.log_mark()
    print("Waiting 65 seconds with the Passer screen stopped", flush=True)
    begin = time.monotonic()
    time.sleep(65)
    elapsed = time.monotonic() - begin
    h.console("/vehicle/telltales/simulator?type=TPMS&state=On&color=Yellow", wait=5)
    on = h.wait_log(r"warningHistory records=\d+ open=1", mark)
    h.console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow", wait=5)
    off = h.wait_log(r"warningHistory records=\d+ open=0", mark)
    state = service_state("home-after-65s")
    background_top = resumed()
    check("background-recording", "After >=60s behind HOME, public polling records TPMS On -> Off; FGS, unfrozen; notification permission denied",
        {"foregroundLog": foreground, "elapsedSeconds": elapsed, "on": on, "off": off, "state": state, "hidden": hidden, "resumed": background_top},
        bool(foreground and elapsed >= 60 and on and off and state["foreground"] and state["notFrozen"] and
             not any("ai.gyeote.app/" in line for line in background_top)))

    # The entire boarding transition must be judged before the app returns to the front.
    h.console("/vehicle/gear?gear=Park", wait=1.2)
    h.console("/vehicle/body?door_fl=true", wait=1.2)
    h.console("/vehicle/body?door_fl=false", wait=3)
    card = h.wait_log(r"handoverCard shown", mark)
    background_speech = h.all_logs(r"handoverSpeech|tts=(start|queued)", mark)
    h.start_app("home")
    h.scroll_top()
    ok, seen = capture("01-history-and-handover", ["안녕하세요. 지금 제 상태를 말씀드릴게요.", "최근 켜졌던 경고등", "타이어 바람"])
    check("background-card-ready", "Background P + driver door open/close prepares a handover card, visible on reopening, no background speech",
        {"cardBeforeReopen": card, "backgroundSpeech": background_speech, "ui": seen}, bool(card and ok and not background_speech))
    check("background-history-visible", "Recently lit warning contains TPMS after reopening", seen, ok)
    h.find("인수했어요", click=True, scroll=True)
    h.start_app("settings")
    h.find("법인·택시", click=True, cls="android.widget.RadioButton")
    h.console("/vehicle/gear?gear=Drive", wait=2)
    current = h.current_km()
    assert current is not None, "No odometer received"
    h.console("/vehicle/odometer?traveled_distance_km=%d" % (int(current) + 1), wait=1.2)
    hide_app()
    mark = h.log_mark()
    h.console("/vehicle/gear?gear=Park", wait=1.2)
    h.console("/vehicle/body?door_fl=true", wait=2)
    returned = h.wait_log(r"returnCard shown", mark)
    h.start_app("home")
    h.scroll_top()
    ok, seen = capture("02-return-ready", ["이번 운행을 정리했어요.", "+1km", "돌려줬어요"])
    check("background-return-ready", "Return is judged behind HOME and its card is visible on reopening", {"beforeReopen": returned, "ui": seen}, bool(returned and ok))
    h.find("나중에", click=True, scroll=True)
    navigation()

    # Grant only this debug app's notification permission so removal can be inspected in NotificationManager.
    h.adb("shell", "pm", "grant", "--user", "current", "ai.gyeote.app", "android.permission.POST_NOTIFICATIONS")
    h.start_app("settings")
    # Repost after granting permission, then allow Android's deferred FGS notification to become visible.
    h.find("끄기", click=True, cls="android.widget.RadioButton")
    h.find("렌터카·카셰어", click=True, cls="android.widget.RadioButton")
    before_off, _, waited = active_notifications(seconds=18)
    check("notification-before-off", "An ongoing notification is actually posted before testing removal",
        {"notifications": before_off, "waitedSeconds": waited}, bool(before_off))
    h.find("끄기", click=True, cls="android.widget.RadioButton")
    time.sleep(2)
    services = h.adb("shell", "dumpsys", "activity", "services", "ai.gyeote.app").decode("utf-8", errors="replace")
    own_active, _, _ = active_notifications()
    ok, seen = capture("03-mode-off", ["끄기", "차 상태 기록을 멈춰요"])
    check("mode-off", "OFF removes VehicleRecordService and the ongoing notification", {"serviceStillPresent": "VehicleRecordService" in services,
        "activeNotifications": own_active, "ui": seen}, bool(before_off) and "VehicleRecordService" not in services and not own_active and ok)
    hide_app()
    h.start_app("settings")
    services = h.adb("shell", "dumpsys", "activity", "services", "ai.gyeote.app").decode("utf-8", errors="replace")
    check("off-reopen-no-service", "Opening the app with stored mode OFF must not start recording",
        {"serviceStillPresent": "VehicleRecordService" in services}, "VehicleRecordService" not in services)
    # An enabled mode must start again, including an observable ongoing notification.
    mark = h.log_mark()
    h.find("렌터카·카셰어", click=True, cls="android.widget.RadioButton")
    restarted = h.wait_log(r"recordService foreground", mark)
    own_active, notes, waited = active_notifications(seconds=18)
    check("mode-enabled-again", "Enabled mode restarts one FGS and its ongoing notification", {"restart": restarted,
        "notifications": own_active, "waitedSeconds": waited}, bool(restarted and own_active))
    # NotificationRecord also prints its channel. Count the current user's stored channels only.
    user = re.search(r"user=UserHandle\{(\d+)\}", own_active[0]) if own_active else None
    own_settings = False
    channels = []
    for line in notes.splitlines():
        entry = re.search(r"AppSettings: (\S+) \((\d+)\)", line)
        if entry:
            own_settings = bool(user and entry[1] == "ai.gyeote.app" and int(entry[2]) // 100000 == int(user[1]))
        elif own_settings and "NotificationChannel{" in line:
            channels.append(line.strip())
    check("quiet-channel", "One LOW channel, no sound or vibration", channels,
        len(channels) == 1 and "mImportance=2" in channels[0] and "mSound=null" in channels[0] and "mVibrationEnabled=false" in channels[0])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    h.SERIAL = args.serial
    h.PROFILE = "backgroundH8-" + uuid.uuid4().hex[:12]
    h.adb("forward", "tcp:18088", "tcp:8088")
    device_failed = False
    try:
        with h.console_session():
            scenario()
    except (subprocess.TimeoutExpired, subprocess.CalledProcessError, OSError, AssertionError) as error:
        # No retry/restart on a stalled emulator. Preserve completed checks and stop this run.
        device_failed = isinstance(error, (subprocess.TimeoutExpired, subprocess.CalledProcessError))
        check("execution-error", "All required scenarios execute without a stalled device", type(error).__name__, False)
        raise
    finally:
        try:
            if not device_failed:
                (BASE / "docs/background-vehicle.log").write_text(h.logs(), encoding="utf-8")
        finally:
            save()
    return 1 if any(not r["passed"] for r in RESULTS) else 0


if __name__ == "__main__":
    raise SystemExit(main())
