"""Synthetic Pleos emulator + local family-server checks; never persists authentication values."""
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
OUTPUT = BASE / "docs/screens/car"
REPORT = BASE / "docs/car-integration.json"
RESULTS = []
HTTP = []
CAPTURES = []
SERIAL = "emulator-5554"


def adb(*parts):
    executable = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    return subprocess.run([str(executable), "-s", SERIAL, *parts], check=True, capture_output=True, timeout=40).stdout


def http(base, path, method="GET", body=None, token=None, record=True):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        result = json.loads(raw) if raw else None
        status = response.status
    if record:
        def public(value):
            if isinstance(value, dict):
                return {k: "[redacted]" if any(word in k.lower() for word in ("token", "paircode", "secret")) else public(v) for k, v in value.items()}
            if isinstance(value, list):
                return [public(v) for v in value]
            return value
        HTTP.append({"method": method, "path": path, "status": status, "response": public(result)})
    return status, result


@contextmanager
def console_session():
    client = "gyeote-J-" + uuid.uuid4().hex
    done = threading.Event()
    def heartbeat():
        while not done.is_set():
            try:
                http("http://127.0.0.1:18088", "/browser/session?client=" + client, "POST", record=False)
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
        http("http://127.0.0.1:18088", "/browser/session?client=" + client + "&closing=1", "POST", record=False)


def console(path):
    status, value = http("http://127.0.0.1:18088", path, "PUT")
    if status != 200:
        raise AssertionError("Console rejected " + path.split("?")[0] + ": " + str(status))
    time.sleep(0.8)
    return value


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/gyeote-J.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/gyeote-J.xml"))


def app_nodes(state):
    return [node for node in state.iter("node") if node.get("package") == "ai.gyeote.app"]


def visible(fragment, click=False, attempts=8, scroll=True):
    for attempt in range(attempts):
        state = tree()
        nodes = app_nodes(state)
        for node in nodes:
            if fragment in node.get("text", "") and (not click or node.get("clickable") == "true"):
                if click:
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    time.sleep(0.5)
                return node
        if scroll:
            target = next((node for node in nodes if node.get("class") == "android.widget.ScrollView"), None)
            if target is not None:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.get("bounds")))
                adb("shell", "input", "swipe", str((x1 + x2) // 2), str(y1 + (y2-y1)*4//5), str((x1+x2)//2), str(y1+(y2-y1)//5), "350")
        time.sleep(0.6)
    raise AssertionError("Expected UI missing: " + fragment)


def screen(name):
    adb("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "screen", name, "--es", "vehicleMode", "pleos")
    time.sleep(0.7)


def capture(name, expected, driving=False):
    visible(expected, scroll=False)
    state = tree()
    nodes = app_nodes(state)
    if driving:
        assert not any(n.get("clickable") == "true" or n.get("class", "").endswith(("Button", "EditText")) for n in nodes), "Driving controls visible"
    # Captures are intentionally made before pairing or on pages without enrollment material.
    assert not any("연결 번호" in n.get("text", "") for n in nodes), "Enrollment material must not enter captures"
    raw = adb("exec-out", "screencap", "-p")
    assert raw.startswith(b"\x89PNG\r\n\x1a\n")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / (name + ".png")).write_bytes(raw)
    (OUTPUT / (name + ".xml")).write_bytes(ET.tostring(state, encoding="utf-8"))
    CAPTURES.append({"file": name + ".png", "sha256": hashlib.sha256(raw).hexdigest(), "expected": expected, "passed": True})


def check(name, expected, observed, passed):
    RESULTS.append({"scenario": name, "expected": expected, "observed": observed, "passed": bool(passed)})
    print(("PASS " if passed else "FAIL ") + name, flush=True)
    save()


def save():
    REPORT.write_text(json.dumps({"at": datetime.now(timezone.utc).isoformat(), "serial": SERIAL,
        "results": RESULTS, "http": HTTP, "captures": CAPTURES,
        "apk_sha256": hashlib.sha256((BASE / "build/outputs/apk/debug/app-debug.apk").read_bytes()).hexdigest()}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def vehicle_checks():
    console("/vehicle/gear?gear=Park")
    console("/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false")
    console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow")
    adb("shell", "am", "force-stop", "ai.gyeote.app")
    screen("home")
    time.sleep(3)
    capture("01-home", "오늘의 동행")
    screen("consent")
    capture("02-consent", "내가 고른 소식만")
    screen("home")
    console("/vehicle/body?door_fl=true")
    visible("문 열림", scroll=False)
    capture("06-door-open", "문 열림")
    console("/vehicle/body?door_fl=false")
    visible("문 닫힘", scroll=False)
    check("console-door", "Open then closed in SDK/app", "문 열림 → 문 닫힘", True)
    console("/vehicle/telltales/simulator?type=TPMS&state=On&color=Yellow")
    visible("타이어 바람", scroll=False)
    visible("뜻과 할 일 보기", click=True)
    capture("03-warning", "타이어 공기압이 낮을 수 있어요.")
    check("console-telltale", "TPMS appears through real SDK", "타이어 경고 상세 표시", True)
    # Native Console labels this property kPa, but a successful SDK reading is required to confirm the app's unit.
    status, value = http("http://127.0.0.1:18088", "/vehicle/tire-pressure?area=1114113&pressure_kpa=210", "PUT")
    time.sleep(2)
    logs = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    pressure_observed = "tire=LEFT_FRONT pressure=210.0" in logs
    check("tire-unit-measurement", "Console 210 kPa -> SDK 210.0", {"http": status, "sdk_value_received": pressure_observed, "unit": "kPa" if pressure_observed else None}, status == 200 and pressure_observed)
    console("/vehicle/gear?gear=Drive")
    capture("04-driving", "안전한 곳에", driving=True)
    console("/vehicle/gear?gear=Park")
    screen("home")
    visible("기어 PARK", scroll=False)
    check("console-gear", "P -> D removes all app inputs, P restores parked UI", "Driving card with 0 input controls and PARK restored", True)
    console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow")
    # Probe uses on-device mode and never registers an app or handles server keys.
    adb("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--ez", "probeSpeech", "true")
    time.sleep(16)
    logs = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    speech = [line for line in logs.splitlines() if "speechProbe=" in line]
    (BASE / "docs/car-speech.log").write_text("\n".join(speech) + "\n", encoding="utf-8")
    check("speech-keyless-probe", "Keyless ON_DEVICE synthesis/recognition or documented fallback", speech, any("speechProbe=" in line for line in speech))


def family_checks():
    base = "http://127.0.0.1:8787"
    status, _ = http(base, "/health")
    assert status == 200
    screen("consent")
    try:
        visible("가족 연결 시작", click=True, attempts=1, scroll=False)
    except AssertionError:
        # Reuse the locally connected demo family; never clear the app's existing data.
        visible("서버 연결 다시 시도", click=True)
    node = visible("연결 번호", scroll=False, attempts=12)
    pair_code = re.search(r"\d{6}", node.get("text")).group(0)
    child_name = "딸 시연 " + uuid.uuid4().hex[:4]
    status, paired = http(base, "/api/pair", "POST", {"pairCode": pair_code, "name": child_name})
    assert status == 201
    child_token = paired["childToken"]
    family = paired["familyId"]
    prefix = "/api/families/" + family
    check("family-create-pair", "App family creation and child pairing 201", {"pair_status": status}, True)
    screen("consent")
    visible("연결한 가족 새로 불러오기", click=True)
    visible(child_name, click=True, scroll=False, attempts=16)
    visible("출발했어요", click=True)
    visible("잘 도착했어요", click=True)
    visible("이 내용에 동의하고 저장", click=True)
    time.sleep(2)
    status, consent = http(base, prefix + "/consent", token=child_token)
    check("consent-on", "Only departure and arrival on, alerts/location off", consent,
          status == 200 and consent["shareDeparture"] and consent["shareArrival"] and not consent["shareAlerts"] and not consent["shareLocation"])
    # Ensure the previous vehicle-only trip has ended and its cooldown is over.
    console("/vehicle/gear?gear=Park")
    console("/vehicle/body?door_fl=true")
    time.sleep(3)
    console("/vehicle/body?door_fl=false")
    time.sleep(16)
    screen("home")
    logs_before = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    console("/vehicle/telltales/simulator?type=TPMS&state=On&color=Yellow")
    visible("타이어 바람", scroll=False)
    time.sleep(2)
    logs_after = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    alert_attempts = logs_after.count("eventHttpAttempt=alert") - logs_before.count("eventHttpAttempt=alert")
    status, events = http(base, prefix + "/events", token=child_token)
    check("alerts-off-not-transmitted", "Alert shown locally with zero alert HTTP attempts/storage", {"http_attempts": alert_attempts, "events": events},
          status == 200 and alert_attempts == 0 and all(e["type"] != "alert" for e in events))
    console("/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow")
    console("/vehicle/gear?gear=Drive")
    time.sleep(2)
    status, events = http(base, prefix + "/events", token=child_token)
    check("departure-child-receives", "P -> D stored departed event visible to child without coordinates", events,
          status == 200 and any(e["type"] == "departed" for e in events) and all("location" not in e for e in events))
    console("/vehicle/gear?gear=Park")
    console("/vehicle/body?door_fl=true")
    time.sleep(3)
    status, events = http(base, prefix + "/events", token=child_token)
    check("arrival-child-receives", "P return + stable door open stores arrived", events,
          status == 200 and any(e["type"] == "arrived" for e in events))
    console("/vehicle/body?door_fl=false")
    status, destination = http(base, prefix + "/destinations", "POST", {"name": "서울 중앙병원", "address": "서울특별시 중구 세종대로", "lat": 37.5665, "lng": 126.9780, "note": "천천히 오세요. 도착하면 만나요."}, token=child_token)
    assert status == 201
    screen("consent")
    visible("연결한 가족 새로 불러오기", click=True)
    screen("destinations")
    visible("서울 중앙병원", scroll=False, attempts=14)
    capture("05-destination", "서울 중앙병원")
    visible("좋아요 · 안내 요청", click=True)
    time.sleep(4)
    status, destinations = http(base, prefix + "/destinations", token=child_token)
    accepted = next((d for d in destinations if d["id"] == destination["id"]), {})
    logs = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    check("destination-request-accepted", "Card acceptance -> NaviHelper request and server status accepted", accepted,
          status == 200 and accepted.get("status") == "accepted" and "navigation=requestSubmitted" in logs)
    # Request accepted is not evidence of actual route guidance success.
    screen("destinations")
    capture("07-destination-response", "함께 가는 길")
    screen("consent")
    visible("소식 전하기 모두 끄기", click=True)
    time.sleep(3)
    status, consent = http(base, prefix + "/consent", token=child_token)
    # Recipient access is expected to be denied immediately after withdrawal.
    event_status, _ = http(base, prefix + "/events", token=child_token)
    check("consent-withdrawal", "All sharing off and child event access 403", {"consent": consent, "events_status": event_status},
          status == 200 and not any(consent[k] for k in ("shareDeparture", "shareArrival", "shareAlerts", "shareLocation")) and event_status == 403)
    # A local DB count is supplied separately, without serializing any family authentication records.
    if ARGS.db_path:
        db = json.loads(Path(ARGS.db_path).read_text(encoding="utf-8"))
        count = sum(1 for event in db.get("events", []) if event.get("familyId") == family)
        check("shared-data-deleted", "Family event rows deleted", {"remaining_events": count}, count == 0)
    log_lines = [line for line in logs.splitlines() if "GyeoteVehicle" in line]
    (BASE / "docs/car-vehicle.log").write_text("\n".join(log_lines) + "\n", encoding="utf-8")


def finish_checks():
    """Continue after navigation brought its own Activity to the foreground."""
    screen("destinations")
    capture("07-destination-response", "함께 가는 길")
    screen("consent")
    node = visible("연결 번호")
    pair_code = re.search(r"\d{6}", node.get("text")).group(0)
    status, paired = http("http://127.0.0.1:8787", "/api/pair", "POST", {"pairCode": pair_code, "name": "완료 검증"})
    assert status == 201
    prefix = "/api/families/" + paired["familyId"]
    screen("consent")
    visible("소식 전하기 모두 끄기", click=True)
    time.sleep(3)
    status, consent = http("http://127.0.0.1:8787", prefix + "/consent", token=paired["childToken"])
    event_status, _ = http("http://127.0.0.1:8787", prefix + "/events", token=paired["childToken"])
    check("consent-withdrawal", "All sharing off and child event access 403", {"consent": consent, "events_status": event_status},
          status == 200 and not any(consent[k] for k in ("shareDeparture", "shareArrival", "shareAlerts", "shareLocation")) and event_status == 403)
    if ARGS.db_path:
        db = json.loads(Path(ARGS.db_path).read_text(encoding="utf-8"))
        count = sum(1 for event in db.get("events", []) if event.get("familyId") == paired["familyId"])
        check("shared-data-deleted", "Family event rows deleted", {"remaining_events": count}, count == 0)
    logs = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    (BASE / "docs/car-vehicle.log").write_text(logs, encoding="utf-8")
    check("navigation-capture-recovered", "Reopen car Activity after NaviHelper foreground transition", "Response page captured and privacy withdrawal verified", True)


def main():
    global ARGS, SERIAL
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--phase", choices=("vehicle", "family", "all", "finish"), default="all")
    parser.add_argument("--db-path")
    ARGS = parser.parse_args()
    SERIAL = ARGS.serial
    if ARGS.phase == "finish" and REPORT.exists():
        previous = json.loads(REPORT.read_text(encoding="utf-8"))
        RESULTS.extend(previous["results"]); HTTP.extend(previous["http"]); CAPTURES.extend(previous["captures"])
    try:
        with console_session():
            if ARGS.phase in ("vehicle", "all"):
                vehicle_checks()
            if ARGS.phase in ("family", "all"):
                family_checks()
            if ARGS.phase == "finish":
                finish_checks()
    except Exception as error:
        check("run-error", "All scenarios complete", {"type": type(error).__name__, "message": str(error)}, False)
        raise
    finally:
        save()


if __name__ == "__main__":
    main()
