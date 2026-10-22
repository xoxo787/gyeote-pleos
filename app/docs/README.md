# 곁에 Android 프로토타입

차량 경고를 쉬운 말로 풀고, 운전자가 직접 고른 소식만 가족에게 전하는 Kotlin 앱이다. 앱 이름은 `src/main/res/values/strings.xml`의 `app_name`과 `app_tagline`에서 바꾼다. 패키지는 `ai.gyeote.app`, 최소 Android 28, compile/target SDK 36이다.

현재 **Pleos 차량 SDK + 서버**를 연결하고 Fake 입력도 유지한다. Vehicle 2.3.2, NaviHelper 3.0.5, 음성 SDK 3.3.1을 공식 Maven에서 받는다. 실행 시 automotive 기능과 Pleos Vehicle Service 패키지 존재를 확인해 어댑터를 선택한다. AAOS용 독립 Activity이며 `android.hardware.type.automotive`와 `android.car` 라이브러리는 `required=false`로 선언해 폰에도 설치할 수 있다.

900dp 이상 창에서는 두 열과 왼쪽 큰 메뉴를 사용한다. Pleos의 클러스터·상단 바·하단 제어 영역을 피하고, 좁은 창에서는 한 열로 바뀐다. 플랫폼 UX 제한을 읽지 못하거나 기어가 P가 아니면 입력 없는 큰 문장 카드로 전환한다. 이는 차량 앱 심사나 주행 허용을 받은 상태라는 의미는 아니다.

## 빌드와 실행

PowerShell, JDK 21 및 Android SDK가 필요하다. `local.properties`는 git에서 제외하며 각 기기의 `sdk.dir`를 넣는다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat test assembleDebug assembleRelease lintDebug --console=plain
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -n ai.gyeote.app/.MainActivity
```

Windows 한글 경로를 유지하기 위해 `android.overridePathCheck=true`와 JVM `file.encoding=COMPAT`를 사용한다. Gradle 8의 JVM 인수 파일은 Windows 네이티브 문자셋과 맞아야 한다([Gradle 이슈](https://github.com/gradle/gradle/issues/30304)). OS 문자셋을 바꾸지 않는다. Kotlin 원본과 JSON은 UTF-8을 사용한다. 빌드는 AGP 8.13.2, Kotlin 2.2.21, Gradle 8.13으로 고정한다([Android 빌드 호환표](https://developer.android.com/build/releases/about-agp), [Gradle 8.13](https://docs.gradle.org/8.13/release-notes.html)).

## 계층과 교체 지점

| 계층 | 파일 | 역할 |
|---|---|---|
| 도메인 | `domain/Warnings.kt` | 경고 사전, 위험도 3단계, 짧은 주행 문구, 빨간 신호 보수적 격상 |
| 도메인 | `domain/Maintenance.kt` | 정비 간격 예시·마지막 정비 거리·기록 검증; 기록이 없으면 추정하지 않음 |
| 도메인 | `domain/JourneyMachine.kt` | P→D 우선 출발, P 복귀+문 열림 도착, 기어 없음 시 기존 도어·브레이크·거리 조합, 중복 방지 |
| 도메인 | `domain/Destinations.kt` | 한국어 의도 해석, 주행 중 응답 차단·정차 후 안내, 보류·중복 방지 |
| 도메인 | `domain/Consent.kt` | 기본 off, 수신자·종류·만료·좌표 별도 허용, 철회·삭제 재시도 |
| 도메인 | `domain/WarningHistory.kt` | 발생/해제 시각·거리·기어·색·SDK 횟수, 30일/200건 보존, 반복 묶기·쉬운 문구 |
| 도메인 | `domain/RouteArrival.kt` | 수락한 길의 도착 확인, 매번 물어보기/자동 선택, 다음 정차 재질문, 암호화 재시도 큐 |
| 도메인 | `domain/PairingCode.kt` | 서버 만료 시각에 따른 연결 번호 남은 시간·만료 판정, 진단 출력 비공개 |
| 기능 선택 | `Features.kt` | 일반 운행 공유·경고 공유·좌표·음성 메시지·타이어 수치·독립 정비 화면 off |
| 차량 포트 | `vehicle/VehiclePorts.kt` | 기존 포트 + GearSource, 저압 여부·바퀴별 Float 수치·선택 단위의 TireState |
| 대체 구현 | `vehicle/FakeVehicle.kt` | 모의 콘솔과 동일한 포트로 신호 전달; 실제 길안내 화면은 없음 |
| 차량 연결 | `vehicle/PleosAdapters.kt` | 실제 SDK 조회·콜백, capability와 권한 확인, 동일 listener/area 해제 |
| 주행 제한 | `vehicle/PlatformDrivingState.kt` | AAOS UX 제한 + 기어 P 조건, 정보 없음 시 입력 제한 |
| 길안내 | `vehicle/PleosNavigator.kt` | 실제 요청·도착·오류 콜백, 요청 접수와 실제 안내 성공 구분 |
| 음성 확인 | `vehicle/PleosSpeechProbe.kt` | 명시적인 debug ON_DEVICE 확인, 계정 등록·서버 키 사용 없음 |
| Android 음성 | `vehicle/AndroidSpeaker.kt` | 오디오 포커스 + 네트워크가 필요 없는 한국어 TTS 음성만 선택 |
| 데이터 | `data/ApiClient.kt` | `docs/api_contract.md`의 부모 역할 API, UTF-8 JSON, Bearer, PATCH 포함 |
| 보관 | `data/SecureStore.kt` | Android Keystore AES-GCM, AtomicFile, 백업 제외 |
| 오디오 | `data/MessagePlayer.kt` | 최대 30초·2MB 메시지, 메모리 재생·완료 후에만 played 처리 |
| 연결·화면 | `AppController.kt`, `MainActivity.kt` | 단일 네트워크 작업자, 수명 관리, 한국어 큰 카드 화면 |

네트워크는 전경 화면이 활성화된 동안 정차 10초·주행 제한 중 30초로 폴링한다. 실패 시 2배 지연, 최대 120초 후 재시도한다. Activity가 멈추면 폴링·TTS·재생·이벤트 발송을 멈춘다. 실제 차량·백그라운드 서비스는 검증 범위에 포함하지 않는다. 음성은 Android의 로컬 한국어 TTS와 모의 인식 결과 입력을 유지한다. Pleos ON_DEVICE 확인의 결과와 한계는 `VERIFICATION_car.md`에 기록한다.

`PRIVATE_CAR_SAFETY`는 앱마켓 사전 승인이 필요하며 **debug 매니페스트에만** 있다. 텔테일 구독과 타이어 수치 API가 이를 요구한다. release는 텔테일 공개 조회를 3초마다 사용하고 비공개 수치 API를 호출하지 않는다. 에뮬레이터 2.1.0의 타이어 수치 capability는 `ERROR_NOT_SUPPORTED_PROPERTY`여서 수치·단위를 실제 수신했다고 표시하지 않는다. 저압 안내는 TPMS 경고 신호에 따른다. SDK AAR·원문 API 덤프는 저장소에 복사하지 않는다.

## 부모 동의와 서버

기본 주소는 개발 빌드의 `http://10.0.2.2:8787`이며 설정에서 HTTPS 도메인으로 바꿀 수 있다. 개발 빌드만 `10.0.2.2`, `127.0.0.1`, `localhost`의 HTTP를 허용한다. 릴리스는 HTTPS만 허용하고 리다이렉트로 인증을 넘기지 않는다. 가족 연결 후 서버 변경을 막아 다른 서버에 기존 인증이 전달되지 않게 한다. 인증 자료는 로그·문서에 출력하지 않는다.

처음에는 공유가 모두 꺼져 있고 받을 사람도 없다. 기본 동의 화면에는 알릴 사람과 **자녀가 보낸 길에 도착하면 알리기**만 표시한다. `shareRouteArrival`을 켜고 **동의하고 저장**을 눌러야 반영된다. 일반 출발·모든 도착·경고·좌표 공유 코드는 기능 스위치 뒤에 보존한다. 현재 화면에서 동의를 저장하면 숨긴 공유 종류는 off가 된다. 보낼 사람이 없거나 기간이 끝나면 이벤트를 보내지 않는다.

동의 범위를 줄이거나 모두 끄면 즉시 로컬 전송을 막고, 서버 동의를 off로 변경한 뒤 `DELETE /api/families/:id/shared-data`를 요청한다. 네트워크가 끊긴 경우 서버 삭제를 완료했다고 표시하지 않는다. 삭제 대기 상태를 암호화 저장하고, 다음 연결에서 먼저 삭제한 뒤 새 동의를 반영한다. 기존 일반 운행 이벤트는 큐에 보관하지 않는다. 수락한 길의 도착 소식과 목적지 응답은 암호화 큐로 유지한다. 동의 off·만료·주행 중·전경 밖에서는 도착 큐도 발송하지 않는다. 403/409는 화면에 구분하고 수동 재시도까지 보관하며, 통신 실패는 전경 정차 중 재시도한다.

경고등 기록은 기존 Keystore AES-GCM 저장소의 추가 키로 보관한다. 공개 3초 조회와 debug 구독 모두 같은 전이 로직을 사용하며 SDK 스냅샷 중복은 새 발생으로 세지 않는다. 시작한 지 30일이 지난 기록을 제거하고 최신 200건을 유지한다. 꺼진 뒤 5분 이내 다시 켜진 같은 종류·색·주행 상태는 부모 타임라인에서 묶고, 정비사 표에는 개별 발생을 유지한다. 화면의 기록 지우기는 정차 중 확인 후에만 반영한다. 기록 시각은 앱이 관찰한 시각이며 앱 미실행 중 발생을 복원하지 않는다.

수락한 목적지에 내비 도착 콜백이나 기어 P 복귀+도어 열림 2초가 들어오면 정차 중 도착을 처리한다. 기본 매번 물어보기는 응답할 때까지 카드를 유지하고, 보낼게요를 누를 때만 `arrived`와 `destinationId`를 보낸다. 응답 없이 출발하면 보내지 않고 다음 정차 때 같은 목적지에 한 번 더 묻는다. 재질문에도 응답 없이 출발하면 조용히 마치고, 이번엔 안 보낼게요를 직접 누르면 즉시 마친다. 재질문 상한은 미정이며 `ARRIVAL_REASK_LIMIT`의 현재 기본값은 1회다. 남은 횟수·다음 정차 대기는 기존 암호화 상태에 추가 필드로 저장한다. 사용자가 자동으로 보내기를 고른 경우 정차 도착 즉시 전송한다. 선택은 앱 로컬 암호화 상태에 저장하며 서버 동의 계약은 같다. 목적지 note는 수락 카드·출발 화면에 표시하고 Speaker에도 전달한다. 실제 음성 합성 성공은 엔진 지원에 달려 있다.

정비사 표는 정비 용어와 SDK 상수명을 함께 유지한다. 의미가 확인되지 않은 우선순위와 횟수 0은 표시하지 않으며, 차량이 알려 준 횟수가 1 이상일 때만 사람이 읽는 문장으로 표시한다. 원래 SDK 값은 로컬 기록에 그대로 보관한다.

서버 주소 편집과 연결 적용은 debug의 `모의 → 개발 연결 설정`에만 있다. 일반 설정에는 가족 연결·수신자·동의만 표시하며 release에는 주소 편집 화면이 없다. `가족 연결 번호 보기`와 `새 번호 받기`는 매번 `POST /api/families/:id/pair-code`를 호출하는 v0.3.1 서버가 필요하다. 서버가 준 `expiresAt`으로 남은 시간을 갱신하고 만료되면 번호를 숨겨 새로 받도록 한다. 새 번호는 메모리에서만 유지하며 앱이 전경을 벗어나면 폐기한다. 저장되어 있던 이전 번호는 표시하지 않는다. 캡처 전 `번호 가리기`를 눌러 유효한 연결 정보를 남기지 않는다.

## 모의 콘솔로 시연

1. Pleos 신호를 받을 때 `모의` 탭에서 가짜 입력으로 전환하거나 아래 인텐트에 `vehicleMode=fake`를 넣는다. 기어를 P→D로 바꾸면 한 번 출발로 판정한다. 기어를 넣지 않으면 문 닫힘·잠금 → 브레이크 밟음 → 2초 뒤 놓기 → 거리 1km 증가의 기존 경로도 동작한다.
2. 모의 목적지를 받고 `응`, `나중에`, `아니` 등의 인식 결과를 주입한다. 모호한 문장은 다시 묻는다. 수락 시 **모의 길안내 요청**이라고 표시한다.
3. 타이어·엔진·ABS 등의 경고를 켜고 상세에서 뜻·지금 할 일을 살핀다. 저압 입력과 수치 입력은 별개이고, 수치로 임의 저압 기준을 만들지 않는다. 단위를 모르면 숫자에 단위를 붙이지 않는다.
4. 엔진오일 시연 기록을 2,000km로 넣고 현재 거리를 11,900→12,000km로 바꾸면 예시 10,000km 간격에 도달한다. 독립 정비 화면은 숨겼고 경고 기록 아래 참고 한 줄로 안내한다. 기본 실제 정비 이력은 없음으로 취급한다. 제조사 정비표가 우선이다.
5. 내비 도착 콜백 또는 거리 변화가 20초 없는 상태에서 문을 2초 열어 도착 판정을 본다. 이미 도착한 뒤 같은 콜백은 중복 발송하지 않는다.
6. 주행 화면으로 전환하면 입력·탭·버튼 없이 1~2문장 카드만 나온다. 받은 목적지는 정차 뒤 여쭙는다. 복귀는 아래 개발 전용 인텐트를 사용한다.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -n ai.gyeote.app/.MainActivity --ez restricted false --es screen console --es vehicleMode fake
```

`screen`·`vehicleMode`·`restricted`·`warning` 인텐트 입력은 debug 빌드에서만 처리한다. `restricted`와 경고 주입은 가짜 어댑터에만 적용되어 Pleos 제한을 우회하지 않는다. `vehicleMode=pleos`로 자동 선택에 복귀한다. 모의 콘솔 탭도 debug 전용이다. release 화면 캡처는 보안 플래그로 차단한다.

## 검증 자료

작업 P의 현재 결과는 **[VERIFICATION_polish.md](VERIFICATION_polish.md)**, HTTP·실제 화면 결과는 `polish-integration.json`, 가로 PNG는 `screens/polish/`에 있다. `python app/tools/polish_integration.py`는 실행 중인 emulator-5554와 고유한 디버그 암호화 저장소·새 임시 DB만 사용한다. 서버 원본 `createApp`이 포트 8787에서 직접 요청을 처리하며 수동 계측 리스너는 메서드·상태와 허용된 동의 필드만 기록한다. 프록시나 응답 주입을 쓰지 않으며 종료 시 서버를 멈추고 임시 폴더는 보존한다. `python app/tools/polish_release_check.py`는 unsigned release APK의 로컬 debug 서명 복사본을 임시 폴더에 만들고 실제 설정 화면 XML을 확인한 뒤 Debug APK를 복귀시킨다. `FLAG_SECURE`는 유지하고 기존 앱 데이터는 지우지 않는다. `python app/tools/verify_polish.py`가 최종 빌드·테스트·린트·문구·출시 권한·실제 버튼 색상과 캡처 APK 해시를 집계한다.

작업 K의 `VERIFICATION_edge.md`와 `edge-integration.json`, 작업 I/J의 `VERIFICATION.md`와 `VERIFICATION_car.md`는 당시 증거로 유지한다. 과거 K의 무응답 종료 기대값은 P의 다음 정차 재질문 결정으로 대체됐다.

개발용 `publicTelltalesOnly=true`는 비공개 구독을 건너뛰어 공개 폴링만 검증한다. `testProfile`은 기존 state.enc를 건드리지 않는 별도 암호화 상태 이름이며 영문·숫자·밑줄·하이픈 40자 이하만 허용한다. `serverUrl`은 개발 화면의 주소 입력 초기값만 바꾼다. 세 입력 모두 debug에서만 적용한다.

`python app/tools/capture_screens.py --serial emulator-5554`가 실제 Activity를 열고 `adb exec-out screencap -p`로 홈·동의·경고·주행 카드·콘솔 PNG와 UI 계층, 캡처 메타데이터를 `app/docs/screens/`에 저장한다. 연결 번호가 담기지 않은 초기 모의 상태에서 실행한다. 실행 결과와 합격 기준별 판정은 `VERIFICATION.md`에 기록한다.

`python app/tools/runtime_smoke.py --serial emulator-5554`는 모의 차량의 문이 열린 초기 상태에서 시작한다. 실제 화면 버튼으로 출발·도착·목적지 음성 수락·정비 기록을 실행하고, 앱 프로세스 재시작 후 기록 보존까지 확인한다. 이 도구는 이 앱의 시연용 정비 기록과 모의 목적지를 남긴다. 가족 연결 전의 시연 기기에서 사용한다.

작업 K에서는 `--report-path app/docs/edge-runtime-smoke.json`을 함께 전달해 기존 J 보고서를 보존한다. 모의 검증은 고유한 `testProfile`을 쓰고, 정비 화면 대신 기록 화면의 참고 줄과 모의 콘솔의 저장 상태를 확인한다. `python app/tools/verify_edge.py --backup <수정 전 백업 경로>`는 JUnit·린트·출시 권한·문구·실제 XML·APK 해시와 기존 테스트 보존을 집계한다.
