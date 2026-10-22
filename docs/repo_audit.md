# 공개 전 저장소 감사 (작업 G, 2026-10-22)

대상: `<저장소 루트>`(로컬 작업 폴더) (master, 커밋 1개, 나머지 전부 미추적). 도구: `rg`(ripgrep), `find`. 모든 검색은 `app/build/`, `.gradle/`, `server/data/`, `node_modules/`, `.git/`를 제외했다(이들은 .gitignore 대상). 비밀값은 값을 출력하지 않고 파일:줄만 적었다.

**결론: 비밀값 0건, SDK 재배포 파일 0개 → 합격. 사용자 결정이 필요한 항목은 (e)의 내부 작업 파일, 개인 경로(d), 영상 파일(f)이다.**

## (a) 비밀값 패턴

| 검색 | 결과 |
|---|---|
| `(api[_-]?key\|secret\|passw(or)?d\|token)\s*[=:]\s*['"]값12자+` | **0건** |
| `Bearer <16자+ 토큰>` | 2건 — `app/src/test/.../ApiClientTest.kt:44,62` 모두 마스킹 문자열 `Bearer <masked>` 비교(비밀 아님) |
| AWS·Google·GitHub·OpenAI·Slack 키 접두사, PEM 개인 키 머리글 | **0건** |
| `secret`/`password` 단어 | 소스·도구·XML에서 8건 모두 비밀 아님: `ApiClientTest.kt:127`(URL에 자격 증명이 있으면 거절하는 테스트 입력 `user:password@example.com`), `ApiClient.kt:61`, `SecureStore.kt:18,27,33`(javax.crypto `SecretKey`), `PairingCode.kt:7`(주석), `car_integration.py:50`(로그에서 `token` 필드 가림), UI XML의 `password="false"` 속성 |
| 파일명 `*.jks *.keystore *.pem *.p12 .env* local.properties` | `./local.properties` 1개 — **.gitignore로 제외됨**, 키는 `sdk.dir` 하나뿐(값 미출력) |
| `server/data/db.json` | .gitignore 대상. 데모 가족 토큰이 평문으로 들어 있을 수 있으므로 **절대 커밋하지 않는다**(`server/.gitignore`·루트 모두 제외 확인) |

## (b) 42dot SDK 재배포 위험 파일

| 검색 | 결과 |
|---|---|
| `find . -iname "*.aar" -o -iname "*.jar" -o -iname "*.class" -o -iname "*.dex" -o -iname "*javap*"` (빌드·gradle-wrapper 제외) | **0개** (`gradle/wrapper/gradle-wrapper.jar`는 Gradle 표준 래퍼라 제외 대상) |
| `rg "Compiled from"` (javap 덤프 머리글) | **0건** |
| `rg -i "javap"` | 4건, 모두 "복사하지 않았다/로 확인했다"는 설명 문장(`docs/pleos_sdk_notes.md:3`, `docs/tasks/J_car_integration.md:33`, `docs/tasks/G_github_prep.md:11`, `app/docs/VERIFICATION_car.md:38`) — 덤프 아님 |
| SDK 문서 원문 복사본 | 해당 없음. `docs/pleos_sdk_notes.md`·`research/06_sdk_feasibility.md`는 자체 요약이며 원문 통째 복사본은 발견하지 못함(**전체를 줄 단위로 대조한 것은 아님 — 추정**). 공개 전 두 파일을 한 번 눈으로 훑기를 권한다 |

## (c) 파일 크기

| 기준 | 결과 |
|---|---|
| 100MB 초과 (git 제외 대상 포함 전체) | **0개** |
| 10MB 초과 | 3개 — 모두 `app/build/`(무시됨): `classes2.dex` 10.7MB, `app-debug.apk` 31.7MB, `app-release-unsigned.apk` 28.3MB |
| 10MB 초과, 빌드·무시 폴더 제외 | **0개** |
| 1MB 초과(빌드 제외) | 위 기준으로 목록 0개 |

## (d) 개인 정보

| 검색 | 결과 |
|---|---|
| 이메일 형식 | 실제 이메일 **0건**. 오탐: `ApiClientTest.kt:127`(`password@example.com`), `ApiClient.kt:140-142`(Kotlin `this@ApiClient` 문법) |
| 전화번호(`0N-NNN(N)-NNNN`, `+82`) | **0건** |
| 이름·계정(Windows 로그인 이름·Git 작성자 이름) | 파일 다수(아래 참고). **실명은 아니지만 Windows 사용자 폴더명**이 로컬 절대 경로로 노출됨 |
| 로컬 절대 경로 `C:/Users/<사용자 폴더>` 형태 | 20개 이상 파일 — `docs/dev_env.md`, `docs/pleos_sdk_notes.md`, `research/05_parent_car_evidence.md`, `research/07_proposal_skeleton.md`, `app/docs/VERIFICATION*.md`, `app/docs/*.log`(빌드 로그), `app/docs/*-exit-state.json`·`polish-backup-path.txt` 등 `docs/tasks/*.md`, `AWAY_REPORT.md` |

판단: 개인 식별 위험은 낮지만(로그인 이름 수준), 공개 저장소에서 사용자 폴더명을 숨기려면 위 파일의 경로를 치환해야 한다. **치환·삭제는 하지 않았다(결정은 사용자).** 또한 `git log`의 작성자 이름은 커밋 메타데이터에 남는다(공개 전 확인)이다.

## (e) 내부 작업 파일 — 공개 적합성과 권고 (결정은 사용자)

| 파일·폴더 | 내용 | 권고 | 이유 |
|---|---|---|---|
| `AWAY_REPORT.md` | 자리 비움 중 작업 보고서, 로컬 경로 포함 | **제외** | 내부 진행 기록, 경로 노출 |
| `docs/tasks/` (8개 지시서) | AI 작업자에게 준 지시서, 경로·금지어 목록 포함 | **제외** | 내부 작업 지시, 심사에 불필요. 단 AI 사용 여부를 공개할지는 팀 결정(README에 한 줄 자리) |
| `research/_drafts/` (5개) | 기획서·결정 기록의 이전 초안 | **제외** | 폐기된 버전, 오해 소지 |
| `docs/api_contract.md.bak-v03` | 계약 백업 | **제외** | 백업 사본 |
| `research/01~07` | 조사·기획서 뼈대 | **팀 판단** | 공모전 내용 선공개·미확정 주장(`[추정]` 표기 포함). 포함 시 `05_*`의 로컬 경로 치환 필요 |
| `app/docs/*.log`, `*-attempt*.json`, `*-exit-state.json`, `polish-backup-path.txt` 등 (약 69개 증거 파일) | 빌드·실행 로그와 시도 기록 | **요약 문서만 포함 권고** | 검증 근거로 `VERIFICATION*.md`가 인용하지만 개수가 많고 경로가 들어 있음. 원문 근거를 남기려면 `app/docs/`를 포함하되 경로 치환 |
| `app/docs/screens/**/*.xml` | 화면 UI 덤프 | **포함 가능** | 용량 작고 민감 정보 없음(연결 번호는 캡처 시 가림 처리했다고 문서화됨) |
| `docs/decisions.md`, `docs/dev_env.md` | 결정·환경 기록 | **포함 가능** | 사용자 결정 기록. 경로 1건(`dev_env.md:5`) 치환 권장 |
| `.playwright-mcp/` | 브라우저 자동화 로그 | **제외(.gitignore에 추가함)** | 도구 산출물 |

## (f) `git status --ignored` 요약

```text
미추적(??) : .gitignore README.md AWAY_REPORT.md app/ build.gradle.kts docs/ gradle.properties gradle/
             gradlew gradlew.bat research/ server/ settings.gradle.kts      (git 추적 파일 0개, 커밋 대상 288개)
무시(!!)   : .gradle/  .playwright-mcp/  app/build/  app/docs/emulator-stderr.log
             app/docs/emulator-stdout.log  local.properties  server/data/
```

- 보강 전 `.playwright-mcp/`는 무시되지 않아 미추적(??)으로 보였고, 보강 후 무시(!!)로 바뀌었다.
- 영상: 현재 `media/`·`apk/` 폴더가 없고 `*.mp4`·`*.mov`는 0개. 다른 작업자가 `media/demo/`를 만드는 중이므로 읽지 않았다. **최종 mp4 포함 여부는 사용자 결정**: 규정상 GitHub 가산점은 소스+APK이고 영상은 별도 제출 항목이므로, 저장소에는 넣지 않고 링크로 거는 방안을 권고(GitHub 파일 100MB 제한·저장소 용량 때문). `media/demo/raw/`는 .gitignore로 제외했다.
- APK: `*.apk`를 무시하되 `apk/*.apk`(제출용 `apk/팀명_앱이름_release.apk`)는 예외로 두었다. `git check-ignore`로 `apk/team_app_release.apk`가 무시되지 않음, `app/build/**`·`.env.local`·`server/data/`·`media/demo/raw/`는 무시됨을 확인했다. **release APK는 unsigned이므로 제출 전 서명이 필요하다**(서명 키는 `.jks`로 .gitignore 제외).

## .gitignore 변경 (기존 줄 유지, 추가만)

추가: `.playwright-mcp/`, `server/data/`, `node_modules/`, `*.apk` + `!apk/*.apk`, `.env`·`.env.*`(`!.env.example`), `local.properties`, `*.jks *.keystore *.pem *.p12`, `*.hprof hs_err_pid* logcat*.txt emulator-*.log *.avd/ captures/`, `media/demo/raw/`, OS·편집기 파일. 기존 `app/docs/*-stdout.log`·`*-stderr.log`는 그대로다.

## 발견된 후속 사항
- `server/README.md`는 "계약 테스트 22개"라고 적었으나 현재 `node --test server/test`는 **48개** 통과(2026-10-22 실행). 담당 범위 밖이라 수정하지 않았다 — 갱신 필요.
- 서버 `package.json`의 `npm test`는 `node --test test/index.js`로, README의 `node --test server/test`와 실행 방식이 다르다(동작 비교 안 함).

---

# 작업 G2 — 제외 규칙·경로 치환·최종 검증 (2026-10-22)

근거: `docs/decisions.md` 맨 아래 'GitHub' 결정(핵심만, 비공개로 시작, AI 도구 사용 한 줄, 라이선스 정하지 않음). **실제 `git add`·커밋·푸시는 하지 않았다**(`git add -n`으로 목록만 확인).

## 1. .gitignore 추가 (기존 줄 유지, 끝에 추가)

| 규칙 | 이유 |
|---|---|
| `AWAY_REPORT.md`, `docs/tasks/`, `research/`, `docs/*.bak*` | 내부 보고·AI 지시서·초안/조사·백업 |
| `media/demo/` | 영상·촬영 스크립트 전체(영상은 별도 제출) |
| `app/docs/*.log`, `*-attempt*.json`, `*-exit-state.json`, `*-static-verification.json`, `polish-backup-path.txt` | 실행 로그·시도 기록 |
| `__pycache__/`, `*.pyc` | `app/tools/__pycache__`의 .pyc 7개가 목록에 잡혀서 추가 |

포함으로 둔 것: `app/docs/VERIFICATION*.md`, `README.md`, `screens/**`, 요약 json(`*-test-summary.json`, `*-runtime-smoke.json`, `*-integration.json`, `polish-release-ui.json`, `car-source-review.json` 등), `polish-http.jsonl`, `*modified-files.txt`.

## 2. 경로 치환 목록

로컬 절대경로(Windows 사용자 폴더 포함)를 아래로 바꿨다. 의미는 유지했고, `polish-release-ui.json`은 치환 후 JSON으로 다시 읽어 구문을 확인했다.

| 파일 | 치환 내용 | 건수 |
|---|---|---|
| `app/docs/VERIFICATION.md` | 작업 폴더 절대경로 → `<저장소 루트>`, APK 절대경로 → `app/build/outputs/apk/debug/app-debug.apk`(저장소 기준 상대) | 2 |
| `app/docs/VERIFICATION_car.md` | 사용자 임시 폴더 경로 → `<임시 폴더>/…`(고유 하위 폴더명은 유지) | 3 |
| `app/docs/VERIFICATION_edge.md` | 임시 폴더 경로 → `<임시 폴더>/…` | 1 |
| `app/docs/polish-release-ui.json` | `local_signed_apk`의 임시 경로 → `<임시 폴더>/…` | 1 |
| `docs/dev_env.md` | Android SDK 경로 → `<Android SDK 경로>` | 1 |
| `docs/pleos_sdk_notes.md` | SDK 덤프 임시 경로 → `<임시 폴더>/…` | 1 |
| `docs/repo_audit.md`(이 문서) | 작업 폴더·사용자 폴더명·키 접두사 표기 → `<저장소 루트>`, `<사용자 폴더>` 등 | 본문 5줄 |

제외 규칙으로 커밋 대상에서 빠지는 파일(`*-exit-state.json`, `*-static-verification.json`, `polish-backup-path.txt`, `docs/tasks/`, `research/`, `AWAY_REPORT.md`)에 든 경로는 **치환하지 않고 그대로 두었다**(커밋되지 않고 로컬에만 남는다).

## 3. 문서 변경

- `README.md`: `research/` 행과 규정 요약 링크 제거, 라이선스를 "정하지 않음(모든 권리 보유)"으로, AI 표기를 "개발에 AI 코딩 도구(Claude Code, Codex 등)를 활용했다"로 확정.
- `server/README.md`: 계약 테스트 수 22 → 48.

## 4. 커밋 대상 목록 (`git add -n .`, 210개)

폴더별 개수:

| 폴더 | 개수 | 내용 |
|---|---|---|
| (루트) | 7 | `.gitignore`, `README.md`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat` |
| `gradle/wrapper/` | 2 | wrapper `.jar`·`.properties` (Gradle 표준 래퍼, SDK 아님) |
| `app/` (직속) | 1 | `app/build.gradle.kts` |
| `app/src/` | 41 | 앱 소스·테스트·리소스 |
| `app/tools/` | 11 | 검증·캡처 스크립트 |
| `app/docs/` | 23 | `README.md`, `VERIFICATION*.md` 4개, 요약 json/jsonl/txt |
| `app/docs/screens/` | 91 | 화면 캡처 png·UI 덤프 xml |
| `docs/` | 5 | `api_contract.md`, `decisions.md`, `dev_env.md`, `pleos_sdk_notes.md`, `repo_audit.md` |
| `server/` | 29 | `src` 3, `test` 3, `public/child` 3, `scripts` 1, `docs` 16(검증 문서 1 + 캡처 15), `package.json`, `README.md`, `.gitignore` |

`app/docs/`(23개) 전체: `README.md`, `VERIFICATION.md`, `VERIFICATION_car.md`, `VERIFICATION_edge.md`, `VERIFICATION_polish.md`, `car-integration.json`, `car-modified-files.txt`, `car-runtime-smoke.json`, `car-source-review.json`, `car-test-summary.json`, `edge-integration-core.json`, `edge-integration.json`, `edge-modified-files.txt`, `edge-runtime-smoke.json`, `edge-test-summary.json`, `modified-files.txt`, `polish-http.jsonl`, `polish-integration.json`, `polish-modified-files.txt`, `polish-release-ui.json`, `polish-test-summary.json`, `runtime-smoke.json`, `test-summary.json`.

전체 210개 목록은 `git add -n .`로 언제든 다시 뽑을 수 있다. 목록에 `research/`·`docs/tasks/`·`AWAY_REPORT.md`·`media/`·`*.bak*`·`*.log`·`*-attempt*`·`*-exit-state*`·`*-static-verification*`·`polish-backup-path.txt`·`__pycache__`·`*.apk`는 **0건**이다.

## 5. 최종 검증 결과 (커밋 대상 210개 기준)

| 검사 | 기대 | 관찰 | 통과 |
|---|---|---|---|
| 제외 대상이 목록에 있는지 | 0 | 0 | 예 |
| 사용자 폴더 경로(`Users/` + 사용자 폴더명) | 0 | 0 | 예 |
| 이메일 형식(`@도메인.com/net/kr`) | 0 | 0 (오탐 제외: `ApiClientTest.kt:127`의 `password@example.com` 테스트 입력) | 예 |
| 토큰 패턴(`gh*_`, `AKIA…16자`, `Bearer 16자+`, `sk-…`, PEM 머리글) | 0 | 0 (오탐 제외: `ApiClientTest.kt:44,62`의 `Bearer dummy-test-token` 더미 테스트 값) | 예 |
| `README.md` 상대 링크 20개가 커밋 대상 안의 파일·폴더를 가리킴 | 전부 | 전부 (깨진 링크 0) | 예 |
| `server/README.md` 링크 | 전부 | 상대 링크 없음 | 예 |

**알려진 제한 (결정 필요)**: `app/docs/VERIFICATION.md`, `VERIFICATION_car.md`, `VERIFICATION_edge.md`, `VERIFICATION_polish.md`는 이번에 제외한 로그·시도 기록을 링크한다. 커밋하면 아래 **17개 링크가 깨진다**(내용은 로컬에만 남음). 링크 문구를 "로컬 증거 파일(저장소에 없음)"로 바꿀지는 이 작업 범위 밖이라 손대지 않았다.
`build-verification.log`(VERIFICATION), `car-build-verification.log`·`car-vehicle.log`·`car-speech-final.log`·`car-exit-state.json`(car), `edge-build-verification.log`·`edge-static-verification.json`·`edge-runtime.log`·`edge-post-checks.log`·`edge-exit-state.json`(edge), `polish-build-verification.log`·`polish-static-verification.json`·`polish-event-attempts.log`·`polish-after-off-signal.log`·`polish-vehicle.log`·`polish-backup-path.txt`·`polish-exit-state.json`(polish).

기타 남은 사항: 서버 `package.json`의 `npm test`와 README의 `node --test server/test` 실행 방식 차이는 그대로다. 저장소는 비공개로 시작하는 결정이므로 공개 전 `git log` 작성자 이름 노출 여부를 한 번 더 확인한다.
