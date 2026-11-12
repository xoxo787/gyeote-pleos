# 충전오락관 — 창문 스위치가 퀴즈 버저가 되는 충전 놀이

> 충전하는 동안, 좌석마다 달린 창문 스위치가 퀴즈 버저가 됩니다. 기어를 옮기는 순간 놀이는 닫힙니다.

## 무엇을 하나

- **기어 P에서만 열립니다.** P가 0.7초 이상 유지돼야 열리고, P를 벗어나면 그 즉시 닫힙니다.
- **참가**: 함께할 자리의 창문을 한 칸 내리면 그 자리가 참가합니다.
- **OX 문제**: 맞다고 생각하면 창문을 한 칸 내리고, 아니면 그대로 둡니다(12초). 맞힌 자리가 별을 받습니다.
- **번개 버저**(세 판마다): "지금!"이 뜬 뒤 가장 먼저 창문을 내린 자리가 별을 받습니다. 미리 내리면 그 판은 쉽니다.
- **차가 내는 문제**(OX 세 문제마다): 지금 이 차의 배터리 잔량, 주행 가능 거리, 누적 주행거리, 충전구 상태로 문제를 만듭니다.
- **충전 중에는 배터리가 시계입니다.** 배터리가 1% 오를 때마다 다음 문제가 옵니다(느린 충전기에서는 45초 뒤 그냥 옵니다). 80%(80% 이상에서 시작했으면 100%)에 닿으면 마지막 판을 하고 끝납니다. 목표에 못 미쳐 충전이 끊기면 하던 판만 마치고 "다음 충전 때 이어서"로 끝납니다.
- 충전 중이 아니면 다섯 판을 합니다.

## 안전을 위해 정한 규칙

| 규칙 | 구현 위치 |
|---|---|
| 창문을 **내리는** 동작만 입력입니다. 올리는 동작에는 시간 제한도 보상도 없습니다 | `domain/Game.kt` `GameMachine.window` |
| 다음 문제는 내려간 창문이 올라올 때까지 기다립니다(재촉하지 않음) | `GameMachine.advance` |
| P를 벗어나면 즉시 닫고, 그 뒤 창문 조작은 무시합니다. 읽던 음성도 그 순간 멈춥니다 | `GameMachine.gear`, `MainActivity.settle` |
| 등록 직후 낡은 P 값이 잠깐 올 수 있어, P가 0.7초 유지된 뒤에만 엽니다. 기어는 구독과 별도로 0.5초마다 다시 읽습니다 | `GameConfig.parkSettleMs`, `PleosCar` |
| 화면을 떠나면 하던 판을 닫고(별은 저장), 돌아오면 P를 다시 확인한 뒤 엽니다 | `GameMachine.screenLeft` |
| 끝날 때 열려 있는 창문이 있으면 어느 자리인지 알려 줍니다 | `Phase.Finished.openWindows` |
| 앱은 창문을 움직이지 않습니다. 차량 신호는 읽기만 합니다 | `vehicle/PleosCar.kt` |
| 주행 최적화(DO)를 선언하지 않은, 정차 전용 앱입니다. 에뮬레이터에서 기어를 D로 옮기면 차가 앱 위에 "주행 중에는 사용할 수 없습니다."를 덮는 것을 확인했습니다(`docs/screens/08-closed-by-driving.png`). 앱의 즉시 종료와 합쳐 두 겹입니다 | `AndroidManifest.xml` |

## 코드 구조

```
차량 신호 ──▶ PleosCar ──▶ MainActivity ──▶ GameMachine ──▶ 화면 · 음성
(Pleos SDK)   (읽기 전용)    (전달·그리기)     (규칙 전부)
                                  ▲
                         100ms마다 시계(tick)
```

| 파일 | 역할 |
|---|---|
| `domain/Game.kt` | 놀이 규칙 전부. 시계와 차를 직접 읽지 않고 입력마다 시각을 받는 상태 기계라서, 같은 입력이면 항상 같은 결과가 나옵니다 |
| `domain/Questions.kt` | 문제 은행(36개), 차 신호로 만드는 문제, 문제를 꺼내는 순서 |
| `domain/Progress.kt` | 판이 끝나거나 주행으로 닫힐 때 별을 한 번만 누적 저장 |
| `vehicle/PleosCar.kt` | Pleos Vehicle SDK 연결: 기어, 옆 창문 4개 위치, 배터리 %, 충전 스위치(1.5초마다 조회), 충전구, 주행 가능 거리, 누적 주행거리 |
| `vehicle/Voice.kt` | 문장을 차례로 읽어 주는 음성(Android TTS) |
| `MainActivity.kt` | 신호와 시계를 규칙에 넘기고, 나온 상태를 그립니다. 규칙은 없습니다 |
| `tools/buzzer_integration.py` | 에뮬레이터 통합 검증 스크립트 |

권한은 `CAR_DRIVE`(기어), `CAR_WINDOWS`(창문 위치), `CAR_ENERGY`(배터리·충전) 세 개입니다. 인터넷 권한은 없습니다. 저장하는 것은 누적 별 수와 문제 순번 두 숫자뿐입니다.

## 실행 방법

저장소 루트에서 실행합니다(JDK는 Android Studio의 JBR).

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :buzzer:testDebugUnitTest :buzzer:assembleDebug :buzzer:assembleRelease :buzzer:lintDebug --console=plain
adb -s emulator-5554 install -r buzzer\build\outputs\apk\debug\buzzer-debug.apk
python buzzer\tools\buzzer_integration.py
```

Virtual Console에서 직접 해 보려면: Gear를 Park로 → 앱 실행 → Body의 창문 값을 10으로(참가) → 0으로 → [시작].

release APK는 저장소의 `apk/김태용_충전오락관_release.apk`에 있습니다(서명됨, 패키지 `ai.gyeote.buzzer`).

## 테스트 환경과 검증 결과 (2026-11-11)

- Pleos Connect 에뮬레이터 이미지 v2.1.0(전기차 설정, 2560×1440, 160dpi), Vehicle SDK 2.3.2.

| 항목 | 결과 | 근거 |
|---|---|---|
| 단위 테스트 | 65개 통과 | `gradlew :buzzer:testDebugUnitTest` |
| 린트 | 통과 | `gradlew :buzzer:lintDebug` |
| 에뮬레이터 통합 검증 | 18개 중 18개 통과 | `docs/integration.json`, 화면 `docs/screens/` |
| release APK | 서명 확인, 권한 3개, 에뮬레이터에서 대기 화면과 창문 참가 확인 | `apksigner verify`, `aapt dump permissions` |

통합 검증이 본 것: 주행 중 잠김, P에서 열림, 창문으로 참가, 창문이 올라올 때까지 기다림, O/X 판정과 별, 번개 버저(미리 내린 자리 제외), 차가 낸 문제, 주행으로 즉시 닫힘과 별 보존, 충전 중 1%마다 문제 도착, 느린 충전 대체 시간, 80%에서 마지막 판, 충전 끊김, 다섯 판 종료와 열린 창문 안내, 앱을 껐다 켜도 별 유지, 화면 문구.

## 확인하지 못한 것

- **실차**: 창문을 한 칸 내렸을 때 신호가 얼마나 빨리 오는지, 값의 범위가 에뮬레이터(0~10)와 같은지 모릅니다.
- **음성**: 이 에뮬레이터에는 한국어 음성이 없어 읽기 요청까지만 확인했습니다. 화면만으로도 진행됩니다.
- **충전 스위치 신호**가 실차에서 "충전 중"과 정확히 같은 뜻인지 모릅니다.
- **화면을 떠났다 돌아오는 경우**는 단위 테스트로만 확인했습니다(이 에뮬레이터는 홈 키로 앱이 멈추지 않음).
- **독립 코드 검토 1회**(2026-11-11)에서 나온 4건(주행 전환 때 남은 음성, 목표 도달과 충전 멈춤이 겹칠 때, 화면을 떠날 때의 문구, 배터리 값이 끊길 때)은 모두 고치고 테스트를 붙였습니다.
- **앱마켓 승인**: 위 권한 세 개가 승인 목록에 있는지 확인하지 못했습니다.
