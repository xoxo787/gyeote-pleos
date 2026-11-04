# 2026-11-04 H8 — 화면 뒤 차량 기록

최종 판정: **A~G 모두 통과**. `test assembleDebug assembleRelease lintDebug --console=plain` 성공. Debug·Release 각각 **259개**, 실패·오류·건너뜀 0(기존 249 + 새 10). 린트 Error 0, Warning 9, Hint 1. 배경 통합 최종 **11/11**, 정적 집계의 모든 검사 true. 환경: Pleos Connect 2.1.0 / API 34.

| 기준 | expected | observed | passed |
|---|---|---|---|
| A | 전체 빌드 성공, 단위 테스트 249 초과, lint 오류 0 | 빌드 성공, Debug·Release 각각 259개, lint 오류 0 | true |
| B | F1 16/18 이상(허용 환경 실패만), F2 16/16, F3 14/14 | F1 17/18(한국어 TTS 환경 실패 1), F2 16/16, F3 14/14 | true |
| C | HOME 뒤 60초 이상 후 경고등 On→Off 기록, FGS, frozen 아님 | 65초 대기 뒤 공개 조회로 TPMS 발생/해제 기록, `isForeground=true`, `isFrozen=false` | true |
| D | 내비 목적지명 콜백이 앱을 다시 열기 전에 도착 | 콜백 **02:29:09.468** → 앱 화면 재연결 **02:29:25.210**. 수신 당시 내비 resumed·Passer 화면 해제·`isFrozen=false`. 안내 종료도 확인 | true |
| E | 배경 카드 즉시 표시, 끄기 서비스·알림 해제, release FGS 권한·PRIVATE 없음 | 배경 인계·반납 카드 즉시 표시. 실제 알림 1개 표시→끄기에서 서비스·알림 0→끄기로 재진입해도 0→다른 모드에서 재시작. LOW 채널 1개·소리/진동 없음. release aapt의 FGS 권한 존재·PRIVATE 없음 | true |
| F | 바뀐 문구 전체 목록, 해당 문구·검증 UI의 금지어·호칭 0 | 아래 전체 목록. 추가·수정 생산 코드 문구 **0건**, 실행 캡처 **29개 XML**의 앱 문구 **0건**. 이전 기록 범위 문장은 생산 코드에서 0건 | true |
| G | 문서·변경 파일·다르게 한 것 기록 | 지정 문서 갱신, `background-modified-files.txt`에 전체 파일 목록, 아래 다르게 한 것 절·실패 시도 보존 기록 | true |

## 구현과 서비스 타입

- `VehicleRecordRuntime`은 application context로 컨트롤러를 한 개 유지한다. `VehicleRecordService`가 기록 수명을 맡고, Activity는 `RecordLifecycle` 화면 콜백을 붙였다 뗀다. 화면을 떠나면 대기 render와 카운트다운도 취소한다. 서비스와 화면이 모두 없으면 SDK 리스너·Vehicle·NaviHelper·TTS·작업자를 닫는다.
- Activity `onStart`에서 켜진 모드만 시작하고, 끄기는 기록을 즉시 차단한 뒤 서비스·알림을 내린다. 다른 모드를 고르면 다시 시작한다. 부팅 수신자·자동 재시작·배경 Activity 실행은 넣지 않았다(`START_NOT_STICKY`). 서비스 프로세스가 종료되면 다시 앱을 열어야 한다.
- 타입은 **`specialUse`**다. 차량 내부 SDK의 경고등 기록과 인수/반납 판정은 위치 서비스나 외부 Bluetooth/USB 기기 연결의 전제가 없으므로 해당 타입의 권한을 억지로 추가하지 않았다. `FOREGROUND_SERVICE`·`FOREGROUND_SERVICE_SPECIAL_USE`와 subtype property를 main 매니페스트에 선언했다. 앱마켓의 개별 승인은 미확인이다. 근거: [Android 서비스 타입](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)(2026-11-04 확인).
- 알림 채널은 `vehicle_record` 1개(LOW, 소리·진동·배지 없음), 상주 알림을 누르면 MainActivity가 열린다. `POST_NOTIFICATIONS`는 선언했으나 자동 권한 창은 열지 않는다. 거절된 상태에서도 FGS는 실행하며 알림 창 노출은 OS 권한 설정에 따른다. 근거: [Android 알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission)(2026-11-04 확인).
- 화면 뒤에서는 TTS·투어 진행·기존 화면 전용 서버 폴링을 멈춘다. 인계 카드가 뒤에서 준비돼도 발화하지 않고, 앱을 열어 카드가 보이면 기존 정착 대기 후 한 번 발화한다. 반납 카드는 기존과 같이 음성이 없다. 경고등 기록은 기존 암호화 저장소에 남고, 대기 인수/반납 판정과 내비 목적지명은 서비스가 유지하는 컨트롤러에 남는다. 강제 종료 뒤 대기 카드 복원은 추가하지 않았다.

## 바뀐 문구 전체 목록

| 위치 | 이전 → 현재 |
|---|---|
| `domain/Handover.kt`, 이력 없음 | `기록이 없어요. (앱이 켜져 있던 동안의 기록이에요)` → `기록이 없어요.` + 줄바꿈 + 아래 공통 문구 |
| 같은 파일, 이력 있음 | 요약 + `앱이 켜져 있던 동안의 기록이에요.` → 요약 + 아래 공통 문구 |
| `MainActivity.kt`, 경고등 기록 상단 | `최근 30일 · 최대 200건 · 앱이 켜져 있던 동안의 기록이에요.` → `최근 30일 · 최대 200건 · ` + 아래 공통 문구 |
| 같은 파일, 끄기 설명 | `끄기를 고르면 인계 카드를 띄우지 않아요.` → `끄기를 고르면 인계 카드와 차 상태 기록을 멈춰요.` |
| 신규 알림 | 채널 `차 상태 기록`, 제목 `Passer`, 본문 `Passer가 차 상태를 기록하고 있어요` |

공통 문구(`HandoverText.RECORDING_SCOPE`): **Passer가 기록 중일 때의 내용이에요. 화면을 닫아도 기록해요. 끄기·앱 종료 중에는 기록하지 않아요.** 제안 문구에 끄기·프로세스 종료의 한계를 추가했다.

## 검증 방법·근거

- 회귀 확인: 문구 테스트를 먼저 실행해 8개 중 변경 동작 3개가 실패함을 확인한 뒤 수정했다. 새 테스트 10개는 기록 범위 2개와 서비스 시작/정지·화면 콜백 분리 8개다. 기존 테스트는 삭제하지 않았다.
- 기존 소스의 테스트 메서드 195개는 매개변수 확장으로 실행 사례 249개가 된다. 수정 전 백업의 메서드 식별자를 현재 JUnit 결과와 대조해 **기존 249개가 모두 실행·통과**했음을 확인했다(제거 0, 매개변수 입력 소스 동일). 새 메서드 10개를 더해 259개다. 기능 스위치·포트·암호화 저장소·JSON 저장 형식 소스도 백업과 byte 동일이다.
- 빌드: `background-build-verification.log`, `background-static-verification.json`. 새 lint 경고 `StaticFieldLeak`는 정적 Session의 Context 필드 때문이며, 실제 전달값은 `context.applicationContext`로 제한한다(Activity를 보관하지 않음). 나머지는 기존 의존성 버전·16KB 정렬 경고다.
- 통합: 순서대로 `handover_integration.py` → `tour_integration.py` → `return_integration.py` → `background_integration.py`. 화면 뒤 검사는 별도 debug 암호화 프로필과 **공개 경고등 3초 조회만** 사용한다. 실행 보고서 `background-integration.json`, 앱 로그 `background-vehicle.log`, 캡처·서비스/프로세스 근거 `screens/background/`.
- 네 통합 보고서의 APK 해시가 모두 최종 debug APK `63d07bdd77402c1e6a07107fe42f011dbbfd4a57947191cfb0d37b088857a9d8`와 같다. 캡처 해시 불일치 0건. release APK SHA-256은 `8da03a03f73107aa91c2e26e6e95818806bff3d2f3569c82841f8456aeba440b`다. 공개 변경 문서·로그·JSON·XML의 개인 경로·작업 핸들 검색 0건.
- 문구 검색 범위는 추가·수정 생산 코드와 이번 네 스크립트의 앱 UI다. 숨긴 옛 기능의 기존 문구는 변경하지 않았으며, F의 0건을 소스 전체에 대한 호칭 제거로 해석하지 않는다. 최종 캡처 `01-history-and-handover`·`02-return-ready`·`03-mode-off`도 직접 열어 확인했다.
- 시작 전 여유 메모리 **4.49GiB** 확인, 에뮬레이터 1회 시작. 기존 앱 저장소·점검 앱은 유지했다. 기존 통합 산출물과 수정 전 소스·문서는 지정된 임시 작업 폴더의 고유 백업에 보존했다. 삭제·커밋·푸시·업로드는 하지 않았다.
- 종료 상태: 에뮬레이터는 켜 두었고 내비 안내는 종료했다. debug 앱은 고유 테스트 프로필의 렌터카·카셰어 모드이며 활성 사용자 10의 알림 권한은 허용 상태다. 범위 안의 남은 작업은 없다. 실차·도착 콜백·앱마켓 승인·프로세스 종료 중 연속 기록은 확인하지 않았다.

## 다르게 한 것

- 서비스 주기는 기존 코드를 유지했다: 공개 텔테일 조회는 3초, 컨트롤러의 판정 tick은 원래의 1초다. 안정화·투어 애니메이션의 타이밍을 바꾸지 않기 위해 tick을 3초로 늦추지 않았다.
- Application 클래스를 추가하는 대신 프로세스 싱글턴에서 application context만 보관했다. 대기 카드는 같은 컨트롤러의 메모리 상태로 유지하고, 경고등 이력·확정 인수/반납은 기존 암호화 형식을 그대로 사용한다.
- 알림 권한을 앱 진입 때 강제로 묻지 않는다. 허용/거절에 따라 알림 창의 표시만 달라지며, 기록 서비스는 권한에 의존하지 않는다.
- 이 이미지의 다중 창에서는 HOME만 누르면 Passer가 오른쪽 창에 계속 resumed 상태로 남았다. HOME 뒤 내비 화면으로 오른쪽 창을 덮고 `recordScreen detached`와 resumed 목록의 Passer 부재를 확인한 뒤 65초를 기다렸다. 단순히 HOME만 누른 상태를 배경 성공으로 세지 않았다.
- 배경 도구의 첫 실행은 중복 UID 조회 명령의 잘못된 인자로 중단됐다(에뮬레이터 종료 아님). 그 시도는 임시 작업 폴더에 보존했고, 중복 조회를 제거한 뒤 실제 `dumpsys activity processes` 근거로 다시 검사했다.
- 두 번째 시도는 C와 배경 카드가 통과했으나, 내비 상세 화면의 [목적지로] 단계 누락 때문에 안내를 시작하지 못했고 알림 권한도 활성 사용자 10에 적용되지 않았다. 그 실패 보고서·화면을 임시 폴더에 보존한 뒤 내비 단계를 추가하고 `pm grant/revoke --user current`로 고쳤다. 기본 사용자 0에 잘못 부여한 새 알림 권한은 회수했다. `preflight-state.txt`는 HOME만 눌렀을 때 TOP 상태였던 사전 확인이며, 최종 배경 근거는 `home-after-65s-state.txt`·`navigation-state.txt`다.
- 세 번째 시도에서 C·D·배경 카드·모드 끄기가 통과했다. 재시작 직후 알림 조회만 실패했으나 잠시 뒤 실제 `NotificationRecord`가 나타났다. OS의 FGS 알림 표시 지연을 고려해 최대 18초 대기를 넣었고, **실제 알림이 나타난 상태를 먼저 확인한 뒤 끄기로 제거하는 순서**로 강화했다. 이 실패 시도의 보고서·화면·앱 로그도 임시 폴더에 보존했다.
- 네 번째 시도에서 실제 알림 표시→끄기 제거→재시작까지 통과했으나, 채널 수 집계가 알림의 `effectiveNotificationChannel`과 저장된 채널을 중복 계산했다. 활성 사용자의 `AppSettings` 안에 저장된 채널만 세도록 고쳤다. 이 시도 역시 보고서·화면·앱 로그를 별도 보존했다. 앱 코드는 첫 통합 실행 이후 바꾸지 않았다.

## 변경 파일

구현: `app/src/main/AndroidManifest.xml`, `AppController.kt`, `MainActivity.kt`, `domain/Handover.kt`, 신규 `vehicle/RecordLifecycle.kt`·`VehicleRecordService.kt`. 테스트: `domain/HandoverTest.kt`, 신규 `vehicle/RecordLifecycleTest.kt`. 도구: 신규 `app/tools/background_integration.py`. 문서: 이 문서·`app/docs/README.md`·`VERIFICATION_handover.md`·`VERIFICATION_return.md`·`docs/pleos_sdk_notes.md`·루트 `README.md` 검증 표. 재실행 산출물은 각 통합 JSON·앱 로그·PNG/XML 캡처다(최종 정확한 목록은 `background-modified-files.txt`).
