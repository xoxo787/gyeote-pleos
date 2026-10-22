# 작업 J 차량 연동 검증

검증일: 2026-10-22. 패키지 `ai.gyeote.app`. 차량 화면·실제 SDK 어댑터·로컬 가족 서버 흐름을 구현했다. **타이어 수치·단위 실측은 에뮬레이터의 SDK property 미지원으로 미통과**다. 이를 실제 수신한 값으로 대체하거나 단위를 추정하지 않았다.

## 합격 기준 — expected / observed / passed

| 기준 | expected | observed | passed |
|---|---|---|---|
| (a) 빌드·테스트·린트 | `gradlew test assembleDebug lintDebug`, 기존 98개 유지 + 기어·타이어 수치 테스트, 실패 0 | 고유 시나리오 **116개**; Debug 116 + Release 116 = **232건**, 실패·오류·건너뜀 각 0; BUILD SUCCESSFUL; lint **0 errors, 8 warnings, 1 hint** | **true** |
| (b) 차량 화면 | Pleos 설치·실행, 가로 PNG 5장 이상 | 기존 `emulator-5554`, Pleos Connect 2.1.0/API 34, **2560×1440 / 160dpi**, 설치 Success·Activity Status ok; 홈·동의·경고 상세·주행 카드·목적지 포함 **7장 + 최종 APK 홈 1장** | **true** |
| (c) Console·수치 실측 | 기어·도어·텔테일·타이어 중 최소 3종의 실제 반영, 공기압 단위 실측 | **기어·도어·TPMS 3종은 실제 SDK→앱 반영 통과**; Console의 210 kPa 입력은 HTTP 200이나 SDK `checkTirePressureCapabilities`는 `ERROR_NOT_SUPPORTED_PROPERTY`; 수치 콜백 없음·단위 미확인 | **false — 수치·단위 부분 차단** |
| (d) 서버 통합 | 생성·페어링 → 동의 → 기어 출발·도착 → 목적지 카드·수락 → 철회 삭제, off 종류 미전송 | 자녀 HTTP 조회에 departed/arrived 표시, 목적지 accepted·NaviHelper requestSubmitted, 철회 뒤 이벤트 행 0·자녀 이벤트 조회 403; 경고 off 및 철회 후 전체 off에서 HTTP 이벤트 시도 **0회** | **true** |
| (e) 문구 검사 | 지정 문구 app/ 텍스트 검색 0건 | Kotlin/XML/문서/도구/JSON/로그 전체에서 **0건**, build·binary·Python cache 제외 | **true** |
| (f) 가짜 입력 보존 | 기존 도메인 테스트와 가짜 어댑터·모의 콘솔 경로 유지 | 기존 98개 모두 유지, Fake 포트 테스트 8개 + 기어 도메인 10개 추가; 가짜 입력의 실제 화면 smoke **7/7 통과** | **true** |

근거: [빌드 로그](car-build-verification.log), [JUnit 집계](car-test-summary.json), [HTTP·시나리오·캡처 해시](car-integration.json), [실제 차량 콜백 로그](car-vehicle.log), [가짜 화면 smoke](car-runtime-smoke.json). 기존 `VERIFICATION.md`는 작업 I의 역사적 보고서이며, `runtime-smoke.json`은 이번 실제 재실행으로 갱신됐다. 해당 도구의 `android-36-back-dispatch`는 기존 시나리오 이름이며 이번 기기의 API는 **34**다.

## 구현과 호환성

- 900dp 이상 창은 왼쪽 80dp 메뉴와 두 열 홈·동의 화면을 사용한다. 버튼은 차량 창에서 최소 76dp다. 좁은 창은 한 열로 유지한다. Pleos 클러스터·상단 바·하단 바 영역을 침범하지 않는 실제 앱 창을 확인했다. 새 빌드의 폰 세로 실행은 하지 않았다.
- `Vehicle 2.3.2`의 실제 Telltale/Odometer/Door/Brake/Gear/Tire 포트를 연결했다. 조회·구독 결과는 main thread로 전달하고 동일한 listener/area로 해제한 뒤 SDK를 release한다. 도어·타이어는 capability가 반환한 영역만 사용한다. 지원되지 않은 공기압을 0으로 만들지 않는다.
- `VehicleSample.gear` 기본값은 UNKNOWN으로 두어 기존 도어·브레이크·거리 테스트를 유지했다. P→D를 출발 우선 신호, P 복귀+문 열림 2초를 도착 보조 신호로 사용한다. 알려진 기어가 있을 때에는 기존 거리 추정이 P/D 의미를 덮어쓰지 않는다. 중복·이전 시각·재출발 대기·거리 없음 경우도 테스트했다.
- `TireSource`는 저압 여부, 바퀴별 Float 수치, 선택 단위를 가진 `TireState`로 확장했다. 저압 여부는 TPMS 신호에서 받고, 수치에 임의 저압 기준을 적용하지 않는다. 값이 NaN·음수·없음이면 해당 바퀴 값을 비운다. 이 Kotlin 포트를 별도로 구현하는 코드가 있다면 `currentTires`/`observeTires`/GearSource를 함께 구현해야 한다. 서버 API·암호화 저장 스키마의 변경과 마이그레이션은 없다.
- 실행 시 automotive 기능과 Pleos Vehicle Service 패키지 존재를 확인한다. 명시적인 debug `vehicleMode=fake` 이외에는 플랫폼 UX 정보를 읽을 수 없거나 기어가 P가 아니면 입력을 제한한다. Pleos 모드에서는 `restricted=false`나 `warning` 인텐트가 차량 신호를 덮어쓰지 않는다. 전경 밖에서는 이벤트를 발송하지 않는다.
- `NaviHelper 3.0.5`에 위도·경도와 목적지를 보낸다. 요청 접수, 경로 계산 완료, 도착, 오류를 구분한다. **목적지 accepted와 requestSubmitted는 실제 경로 안내 성공의 증거가 아니다.** 요청 뒤 내비 Activity가 전면에 와서 최초 추가 캡처가 중단됐으나 앱을 다시 열어 후속 캡처·철회·삭제를 완료했다. 초기 실패와 복구 기록을 `car-integration.json`에 남겼다.
- `Signal`의 구독 취소 테스트에서 mutable receiver를 가진 함수 참조의 hash가 바뀌면 제거되지 않는 기존 문제가 드러났다. 구독마다 안정된 wrapper를 저장하도록 수정했고 취소 후 추가 알림이 오지 않는 테스트가 통과했다.

## 매니페스트·권한·카테고리

일반 선언은 `CAR_DRIVE`, `CAR_DOORS`, `CAR_SAFETY`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`다. automotive와 android.car는 optional이며 LAUNCHER/CAR_LAUNCHER, `distractionOptimized=true`를 선언했다. 주행 화면에는 앱의 Button/EditText/clickable 노드가 **0개**다. 단순 문장 카드 표시가 앱마켓 승인이나 실차 주행 허용을 의미하지는 않는다.

**PRIVATE_CAR_SAFETY = 앱마켓 사전 승인 필요, 출시 빌드 미포함.** 조정자가 로컬 검증용 debug 선언을 명시적으로 허용했다. 실제 기기에서 `dumpsys package ai.gyeote.app`의 `PRIVATE_CAR_SAFETY: granted=true`를 확인했으며 시스템 권한 우회는 하지 않았다. 이 권한이 필요한 API는 `registerTelltales`/`unregisterTelltales`, `checkTirePressureCapabilities`, `getTirePressure`, `registerTirePressure`/`unregisterTirePressure`다. 권한이 없으면 텔테일은 공개 `getTelltalesInfo`를 3초마다 조회하고 비공개 타이어 API는 건너뛴다. SecurityException과 API 실패는 잡아서 대체 경로로 동작한다.

release 병합 매니페스트를 XML로 읽어 비공개 권한과 TTS_SERVICE/STT_SERVICE/RECORD_AUDIO가 **모두 없음**을 확인했다. debug에는 확인용 음성 권한이 있다. 타이어 수치 API는 debug에서도 지원되지 않았다.

기획상 범주는 차량 상태 안내를 중심으로 하는 **Mobility Services**가 적합한 후보로 기록한다. 공식 범주에는 차량 관리 안내가 포함되고, 음악 재생이 주기능인 앱의 audio 범주 요건을 이 앱에 적용하지 않았다. Android `appCategory`에는 Mobility Services 항목이 없어 임의로 audio/maps/business로 지정하지 않았다. 실제 App Market 프로젝트 범주는 아직 등록하지 않았다.

확인한 공식 문서: [Vehicle 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [NaviHelper 권한](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/declaring-permissions), [차량 화면·범주](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), [주행 예제의 매니페스트 설명](https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk), [API 지원 범위](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability). 문서 버전과 SDK API가 다를 수 있어 실제 Gradle 캐시의 공개 시그니처·어노테이션과 런타임 결과를 함께 확인했다. SDK 원문·AAR·javap 덤프는 저장소에 넣지 않았다.

## 공기압·음성의 남은 제한

Console catalog에는 숨겨진 tire-pressure 항목과 kPa 표기가 있다. `PUT /vehicle/tire-pressure?area=1114113&pressure_kpa=210`은 HTTP 200이었지만 실제 SDK capability는 `ERROR_NOT_SUPPORTED_PROPERTY`였다. 따라서 **Console 입력 단위는 kPa, SDK 수신 수치의 단위는 미확인**으로 구분한다. 앱은 수치가 없는 상태를 표시하고 단위를 붙이지 않는다. SDK getter/register 경로는 구현했으나 현재 이미지에서 실제 Float 콜백을 입증하지 못했다. 이를 확인하려면 해당 property를 지원하는 공식 이미지나 차량이 필요하다.

TextToSpeech/SpeechToText 3.3.1은 `ON_DEVICE`로, 앱 등록·계정·서버 키 없이 초기화했다. 최종 확인에서는 두 SDK 초기화가 성공했고 TTS는 Ready/Start까지 왔지만, TTS와 STT 모두 **ON_DEVICE_ENGINE_ERROR**를 반환했다. 한국어 합성 완료·음성 인식 성공은 **passed=false**이며 정확한 엔진 오류 원인은 확인하지 못했다. 기존 Android TTS와 가짜 인식 결과 입력을 유지한다. 이 에뮬레이터에는 기존 Android 로컬 한국어 음성도 없어 화면 안내를 사용했다. 키를 생성·요청·읽지 않았고 server/hybrid 등록도 하지 않았다. 최종 근거는 [car-speech-final.log](car-speech-final.log)이며 초기 확인 로그와 구분한다. 공식 [STT 모드 설명](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)도 ON_DEVICE 단독 사용에는 앱 등록이 필요 없다고 명시한다.

린트의 8개 경고는 기존 의존성/Gradle 새 버전 5개와 SDK 전이 의존성 `jna`의 x86_64 native library 16KB 정렬 3개다. 이번 API 34 에뮬레이터 실행은 통과했으나 16KB 페이지 기기 호환성을 입증하지 않는다. SDK 버전은 지시서대로 고정했다.

## 서버 통합의 단계별 증거

`DATA_DIR`에 새 고유 임시 폴더 `<임시 폴더>/gyeote-car-data-4f733d09154f4d69a8722845d31a61f0`을 지정했다. 현재 서버는 DATA_DIR를 읽지 않고 **DB_PATH**를 읽으므로, 같은 폴더의 db.json을 DB_PATH에도 지정했다. 서버 코드는 수정하지 않았다. 호스트 PORT는 8787, 앱 주소는 `http://10.0.2.2:8787`이었다. 자녀 요청은 Python의 실제 HTTP 클라이언트를 사용했다. 인증·연결 번호는 출력 보고서에서 제외했고 토큰은 마스킹했다.

| 단계 | expected | observed HTTP / 상태 발췌 | passed |
|---|---|---|---|
| 가족 생성·페어링 | 앱에서 가족 생성 후 자녀 연결 | 앱의 가족 생성 UI 완료, `POST /api/pair` **201**, 응답 familyId/childId; 인증 필드는 `[redacted]` | true |
| 동의 저장 | 자녀 선택, 출발·도착만 on | `GET .../consent` **200**, `shareDeparture=true, shareArrival=true, shareAlerts=false, shareLocation=false` | true |
| 경고 off | 실제 TPMS는 앱에 표시, 이벤트는 미전송 | `GET .../events` **200**, alert 없음; 앞뒤 `eventHttpAttempt=alert` 로그 증가 **0** | true |
| 기어 출발 | Console P→D가 저장되고 자녀가 조회 | `GET .../events` **200**, `type=departed, summary=출발했어요`, 좌표 없음 | true |
| 도착 | P 복귀 + 문 열림 안정화 | 같은 자녀 조회 **200**, `type=arrived, summary=도착했어요` 추가, 좌표 없음 | true |
| 목적지 전송 | 자녀가 보내고 앱에 카드 표시 | `POST .../destinations` **201**, `status=pending`; 실제 차 화면에 서울 중앙병원 카드 | true |
| 수락 | 운전자 수락이 서버·내비 요청에 반영 | 자녀 GET **200**, 해당 항목 `status=accepted`; SDK 로그 `navigation=requestSubmitted` | true |
| 철회·삭제 | 모두 off, 공유 자료 삭제 | consent GET **200**·공유 네 종류 false·recipients 빈 배열; 자녀 이벤트 GET **403**; 해당 가족의 로컬 DB events 행 **0** | true |
| 철회 후 새 주행 | 새 출발·도착도 발송하지 않음 | 실제 P→D→P+문 입력 뒤 `eventHttpAttempt=` 로그 증가 **0** | true |

이 표는 실제 받은 HTTP 조회/페어링/목적지 응답을 기록한다. 앱 내부 개별 POST 이벤트와 DELETE의 응답 코드를 따로 패킷 캡처했다고 주장하지 않는다. 서버 저장·삭제는 자녀 HTTP 조회와 격리 DB의 이벤트 개수로 검증했다. 실제 가족 기기·운영 서버·실차 안내 성공은 검증하지 않았다.

## 화면·APK·실행 명령

| 화면 | 실제 PNG |
|---|---|
| 홈 | [01-home.png](screens/car/01-home.png) |
| 동의 | [02-consent.png](screens/car/02-consent.png) |
| 경고 상세 | [03-warning.png](screens/car/03-warning.png) |
| 주행 카드 | [04-driving.png](screens/car/04-driving.png) |
| 목적지 카드 | [05-destination.png](screens/car/05-destination.png) |
| 실제 도어 열림 | [06-door-open.png](screens/car/06-door-open.png) |
| 내비 요청 뒤 앱 복귀 | [07-destination-response.png](screens/car/07-destination-response.png) |
| 최종 APK·Pleos 자동 선택 복귀 | [08-final-home.png](screens/car/08-final-home.png) |

모든 PNG는 `adb exec-out screencap -p`의 원본 2560×1440 파일이며 같은 이름의 XML도 있다. 홈·동의·경고·주행·목적지 PNG를 직접 열어 확인했다. 01~07 캡처 APK SHA-256은 `9bd0ef0ba24d8eb99ad4b1142fc21db1c33b9bf799c24717879e43c7cfaf3f96`이다. 이후 음성 probe의 SDK 초기화 순서를 수정한 **최종 APK**는 `fe322e576b53fbf93e057b9e1f7c9f97e946061f44390aec56eb13ed353909d1`이며, 이 최종 APK로 빌드·232개 테스트·린트, 재설치·최종 음성 probe·철회 후 미전송·가짜 화면 7개를 검증했다. 최종 APK의 08 홈 캡처와 해시는 [final-capture.json](screens/car/final-capture.json)에 있다. 최종 APK와 01~07 캡처 APK를 동일하다고 표시하지 않는다.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat test assembleDebug lintDebug :app:processReleaseMainManifest --console=plain
# BUILD SUCCESSFUL in 22s, 82 actionable tasks

& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
# Success
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -W -n ai.gyeote.app/.MainActivity
# Status: ok

python -u app/tools/car_integration.py --db-path '<임시 폴더>/gyeote-car-data-4f733d09154f4d69a8722845d31a61f0\db.json'
# 보고서 JSON에는 인증 값이 없다.
python -u app/tools/car_integration.py --phase finish --db-path '<임시 폴더>/gyeote-car-data-4f733d09154f4d69a8722845d31a61f0\db.json'
# 내비 Activity 전환 뒤 앱 복귀·철회·삭제까지 이어 실행했다.
python -u app/tools/runtime_smoke.py --serial emulator-5554
# 가짜 입력의 실제 화면 7/7 통과
```

Console은 URL의 HTML을 받는 것만으로 활성화되지 않고 프런트엔드의 browser/session heartbeat가 필요했다. 최초 단순 PUT의 `503 emulator_inactive`를 확인한 뒤, 테스트 도구가 프런트엔드와 같은 `/browser/session` 요청을 보내는 동안 PUT과 실제 SDK 수신을 검증했다. 확인한 API를 사용했으며 별도 차량 에뮬레이터는 켜지 않았다.

## 변경 범위와 종료 상태

실제 소스·설정 변경 목록은 [car-modified-files.txt](car-modified-files.txt), 수정 전 백업과의 비교는 [car-source-review.json](car-source-review.json)에 있다. 추가 보고서·로그·캡처는 모두 app/docs/ 아래다. `server/`, 루트 `docs/`, `research/`는 읽기만 했다. 원본 src·Gradle 설정·README 백업은 저장소 밖 고유 임시 폴더 `<임시 폴더>/pleos-J-738e4f7cc64f43dbb985085b5c6e2baf`에 보존했다.

파일·폴더 삭제, git commit/push, 외부 게시·배포·결제, 자격 증명 출력·반출은 수행하지 않았다. Gradle workers.max=2와 에뮬레이터 1개를 유지했다. [car-exit-state.json](car-exit-state.json)에서 작업용 node PID 33504 종료와 8787 포트 비수신, emulator-5554의 device 상태를 확인했다. 공유 동의는 off로 두었고 앱은 Pleos 자동 선택으로 복귀했다. 로컬 앱에는 시연용 가족 연결·정비 기록이 남아 있다.
