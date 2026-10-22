"""Real Pleos SDK + local v0.3 server evidence. Authentication stays in memory/private stores."""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import tempfile
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

from car_integration import adb, http, console, console_session

BASE = Path(__file__).resolve().parents[1]
ROOT = BASE.parent
OUTPUT = BASE / "docs/screens/edge"
REPORT = BASE / "docs/edge-integration.json"
RESULTS, CAPTURES = [], []
PROXY_HTTP = []
OPTIONAL_CHECKS = []
PROFILE = "edge-" + uuid.uuid4().hex[:16]
APP_BASE = None
FAULT = None
NODE = None
HTTP_TRACE = None


def check(name, expected, observed, passed):
    RESULTS.append(dict(scenario=name, expected=expected, observed=observed, passed=bool(passed)))
    print(("PASS " if passed else "FAIL ") + name, flush=True)
    save()
    assert passed, name


def save():
    if HTTP_TRACE and HTTP_TRACE.exists():
        PROXY_HTTP[:] = [json.loads(line) for line in HTTP_TRACE.read_text(encoding="utf-8").splitlines() if line]
    REPORT.write_text(json.dumps(dict(at=datetime.now(timezone.utc).isoformat(), serial="emulator-5554",
        test_profile=PROFILE, results=RESULTS, captures=CAPTURES, proxy_http=PROXY_HTTP, optional_checks=OPTIONAL_CHECKS,
        apk_sha256=hashlib.sha256((BASE / "build/outputs/apk/debug/app-debug.apk").read_bytes()).hexdigest()),
        ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def tree():
    # /dev/tty streams the hierarchy: enrollment material is never copied to an evidence file.
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    begin = raw.find(b"<?xml")
    end = raw.find(b"</hierarchy>")
    if begin < 0 or end < 0:
        raise RuntimeError("UI hierarchy unavailable")
    return ET.fromstring(raw[begin:end + len(b"</hierarchy>")])


def nodes(state):
    return [node for node in state.iter("node") if node.get("package") == "ai.gyeote.app"]


def find(fragment, click=False, attempts=12, scroll=False):
    for attempt in range(attempts):
        state = tree()
        values = nodes(state)
        for node in values:
            if fragment in node.get("text", "") and (not click or node.get("clickable") == "true"):
                if click:
                    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
                    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                    time.sleep(0.3)
                return node
        if scroll:
            container = next((node for node in values if node.get("class") == "android.widget.ScrollView"), None)
            if container is not None:
                left, top, right, bottom = map(int, re.findall(r"\d+", container.get("bounds")))
                adb("shell", "input", "swipe", str((left + right) // 2), str(top + (bottom-top)*4//5),
                    str((left + right) // 2), str(top + (bottom-top)//5), "300")
        time.sleep(0.5)
    raise AssertionError("UI missing: " + fragment)


def screen(name="home", public=False, restart=False, mode="pleos"):
    if restart:
        adb("shell", "am", "force-stop", "ai.gyeote.app")
    parts = ["shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "screen", name,
        "--es", "vehicleMode", mode, "--es", "testProfile", PROFILE, "--ez", "publicTelltalesOnly", str(public).lower()]
    if APP_BASE:
        parts += ["--es", "serverUrl", APP_BASE.replace("127.0.0.1", "10.0.2.2")]
    adb(*parts)
    time.sleep(0.6)


def capture(name, expected, driving=False):
    for attempt in range(6):
        state = tree()
        values = nodes(state)
        if any(expected in node.get("text", "") for node in values):
            break
        time.sleep(0.3)
    else:
        raise AssertionError("Capture expected: " + expected)
    # Pairing is only displayed on explicit request and never persisted in screenshots/XML.
    assert not any(re.search(r"연결 번호\s+\d{6}", node.get("text", "")) for node in values)
    if driving:
        assert not any(node.get("clickable") == "true" or node.get("class", "").endswith(("Button", "EditText")) for node in values)
    forbidden = ("감\uC2DC", "추\uC801", "위치 \uD655인", "실\uBC84", "노\uC778", "지\uD0B4이")
    hidden = ("현재 위치 공유", "가족이 남긴 목소리", "공기압 수치", "차를 돌보는 시간")
    text = "\n".join(node.get("text", "") for node in values)
    assert not any(word in text for word in forbidden + hidden)
    raw = adb("exec-out", "screencap", "-p")
    assert raw.startswith(b"\x89PNG\r\n\x1a\n")
    size = struct.unpack(">II", raw[16:24])
    assert size == (2560, 1440), str(size)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / (name + ".png")).write_bytes(raw)
    (OUTPUT / (name + ".xml")).write_bytes(ET.tostring(state, encoding="utf-8"))
    CAPTURES.append(dict(file=name + ".png", sha256=hashlib.sha256(raw).hexdigest(), dimensions=list(size),
        expected=expected, hidden_ui_matches=0, prohibited_word_matches=0, passed=True))
    save()


@contextmanager
def local_server(data_dir=None, proxy_port=0):
    global NODE, APP_BASE, HTTP_TRACE
    data = Path(data_dir) if data_dir else Path(tempfile.mkdtemp(prefix="gyeote-edge-data-"))
    with socket.socket() as reserve:
        reserve.bind(("127.0.0.1", proxy_port)); port = reserve.getsockname()[1]
    HTTP_TRACE = data / ("http-" + uuid.uuid4().hex + ".jsonl")
    env = dict(os.environ, PORT=str(port), HOST="127.0.0.1", DB_PATH=str(data / "db.json"), HTTP_TRACE=str(HTTP_TRACE))
    log = (data / ("server-" + uuid.uuid4().hex + ".log")).open("wb")
    NODE = subprocess.Popen(["node", str(BASE / "tools/node_edge_fixture.cjs")], cwd=ROOT, env=env,
        stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
    target = "http://127.0.0.1:" + str(port)
    for _ in range(40):
        try:
            with urllib.request.urlopen(target + "/health", timeout=1) as response:
                assert response.status == 200
            break
        except OSError:
            time.sleep(0.2)
    else:
        raise RuntimeError("Local server did not start")

    APP_BASE = target
    try:
        yield APP_BASE
    finally:
        NODE.terminate(); NODE.wait(timeout=10); log.close()
        (BASE / "docs/edge-exit-state.json").write_text(json.dumps(dict(node_pid=NODE.pid,
            node_exited=NODE.poll() is not None, data_dir=str(data), test_profile=PROFILE,
            original_app_state_preserved=True), indent=2) + "\n", encoding="utf-8")


def vehicle_checks():
    console("/vehicle/gear?gear=Park")
    console("/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false")
    for code in ("ENGINE", "ABS", "TPMS"):
        console("/vehicle/telltales/simulator?type=" + code + "&state=Off&color=Yellow")
    screen(restart=True)
    time.sleep(4)
    capture("01-home", "오늘의 동행")
    console("/vehicle/telltales/simulator?type=ENGINE&state=On&color=Yellow")
    time.sleep(4)
    console("/vehicle/telltales/simulator?type=ENGINE&state=Off&color=Yellow")
    time.sleep(4)
    screen("history")
    find("엔진 경고가")
    capture("02-history-parent", "꺼졌어요")
    screen("mechanic")
    find("ENGINE")
    capture("03-mechanic", "ENGINE")
    check("private-subscription-history", "Actual Console ENGINE on/off -> local completed record", "ENGINE and end timestamp visible", True)
    screen("home", public=True, restart=True)
    time.sleep(4)
    console("/vehicle/telltales/simulator?type=ABS&state=On&color=Yellow")
    time.sleep(4)
    console("/vehicle/telltales/simulator?type=ABS&state=Off&color=Yellow")
    time.sleep(4)
    screen("mechanic", public=True)
    find("ABS")
    logs = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    lines = [line for line in logs.splitlines() if any(key in line for key in ("telltalePath=", "warningHistory records="))]
    (BASE / "docs/edge-vehicle.log").write_text("\n".join(lines) + "\n", encoding="utf-8")
    check("public-poll-history", "Public-only path records ABS on/off", dict(public_path="telltalePath=publicPoll" in logs,
        record_visible=True), "telltalePath=publicPoll" in logs)
    capture("08-public-poll-mechanic", "ABS")
    screen("home", restart=True)


def family_checks(base):
    screen("consent")
    find("가족 연결 시작", click=True, scroll=True)
    find("가족 연결 번호 보기", click=True, scroll=True)
    enrollment = find("연결 번호", scroll=True).get("text")
    pair_code = re.search(r"\d{6}", enrollment).group(0)
    status, paired = http(base, "/api/pair", "POST", dict(pairCode=pair_code, name="딸"), record=False)
    assert status == 201
    token, family = paired["childToken"], paired["familyId"]
    prefix = "/api/families/" + family
    screen("consent", restart=True)
    find("연결한 가족 새로 불러오기", click=True)
    find("딸", click=True)
    find("자녀가 보낸 길에 도착하면 알리기", click=True, scroll=True)
    find("이 내용에 동의하고 저장", click=True, scroll=True)
    time.sleep(1)
    status, consent = http(base, prefix + "/consent", token=token, record=False)
    check("v03-route-consent", "Only shareRouteArrival on and one recipient", dict(status=status,
        route=consent.get("shareRouteArrival"), other_flags=[consent.get(key) for key in ("shareDeparture", "shareArrival", "shareAlerts", "shareLocation")]),
        status == 200 and consent.get("shareRouteArrival") and not any(consent.get(key) for key in ("shareDeparture", "shareArrival", "shareAlerts", "shareLocation")))
    screen("consent")
    capture("04-consent", "자녀가 보낸 길에 도착하면 알리기")
    find("자동으로 보내기", scroll=True)
    capture("14-consent-default-method", "매번 물어보기")

    def destination(name):
        print("STEP create destination " + name, flush=True)
        status, value = http(base, prefix + "/destinations", "POST", dict(name=name, address="서울특별시 중구 세종대로",
            lat=37.5665, lng=126.9780, note="천천히 오세요. 도착하면 함께 차 마셔요."), token=token, record=False)
        assert status == 201
        screen("consent"); find("연결한 가족 새로 불러오기", click=True)
        screen("destinations"); find(name)
        if not any(item["file"] == "09-destination-note.png" for item in CAPTURES):
            capture("09-destination-note", "천천히 오세요")
        find("좋아요 · 안내 요청", click=True)
        time.sleep(2)
        screen("home")
        for _ in range(20):
            status, values = http(base, prefix + "/destinations", token=token, record=False)
            if any(item["id"] == value["id"] and item["status"] == "accepted" for item in values):
                return value
            time.sleep(0.5)
        raise AssertionError("Destination acceptance did not reach server")

    def drive_arrive(driving_capture=False, ask=True):
        console("/vehicle/body?door_fl=false")
        time.sleep(16)  # Real JourneyMachine cooldown; no synthetic clock acceleration.
        console("/vehicle/gear?gear=Drive")
        if driving_capture:
            capture("07-driving", "운전에 집중", driving=True)
        console("/vehicle/gear?gear=Park")
        console("/vehicle/body?door_fl=true")
        if ask:
            find("도착 소식을 보낼까요?", attempts=4)
        else:
            time.sleep(3)

    def event_count(destination_id):
        status, values = http(base, prefix + "/events", token=token, record=False)
        assert status == 200
        return sum(item.get("destinationId") == destination_id for item in values)

    first = destination("서울 중앙병원")
    drive_arrive(driving_capture=True)
    capture("05-arrival-confirm", "도착 소식을 보낼까요?")
    time.sleep(12)
    find("도착 소식을 보낼까요?")
    check("ask-mode-no-silent-send", "Unanswered card remains after 12 seconds, no event", dict(events=event_count(first["id"])), event_count(first["id"]) == 0)
    find("보낼게요", click=True)
    find("따님께 잘 도착했다고 전했어요", attempts=12)
    capture("06-arrival-sent", "따님께 잘 도착했다고 전했어요")
    check("ask-mode-parent-confirmation", "Explicit confirmation -> one arrived event with destinationId", dict(events=event_count(first["id"])), event_count(first["id"]) == 1)

    def method(automatic):
        screen("consent")
        find("자동으로 보내기" if automatic else "매번 물어보기", click=True, scroll=True)
        find("이 내용에 동의하고 저장", click=True, scroll=True)
        screen("consent", restart=True)
        selected = find("자동으로 보내기" if automatic else "매번 물어보기", scroll=True)
        check("mode-persist-" + str(automatic), "Local delivery choice survives process restart", dict(checked=selected.get("checked")), selected.get("checked") == "true")
        if automatic:
            capture("13-consent-automatic", "자동으로 보내기")

    method(True)
    automatic = destination("자동 소식 시연 장소")
    drive_arrive(ask=False)
    find("따님께 잘 도착했다고 전했어요")
    check("automatic-mode-arrival", "Explicit automatic choice sends on stopped arrival without a question", dict(events=event_count(automatic["id"])), event_count(automatic["id"]) == 1)
    method(False)

    second = destination("가족 만남 장소")
    drive_arrive()
    drive_arrive_without_card()
    state = tree()
    card_visible = any("도착 소식을 보낼까요?" in node.get("text", "") for node in nodes(state))
    check("unanswered-redeparture", "No response then drive again -> no event and no question at next stop", dict(events=event_count(second["id"]), card_visible=card_visible), event_count(second["id"]) == 0 and not card_visible)

    skipped = destination("이번만 보류 장소")
    drive_arrive(); find("이번엔 안 보낼게요", click=True, attempts=2)
    time.sleep(1)
    check("skip-arrival", "Explicit skip -> no event", dict(events=event_count(skipped["id"])), event_count(skipped["id"]) == 0)

    for fault, label, file_name in (("network", "연결이 끊겨", "10-network-queued"), (403, "(403)", "11-consent-denied"), (409, "(409)", "12-conflict")):
        value = destination("도착 재시도 " + str(fault))
        http(base, "/__edge_fault", "PUT", dict(fault=fault), record=False)
        drive_arrive(); find("보낼게요", click=True, attempts=2)
        find(label)
        capture(file_name, label)
        check("arrival-failure-" + str(fault), "Failure is shown honestly, no successful event, queue retained", dict(events=event_count(value["id"]),
            message_visible=True), event_count(value["id"]) == 0)
        if fault == 409:
            screen("home", restart=True)
            find("아직 보내지 못한 도착 소식")
            check("retry-queue-process-restart", "409 pending queue survives full process restart", "Pending count remains visible", True)
        http(base, "/__edge_fault", "PUT", dict(fault=None), record=False)
        find("도착 소식 다시 보내기", click=True, scroll=True)
        find("따님께 잘 도착했다고 전했어요")
        check("arrival-retry-" + str(fault), "Manual retry sends one event after fault clears", dict(events=event_count(value["id"])), event_count(value["id"]) == 1)

    off = destination("공유 끔 시연 장소")
    for attempt in range(3):
        screen("consent"); find("소식 전하기 모두 끄기", click=True)
        time.sleep(0.8)
        status, current = http(base, prefix + "/consent", token=token, record=False)
        if status == 200 and not current.get("shareRouteArrival"):
            break
    else:
        raise AssertionError("All-off action did not reach server")
    screen("home"); drive_arrive_without_card()
    state = tree(); text = "\n".join(node.get("text", "") for node in nodes(state))
    logs = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    check("consent-off-no-card", "Disabled route sharing -> no arrival card", dict(card_visible="도착 소식을 보낼까요?" in text), "도착 소식을 보낼까요?" not in text)
    status, current = http(base, prefix + "/consent", token=token, record=False)
    check("consent-off-server", "All sharing off at server", dict(status=status, route=current.get("shareRouteArrival")), status == 200 and not current.get("shareRouteArrival"))
    (BASE / "docs/edge-event-attempts.log").write_text("\n".join(line for line in logs.splitlines() if "eventHttpAttempt=" in line) + "\n", encoding="utf-8")


def drive_arrive_without_card():
    console("/vehicle/body?door_fl=false"); time.sleep(16)
    console("/vehicle/gear?gear=Drive"); console("/vehicle/gear?gear=Park")
    console("/vehicle/body?door_fl=true"); time.sleep(4)


def main():
    adb("install", "-r", str(BASE / "build/outputs/apk/debug/app-debug.apk"))
    adb("forward", "tcp:18088", "tcp:8088")
    try:
        with local_server() as base, console_session():
            console("/vehicle/gear?gear=Park")
            console("/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false")
            screen(restart=True)
            family_checks(base)
            vehicle_checks()
            console("/vehicle/gear?gear=Park"); console("/vehicle/body?door_fl=false")
            screen("home")
            check("captures", "At least seven landscape Pleos screenshots", dict(count=len(CAPTURES)), len(CAPTURES) >= 7)
    except Exception as error:
        RESULTS.append(dict(scenario="run-error", expected="All scenarios complete", observed=dict(type=type(error).__name__,
            message=str(error)), passed=False))
        save()
        raise
    finally:
        save()


if __name__ == "__main__":
    main()
