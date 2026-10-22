"""Aggregate final polish evidence without emitting enrollment or authentication material."""
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from PIL import Image
from car_integration import adb

BASE = Path(__file__).resolve().parents[1]


def main():
    results = []
    def check(name, expected, observed, passed):
        results.append(dict(scenario=name, expected=expected, observed=observed, passed=bool(passed)))
        print(("PASS " if passed else "FAIL ") + name)

    backup = Path((BASE / "docs/polish-backup-path.txt").read_text(encoding="utf-8-sig").strip())
    totals = {}
    for folder in (BASE / "build/test-results").glob("test*UnitTest"):
        suites = [ET.parse(path).getroot() for path in folder.glob("TEST-*.xml")]
        totals[folder.name] = {key: sum(int(suite.get(key, "0")) for suite in suites) for key in ("tests", "failures", "errors", "skipped")}
    (BASE / "docs/polish-test-summary.json").write_text(json.dumps(totals, indent=2) + "\n", encoding="utf-8")
    check("build-tests", "Requested build command succeeds and both variants have zero test failures/errors/skips", totals,
        "BUILD SUCCESSFUL" in (BASE / "docs/polish-build-verification.log").read_text(encoding="utf-8", errors="replace") and
        len(totals) == 2 and all(value["tests"] >= 170 and not any(value[key] for key in ("failures", "errors", "skipped")) for value in totals.values()))
    issues = Counter(issue.get("severity") for issue in ET.parse(BASE / "build/reports/lint-results-debug.xml").getroot().findall("issue"))
    check("lint", "Zero lint errors/fatal findings", dict(issues), not issues["Error"] and not issues["Fatal"])
    manifest = next((BASE / "build/intermediates/merged_manifests/release").rglob("AndroidManifest.xml"))
    permissions = [node.get("{http://schemas.android.com/apk/res/android}name") for node in ET.parse(manifest).getroot().findall("uses-permission")]
    check("release-permission", "Private safety permission absent from final release", permissions,
        not any("PRIVATE_CAR_SAFETY" in name for name in permissions))
    banned = tuple("".join(map(chr, codes)) for codes in ((44048, 49884), (52628, 51201), (50948, 52824, 32, 54869, 51064),
        (49892, 48260), (45432, 51064), (51648, 53428, 51060), (44553, 48156, 51652), (54168, 45804, 32, 50724, 51312, 51089)))
    matches = []
    for folder in (BASE / "src", BASE / "tools", BASE / "docs"):
        for path in folder.rglob("*"):
            if not path.is_file() or path.suffix not in (".kt", ".py", ".cjs", ".xml", ".md", ".json", ".jsonl", ".log", ".txt"):
                continue
            for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if any(word in line for word in banned):
                    matches.append(dict(file=str(path.relative_to(BASE)), line=number))
    check("word-scan", "Zero prohibited terms in app source, tools and text evidence", dict(matches=matches), not matches)
    runtime = json.loads((BASE / "docs/polish-integration.json").read_text(encoding="utf-8"))
    check("runtime", "All direct-server/Pleos scenarios pass", dict(passed=sum(item["passed"] for item in runtime["results"]), total=len(runtime["results"])),
        all(item["passed"] for item in runtime["results"]))
    trace = [json.loads(line) for line in (BASE / "docs/polish-http.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    withdrawal = next(item for item in reversed(trace) if item["method"] == "DELETE" and item["path"].endswith("/shared-data"))
    last_offer = next(line for line in reversed((BASE / "docs/polish-vehicle.log").read_text(encoding="utf-8").splitlines()) if "arrivalOffer " in line)
    app_pid = last_offer.split()[2]
    beginning, ending = datetime.fromisoformat(withdrawal["at"]).timestamp(), datetime.fromisoformat(runtime["at"]).timestamp()
    epoch_logs = adb("logcat", "-d", "-v", "epoch", "-s", "GyeoteVehicle:I").decode("utf-8", errors="replace")
    after_off = []
    for line in epoch_logs.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1] == app_pid:
            try:
                inside = beginning <= float(parts[0]) <= ending
            except ValueError:
                inside = False
            if inside and any(part in line for part in ("journeyEvent=", "arrivalOffer ", "eventHttpAttempt=")):
                after_off.append(line)
    (BASE / "docs/polish-after-off-signal.log").write_text("\n".join(after_off) + "\n", encoding="utf-8")
    observed = dict(app_pid=app_pid, arrived_signals=sum("journeyEvent=ARRIVED" in line for line in after_off),
        client_http_attempts=sum("eventHttpAttempt=" in line for line in after_off),
        disabled_offer=any("allowed=false prompt=false" in line for line in after_off))
    check("arrival-after-withdrawal", "Actual SDK arrival occurs after DELETE with sharing disabled and zero event HTTP attempts", observed,
        observed["arrived_signals"] >= 1 and observed["client_http_attempts"] == 0 and observed["disabled_offer"])
    check("capture-apk", "Captured Debug APK matches final build", dict(sha256=runtime["apk_sha256"]),
        runtime["apk_sha256"] == hashlib.sha256((BASE / "build/outputs/apk/debug/app-debug.apk").read_bytes()).hexdigest())
    captures = runtime["captures"]
    required = {"01-arrival-confirm.png", "02-mechanic.png", "03-settings.png", "04-pairing-countdown.png", "05-debug-menu.png", "08-arrival-reask.png"}
    check("screenshots", "All required landscape screens and reask are captured", dict(count=len(captures), files=[item["file"] for item in captures]),
        required <= {item["file"] for item in captures} and all(item["dimensions"] == [2560, 1440] for item in captures))
    state = ET.parse(BASE / "docs/screens/polish/01-arrival-confirm.xml").getroot()
    primary = next(node for node in state.iter("node") if node.get("text") == "보낼게요")
    secondary = next(node for node in state.iter("node") if node.get("text") == "이번엔 안 보낼게요")
    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))
    left, top, right, bottom = bounds(primary)
    image = Image.open(BASE / "docs/screens/polish/01-arrival-confirm.png").convert("RGB")
    colors = Counter(image.crop((left, top, right, bottom)).get_flattened_data())
    dark, mint = (19, 45, 40), (169, 231, 203)
    def luminance(color):
        def linear(value):
            value /= 255
            return value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4
        return sum(weight * linear(value) for value, weight in zip(color, (0.2126, 0.7152, 0.0722)))
    ratio = (luminance(mint) + 0.05) / (luminance(dark) + 0.05)
    check("arrival-button-contrast", "Primary text/background and button/card contrast >=4.5:1 with larger primary control",
        dict(ratio=round(ratio, 3), primary_pixels=colors[dark], text_pixels=colors[mint], primary_height=bottom-top,
            secondary_height=bounds(secondary)[3]-bounds(secondary)[1]),
        ratio >= 4.5 and colors[dark] > 1000 and colors[mint] > 100 and bottom-top > bounds(secondary)[3]-bounds(secondary)[1])
    release = json.loads((BASE / "docs/polish-release-ui.json").read_text(encoding="utf-8"))
    check("release-ui", "Actual release UI has no developer/editor surface", release["results"], all(item["passed"] for item in release["results"]))
    check("release-apk", "Release UI evidence matches final unsigned build", dict(sha256=release["release_apk_sha256"]),
        release["release_apk_sha256"] == hashlib.sha256((BASE / "build/outputs/apk/release/app-release-unsigned.apk").read_bytes()).hexdigest())
    exit_state = json.loads((BASE / "docs/polish-exit-state.json").read_text(encoding="utf-8"))
    check("exit-state", "Task server stopped and original Debug APK restored", dict(node_exited=exit_state["node_exited"],
        debug_restored=release.get("debug_restored")), exit_state["node_exited"] and release.get("debug_restored"))
    modified = []
    for folder in ("src", "tools"):
        for path in (BASE / folder).rglob("*"):
            if not path.is_file() or "__pycache__" in path.parts:
                continue
            old = backup / path.relative_to(BASE)
            if not old.exists() or old.read_bytes() != path.read_bytes():
                modified.append("app/" + path.relative_to(BASE).as_posix())
    if (BASE / "docs/README.md").read_bytes() != (backup / "README.md").read_bytes():
        modified.append("app/docs/README.md")
    (BASE / "docs/polish-modified-files.txt").write_text("\n".join(sorted(modified)) + "\n", encoding="utf-8")
    report = dict(results=results, modified=sorted(modified), backup=str(backup), passed=all(item["passed"] for item in results))
    (BASE / "docs/polish-static-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assert report["passed"], "Polish verification failed"


if __name__ == "__main__":
    main()
