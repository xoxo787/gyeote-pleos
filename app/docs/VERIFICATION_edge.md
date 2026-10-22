# 작업 K 앱 엣지 기능 검증 — C안 최종

검증일: 2026-10-22. **필수 합격 기준 (a)~(e)는 모두 통과했다.** 도착 소식은 조정자가 전달한 사용자 결정과 [결정 기록](../../docs/decisions.md)에 따라 **매번 물어보기(기본) / 자동으로 보내기**로 구현했다. 초안의 응답 시간에 따른 자동 발송은 제거했다. FCA 등의 추가 실신호 입력은 아래처럼 별도 판정했다.

## expected / observed / passed

| 기준 | expected | observed | passed |
|---|---|---|---|
| (a) 빌드·테스트·린트 | `gradlew test assembleDebug assembleRelease lintDebug`, 실패 0, 새 로직 테스트 | 최종 소스 BUILD SUCCESSFUL; Debug 161 + Release 161 = **322건**, 실패·오류·건너뜀 각 0; 기존 116개 시나리오 유지 + 45개 추가; 테스트 메서드 66 → 107개(신규 41개), 제거 0; lint 오류 0·경고 8·힌트 1 | **true** |
| (b) Pleos 화면 | 기존 emulator-5554에서 필수 7종 이상, 2560×1440 | 같은 최종 APK의 PNG **21장** + XML; 홈·부모 기록·정비사 표·단순 동의·응답 대기 카드·전송 후·주행 카드 포함, C안 기본/자동 선택도 캡처 | **true** |
| (c) 실제 Console 경고 기록 | 실제 켜기/끄기 입력이 로컬 기록으로 남음 | ENGINE은 허용된 debug 구독 경로, ABS는 `publicTelltalesOnly=true`의 공개 3초 조회 경로에서 발생·해제 기록 확인; 최종 APK의 실제 구독 등록 로그도 확인; TPMS 실제 상세 화면 확인 | **true** |
| (d) 숨김 | 좌표 공유·자녀 음성 메시지·타이어 수치·독립 정비 화면이 보이지 않음 | 캡처 전체 앱 XML 검색 0건; 기존 정비/메시지 debug 경로도 기록/홈으로 이동; 주행 화면의 입력 노드 0개 | **true** |
| (e) 문구·출시 권한 | 요청 문구 8종 0건, release에 PRIVATE_CAR_SAFETY 없음 | src/tools/docs의 텍스트·로그·XML 검색 **0건**; release 병합 매니페스트에 해당 비공개 권한과 음성 권한 없음 | **true** |

근거: [빌드 로그](edge-build-verification.log), [JUnit 집계](edge-test-summary.json), [정적 검사](edge-static-verification.json), [서버·차량·UI 결과](edge-integration.json), [주 실행 로그](edge-runtime.log), [추가 화면 검사](edge-post-checks.log), [기존 모의 입력 검사](edge-runtime-smoke.json). 최종 서버·차량·추가 UI 검증 **28/28**, 기존 모의 입력 **7/7**, 정적 검사 **12/12**가 통과했다. 가짜 입력 검사의 `android-36-back-dispatch`는 기존 시나리오 이름이며 실제 단말 API는 **34**다.

## 구현과 실제 검증

- 기존 Keystore AES-GCM + AtomicFile 저장소에 경고 기록과 전송 큐를 추가했다. 종류·SDK 상수·색상·위험도·우선순위·SDK 횟수, 발생/해제 시각·거리·기어·주행 상태를 보관한다. 거리가 없으면 미수신으로 남기고, 시작 시각이 최근 30일인 최신 200건만 유지한다. 같은 종류·색·주행 상태가 꺼진 뒤 5분 안에 반복되면 부모 타임라인에서 묶으며, 정비사 표는 개별 발생을 유지한다. 경고 기록은 서버로 보내지 않는다.
- 부모의 기록 지우기 취소는 실제 기록을 유지했고, 확인 버튼을 누른 뒤에는 빈 타임라인을 확인했다. 테스트는 별도 디버그 저장소에서 수행했다. 정비 안내는 기록 아래 참고 문장으로 합쳤다.
- 기본 모드는 응답 없는 상태에서 **12초 이상** 지나도 카드가 남고 이벤트가 0건이었다. 보낼게요를 누른 뒤 `arrived + destinationId + summary` 이벤트가 정확히 1건 생성됐다. 응답 없이 다시 출발하면 보내지 않고 다음 정차에도 묻지 않았다. 이번엔 안 보낼게요도 0건이었다.
- 자동 모드는 사용자가 직접 선택한 값을 앱 로컬에 저장했다. 프로세스 재시작 뒤 선택이 유지됐고, 정차 도착 시 질문 없이 이벤트 1건을 보냈다. 전송 방법은 서버 Consent에 추가하지 않았다. 공유 동의는 `shareRouteArrival`과 수신자·만료 규칙으로 확인한다.
- 네트워크 단절·403·409는 전송 완료와 구분해 표시하고 암호화 큐에 남겼다. 409 큐는 프로세스 재시작 뒤 복구됐으며 각 오류 해소 후 수동 재시도는 한 번만 저장됐다. 이 오류들은 **로컬 검증 장치가 연결 종료/HTTP 응답을 주입**했고, 정상 요청은 수정하지 않은 실제 서버의 `createApp` 핸들러가 처리했다. 모든 공유를 끈 뒤 서버 동의 off와 카드 없음, 철회 이후 이벤트 POST 0건을 확인했다.
- 목적지 note는 수락 카드와 주행 화면에 보이고 Speaker에 전달한다. 음성 합성 완료는 검증하지 않았다. NaviHelper 요청 접수는 실제 길안내 완료나 실차 도착의 증거로 삼지 않았다. 이번 실제 도착 검증은 Console의 기어 P 복귀 + 도어 열림 안정화 경로를 사용했다.
- 같은 SDK 스냅샷과 변경 없는 폴링에서 전체 화면을 반복 생성하지 않도록 보완했다. 실제 차량 상태 변화는 갱신하며, 응답을 기다리는 도착 카드의 입력 요소는 유지한다. 최종 검증에서는 전체 끄기의 서버 반영을 확인한 뒤 후속 주행을 실행했다.

## FCA와 의미 미확인 항목

FCA는 전방 충돌방지 보조의 쉬운 설명을 추가하되 원인을 확정하지 않는 문구로 작성했다. `ACCEL_RESPONSE_DASH`, `ACCEL_RESPONSE_ACCEL` 상수의 구체적 의미는 **미확인**이다. 해당 접두사는 일반 안전 기능 안내로만 연결하며 특정 원인이나 기능을 단정하지 않는다. 단위 테스트와 모의 상세 화면은 통과했다. 20/21번 캡처는 **모의 차량 입력**이며 실제 SDK 신호 수신을 증명하지 않는다.

| 추가 확인 | expected | observed | passed |
|---|---|---|---|
| console-input-FCA | Console accepts the SDK constant for a live signal check | {"http_status": 400} | **false** |
| console-input-ACCEL_RESPONSE_DASH | Console accepts the SDK constant for a live signal check | {"http_status": 400} | **false** |

이 두 추가 실신호 검사는 현재 Console 입력의 HTTP 400으로 차단됐다. 필수 (c)의 실신호 증거는 실제 ENGINE/ABS/TPMS이며 이 모의 캡처와 구분한다. 차량 제조사별 의미 확정과 실제 FCA 신호 수신은 남은 확인 사항이다.

## 화면과 APK

| 화면 | PNG |
|---|---|
| 홈 | [01-home.png](screens/edge/01-home.png) |
| 부모 경고 기록 | [02-history-parent.png](screens/edge/02-history-parent.png) |
| 정비사 표 | [03-mechanic.png](screens/edge/03-mechanic.png) |
| 단순 동의 | [04-consent.png](screens/edge/04-consent.png) |
| 매번 물어보기 카드 | [05-arrival-confirm.png](screens/edge/05-arrival-confirm.png) |
| 보낸 뒤 | [06-arrival-sent.png](screens/edge/06-arrival-sent.png) |
| 주행 카드·자녀 한마디 | [07-driving.png](screens/edge/07-driving.png) |
| 공개 폴링 기록 | [08-public-poll-mechanic.png](screens/edge/08-public-poll-mechanic.png) |
| 자녀 한마디 수락 카드 | [09-destination-note.png](screens/edge/09-destination-note.png) |
| 통신 실패·큐 | [10-network-queued.png](screens/edge/10-network-queued.png) |
| 403 | [11-consent-denied.png](screens/edge/11-consent-denied.png) |
| 409 | [12-conflict.png](screens/edge/12-conflict.png) |
| 자동 모드 | [13-consent-automatic.png](screens/edge/13-consent-automatic.png) |
| 기본 모드 | [14-consent-default-method.png](screens/edge/14-consent-default-method.png) |
| 기록 삭제 확인 / 결과 | [15](screens/edge/15-clear-confirmation.png), [16](screens/edge/16-history-cleared.png) |
| 숨긴 정비 / 음성 경로 | [17](screens/edge/17-hidden-maintenance.png), [18](screens/edge/18-hidden-messages.png) |
| 실제 TPMS 상세 | [19-tpms-detail.png](screens/edge/19-tpms-detail.png) |
| 모의 FCA / 일반 안전 기능 | [20](screens/edge/20-fca-detail.png), [21](screens/edge/21-safety-meaning-unconfirmed.png) |

최종 Debug APK SHA-256: `d263027ee6b8dc2d266c93f5cebeb302663f6fe6a2c58881c0e56d4baa2580d7`. 모든 최종 캡처는 이 APK를 사용했고 PNG별 해시는 결과 JSON에 있다. Debug/Release APK는 `app/build/outputs/apk/`에 있다. Release는 unsigned 산출물이며 배포용 서명·게시·앱마켓 승인을 수행하지 않았다.

## 호환성·실행 경계·종료 상태

`Consent.shareRouteArrival`과 `SharedEvent.destinationId`는 추가 필드이며 이전 저장값은 공유 off, 전송 방식은 매번 물어보기로 읽는다. 기존 동의 필드와 가짜 포트·정비/음성/수치 코드는 기능 스위치 뒤에 유지했다. 서버 v0.3 이상이 새 도착 소식에 필요하다. 별도 외부 데이터 마이그레이션은 없다.

기록은 앱이 받은 스냅샷의 관찰 시각이다. 공개 조회는 조회 간격과 SDK 응답만큼 지연될 수 있고, 앱 미실행 중의 모든 변화를 복원하지 않는다. 주행 상태는 기어·여정 판정에 따른 것이며 속도를 별도로 실측하지 않았다. 단말 시간대는 `-07:00` 그대로 사용했고 시각은 UTC로 저장한다. 시간대·시계를 변경하지 않았다. 전경 밖에서 전송하지 않으며 실차·운영 서버·음성 출력 완료는 검증 범위 밖이다. lint 경고 8건은 의존성 버전 및 SDK native library의 16KB 정렬 항목이며, 현재 API 34 실행이 다른 기기의 16KB 페이지 호환성을 보장하지 않는다.

수정한 소스·설정·도구는 **24개**이며 [목록](edge-modified-files.txt)에 있다. `server/`, 루트 `docs/`, `research/`와 루트 Gradle 설정은 수정하지 않았다. 수정 전 src/tools/README는 `<임시 폴더>/pleos-K-f84167d275d34abfa557bbc4043289dd`에 보존했다. 빌드 산출물과 새 보고서·로그·캡처는 app 아래에 있고 원본 J 보고서는 보존했다.

별도 `testProfile`과 고유 임시 DB 폴더를 사용했다. 인증·연결 번호는 보고서·캡처에 남기지 않았다. SDK AAR·API 덤프를 저장소에 넣지 않았고 commit/push/게시/결제, 파일·폴더 삭제를 수행하지 않았다. Gradle workers 2와 emulator-5554 한 대를 유지했다. [종료 상태](edge-exit-state.json)에서 작업용 node 종료와 기본 저장소·Pleos 자동 선택 복귀를 확인했다. 임시 폴더는 지우지 않았다.

## 재실행

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat test assembleDebug assembleRelease lintDebug --console=plain
python -u app/tools/edge_integration.py
python -u app/tools/edge_post_checks.py
python -u app/tools/runtime_smoke.py --serial emulator-5554 --report-path app/docs/edge-runtime-smoke.json
python -u app/tools/verify_edge.py --backup '<임시 폴더>/pleos-K-f84167d275d34abfa557bbc4043289dd'
```

`edge-runtime-attempt1..7.log`, `edge-integration-attempt1..7.json`과 `edge-post-checks-attempt1.log`는 초기 검증 장치·캡처·입력 실패의 원본 기록으로 남겼다. Python 중계의 응답 처리 문제는 동일 서버 핸들러를 직접 쓰는 Node 검증 장치로 교체해 해결했고, 일시적인 뷰 교체 시 캡처는 안정된 XML을 다시 읽도록 했다. 최종 판단은 위 최종 빌드·결과 JSON·21장 캡처에 따른다.
