# 담당 I 검증 보고서

검증일: 2026-10-22. 작업 폴더: `<저장소 루트>`. 패키지: `ai.gyeote.app`. Android 앱·루트 Gradle 범위만 구현했고, 서버·계약·조사 문서는 수정하지 않았다. 커밋·푸시·게시·유료 호출은 수행하지 않았다.

## 합격 기준별 expected / observed / passed

| 기준 | expected | observed | passed |
|---|---|---|---|
| (a) `gradlew test` | 경고 사전·정비·출발/도착 상태기계·의도·동의 테스트 통과 | 고유 시나리오 98개를 Debug 98 + Release 98 = **196건 실행**, 실패 0·오류 0·건너뜀 0 | **true** |
| (b) `gradlew assembleDebug` | 설치 가능한 Debug APK 생성 | `app/build/outputs/apk/debug/app-debug.apk`, AVD 설치 `Success`, 실제 Activity 실행 `Status: ok` | **true** |
| (c) 폰 AVD 실행·캡처 | 홈·동의·경고 상세·주행 카드·모의 콘솔의 실제 PNG 5개 | `Medium_Phone_API_36.1` / `emulator-5554`, SDK 36, `sdk_gphone64_x86_64`; `adb exec-out screencap -p` PNG 5개 및 UI XML 저장; 주행 카드에는 앱의 clickable/Button/EditText 0개 | **true** |
| (d) 문구 검사 | 지정 금지 문구 검색 0건 | 빌드 산출물을 제외한 앱의 Kotlin·XML·Markdown·Python·JSON·로그 검색 **0건** | **true** |
| (e) 공급사 의존성 | 빌드 파일의 공급사 SDK 의존성 0개 | `settings.gradle.kts`, 루트/앱 build 파일, `gradle.properties` 검색 **0건**; 빈 연동 TODO만 존재 | **true** |
| 추가: Android 린트 | 오류 없이 완료 | `lintDebug` **0 errors, 5 warnings, 1 hint**, BUILD SUCCESSFUL | **true** |

테스트 상세 집계: [test-summary.json](test-summary.json). 최종 빌드 로그: [build-verification.log](build-verification.log). 이 보고서의 테스트 수는 실제 JUnit XML에서 집계했다.

| 스위트 | Debug | Release | 검증 행동 |
|---|---:|---:|---|
| WarningDictionaryTest | 32 | 32 | 16종 안내, 불확실성 표현, 주행 1~2문장, 빨간 신호 격상 |
| MaintenanceTest | 7 | 7 | 기록 없음, 11,900→12,000km 경계, 초과·정비 재기록, 잘못된 거리 |
| JourneyMachineTest | 10 | 10 | 도어·브레이크·거리 조합, 문 튐, 정지 시간, 중복, 재출발 대기, 역행·잘못된 신호 |
| IntentParserTest | 21 | 21 | 한국어 수락·보류·거절·모호한 말·부분 문자열 오인 방지 |
| DestinationInboxTest | 5 | 5 | 주행 중 보류, 정차 후 안내, 중복, 보류 후 수락, 재시작 복원 |
| ConsentTest | 10 | 10 | 기본 off, 수신자·종류·만료·좌표 별도 허용, 철회 순서, 삭제 실패 재시도·복원 |
| ApiClientTest | 13 | 13 | MockWebServer에서 UTF-8·인증·메서드·경로·본문·204·403·리다이렉트·HTTP 제한 |
| 합계 | **98** | **98** | **196건 전부 통과** |

## 실제 실행 명령과 결과

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --version
# Gradle 8.13 / JDK 21.0.8
.\gradlew.bat test assembleDebug lintDebug --console=plain
# BUILD SUCCESSFUL in 51s, 80 actionable tasks
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
# Performing Streamed Install / Success
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -W -n ai.gyeote.app/.MainActivity
# Status: ok / Activity: ai.gyeote.app/.MainActivity
python -u app/tools/capture_screens.py --serial emulator-5554
# PASS 01-home / 02-consent / 03-warning / 04-driving / 05-console
python -u app/tools/runtime_smoke.py --serial emulator-5554
# PASS 7/7: 출발, 도착, 한국어 수락→모의 경로, 수신함 정리, 뒤로가기, 정비 기록, 재시작 보존
```

첫 빌드의 Windows 한글 경로 검사와 테스트 인수 파일 인코딩 문제를 프로젝트 설정으로 해결했다. 최초 린트에서 발견한 Android 16 뒤로가기와 개발 네트워크 도메인 속성 오류도 수정한 뒤 위 최종 명령을 다시 실행했다. 남은 린트 5건은 고정한 라이브러리·Gradle의 새 버전 안내, 힌트 1건은 KTX 권장이다.

## APK와 화면 증거

APK 절대 경로: `app/build/outputs/apk/debug/app-debug.apk`.

SHA-256: `a79211e9968476a40b5a8a0c4db5c231858286643c6dc06f3f94315c6247cd71`.

| 화면 | PNG | 함께 저장한 증거 |
|---|---|---|
| 홈 | [01-home.png](screens/01-home.png) | 오늘 상태·차량 경고·기본 공유 off |
| 동의 설정 | [02-consent.png](screens/02-consent.png) | 맨 위 전체 끄기·수신자·공유 선택 |
| 경고 상세 | [03-warning.png](screens/03-warning.png) | 타이어 안내의 뜻·지금 할 일 |
| 주행 중 | [04-driving.png](screens/04-driving.png) | 단일 큰 카드·앱 입력 요소 0개 |
| 모의 콘솔 | [05-console.png](screens/05-console.png) | 문·브레이크·거리·내비 도착 입력 |

각 PNG와 같은 이름의 XML이 실제 UI 계층이며 [captures.json](screens/captures.json)에 캡처 시각·기기·각 파일 해시와 캡처 대상 APK 해시를 기록했다. PNG를 직접 열어 홈·동의·경고·주행·콘솔의 글자와 배치를 확인했다. 첫 설치 직후 시작 화면이 잡힌 캡처는 실행 완료 대기를 추가한 도구로 다시 찍었다.

추가로 AVD 화면의 실제 버튼을 UI XML의 좌표로 눌러 **7개 흐름이 모두 통과**했다. 문·브레이크·거리 입력에서 출발, 내비 콜백에서 도착, 한국어 `응` 입력에서 모의 길안내 요청, 수락 뒤 수신함 정리, Android 36 뒤로가기, 정비 거리 표시, 앱 프로세스 종료·재시작 후 정비 기록 보존을 확인했다. 기대 문구와 결과는 [runtime-smoke.json](runtime-smoke.json)에 있다. 이 추가 검증은 자체 모의 입력을 사용했고 실제 서버로 이벤트를 보내지 않았다.

## 검증 경계와 다음 연결 작업

- 실제 차량 SDK·공급사 이미지·공급사 에뮬레이터는 다운로드·설치·사용하지 않았다. 테스트한 기기는 표준 Android 폰 AVD이다. 해당 AVD는 통합 검증에 재사용할 수 있게 실행 상태로 두었다.
- Telltale/Odometer/Door/Brake/Tire/Navigator/Listener/DrivingState는 Fake 포트이다. 길안내는 요청 기록만 남기며, 실제 지도 화면을 실행했다고 표시하지 않는다. STT는 한국어 인식 결과 텍스트를 주입한다. 실제 한국어 음성 품질이나 마이크 인식은 검증하지 않았다.
- HTTP 계약은 실제 OkHttp 요청을 로컬 MockWebServer로 검증했다. 운영 서버·외부 가족 기기의 수신, 실차 주행·백그라운드 동작, 플랫폼 권한·심사 적합성을 입증하지 않는다. 서버·자녀 웹과의 실제 통합은 다음 단계이다.
- 폴링은 화면 활성 중 정차 10초·제한 중 30초, 실패 시 최대 120초 지연이다. 화면 종료 시 중단한다. 공유 철회는 로컬 차단을 우선하며, 연결이 끊기면 서버 삭제를 완료했다고 말하지 않고 삭제 대기·재시도 상태를 유지한다.
- 현재 위치는 별도 동의 필드까지 구현했으나 이 Fake 차량은 좌표를 수집하지 않는다. 정비 주기는 시연 예시이며 제조사 설명서가 우선이다.
- 공급사 연동 후 실제 색/상태·단위·지원 여부, 내비 성공 콜백·에러, 주행 제한의 실제 전달, 오디오 포커스·한국어 음성, 백그라운드 허용을 다시 검증해야 한다.

## 변경 파일

전체 목록은 [modified-files.txt](modified-files.txt)에 기록했다. 구조와 시연 방법은 [README.md](README.md)에 있다. `local.properties`와 Gradle/빌드 캐시·에뮬레이터 실행 로그는 로컬 생성 파일이며 git에서 제외한다.
