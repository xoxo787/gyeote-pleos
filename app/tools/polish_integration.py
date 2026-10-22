"""Direct real-server/real-Pleos UI evidence, with enrollment and authentication kept private."""
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import socket
import tempfile
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

from car_integration import adb, console, console_session, http
from edge_integration import tree, nodes, find

BASE = Path(__file__).resolve().parents[1]
ROOT = BASE.parent
OUT = BASE / "docs/screens/polish"
REPORT = BASE / "docs/polish-integration.json"
TRACE = BASE / "docs/polish-http.jsonl"
PROFILE = "polish-" + uuid.uuid4().hex[:16]
RESULTS, CAPTURES, HTTP = [], [], []
LOCAL = "http://127.0.0.1:8787"
FLAGS = ("shareDeparture", "shareArrival", "shareAlerts", "shareLocation", "shareRouteArrival")
APK = BASE / "build/outputs/apk/debug/app-debug.apk"


def save():
    REPORT.write_text(json.dumps(dict(at=datetime.now(timezone.utc).isoformat(), serial="emulator-5554",
        test_profile=PROFILE, apk_sha256=hashlib.sha256(APK.read_bytes()).hexdigest(),
        results=RESULTS, captures=CAPTURES, http=HTTP), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check(name, expected, observed, passed):
    RESULTS.append(dict(scenario=name, expected=expected, observed=observed, passed=bool(passed)))
    save()
    print(("PASS " if passed else "FAIL ") + name, flush=True)
    assert passed, name


def screen(name="home", restart=False):
    if restart:
        adb("shell", "am", "force-stop", "ai.gyeote.app")
    adb("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "screen", name,
        "--es", "vehicleMode", "pleos", "--es", "testProfile", PROFILE,
        "--es", "serverUrl", "http://10.0.2.2:8787")
    time.sleep(0.6)


def capture(name, expected):
    find(expected, scroll=False)
    state = tree()
    text = "\n".join(node.get("text", "") for node in nodes(state))
    assert not re.search(r"연결 번호\s+\d{6}", text), "Enrollment must be hidden before capture"
    raw = adb("exec-out", "screencap", "-p")
    size = struct.unpack(">II", raw[16:24])
    assert size == (2560, 1440)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / (name + ".png")).write_bytes(raw)
    (OUT / (name + ".xml")).write_bytes(ET.tostring(state, encoding="utf-8"))
    CAPTURES.append(dict(file=name + ".png", sha256=hashlib.sha256(raw).hexdigest(), dimensions=list(size), passed=True))
    save()
    return state


def request(path, method="GET", body=None, token=None):
    status, value = http(LOCAL, path, method, body, token, record=False)
    entry = dict(method=method, path=re.sub(r"/(families|destinations)/[^/?]+", r"/\1/[id]", path),
        status=status, authorization="[masked]" if token else "[absent]")
    if path.endswith("/consent") and isinstance(value, dict):
        entry["response"] = {key: value.get(key) for key in FLAGS}
        entry["response"]["recipientCount"] = len(value.get("recipients", []))
    elif isinstance(value, dict) and "error" in value:
        entry["response"] = dict(error=value["error"])
    HTTP.append(entry)
    save()
    return status, value


@contextmanager
def direct_server():
    data = Path(tempfile.mkdtemp(prefix="gyeote-polish-data-"))
    with socket.socket() as reserve:
        reserve.bind(("127.0.0.1", 8787))
    env = dict(os.environ, DB_PATH=str(data / "db.json"), HTTP_TRACE=str(TRACE))
    with (data / "server.log").open("wb") as log:
        process = subprocess.Popen(["node", str(BASE / "tools/node_polish_server.cjs")], cwd=ROOT, env=env,
            stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            for _ in range(50):
                if process.poll() is not None:
                    raise RuntimeError("Port 8787 server could not start; existing listeners are preserved")
                try:
                    with urllib.request.urlopen(LOCAL + "/health", timeout=1) as response:
                        assert response.status == 200
                    break
                except OSError:
                    time.sleep(0.2)
            else:
                raise RuntimeError("Local server did not become ready")
            yield
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
            (BASE / "docs/polish-exit-state.json").write_text(json.dumps(dict(node_pid=process.pid,
                node_exited=process.poll() is not None, data_dir=str(data), test_profile=PROFILE,
                original_app_data_preserved=True, emulator="emulator-5554"), indent=2) + "\n", encoding="utf-8")


def enrollment():
    screen("consent", restart=True)
    state = capture("03-settings", "내가 고른 소식만")
    check("debug-settings-no-server", "Normal settings contains no server-address editor",
        dict(server_editor=False), not any(node.get("text") == "서버 주소" for node in nodes(state)))
    find("가족 연결 시작", click=True, scroll=True)
    find("가족 연결 번호 보기", click=True, scroll=True)
    first = re.search(r"\d{6}", find("연결 번호  ").get("text")).group(0)
    find("새 번호 받기", click=True)
    second = re.search(r"\d{6}", find("연결 번호  ").get("text")).group(0)
    status, _ = request("/api/pair", "POST", dict(pairCode=first, name="가족"))
    check("fresh-code-invalidates-old", "Explicit reissue replaces earlier one-use code", dict(old_status=status, changed=first != second),
        status == 404 and first != second)
    before = find("남은 시간").get("text")
    time.sleep(2)
    after = find("남은 시간").get("text")
    check("live-countdown", "Remaining time changes without rebuilding the page", dict(before=before, after=after), before != after)
    find("번호 가리기", click=True)
    capture("04-pairing-countdown", "남은 시간")
    find("새 번호 받기", click=True)
    digits = re.search(r"\d{6}", find("연결 번호  ").get("text")).group(0)
    status, child = request("/api/pair", "POST", dict(pairCode=digits, name="딸"))
    assert status == 201
    again, _ = request("/api/pair", "POST", dict(pairCode=digits, name="가족"))
    check("pair-code-single-use", "Consumed code cannot enroll another child", dict(first=status, second=again), status == 201 and again == 404)
    screen("consent")
    find("연결한 가족 새로 불러오기", click=True)
    find("딸", click=True)
    find("자녀가 보낸 길에 도착하면 알리기", click=True, scroll=True)
    find("이 내용에 동의하고 저장", click=True, scroll=True)
    time.sleep(1)
    prefix, token = "/api/families/" + child["familyId"], child["childToken"]
    status, consent = request(prefix + "/consent", token=token)
    check("route-sharing-on", "Only route-arrival consent enabled for one recipient", dict(status=status, flags={key: consent.get(key) for key in FLAGS}),
        status == 200 and consent.get("shareRouteArrival") and not any(consent.get(key) for key in FLAGS[:-1]))
    screen("consent")
    find("가족 연결 번호 보기", scroll=True)
    state = capture("06-settings-family", "가족과 이어지기")
    check("family-settings-no-server", "Lower settings card also has no server address or developer controls",
        dict(server_editor=False), not any(node.get("text") == "서버 주소" for node in nodes(state)))
    screen("console")
    capture("05-debug-menu", "개발 연결 설정")
    return prefix, token


def destination(prefix, token, name):
    status, value = request(prefix + "/destinations", "POST", dict(name=name, address="서울특별시 중구 세종대로",
        lat=37.5665, lng=126.9780, note="천천히 오세요."), token)
    assert status == 201
    screen("consent")
    find("연결한 가족 새로 불러오기", click=True)
    screen("destinations")
    find(name)
    find("좋아요 · 안내 요청", click=True)
    for _ in range(20):
        _, values = request(prefix + "/destinations", token=token)
        if any(item["id"] == value["id"] and item["status"] == "accepted" for item in values):
            return value
        time.sleep(0.5)
    raise AssertionError("Destination acceptance did not reach direct server")


def drive_arrive():
    console("/vehicle/body?door_fl=false")
    time.sleep(16)
    console("/vehicle/gear?gear=Drive")
    console("/vehicle/gear?gear=Park")
    console("/vehicle/body?door_fl=true")
    time.sleep(4)
    # Console/gear changes can focus the left driving-view task on this multiwindow AVD.
    # Bring the actual app task forward before reading its accessibility hierarchy.
    screen("home")


def family_checks(prefix, token):
    destination(prefix, token, "서울 중앙병원")
    screen("home")
    drive_arrive()
    capture("01-arrival-confirm", "도착 소식을 보낼까요?")
    drive_arrive()
    capture("08-arrival-reask", "이번에도 답 없이 출발하면")
    status, events = request(prefix + "/events", token=token)
    check("unanswered-reask-no-event", "Unanswered first prompt reappears at next actual stop without sending an event",
        dict(status=status, count=len(events)), status == 200 and len(events) == 0)
    find("보낼게요", click=True)
    find("따님께 잘 도착했다고 전했어요")
    status, events = request(prefix + "/events", token=token)
    check("arrival-positive-control", "Direct server receives exactly one parent-confirmed event", dict(status=status, count=len(events)), status == 200 and len(events) == 1)
    destination(prefix, token, "재질문 종료 장소")
    screen("home")
    drive_arrive()
    find("도착 소식을 보낼까요?")
    drive_arrive()
    find("이번에도 답 없이 출발하면")
    drive_arrive()
    text = "\n".join(node.get("text", "") for node in nodes(tree()))
    _, events = request(prefix + "/events", token=token)
    check("unanswered-reask-closes", "No response to the one reask ends silently at a later stop without another event",
        dict(card="도착 소식을 보낼까요?" in text, events=len(events)), "도착 소식을 보낼까요?" not in text and len(events) == 1)
    destination(prefix, token, "가족 만남 장소")
    screen("consent")
    find("소식 전하기 모두 끄기", click=True)
    for _ in range(20):
        time.sleep(0.5)
        status, current = request(prefix + "/consent", token=token)
        trace = [json.loads(line) for line in TRACE.read_text(encoding="utf-8").splitlines() if line.strip()]
        deleted = any(item["method"] == "DELETE" and item["path"].endswith("/shared-data") and item["status"] == 204 for item in trace)
        if status == 200 and not any(current.get(key) for key in FLAGS) and deleted:
            break
    trace = [json.loads(line) for line in TRACE.read_text(encoding="utf-8").splitlines() if line.strip()]
    deletions = [item for item in trace if item["method"] == "DELETE" and item["path"].endswith("/shared-data")]
    check("all-off-server", "Actual UI tap turns all server consent flags off and invokes shared-data deletion",
        dict(status=status, flags={key: current.get(key) for key in FLAGS}, delete_statuses=[item["status"] for item in deletions]),
        status == 200 and not any(current.get(key) for key in FLAGS) and any(item["status"] == 204 for item in deletions))
    capture("07-all-off", "소식 전하기 모두 끄기")
    baseline = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    before_attempts = sum("eventHttpAttempt=" in line for line in baseline.splitlines())
    screen("home")
    drive_arrive()
    time.sleep(11)
    logs = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    attempts = [line for line in logs.splitlines() if "eventHttpAttempt=" in line]
    trace = [json.loads(line) for line in TRACE.read_text(encoding="utf-8").splitlines() if line.strip()]
    last_delete = max(index for index, item in enumerate(trace) if item["method"] == "DELETE" and item["path"].endswith("/shared-data"))
    posts = sum(item["method"] == "POST" and item["path"].endswith("/events") for item in trace[last_delete + 1:])
    text = "\n".join(node.get("text", "") for node in nodes(tree()))
    (BASE / "docs/polish-event-attempts.log").write_text("Before withdrawal event-attempt count: " + str(before_attempts) +
        "\nAfter withdrawal new event-attempt count: " + str(len(attempts) - before_attempts) +
        "\nServer event POST count after DELETE: " + str(posts) + "\n" + "\n".join(attempts[before_attempts:]) + "\n", encoding="utf-8")
    check("off-arrival-no-http", "Actual Console drive/park/door arrival after withdrawal causes zero event HTTP attempts",
        dict(server_posts=posts, client_attempts=len(attempts) - before_attempts, arrival_card="도착 소식을 보낼까요?" in text),
        posts == 0 and len(attempts) == before_attempts and "도착 소식을 보낼까요?" not in text)


def mechanic_checks():
    console("/vehicle/telltales/simulator?type=ENGINE&state=On&color=Yellow")
    time.sleep(4)
    console("/vehicle/telltales/simulator?type=ENGINE&state=Off&color=Yellow")
    time.sleep(4)
    screen("mechanic")
    state = capture("02-mechanic", "ENGINE")
    text = "\n".join(node.get("text", "") for node in nodes(state))
    check("mechanic-no-raw-placeholders", "Actual SDK warning record preserves standard/SDK names without sentinel metadata",
        dict(engine="ENGINE" in text, placeholders=False), "ENGINE" in text and not any(part in text for part in ("우선순위", "SDK 횟수", "횟수 0")))


def main():
    archive = Path(tempfile.mkdtemp(prefix="gyeote-polish-prior-evidence-"))
    for path in (BASE / "docs").glob("polish-*"):
        if path.is_file() and path.name != "polish-backup-path.txt":
            shutil.copyfile(path, archive / path.name)
    if OUT.exists():
        shutil.copytree(OUT, archive / "screens")
    TRACE.write_text("", encoding="utf-8")
    adb("install", "-r", str(APK))
    adb("forward", "tcp:18088", "tcp:8088")
    try:
        with direct_server(), console_session():
            console("/vehicle/gear?gear=Park")
            console("/vehicle/body?door_fl=false&door_fr=false&door_rl=false&door_rr=false")
            prefix, token = enrollment()
            family_checks(prefix, token)
            mechanic_checks()
            check("landscape-captures", "At least five landscape screenshots covering required screens", dict(count=len(CAPTURES)), len(CAPTURES) >= 5)
    except Exception as error:
        state = tree()
        text = "\n".join(node.get("text", "") for node in nodes(state))
        if not re.search(r"연결 번호\s+\d{6}", text):
            OUT.mkdir(parents=True, exist_ok=True)
            (OUT / "run-failure.xml").write_bytes(ET.tostring(state, encoding="utf-8"))
            (OUT / "run-failure.png").write_bytes(adb("exec-out", "screencap", "-p"))
        RESULTS.append(dict(scenario="run-error", expected="All scenarios complete", observed=dict(type=type(error).__name__, message=str(error)), passed=False))
        save()
        raise
    finally:
        logs = adb("logcat", "-d", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
        safe = ("journeyEvent=", "arrivalOffer ", "gear=", "restricted=", "doorOpen=", "eventHttpAttempt=")
        (BASE / "docs/polish-vehicle.log").write_text("\n".join(line for line in logs.splitlines() if any(part in line for part in safe)) + "\n", encoding="utf-8")
        adb("shell", "am", "force-stop", "ai.gyeote.app")
        adb("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "vehicleMode", "pleos")


if __name__ == "__main__":
    main()
