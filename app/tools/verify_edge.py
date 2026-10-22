"""Collect source preservation, JUnit, lint, manifest and actual UI evidence without private data."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def methods(root):
    result = set()
    for path in root.rglob("*.kt"):
        result.update((str(path.relative_to(root)), name) for name in re.findall(
            r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)", path.read_text(encoding="utf-8")))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backup", type=Path, required=True)
    args = parser.parse_args()
    results = []

    def check(name, expected, observed, passed):
        results.append(dict(scenario=name, expected=expected, observed=observed, passed=bool(passed)))
        print(("PASS " if passed else "FAIL ") + name)

    baseline = methods(args.backup / "src/test")
    current = methods(BASE / "src/test")
    totals = {}
    for folder in (BASE / "build/test-results").glob("test*UnitTest"):
        suites = [ET.parse(path).getroot() for path in folder.glob("TEST-*.xml")]
        totals[folder.name] = {key: sum(int(suite.get(key, "0")) for suite in suites) for key in ("tests", "failures", "errors", "skipped")}
    summary = dict(baseline_test_methods=len(baseline), current_test_methods=len(current),
        added_test_methods=len(current-baseline), removed_test_methods=sorted(baseline-current), variants=totals)
    (BASE / "docs/edge-test-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    check("original-tests-preserved", "Every original test method remains", summary, not baseline-current)
    check("unit-tests", "Debug and Release suites with no failures/errors/skips", totals,
        len(totals) == 2 and all(value["tests"] > 116 and not any(value[key] for key in ("failures", "errors", "skipped")) for value in totals.values()))

    issues = ET.parse(BASE / "build/reports/lint-results-debug.xml").getroot().findall("issue")
    severity = dict(Counter(issue.get("severity") for issue in issues))
    severity.setdefault("Error", 0)
    severity.setdefault("Fatal", 0)
    check("lint", "No errors or fatal findings", severity, not severity["Error"] and not severity["Fatal"])
    manifests = list((BASE / "build/intermediates").glob("merged_manifests/release/**/AndroidManifest.xml"))
    manifest = manifests[0]
    permissions = [node.get("{http://schemas.android.com/apk/res/android}name") for node in ET.parse(manifest).getroot().findall("uses-permission")]
    check("release-permissions", "No private safety or speech permissions", permissions,
        not any(any(part in name for part in ("PRIVATE_CAR_SAFETY", "TTS_SERVICE", "STT_SERVICE", "RECORD_AUDIO")) for name in permissions))

    banned = tuple("".join(map(chr, codes)) for codes in ((44048, 49884), (52628, 51201), (50948, 52824, 32, 54869, 51064),
        (49892, 48260), (45432, 51064), (51648, 53428, 51060), (44553, 48156, 51652), (54168, 45804, 32, 50724, 51312, 51089)))
    matches = []
    for folder in (BASE / "src", BASE / "tools", BASE / "docs"):
        for path in folder.rglob("*"):
            if not path.is_file() or path.suffix not in (".kt", ".py", ".cjs", ".xml", ".md", ".json", ".log", ".txt"):
                continue
            for line_no, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if any(word in line for word in banned):
                    matches.append(dict(file=str(path.relative_to(BASE)), line=line_no))
    check("word-scan", "Zero prohibited words in app text/artifacts", dict(matches=matches), not matches)

    captures = json.loads((BASE / "docs/edge-integration.json").read_text(encoding="utf-8"))
    trace = captures.get("proxy_http", [])
    withdrawal = max((index for index, item in enumerate(trace) if item["method"] == "DELETE" and item["path"].endswith("/shared-data")), default=-1)
    posts_after_off = sum(item["method"] == "POST" and item["path"].endswith("/events") for item in trace[withdrawal+1:])
    check("consent-off-no-http", "No event HTTP attempts after withdrawal", dict(withdrawal_observed=withdrawal >= 0, event_posts=posts_after_off), withdrawal >= 0 and posts_after_off == 0)
    required = {"01-home.png", "02-history-parent.png", "03-mechanic.png", "04-consent.png", "05-arrival-confirm.png", "06-arrival-sent.png", "07-driving.png"}
    actual = {item["file"] for item in captures["captures"]}
    check("screenshots", "Seven required screens plus C delivery choices, all 2560x1440", dict(files=sorted(actual)),
        required <= actual and all(struct.unpack(">II", (BASE / "docs/screens/edge" / name).read_bytes()[16:24]) == (2560,1440) for name in actual))
    hidden = ("현재 위치 공유", "가족이 남긴 목소리", "공기압 수치", "차를 돌보는 시간")
    ui_matches = []
    for name in actual:
        values = [node for node in ET.parse(BASE / "docs/screens/edge" / name.replace(".png", ".xml")).getroot().iter("node") if node.get("package") == "ai.gyeote.app"]
        if any(any(fragment in node.get("text", "") for fragment in hidden) for node in values):
            ui_matches.append(name)
    check("hidden-ui", "Hidden feature strings absent from every captured app XML", dict(matches=ui_matches), not ui_matches)
    driving = [node for node in ET.parse(BASE / "docs/screens/edge/07-driving.xml").getroot().iter("node") if node.get("package") == "ai.gyeote.app"]
    controls = sum(node.get("clickable") == "true" or node.get("class", "").endswith(("Button", "EditText")) for node in driving)
    check("driving-inputs", "No interactive app controls while driving", dict(count=controls), controls == 0)
    for file_name, fragment in (("14-consent-default-method.xml", "매번 물어보기"), ("13-consent-automatic.xml", "자동으로 보내기")):
        values = list(ET.parse(BASE / "docs/screens/edge" / file_name).getroot().iter("node"))
        checked = any(fragment in node.get("text", "") and node.get("checked") == "true" for node in values)
        check("mode-choice-" + file_name, "Specified C option is visibly selected", dict(selected=checked), checked)
    apk_hash = digest(BASE / "build/outputs/apk/debug/app-debug.apk")
    check("capture-apk", "All final captures use the final Debug APK", dict(build_sha256=apk_hash, capture_sha256=captures["apk_sha256"]), apk_hash == captures["apk_sha256"])

    modified = []
    for folder in ("src", "tools"):
        for path in (BASE / folder).rglob("*"):
            if not path.is_file() or "__pycache__" in path.parts:
                continue
            old = args.backup / path.relative_to(BASE)
            if not old.exists() or digest(path) != digest(old):
                modified.append("app/" + path.relative_to(BASE).as_posix())
    if digest(BASE / "docs/README.md") != digest(args.backup / "README.md"):
        modified.append("app/docs/README.md")
    (BASE / "docs/edge-modified-files.txt").write_text("\n".join(sorted(modified)) + "\n", encoding="utf-8")
    report = dict(results=results, source_modified=sorted(modified), backup=str(args.backup),
        release_manifest=str(manifest.relative_to(BASE)), passed=all(result["passed"] for result in results))
    (BASE / "docs/edge-static-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
