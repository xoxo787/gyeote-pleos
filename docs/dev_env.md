# 개발 환경 (2026-10-22 구성·확인)

| 항목 | 값 | 확인 |
|---|---|---|
| Android SDK | `<Android SDK 경로>` (platforms android-36, build-tools 35.0.0/36.1.0) | 디렉터리 확인 |
| JDK | Android Studio 내장 `C:\Program Files\Android\Android Studio\jbr` (21.0.8) | `java -version` |
| Pleos 에뮬레이터 이미지 | `system-images\android-34\pleos_car_emulator\x86_64` — Pleos Connect v2.1.0, API 34, 42dot | zip SHA-1 `b4f858d5…acaa` = 공식 매니페스트 값 |
| AVD | `Pleos_Connect` (2560×1440, 160dpi, RAM 4GB, 콜드부트) | `emulator -list-avds`, 부팅 45초, `ro.build.display.id=RELEASE.release_connect_v2.1.0`, `android.hardware.type.automotive` 확인 |
| Virtual Console | `adb -s emulator-5554 forward tcp:18088 tcp:8088` → `http://127.0.0.1:18088/` | HTTP 200, 제목 "Pleos Connect Virtual Console" |
| 데모 서버 | `node server/src/index.js` (PORT 기본 8787). 에뮬레이터 안에서는 `http://10.0.2.2:8787` | `node --test server/test` 22/22 |

## 주의
- 이 PC에서는 호스트 포트 **8088이 Windows에 예약**되어 있어(오류 10013) 공식 문서의 `tcp:8088 tcp:8088` 대신 **18088**을 쓴다.
- 에뮬레이터 실행: `emulator -avd Pleos_Connect -no-snapshot -no-boot-anim`
- SDK 저장소는 로그인 없이 받을 수 있다. SDK 시그니처 요약은 `docs/pleos_sdk_notes.md`.
