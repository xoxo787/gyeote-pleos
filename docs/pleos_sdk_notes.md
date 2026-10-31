# Pleos SDK 실측 노트 (2026-10-22)

공식 Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`, 로그인 불필요)에서 AAR을 받아 `javap`로 공개 API를 확인했다. 사용자가 42dot SDK 라이선스에 동의한 뒤 진행했다(docs/decisions.md).
전체 시그니처 덤프는 재배포 위험을 피하려고 저장소 밖 `<임시 폴더>/pleos-sdk-KWkrse\api_dump.txt`(2,922줄)에 두었다. **커밋하거나 공개하지 않는다.**

## 최신 버전 (maven-metadata 기준)
| 좌표 | 버전 | 주요 의존성 |
|---|---|---|
| `ai.pleos.playground:Vehicle` | 2.3.2 | vehicle-aidl 1.1.1, vehicle-data 1.1.11, kotlin 1.9.25, coroutines 1.6.3, gson |
| `ai.pleos.playground:TextToSpeech` | 3.3.1 | universal-access 0.6.0 |
| `ai.pleos.playground:SpeechToText` | 3.3.1 | universal-access 0.6.0 |
| `ai.pleos.playground:NaviHelper` | 3.0.5 | navi-aidl 3.0.1, navi-data 3.0.4 |
| `ai.pleos.playground:FusedLocation` | 1.1.3 | fused-location-aidl 0.0.5, fused-location-data 0.0.3 |

## 06(문서 조사)을 바로잡는 사실
| 06의 판단 | 실측 (Vehicle 2.3.2) | 영향 |
|---|---|---|
| 기어·시동 전용 API 없음 | **`Vehicle.getGear()`** → `getCurrentGearSelectionState`, `registerGearSelection(GearSelectionListener)`. 값: `VehicleGear.GEAR_PARK / DRIVE / REVERSE / NEUTRAL / UNKNOWN` | 출발/도착 판정에 **기어 P↔D**를 주 신호로 쓸 수 있다(도어·브레이크는 보조). Virtual Console에 Gear 카드도 있다 |
| 타이어는 저압 여부만, 콜백 없음 | **`Tire.getTirePressure(WheelArea)` + `registerTirePressure(WheelArea, TirePressureListener)`** — `onTirePressureUpdated(Float)`. `getLowTirePressure`도 Float 반환 | 공기압 **수치**로 안내 가능. 단위는 [미확인] → 에뮬레이터에서 실측 |
| 텔테일 종류 [검색요약] | `TelltaleType` 상수 96개(TPMS, ENGINE, ENGINE_OIL_PRESSURE, ENGINE_TEMPERATURE, ABS, AIRBAG, SEAT_BELT, LOW_FUEL, MASTER_WARNING 등 확인). `TelltaleData(type, state, color:int, priority:int, warningCount:int)` | 사전 키를 SDK 상수명과 맞춘다 |
| Safety 추가 기능 | 엔진오일·브레이크액·변속기오일 레벨(`OilLevelState`) 조회·콜백, 워셔액 부족, 안전벨트 미착용, 에어백 | 정비 안내에 '오일 레벨'을 더할 수 있다 |

## 에뮬레이터 실측 (작업 J, 2026-10-22, 근거 `app/docs/VERIFICATION_car.md`)
- **`PRIVATE_CAR_SAFETY` 필요 API**: `registerTelltales`/`unregisterTelltales`, `checkTirePressureCapabilities`, `getTirePressure`, `registerTirePressure`. 일반 `CAR_SAFETY`로는 SecurityException. 공개 가이드상 **앱마켓 사전 승인 필요** → 출시 빌드는 공개 `getTelltalesInfo` 3초 폴링으로 대체. 설명회 1순위 질문.
- 타이어 공기압 수치: Pleos Connect 2.1.0 에뮬레이터는 `ERROR_NOT_SUPPORTED_PROPERTY`(Console 입력 단위는 kPa, SDK 수신 단위는 미확인). TPMS 경고등은 동작.
- 기어·도어·TPMS 텔테일: Virtual Console 변경이 앱에 실제 반영됨.
- TTS/STT 3.3.1 `ON_DEVICE`: 키 없이 초기화는 되지만 둘 다 `ON_DEVICE_ENGINE_ERROR`. 원인 미확인.

## 호출 모델
- 모든 조회는 `get…(onComplete: (T)->Unit, onFailed: (Exception)->Unit)` 형태의 Kotlin 람다, 변경 감지는 `register…(Listener)` / `unregister…`.
- `Vehicle(context).initialize()` 후 도메인 객체를 얻고, 끝나면 `release()`.
- STT에는 `stt.data.Speaker`, `CallApi`, `AmbientAiApi` 같은 클래스가 있다 — 화자 정보 제공 여부는 [미확인](06의 '화자 식별 없음'을 재검토할 대상).

## 에뮬레이터 실측 추가 (2026-10-26, 메인 — 읽기 전용 점검 앱 `ai.gyeote.app.probe`, 임시 복사본에서 빌드)
에뮬레이터는 **전기차 설정**이다. Virtual Console로 값을 바꾸면 2초 조회에 그대로 반영됐다.
| 영역 | 결과 | 비고 |
|---|---|---|
| 배터리 잔량 % · 용량 kWh · 충전 켜짐 · 충전구 열림 · 충전 전류 상한 | **동작** (`CAR_ENERGY`) | 잔량 %를 바꿔도 에너지 kWh는 그대로(77.4) → kWh는 잔량×용량으로 계산 |
| 주행가능거리 · 누적 km | 동작 | 트립 거리·시간은 `ERROR_NOT_SUPPORTED_PROPERTY` |
| 기어 · 브레이크 페달(켬/끔) · 핸들 각도 | 동작 | 핸들 각도는 **부호가 반대로** 들어옴(콘솔 +120 → SDK −120). 리스너는 콘솔 변경마다 1건씩 옴(200ms 간격 쓰기에 모두 추종, 정확한 지연은 미측정) |
| 방향지시등(OFF/LEFT/RIGHT) · 와이퍼 · 창문 위치 · 좌석 착석 | **동작** (`CAR_LIGHTS`·`CAR_WINDOWS`·`CAR_SEATS`) | 창문 잠금은 area 오류. 좌석 착석은 콘솔 쓰기 형식을 못 찾음(기본값 true) |
| 연료 % · 연비 · 엔진오일 · 변속기오일 · 브레이크액 · 전비 · 타이어 수치 | **미지원** | 전기차 이미지라 연료·오일 계열이 없음. 실차(내연·하이브리드)는 미확인 |
- 권한 없이 호출하면 `SecurityException … need Permission`, `initialize()` 전에 호출하면 `Service connection failed`.
- 콘솔 쓰기 형식(확인): `/vehicle/controls?steering=120|brake=60`, `/vehicle/ev-battery?field=soc|charge_switch|charge_port_open|current_limit&value=…`, `/vehicle/odometer?traveled_distance_km=…|remaining_range_m=…`, `/vehicle/turn-signal?mode=Off|BlinkLeft|BlinkRight`, `/vehicle/body?window_fl=0..100|front_wiper=Off|Low|High`. 모두 PUT이며 `/browser/session?client=…` POST 하트비트가 없으면 503.

## 작업 H1 반영 (2026-10-26, 근거 `app/docs/VERIFICATION_handover.md`)
- 앱 main 매니페스트에 `CAR_ENERGY`·`CAR_LIGHTS`·`CAR_SEATS`를 추가했다. 공개 권한으로 보고 넣었지만 **앱마켓 승인 목록에 들어 있는지는 미확인**이다(설명회 질문 3번과 함께 확인). 에뮬레이터에서는 세 권한 모두 granted=true.
- 주행가능거리 `getRangeRemainingDistance`/`RangeRemainingListener`는 **미터** 단위로 온다(콘솔 `remaining_range_m=210000` → 210000.0). 앱은 km로 바꿔 쓴다.
- 핸들 각도는 앱 포트에서 부호를 뒤집어 콘솔과 맞춘다(SDK −120 → 앱 +120). +가 실제 왼쪽/오른쪽 중 어느 쪽인지는 미확인.
- 콘솔 누적 km는 **줄일 수 없다**(`traveled_distance_must_not_decrease`, 400). 이 에뮬레이터는 이미 12,346km라 12,345km로 되돌릴 수 없다.
- 콘솔 쓰기 형식 추가 확인: 트렁크 `/vehicle/body?trunk=true|false`, 충전구 `/vehicle/ev-battery?field=charge_port_open&value=…`. 좌석 착석 `/vehicle/seat?kind=occupied&area=driver&value=false`는 200을 돌려주지만 앱 콜백은 바뀌지 않았다(형식 미확인, 기본값 true 유지).
- 이 에뮬레이터에는 Android 로컬 한국어 TTS 음성이 없어 인계 음성은 요청까지만 확인했다(`tts=queued`, 화면 안내로 대체).

## 작업 H3 반영 (2026-10-27, 근거 `app/docs/VERIFICATION_return.md`)
- 돌려주기 카드의 창문 줄을 위해 main 매니페스트에 `CAR_WINDOWS`를 추가했다(10-26 점검 앱에서 창문 위치 조회 동작). 앱마켓 승인 목록 포함 여부는 다른 권한과 같이 **미확인**.
- 앱은 옆 창문 4개(`WindowValue.ROW_1_LEFT`~`ROW_2_RIGHT`)의 `getCurrentWindowPosition`/`registerWindowPosition` 값이 0보다 크면 '열림'으로 본다. **0=닫힘이라는 해석은 앱에서 아직 확인하지 않았다**(콘솔 `window_fl=0..100` 형식에서 추정). 에뮬레이터 재실행 때 `windowOpen=` 로그로 확인할 것.
- 법인 운행 표의 목적지명은 `NaviHelperEventListener.onRouteStarted(RouteStartInfo.name)`·`onDestinationArrived(DestinationArrivedInfo.name)`에서 받는다. 에뮬레이터에서 이 콜백이 오는지는 **미확인**(없으면 목적지 칸은 '—').

## 작업 H4 실측 (2026-10-27, 근거 `app/docs/VERIFICATION_return.md` 10-27 절)
- **창문 0=닫힘 맞음(에뮬레이터 기준).** 콘솔 화면이 `window_fl` 0%를 "Closed"로, 그 이상을 열린 비율로 표시한다. SDK `onWindowPositionUpdated` 값은 **콘솔 %÷10의 정수(0~10)**로 온다: 50→5, 100→10, 1→0. 앱 카드는 0·1%에서 '모두 닫혀 있어요.', 50·100%에서 '열려 있는 창문이 있어요.'. 그래서 10% 미만으로 살짝 열린 창문은 '닫힘'으로 보인다. 실차의 값 범위는 미확인.
- **내비 목적지명 콜백은 오지 않았다.** F3 시나리오 전체와 이전 실행 로그(`app/docs/*.log`·`*.json`)를 통틀어 `navigation=connected`·`requestSubmitted`만 있고 `onRouteStarted`·`onDestinationArrived`·`onRoutingCompleted` 기록은 0건. 이 경우 운행 표 목적지 칸은 '—'로 표시됨을 확인(`tripLog … destination=false`). 실제 경로 안내를 시작했을 때 콜백이 오는지는 여전히 미확인(내비 앱에서 경로를 시작해 보거나 실차 필요).

## 작업 H5 내비 콜백 실측 시도 (2026-10-27, 확인 불가)
- 에뮬레이터 VM이 응답하지 않아(adb는 `device`로 보이지만 shell 명령 전부 시간 초과, qemu 스레드 1개·CPU 시간 정지) 내비 앱 조작을 하지 못했다. 재시작은 사용자 허가 대기.
- **H4의 "콜백 0건"은 결정적이지 않다**(코드 읽기 기준, 실행 아님): `PleosNavigator.kt`의 `onRouteStarted`·`onDestinationArrived`는 목적지명이 비어 있으면 로그를 남기지 않는다. 즉 "이름이 담긴 콜백을 못 봤다"가 정확한 표현. `onRoutingCompleted`만 항상 로그를 남긴다. 다음 실측 때는 logcat 전체에서 `navi`로 걸러 SDK 쪽 로그도 함께 본다.

## 작업 H6 내비 콜백 실측 (2026-10-31, 근거 `app/docs/VERIFICATION_return.md` 10-31 절)
- **`onRouteStarted`는 목적지명(`RouteStartInfo.name`)을 담아 우리 앱까지 온다.** 에뮬레이터 내비 앱에서 [안내 시작]을 누르면 내비가 `notifyRouteStarted`를 보내고, navi 서비스가 `NaviHelper`를 등록한 앱들에 `onRouteStarted`를 뿌린다. 운행 표 목적지 칸이 '서울 중앙병원'(가상)으로 채워짐을 확인(`tripLog … destination=true`).
- **주의: 뒤에 있는 앱은 Android가 얼려 두어 콜백이 늦게 온다.** 내비 화면이 앞에 있는 동안 우리 앱은 `isFrozen=true`였고, 다른 앱은 00:03:04에 받은 콜백을 우리 앱은 앞으로 가져온 00:04:11에야 받았다. 로그 원문:
  - `10-31 00:03:04.672 … NavigationUseCase: TASUseCase, notifyRouteStarted, data:RouteStartInfo(duration=3240, distance=22000, tollFare=700, name=서울 중앙병원, …)`
  - `10-31 00:04:11.886 … ActivityManager: quick sync unfreeze 22262 for 1` → `10-31 00:04:12.160 22262 22262 I GyeoteVehicle: navigation=destinationName chars=7`
- 그래서 실차에서 우리 앱이 안내 시작부터 반납까지 한 번도 앞에 오지 않으면 목적지명이 늦거나 빠질 수 있다(미시험). 도착 콜백은 실제 주행이 필요해 미확인.
