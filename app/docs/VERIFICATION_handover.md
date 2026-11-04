# 작업 H1 검증 — 가족 기능 숨기기 + F1 '인계 30초' 카드 (2026-10-26)

근거 파일: `handover-integration.json`(에뮬레이터 시나리오), `handover-vehicle.log`(앱 로그 `GyeoteVehicle`), `handover-build-verification.log`(빌드), `screens/handover/*.png`·`*.xml`(실제 화면과 UI 계층).
에뮬레이터: Pleos Connect `emulator-5554`(2560×1440, 전기차 설정). 앱 상태는 별도 암호화 테스트 프로필 `handoverH1`을 써서 기존 `state.enc`를 건드리지 않았다.

## 합격 기준

| 기준 | expected | observed | passed |
|---|---|---|---|
| (a) 빌드·테스트·린트 | `test assembleDebug assembleRelease lintDebug` 통과 | BUILD SUCCESSFUL. Debug 203개·Release 203개(실패 0, 오류 0, 건너뜀 0; 이전 173 + 새 30). 린트 Error 0, Warning 8(버전 알림·16KB 정렬 등, 새 코드의 경고 0) | true |
| (b) 에뮬레이터 ①~③ | ① TPMS 켰다 끄기 → 기록 ② 잔량 54%·누적 12,345km ③ 문 열림→닫힘 → 카드·TTS → [인수했어요] → 스냅샷 로그, 스크린샷 3장 이상 | ① 기록 생성(open=1 → open=0) ② SOC 54·주행가능거리 210,000m 수신. **누적 km는 12,346으로 진행**(아래 막힌 것) ③ 문 닫힘 직후 카드 표시, [인수했어요] → 스냅샷 저장·재읽기 일치(`restored=true`). 앱은 음성을 요청했지만 **에뮬레이터에 한국어 TTS 음성이 없어 실제 발화는 못 함**. 스크린샷 9장 | **부분**(카드·스냅샷 true, TTS 발화 false, 12,345 정확값 false) |
| (c) 호칭·금지어 0 | 남는 기능의 사용자 문구에 호칭·금지어 0 | 정적 검색: 금지어(이번에 추가한 3개 포함, 목록은 `tools/handover_integration.py`의 코드값) `src/main` 0건. 호칭이 든 문자열은 모두 기능 스위치로 숨긴 경로(아래 표). 실행 화면 9개 UI 계층 검색: 0건. 예외 1건 — 설정의 모드 이름 '가족 공유'(설계 09가 지정한 이름) | true(예외 1건 명시) |
| (d) 권한 | release에 `PRIVATE_CAR_SAFETY` 없음, 새 권한 3개는 main 매니페스트 | release 병합 매니페스트: PRIVATE 0건, `CAR_ENERGY`·`CAR_LIGHTS`·`CAR_SEATS` 있음. debug에만 PRIVATE 1건(기존 유지). 에뮬레이터 `dumpsys package`: 세 권한 granted=true | true |

## 에뮬레이터 시나리오 (`python app/tools/handover_integration.py`)

| 시나리오 | expected | observed | passed |
|---|---|---|---|
| no-card-in-drive | D 상태로 앱 시작: 카드 없음, 입력 버튼 없음 | 카드 로그 없음, 버튼 0 | true |
| 1-tpms-history | 콘솔 TPMS On→Off가 경고 기록 1건 | `warningHistory … open=1` → `open=0` | true |
| 2-energy-odometer | SOC 54, 누적 km, 주행가능거리 수신 | `socPercent=54`, `odometerKm=12346.0`, `rangeMeters=210000.0` | true |
| 2-odometer-exactly-12345 | 누적 12,345km로 설정 | 콘솔이 `traveled_distance_must_not_decrease`(400)로 거부. 에뮬레이터가 이미 12,346km라 12,346 유지 | **false** |
| port-steering-sign | 콘솔 +120 → SDK −120 → 앱 +120 | `steeringSdk=-120.0 steering=120.0` | true |
| port-turn-signal | 콘솔 BlinkLeft → LEFT | `turnSignal=LEFT` | true |
| port-seat-occupancy | 착석 값 수신 | 운전석·조수석 첫 조회 `occupied=true`. 콘솔 쓰기(`/vehicle/seat?kind=occupied…`)는 200이지만 앱 콜백 변화는 없음 → 쓰기 형식 미확인 | true(수신만) |
| 3-trigger | P에서 운전석 문 열림→닫힘 뒤에만 카드 | 열림 때 없음, 닫힘 직후 `handoverCard shown mode=RENTAL gear=PARK` | true |
| 3-card-content | 인사·배터리·거리·기록·누적 km·충전구 | 화면 문구 모두 확인(01-handover-card) | true |
| 3-tts | 같은 내용을 TTS로 읽음 | `handoverSpeech length=NORMAL chars=140` 요청됨, `tts=queued status=음성 엔진이 없어 화면으로 안내해요` | **false** |
| 3-first-ride | [처음 타요] → "다음 단계에서 준비돼요" | 표시(02) | true |
| 3-snapshot | [인수했어요] → 차 상태만 저장·재읽기 | `soc=54 odometerKm=12346.0 warnings= door=false trunk=false chargePort=true restored=true` | true |
| 3-home-after-accept | 홈에 마지막 인수 기록 | 표시(03) | true |
| same-boarding-once | 주행 없이 문을 다시 여닫으면 카드 다시 안 뜸 | 카드 로그 없음 | true |
| drive-no-input | D에서 인계 버튼 없음 | 주행 카드 문장 1개만(04) | true |
| restart-restore | 강제 종료 후 재시작: 스냅샷 복원, P 시작이라 카드 1회 | 카드 1회, 설정 화면에 저장된 기록(05) | true |
| ui-wording | 보이는 화면에 금지어·호칭 0 | 9개 화면 0건, 모드 이름 예외 2화면 | true |

스크린샷: `screens/handover/01-handover-card.png`, `02-first-ride-placeholder.png`, `03-after-accept-home.png`, `04-driving.png`, `05-settings-restored.png`, `ui-home.png`, `ui-history.png`, `ui-mechanic.png`, `ui-settings.png`.
카드의 '타이어 바람 3번'은 같은 테스트 프로필로 시나리오를 세 번 돌린 누적 기록이다(앱이 켜져 있던 동안의 기록만).

## 호칭이 남은 문자열 (정적 검색, 모두 숨김 경로)

| 위치 | 숨기는 스위치 |
|---|---|
| 동의·연결 번호 화면, 서버 연결 문구(`AppController.connection` 등) | `Features.familyConnection`·`serverSync`(탭·이동 차단, 상단 줄은 '인계 방식'으로 대체) |
| 보낸 길·도착 소식·자녀 한마디(홈 카드, 주행 카드, 목적지 화면, 콘솔 카드) | `Features.sharedRoutes` |
| 음성 메시지 화면·예시 메시지 | `Features.familyVoiceMessages`(기존) |
| 모드 이름 '가족 공유' | **예외**: 설계 09가 지정한 설정 이름. 사람 호칭이 아니라 쓰는 방식 이름이라 남겼다. 바꿀지는 결정 필요 |

## 한계
- 경고등 이력은 앱이 켜져 있던 동안의 기록이다. 앱 비실행 중 발생은 복원하지 않는다(카드에도 같은 문장 표시).
- 운전석 문은 `DoorValue.ROW_1_LEFT`(좌핸들)로 가정했다. 문 위치를 주지 않는 차는 '아무 문'으로 판정한다.
- 주행가능거리 단위는 미터로 실측(콘솔 `remaining_range_m=210000` → SDK 210000.0)해 km로 바꾼다.
- 핸들 각도의 +가 실제로 왼쪽인지 오른쪽인지는 아직 확인하지 않았다(콘솔 부호에 맞추기만 함).

## H2 이후 (2026-10-26)
- [처음 타요]는 이제 인수 스냅샷을 남기고 F2 투어를 연다(`VERIFICATION_tour.md`). 그래서 `handover_integration.py`의 `3-first-ride` 항목은 "카드에 [처음 타요]가 있다"만 확인하도록 고쳤다. `screens/handover/02-first-ride-placeholder.png`는 H1 당시 화면으로 남아 있다(지금은 나타나지 않음).
- H2 뒤 재실행: 17개 중 15개 통과, 실패 2개는 위와 같은 `2-odometer-exactly-12345`·`3-tts`.

## 2026-10-31 Passer 재검증 (작업 H6)
- 앱 표시 이름 「곁에」→ **Passer**(`strings.xml`만, 패키지 `ai.gyeote.app` 유지). `gradlew test assembleDebug assembleRelease lintDebug` 성공, 단위 테스트 **244개 통과**(debug·release 각 244, 실패 0), lint 오류 0. release APK 권한에 `PRIVATE_CAR_SAFETY` 없음(aapt, debug에만 있음). debug APK sha256 앞 12자리 `e993f8b6bac2`.
- 에뮬레이터를 새로 켠 뒤라 누적 km가 0에서 시작해 `2-odometer-exactly-12345`가 이번에는 통과했다. 결과 **17개 중 16개 통과**, 실패는 환경 문제인 `3-tts`(한국어 음성 없음) 1개.
- 스크린샷·UI 덤프·`handover-integration.json`을 새로 만들었고 상단 제목은 'Passer'. `02-first-ride-placeholder`는 H1 때 화면이라 다시 만들 수 없어 옛 이름이 남아 있다(지금은 나타나지 않는 화면).
- 내비 목적지명 콜백 실측 결과는 `VERIFICATION_return.md` 10-31 절.

## 2026-10-31 H7 (모드 라벨·동작 조정)
- 설정의 모드 순서·라벨: **렌터카·카셰어 · 기본 → 법인·택시 → 함께 쓰는 차 → 끄기**(`screens/handover/ui-settings.png`, 새 항목 `settings-mode-order` 통과). 위 표의 모드 이름 예외 1건은 라벨이 바뀌어 없어졌다.
- 모드별 기본 발화 길이: 렌터카·카셰어 **짧게**(이전 보통), 법인·택시 보통, 함께 쓰는 차 짧게. 저장된 길이는 그대로 우선한다. 그래서 `3-tts`는 `length=SHORT|NORMAL` 둘 다 받도록 고쳤다(이번 실행 `length=SHORT chars=86`).
- 재실행(debug `d10a2cd0c078`): **18개 중 16개 통과**. 실패 2개는 환경 문제: `3-tts`(한국어 음성 없음), `2-odometer-exactly-12345`(누적 km가 이미 12,387km라 줄일 수 없음). 단위 테스트 249개(debug·release 각 249, 실패 0).

## 2026-11-04 H8
- 기록 foreground service를 추가했다. 화면 콜백만 붙였다 떼며 화면 뒤에서도 경고등 공개 3초 조회·인수/반납 판정을 유지한다. 빌드 성공, 단위 테스트 Debug·Release 각 **259개**, lint 오류 0.
- F1 재실행 **17/18** 통과(허용 환경 실패 `3-tts` 1개), F2 **16/16**, F3 **14/14**. debug APK sha256 앞 12자리 `63d07bdd7740`.
- 실제 화면이 멈춘 뒤 **65초** 대기 → TPMS 켬/끔 기록, 서비스 `isForeground=true`, 프로세스 `curProcState=4`·`isFrozen=false`. 화면 뒤 P+운전석 문 여닫기로 준비된 인계 카드를 앱 복귀 직후 확인했다(`screens/background/01-history-and-handover.png`). 배경 인계 발화 0건.
- 기록 범위 문구에 화면을 닫아도 기록한다는 설명과 끄기·앱 종료의 한계를 넣었다. 전체 문구·A~G 판정·알림 및 내비 실측은 [VERIFICATION_background.md](VERIFICATION_background.md), 원시 결과는 `background-integration.json`.
- 한계: 실차·앱마켓 정책 미확인, 한국어 TTS는 요청까지만 확인. 프로세스 종료 중 기록과 대기 카드 복원은 보장하지 않는다. HOME 단독으로는 다중 창의 앱이 계속 resumed여서 내비 화면으로 덮고 화면 해제를 확인한 뒤 검사했다.
