"""Focused installed-APK checks after the complete server/vehicle run, using its isolated profile."""
import hashlib
import json
import time

import edge_integration as edge


def main():
    core_copy = edge.BASE / "docs/edge-integration-core.json"
    latest = json.loads(edge.REPORT.read_text(encoding="utf-8"))
    cached = json.loads(core_copy.read_text(encoding="utf-8")) if core_copy.exists() else None
    same_run = cached is not None and cached["test_profile"] == latest["test_profile"] and cached["apk_sha256"] == latest["apk_sha256"]
    previous = cached if same_run else latest
    assert all(item["passed"] for item in previous["results"]), "Complete the core run first"
    apk = edge.BASE / "build/outputs/apk/debug/app-debug.apk"
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == previous["apk_sha256"]
    if not same_run:
        if cached:
            archive = edge.BASE / "docs" / ("edge-integration-core-" + cached["test_profile"] + ".json")
            if not archive.exists():
                archive.write_text(core_copy.read_text(encoding="utf-8"), encoding="utf-8")
        core_copy.write_text(edge.REPORT.read_text(encoding="utf-8"), encoding="utf-8")
    edge.PROFILE = previous["test_profile"]
    edge.RESULTS.extend(previous["results"])
    edge.CAPTURES.extend(previous["captures"])
    edge.PROXY_HTTP.extend(previous.get("proxy_http", []))

    def detail(code, mode="pleos"):
        edge.adb("shell", "am", "start", "-W", "-n", "ai.gyeote.app/.MainActivity", "--es", "screen", "warning",
            "--es", "warning", code, "--es", "vehicleMode", mode, "--es", "testProfile", edge.PROFILE)
        time.sleep(0.7)

    with edge.console_session():
        edge.console("/vehicle/gear?gear=Park")
        edge.console("/vehicle/body?door_fl=false")
        edge.screen("home", restart=True)
        for sdk_code, app_code, expected, name in (
            ("TPMS", "TPMS", "타이어 공기압이 낮을 수 있어요.", "19-tpms-detail"),
            ("FCA", "FCA", "줄였을 수 있어요.", "20-fca-detail"),
            ("ACCEL_RESPONSE_DASH", "SAFETY_ASSIST", "차의 안전 기능이 작동했을 수 있어요.", "21-safety-meaning-unconfirmed"),
        ):
            status, response = edge.http("http://127.0.0.1:18088", "/vehicle/telltales/simulator?type=" + sdk_code + "&state=On&color=Yellow", "PUT", record=False)
            real = status == 200
            if not real:
                edge.OPTIONAL_CHECKS.append(dict(scenario="console-input-" + sdk_code,
                    expected="Console accepts the SDK constant for a live signal check",
                    observed=dict(http_status=status), passed=False))
            time.sleep(2)
            detail(app_code, "pleos" if real else "fake")
            edge.find(expected)
            edge.capture(name, expected)
            edge.check(("sdk-detail-" if real else "mock-detail-") + sdk_code,
                "Expected cautious description and hidden numeric UI; provenance explicitly recorded",
                dict(signal=sdk_code, source="actual-sdk" if real else "fake-debug", text_visible=True), True)
            if real:
                edge.console("/vehicle/telltales/simulator?type=" + sdk_code + "&state=Off&color=Yellow")
            else:
                edge.screen("home", mode="pleos", restart=True)
                time.sleep(2)
        pid = edge.adb("shell", "pidof", "ai.gyeote.app").decode().strip()
        logs = edge.adb("logcat", "-d", "--pid", pid, "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
        paths = [line for line in logs.splitlines() if "telltalePath=" in line]
        (edge.BASE / "docs/edge-paths-final.log").write_text("\n".join(paths) + "\n", encoding="utf-8")
        registered = "telltalePath=privateSubscription+publicPoll" in logs
        edge.check("debug-private-subscription-active", "Actual final APK registers the permitted debug subscription",
            dict(registered=registered), registered)
        time.sleep(3)
        edge.screen("history")
        edge.find("경고 기록 지우기", click=True, scroll=True)
        edge.find("이 차의 경고 기록을 모두 지울까요?", scroll=True)
        edge.capture("15-clear-confirmation", "이 차의 경고 기록을 모두 지울까요?")
        edge.find("기록을 남겨둘게요", click=True, scroll=True)
        edge.screen("history")
        edge.find("경고가", scroll=True)
        edge.check("history-clear-cancel", "Cancel keeps actual observed records", "Timeline remains visible", True)
        edge.find("경고 기록 지우기", click=True, scroll=True)
        edge.find("네, 기록을 지울게요", click=True, scroll=True)
        edge.screen("history")
        edge.find("아직 기록된 경고가 없어요.")
        edge.capture("16-history-cleared", "아직 기록된 경고가 없어요.")
        edge.check("history-clear-confirmed", "Explicit confirmation clears only the isolated profile history", "Empty timeline visible", True)
        edge.screen("maintenance")
        edge.capture("17-hidden-maintenance", "차 안에 남긴 경고 기록")
        edge.check("hidden-maintenance-redirect", "Old debug route opens merged history instead of standalone maintenance", "History shown", True)
        edge.screen("messages")
        edge.capture("18-hidden-messages", "오늘의 동행")
        edge.check("hidden-messages-redirect", "Old debug voice route opens home", "Home shown", True)
    edge.save()


if __name__ == "__main__":
    main()
