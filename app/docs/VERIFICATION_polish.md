# 작업 P — 시연 화면·연결 번호·다음 정차 재질문 검증

검증일: 2026-10-22. **필수 기준 (a)~(d)를 모두 통과했다.** 추가 지시의 다음 정차 재질문도 단위 테스트와 실제 Pleos 입력으로 확인했다. **재질문 상한은 미정, 현재 기본 1회**이며 `ARRIVAL_REASK_LIMIT` 한 곳에 둔다.

## expected / observed / passed

| 기준 | expected | observed | passed |
|---|---|---|---|
| (a) 빌드·테스트·린트 | `gradlew test assembleDebug assembleRelease lintDebug` 성공, 실패 0, 번호 재발급·재질문 로직 테스트 | 최종 소스 BUILD SUCCESSFUL; Debug 173 + Release 173 = **346건**, 실패·오류·건너뜀 각 0; 연결 번호·재질문·저장 복원 등 시나리오 순증 12개; lint 오류 0·경고 8·힌트 1 | **true** |
| (b) 화면·출시 UI | 필수 가로 화면 5장 이상, 출시 APK에서 서버 주소 편집 없음, 다음 정차 재질문 | **2560×1440 PNG 8장**과 XML; 실제 release 설치 후 설정 상·하단과 개발 인텐트 확인, 주소 편집·모의 메뉴 0건; 다음 정차 재질문 화면 포함 | **true** |
| (c) 모두 끄기 | 새 임시 DB·포트 8787에 직접 연결; 실제 UI 조작 → 서버 동의 off·shared-data 삭제·후속 도착 전송 시도 0 | 실제 PUT 200·DELETE 204·GET 동의 전체 false; 철회 이후 같은 앱 PID의 SDK 도착 판정 **1회**, 앱 이벤트 HTTP 시도 **0회**, 서버 이벤트 POST **0회** | **true** |
| (d) 문구·출시 권한 | 지정 문구 8종 0건, 출시 매니페스트에 PRIVATE_CAR_SAFETY 없음 | app/src·tools·docs 텍스트·로그·XML 검색 0건; 최종 release 병합 매니페스트에 비공개 안전 권한 없음 | **true** |

근거: [빌드 로그](polish-build-verification.log), [JUnit 집계](polish-test-summary.json), [종합 검증 12/12](polish-static-verification.json), [실제 서버·Pleos 검증 13/13](polish-integration.json), [출시 APK 검증 3/3](polish-release-ui.json). 출시 UI 검증에서는 unsigned APK에 로컬 debug 서명만 추가한 임시 복사본을 설치했다. 서명 전후 모든 비서명 ZIP 항목의 바이트가 같음을 확인했다. 화면 보안 플래그는 유지했으며 출시 화면은 [실제 UI XML](screens/polish/release-settings-1.xml)과 [개발 인텐트 무시 XML](screens/polish/release-debug-intent-ignored.xml)로 확인했다.

## 구현과 검증한 동작

- 도착 카드의 주 버튼은 `#132D28` 배경·`#A9E7CB` 글자로 크게 표시하고, 보조 버튼은 카드 색 배경과 테두리로 표시한다. 실제 PNG에서 두 색을 확인했다. 글자/버튼 및 버튼/카드 대비는 **10.448:1**, 실제 주 버튼 높이 104px·보조 76px로 기준 4.5:1을 넘는다.
- 정비사 표는 정비 용어와 `ENGINE` 같은 SDK 상수명을 유지한다. 의미가 확인되지 않은 우선순위는 표시하지 않고, SDK 횟수는 1 이상일 때만 `차량이 알린 횟수 2회` 같은 문장으로 표시한다. 원래 메타데이터는 로컬 기록에 보존한다. 실제 Console의 ENGINE 켜기/끄기 기록에서 원시 기본값이 숨겨졌음을 확인했다.
- 일반 설정에서 서버 주소·연결 적용 항목을 제거하고 debug의 `모의 → 개발 연결 설정`으로 옮겼다. release에서는 이 편집 UI가 생성되지 않으며 `screen=console`·`serverUrl` 개발 인텐트로도 열리지 않는다.
- `가족 연결 번호 보기`와 `새 번호 받기`는 매번 인증된 `POST /api/families/:id/pair-code`를 호출한다. 저장된 이전 번호는 표시하지 않는다. 새 번호와 서버 `expiresAt`은 메모리에서만 유지하고 앱이 전경을 벗어나면 폐기한다. 연결된 가족이 새로 조회되면 표시도 무효화한다.
- 남은 시간은 서버 만료 시각을 기준으로 매초 갱신하고, 남은 마지막 소수 초는 올림한다. 만료되면 숫자를 숨기고 `새 번호 받기`를 제공한다. 단위 테스트는 10분/임의 서버 기한·만료 경계·음수 방지·잘못된 응답·요청 실패 시 옛 번호 미사용을 확인한다. 실제 실행에서는 재발급 후 옛 번호 404, 새 번호 연결 201, 같은 번호 재사용 404, 남은 시간 변화와 번호 가리기를 확인했다. 유효한 숫자·인증 토큰은 로그·PNG·XML에 남기지 않았다.
- 기본 매번 물어보기에서 무응답으로 출발하면 현재 전송은 하지 않고 같은 목적지 한 건을 다음 정차에 다시 묻는다. 재질문에서 응답하면 이벤트는 정확히 1건이다. 재질문에도 응답 없이 출발하면 소식을 보내지 않고 조용히 마치며 이후 정차에도 다시 묻지 않는다. 이 두 흐름 모두 실제 기어 D→P·도어 열림으로 확인했다. 응답 전 전송은 0건이며 자동 전송 방식은 유지한다.
- 다음 정차 대기·남은 재질문 횟수는 기존 AES-GCM 상태의 추가 필드로 보관한다. 중복 출발/도착 콜백은 추가 질문을 소비하거나 중복 이벤트를 만들지 않는다. 프로세스 복원·동의 철회 취소도 단위 테스트로 확인했다. 이전의 무응답 즉시 종료 테스트 한 개는 새 사용자 결정의 재질문 기대값으로 교체했다.

## 실제 모두 끄기 로그

앱은 `http://10.0.2.2:8787`로 호스트의 `127.0.0.1:8787` 서버에 직접 연결했다. 서버 원본 `createApp`에 요청을 기록하는 수동 관찰 리스너만 붙였다. 프록시·오류 응답 주입·서버 소스 수정은 없다. 인증 헤더는 아래처럼 마스킹하고 동의 필드만 기록한다.

```text
2026-10-22T04:18:29.514Z
PUT /api/families/[id]/consent
Authorization: Bearer [masked]
request: shareDeparture=false, shareArrival=false, shareAlerts=false,
         shareLocation=false, shareRouteArrival=false, recipientCount=0
response: 200

2026-10-22T04:18:29.521Z
DELETE /api/families/[id]/shared-data
Authorization: Bearer [masked]
response: 204 [empty]

GET /api/families/[id]/consent
Authorization: Bearer [masked]
response: 200 {shareDeparture:false, shareArrival:false, shareAlerts:false,
               shareLocation:false, shareRouteArrival:false, recipientCount:0}

후속 실제 SDK 판정: journeyEvent=ARRIVED
arrivalOffer accepted=true allowed=false prompt=false deferred=false handled=2
후속 도착 앱 eventHttpAttempt: 0회
DELETE 이후 서버 POST /events: 0회
```

원본 안전 로그: [HTTP 요청·응답](polish-http.jsonl), [동의 응답·시나리오](polish-integration.json), [전송 시도 집계](polish-event-attempts.log), [철회 이후 epoch 시각 SDK 로그](polish-after-off-signal.log), [차량 상태 로그](polish-vehicle.log). 정상 전송의 양성 대조에서는 부모 확인 후 직접 연결한 서버에 이벤트가 1건 저장됐다. 철회 이후 SDK 로그는 같은 앱 PID와 DELETE~검증 종료 시간 범위로 한정했다.

## 최종 화면

| 화면 | PNG |
|---|---|
| 도착 확인 카드 | [01-arrival-confirm.png](screens/polish/01-arrival-confirm.png) |
| 정비사 표 | [02-mechanic.png](screens/polish/02-mechanic.png) |
| 일반 설정 | [03-settings.png](screens/polish/03-settings.png) |
| 번호 가리기·남은 시간 | [04-pairing-countdown.png](screens/polish/04-pairing-countdown.png) |
| 모의 메뉴의 개발 연결 설정 | [05-debug-menu.png](screens/polish/05-debug-menu.png) |
| 일반 설정의 가족 연결 카드 | [06-settings-family.png](screens/polish/06-settings-family.png) |
| 실제 모두 끄기 이후 | [07-all-off.png](screens/polish/07-all-off.png) |
| 다음 정차의 1회 재질문 | [08-arrival-reask.png](screens/polish/08-arrival-reask.png) |

위 8장은 모두 같은 최종 Debug APK로 촬영했다. PNG별 SHA-256은 `polish-integration.json`에 있다.

- Debug APK SHA-256: `4e701c672adabd7f4097409c8bf69fc9db1fd661e6962c3f023dd8d57bd59f34`
- unsigned Release APK SHA-256: `99300ea3e1197c5f6c5cf824d213746a386d52f6734a5fe39dbccac982229213`

## 범위·호환성·남은 한계

코드·테스트·도구·README 변경은 **17개**이며 [목록](polish-modified-files.txt)에 있다. 이 보고서와 `polish-*` 로그·JSON, `screens/polish/`는 새 검증 산출물이다. 본 작업에서 `server/`, 루트 `docs/`, `research/`와 루트 Gradle 파일을 수정하지 않았다. [수정 전 백업 경로](polish-backup-path.txt)에 원본과 초기 실패 1~3차 기록을 보존했다. `run-failure.*`는 초기 진단 자료이며 최종 8장에 포함하지 않는다. 화면은 실제로 표시되었지만 uiautomator가 왼쪽 차량 화면만 읽는 포커스 문제와 로그의 빈 줄 처리 오류가 있었다. 검증 도구에서 주행 입력 후 앱 Activity를 전면으로 가져오고 빈 줄을 제외하도록 수정한 뒤 최종 13/13이 통과했다.

기존 저장 상태는 그대로 읽고 새 재질문 필드는 기본값으로 채운다. 기존 handled 목적지·동의·큐를 임의 초기화하지 않는다. 새 연결 번호에는 v0.3.1 서버가 필요하며 이전 서버에서 실패하면 옛 번호로 대체하지 않고 재시도 안내를 표시한다. 별도 데이터 마이그레이션은 없다.

필수 항목 중 막힌 항목은 없다. 다만 재질문 상한의 최종 제품 결정은 미정이며 기본 1회를 적용했다. 실제 기기에서 10분 전체를 기다리는 만료 검사는 하지 않았고 만료 경계는 단위 테스트로 검증했다. 실행한 차량은 기존 Pleos 에뮬레이터 `emulator-5554` 한 대이며 실차·운영 서버·실제 길안내 완료·음성 출력 성공을 증명하지 않는다. release는 기존 기본값인 개발용 로컬 HTTP 주소 연결을 차단한다. 운영 HTTPS 주소 구성과 실제 운영 연결은 검증하지 않았다. 배포용 서명·게시·앱마켓 승인도 하지 않았다. 기존 lint 경고 8건과 힌트 1건은 남아 있으며 테스트 커버리지 비율은 측정하지 않았다.

[종료 상태](polish-exit-state.json)에서 작업용 Node PID 73768의 종료를 확인했고 포트 8787의 수신 서버가 없음을 확인했다. [출시 UI 결과](polish-release-ui.json)에 Debug APK 복귀 확인을 남겼다. 검증용 `testProfile`과 새 임시 DB를 사용하고 마지막에는 기본 저장소·Pleos 자동 선택으로 복귀했다. 기존 데이터를 초기화하거나 파일·폴더를 삭제하지 않았다. 임시 폴더는 보존했으며 SDK AAR·덤프를 저장소에 추가하지 않았다. commit·push·게시·결제는 수행하지 않았다.

## 재실행

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PYTHONIOENCODING='utf-8'
.\gradlew.bat test assembleDebug assembleRelease lintDebug --console=plain
python -u app/tools/polish_integration.py
python -u app/tools/polish_release_check.py
python -u app/tools/verify_polish.py
```

기존 Pleos 한 대가 켜져 있고 포트 8787이 비어 있어야 한다. 통합 검증은 실행 전 이전 결과를 새 임시 보관 폴더에 복사한다. 출시 UI 도구는 로컬 debug 서명 사본을 사용한 뒤 원래 Debug APK를 다시 설치하며 앱 데이터는 지우지 않는다.
