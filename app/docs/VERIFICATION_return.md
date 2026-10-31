# 작업 H3 검증 — F3 '돌려주기 카드' + 법인 운행 표 + 기록 화면 정리 (2026-10-27)

근거 파일: `return-build-verification.log`(빌드·테스트·린트, 개인 경로는 `<project>`/`<home>`으로 가림), 단위 테스트 결과(`app/build/test-results/`, 저장소 밖 빌드 산출물).
에뮬레이터 확인(b)(d)는 2026-10-27 작업 H4에서 실행했다. 근거: `return-integration.json`, `return-vehicle.log`(앱 로그 `GyeoteVehicle`, 저장소 무시 목록), `screens/return/*.png`·`*.xml`, `screens/return/window-nav-probe.txt`. 자세한 내용은 맨 끝 "2026-10-27 에뮬레이터 검증" 절. (처음 작성 때는 에뮬레이터가 메모리 부족으로 종료돼 스크립트만 써 두었다.)

## 합격 기준

| 기준 | expected | observed | passed |
|---|---|---|---|
| (a) 빌드·테스트·린트 | `test assembleDebug assembleRelease lintDebug` 통과, 216개 유지 + 새 테스트 | BUILD SUCCESSFUL. Debug 244개·Release 244개(실패 0, 오류 0, 건너뜀 0; 216 + 새 28). 린트 Error 0, Warning 8(H2와 같은 종류: 버전 알림·16KB 정렬 등) | true |
| (b) 에뮬레이터 시나리오 | 인수 → D → `soc=41`·누적 +32km·TPMS 켬 → P → 문 열림 → 돌려주기 카드(54→41%, +32km, 새 경고등 1) → 한마디 → [돌려줬어요] → '인계 이력' 1쌍 → 새 인계 카드에 한마디 → 법인 운행 표 1건, 스크린샷 6장 | 10-27 실행: `return_integration.py` 12개 항목 모두 통과. 카드 로그 `soc=54->41 distanceKm=32.0 newWarnings=1 names=TPMS`, `returnNote saved chars=17`, `returnPair saved … pairs=1`, 다음 인계 카드에 '이전 운전자 한마디', 법인 `tripLog added … distanceKm=5.0 … trips=1`. 스크린샷 6장(`screens/return/01~06`) | **true** |
| (c) 금지어·호칭 0, D에서 돌려주기 카드 0 | 정적 검색 0건, D에서 카드 0 | 정적 검색: 금지어 `src/main` 전체 0건. 새 코드(`domain/Return.kt`, 돌려주기·기록 탭·모두 지우기 화면 블록, 컨트롤러 F3 블록) 금지어·호칭 0건. 예전 콘셉트 문구는 숨긴 경로(`Features.sharedRoutes`)의 '마음이 닿는 소식' 1건만 남음. D 조건은 단위 테스트 + 10-27 실행 화면으로 확인(`d-no-return`: D에서 운전석 문을 열어도 카드 로그 0·카드 문구 0, 스크린샷 06)(`driveHidesAnOpenCard`, `parkAndDriverDoorAfterDriveOffersOnce`) + 화면 조건 `returnVisible`이 `gear == PARK && !restricted`를 요구. 10-27 실행 화면 6장 UI 계층에서도 금지어·호칭 0(`ui-wording`) | **true** |
| (d) H1·H2 스크립트 재실행 | 알려진 환경 실패 2건 제외 통과 | 10-27 재실행: H1 17개 중 16개 통과(실패 `3-tts` 1건 = 한국어 음성 없음, 알려진 환경 실패). 누적 km 항목은 이번엔 통과(에뮬레이터 누적 km가 75km로 초기화돼 있어 12,345로 올릴 수 있었음). H2 16개 모두 통과 | **true**(알려진 TTS 실패 1건 제외) |

release 병합 매니페스트: `PRIVATE_CAR_SAFETY` 0건(유지), `CAR_WINDOWS` 추가됨. debug에만 PRIVATE 1건(기존).

## 새 단위 테스트 (28개)

| 묶음 | 확인하는 것 |
|---|---|
| `ReturnTriggerTest` (10) | D 뒤 P+운전석 문 열림에만 카드, 인수 없음·주행 없음(후진 포함)·같은 정차 중복·반납 끝난 인수는 안 뜸, [나중에] 뒤 다시 주행하면 다음 정차에 1회, D로 바뀌면 숨김, 문이 닫히면 다음 인계 카드로 넘어감, 재시작 뒤 열린 인수는 주행이 있어야 함, 모두 지우기 뒤 안 뜸 |
| `ReturnComparisonTest` (4) | 새로 켜진 경고등 = 지금 집합 − 인수 때 집합(꺼진 것·중복 제외), 카드 줄(54% → 41%, +32km, 1개: 타이어 바람), 값 없음·거리 역행은 '확인 못 함', 창문은 읽힐 때만, 금지어·호칭 0 |
| `HandoverLedgerTest` (8) | 한마디 저장 → 다음 인계 카드 '이전 운전자 한마디'·음성 → 인수하면 지워짐, 한 줄·60자, 쌍 저장·인수 닫힘, 법인 운행 1건(출발·도착 km·거리·목적지·시각), 월별 묶음·합계, 50쌍 상한(오래된 것부터 버림), 같은 인수 중복 쌍 없음, 모두 지우기 |
| `ReturnJsonTest` (4) | 쌍·운행 왕복, 저장 키가 차 상태뿐(사람 정보 키 없음), 깨진 항목 건너뜀, 창문 키 없는 예전 스냅샷 복원 |
| `ReturnFakeVehicleTest` (2) | FakeVehicle 신호로 인수→D→잔량·km·TPMS→P→문 열림→카드→한마디→반납(법인, 내비 목적지명)→문 닫힘→새 인계 카드에 한마디→문 다시 열어도 카드 없음. 창문 신호 |

## 동작 규칙(구현)
- 트리거: 인수 스냅샷이 아직 반납 쌍이 없고(열린 인수), 인수 뒤 기어 D를 한 번 이상 거쳤고, 지금 P + 운전석 문 열림. 정차 1번에 1회. [나중에]는 기록 없이 이번 정차만 숨긴다(다시 D를 거치면 다음 정차에 다시 뜸). 문을 닫으면(다음 탑승) 답하지 않은 카드는 인계 카드에 자리를 넘긴다.
- 카드: "이번 운행을 정리했어요." + 인수 때 → 지금(배터리 %, 누적 +N km, 새로 켜진 경고등 N개와 쉬운 말 이름, 문·트렁크·충전구, 창문은 차가 알려 줄 때만). TTS는 넣지 않았다.
- [다음 사람에게 한마디]: 글 한 줄(최대 60자) → [한마디 저장] 즉시 암호화 저장 → 다음 인계 카드에 한 번 보이고, 그 사람이 인수하면 지운다. 인계 이력에는 한마디를 넣지 않는다(차 상태만).
- [돌려줬어요]: 반납 스냅샷을 인수 스냅샷과 한 쌍으로 저장(최근 50쌍). 법인 모드면 운행 1건(날짜·출발/도착 km·거리·내비 목적지명·인수/반납 시각)을 함께 적는다(최대 500건).
- 기록 화면 탭: 경고등(기존 타임라인 + 정비사에게 보여주기 표) / 인계 이력(쌍 목록) / 운행 표(법인 모드에서만, 월별 목록 + 합계 km).
- 설정 '모두 지우기'(확인 한 번 더): 경고등 기록·인계 이력·운행 표·마지막 인수 기록·한마디를 지운다. 설정은 남긴다.
- 홈 제목은 임시 중립 문구 "오늘", 태그라인은 비움(`app_tagline` 빈 값). 출발·정차 알림 문구도 중립으로 바꿈. 모드 이름 '가족 공유'는 그대로.

## 한계·미확인
- 창문 해석·내비 목적지명 콜백은 10-27 절 참고. 목적지명 콜백은 경로 안내를 실제로 시작한 경우가 여전히 미확인.
- 스크립트의 한마디는 ASCII 문장이다(adb `input text`가 한국어를 못 넣음).

## 2026-10-27 에뮬레이터 검증 (작업 H4)
에뮬레이터 `emulator-5554`(2560×1440, 전기차 설정), 현재 작업 사본으로 `assembleDebug` → `adb install -r`(APK sha256 앞 12자리 `f29bd5b92d6e`). 테스트 프로필 `returnH3`(기존 `state.enc` 안 건드림). 앱 코드는 바꾸지 않았다.

**스크립트 수정 1건**: 첫 실행에서 4개 항목(`note-saved`·`return-pair`·`history-pairs`·`next-card-note`) 실패. 원인은 앱이 아니라 스크립트 — Pleos 키보드가 전체 화면(추출 모드)으로 떠서 글 입력 뒤 탭이 키보드로 들어갔다(한마디 칸에 글자가 덧붙음). `hide_keyboard()`(입력 뒤 `mInputShown=true`면 BACK)를 넣고 다시 실행해 12/12 통과.

| 시나리오 | expected | observed | passed |
|---|---|---|---|
| takeover | 인계 카드 [인수했어요] → 스냅샷(soc 54) | `handoverSnapshot saved soc=54 odometerKm=37.0 … via=accept` | true |
| no-drive-no-card | 주행 없이 문 여닫기: 돌려주기 카드 없음 | 카드 로그 없음 | true |
| d-no-return | D(문 열어도): 돌려주기 카드 0 | 카드 로그 0, 화면 문구 0, 주행 문장만(06) | true |
| return-card | P만으로는 없음, P+운전석 문 열림에 카드 | `returnCard shown mode=RENTAL gear=PARK soc=54->41 distanceKm=32.0 newWarnings=1 names=TPMS window=false`, 화면 '54% → 41%'·'+32km'·'1개: 타이어 바람'·창문 '모두 닫혀 있어요.'(01) | true |
| window-read | 창문 줄은 차가 알려 줄 때만 | `windowOpen=false` 수신, 창문 줄 표시 | true |
| note-saved | 한마디 한 줄 → [한마디 저장] | `returnNote saved chars=17`(02) | true |
| return-pair | [돌려줬어요] → 쌍 저장, 렌터카는 운행 표 없음 | `returnPair saved soc=54->41 odometerKm=37.0->69.0 newWarnings=1 pairs=1 restored=true`, `tripLog` 없음 | true |
| history-pairs | 기록 탭 경고등/인계 이력(운행 표 숨김), 1쌍 | 1쌍, 운행 표 탭 없음(03) | true |
| next-card-note | 문 닫고 다시 열기: 새 인계 카드에 한마디, 돌려주기 카드 재표시 없음 | `handoverCard shown`, '이전 운전자 한마디 · Rear tire air low'(04) | true |
| fleet-trip | 법인 모드 → +5km → [돌려줬어요] → 운행 표 1건 | `handoverSettings mode=FLEET`, `tripLog added startKm=69.0 endKm=74.0 distanceKm=5.0 destination=false trips=1`, '2026년 10월 · 1건 · 합계 5km', 목적지 '—'(05) | true |
| clear-all | 설정 '모두 지우기' | `records cleared warnings=0 pairs=0 trips=0 snapshot=false note=false`, 렌터카 모드로 되돌림 | true |
| ui-wording | 캡처 화면 금지어·호칭 0 | 6화면 0건 | true |

스크린샷: `screens/return/01-return-card.png`, `02-note.png`, `03-history-pairs.png`, `04-next-card-note.png`, `05-trip-table.png`, `06-driving-no-return.png`(각 `.xml` UI 계층 동반). 창문 실측: `window-0.png`·`window-1.png`·`window-50.png`·`window-100.png`, 로그 요약 `window-nav-probe.txt`.

### 열린 질문 결론
- **(가) 창문 0=닫힘: 맞음(에뮬레이터 기준).** 콘솔 화면이 0%를 "Closed"로 표시한다. 돌려주기 카드를 띄운 채 `window_fl`을 바꿨더니 SDK 값은 콘솔 %÷10 정수로 왔다(50→5, 100→10, 1→0). 카드: 0·1% '모두 닫혀 있어요.', 50·100% '열려 있는 창문이 있어요.'. 한계: 10% 미만으로 조금 열린 창문은 '닫힘'으로 보인다. 실차 값 범위는 미확인.
- **(나) 내비 목적지명 콜백: 이 에뮬레이터에서는 오지 않았다.** F3 실행과 이전 로그 전체에 `navigation=connected`·`requestSubmitted`만 있고 경로 시작·도착·경로 계산 완료 콜백은 0건. 그때 운행 표 목적지 칸은 '—'로 표시됨을 확인(05, `destination=false`). 경로 안내를 실제로 시작했을 때 오는지는 미확인(내비 앱에서 경로 시작 또는 실차 필요).

### 관찰(수정하지 않음)
- 04 화면에서 인계 카드가 떠 있는 동안 왼쪽 메뉴는 '기록'이 선택된 채로 보인다(기록 화면 위에 카드가 떠서). 동작에는 영향 없음.
- 04의 '갈 수 있는 거리 약 0km'는 이번 에뮬레이터 주행가능거리 값이 0이어서다(H1 스크립트가 나중에 210,000m로 맞춤).

## 2026-10-31 Passer 재검증 (작업 H6)
- 앱 표시 이름을 **Passer**로 바꾼 빌드(debug sha256 앞 12자리 `e993f8b6bac2`)로 다시 돌렸다. 단위 테스트 244개 통과. `return_integration.py` **12개 중 12개 통과**, `screens/return/01~06`과 `return-integration.json`을 새로 만들었다.
- `window-0/1/50/100.png`(H4 수동 실측)는 별도 프로필 `navH6`의 법인 모드 돌려주기 카드에서 다시 찍었다. 결과는 H4와 같다(0·1% '모두 닫혀 있어요.', 50·100% '열려 있는 창문이 있어요.'). `window-nav-probe.txt`는 H4 기록으로 그대로 둔다.
- **내비 목적지명 콜백: 온다. 단, 우리 앱이 화면 뒤에 있으면 앱이 다시 앞에 올 때까지 늦게 온다.** 에뮬레이터 내비 앱(`ai.umos.maps.android.navigation.app`)에서 최근 목적지 '서울 중앙병원'(가상)으로 [안내 시작] → 내비가 `notifyRouteStarted(name=서울 중앙병원)`을 보냈고 다른 앱들은 즉시 받았지만, 우리 앱은 뒤로 가 있는 동안 Android가 프로세스를 얼려 두어(`isFrozen=true`) 바로 받지 못했다. 앱을 앞으로 가져오자(`unfreeze 22262`) 바로 `onRouteStarted`가 도착해 `navigation=destinationName chars=7`이 찍혔다.
- 이어서 +5km 주행 → P → 문 열림 → [돌려줬어요]: `tripLog added … distanceKm=5.0 destination=true trips=1`, 운행 표 목적지 칸에 '서울 중앙병원'(`nav-probe-06-trip-table.png`). 화면: `nav-probe-01~03`(내비 앱), `04`(콜백 뒤 앱), `05`(돌려주기 카드), `06`(운행 표), 로그 발췌 `nav-probe-log.txt`. 실험 뒤 내비 안내는 종료했다.
- 한계: 앱이 뒤에 있는 채로 안내를 시작하고 [돌려줬어요]까지 한 번도 앞에 오지 않는 경우는 시험하지 않았다(돌려주기 카드가 앱을 앞으로 띄우는지에 따라 달라짐). 도착 콜백(`onDestinationArrived`)은 실제 주행이 필요해 확인하지 않았다.

## 2026-10-31 H7 (모드 라벨·동작 조정)
- 라벨 '법인'→**법인·택시**, '가족 공유'→**함께 쓰는 차**(enum `FLEET`·`FAMILY`와 저장값은 그대로). **렌터카·카셰어 모드는 돌려주기 카드의 [다음 사람에게 한마디]와 인계 카드의 '이전 운전자 한마디'를 숨긴다.** 법인·택시·함께 쓰는 차는 그대로.
- 이에 맞춰 `return_integration.py`의 순서를 바꿨다: 렌터카 단계에서 한마디가 없는 것을 확인(`rental-no-note`, `screens/return/07-rental-no-note.png`)하고, 한마디 저장(`02-note`)과 다음 인계 카드의 한마디(`04-next-card-note`)는 법인·택시 단계로 옮겼다. 렌터카 단계의 다음 인계 카드는 `next-card`로 남겼다.
- 재실행(debug sha256 앞 12자리 `d10a2cd0c078`): **14개 중 14개 통과**, 스크린샷 7장 모두 기대 문구 일치. 단위 테스트 249개 통과.
