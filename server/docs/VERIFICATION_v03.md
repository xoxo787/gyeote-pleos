# 서버·자녀 웹 v0.3 검증 (2026-10-22, 작업 L)

| 항목 | expected | observed | passed |
|---|---|---|---|
| (a) `node --test server/test` | 기존 + v0.3 테스트 전부 통과 | 31개 중 31개 통과 (기존 22 + v0.3 9), 실패 0 | true |
| (a) 동의 off → 403 | `403 consent_required`, 목적지 상태 불변 | 테스트 통과 | true |
| (a) 다른 가족 목적지 → 404 | `404 not_found`, 상대 목적지 불변 | 테스트 통과 | true |
| (a) pending/declined/이미 arrived → 409 | `409 invalid_state`, 이벤트 미저장 | 테스트 통과 | true |
| (a) 성공 시 상태 | 목적지 `arrived`·`arrivedAt=at`, 이벤트에 `destinationId` | 테스트 통과 | true |
| (a) destinationId 없는 도착 | 기존대로 `shareArrival` 필요 (`shareRouteArrival`만으로는 403) | 테스트 통과 | true |
| (a) 자녀 조회 | `GET events`에 `destinationId` 포함 | 테스트 통과 | true |
| (a) v0.2 저장본 호환 | `shareRouteArrival=false`, `arrivedAt=null`로 읽힘 | 테스트 통과 | true |
| (b) `demo-car.js --base http://localhost:8790 --withdraw --location` | 끝까지 성공(exit 0), 새 임시 폴더 `DB_PATH` | 15단계 모두 통과, exit 0 (409/403 거절 단계 포함) | true |
| (c) 폰 크기(390×1240) 스크린샷 3장 | `server/docs/screens/v03/` | `01-send-route.png`, `02-sent-accepted.png`, `03-arrived.png` | true |
| (d) 외부 의존성 0 | `require`는 `node:` 내장·내부 파일만, package.json에 dependencies 없음 | 확인 | true |
| (d) 금지어 0 | 감시·추적·위치 확인·실버·노인·지킴이 | `server/`(data 제외) 검색 결과 0건 | true |

## 확인하지 못했거나 판단이 필요한 것
- 자녀 웹 도착 문구는 브라우저로 직접 확인(03 스크린샷). 도착 문구의 호칭은 `parentName` + 이/가 조사 자동 처리(코드: `iga`).
- 좌표 입력: 지오코딩 없이 외부 의존 0을 지키려고 예시 장소 선택 또는 '좌표 직접 입력' 접이식 칸을 둠(길 보내기 서버 계약이 lat/lng 필수).
- (해결됨, 아래 v0.3.1) 이전에 열려 있던 ① arrived PATCH 되돌리기 ② 동의 철회 시 도착 상태 처리는 v0.3.1에서 결정·구현됨.
- 시험 서버는 포트 8790에서 실행 후 종료함. 기존 `server/data/db.json`은 건드리지 않음.

# v0.3.1 후속 검증 (2026-10-22, 작업 L2)

| 항목 | expected | observed | passed |
|---|---|---|---|
| `node --test server/test` | 기존 31 + v0.3.1 3 전부 통과 | 34개 중 34개 통과, 실패 0 | true |
| (1) arrived PATCH 되돌리기 | accepted/deferred/declined 모두 `409 invalid_state`, 목적지는 arrived·arrivedAt 유지 | 테스트 통과 | true |
| (2) 동의 철회 | 내 가족 arrived 길 → `closed`, `arrivedAt=null`; 다른 상태(accepted)·다른 가족 arrived는 불변; `?status=closed` 조회 가능 | 테스트 통과 | true |
| (3) 자녀 웹 목소리 메뉴 | 하단 탭에 '목소리' 없음, '지난 길' 라벨 코드 포함 | 테스트 통과, 브라우저 탭에 '보낸 길'만 표시 | true |
| `demo-car.js --base http://localhost:8790 --withdraw --location` (새 임시 `DB_PATH`) | exit 0, 되돌리기 409·철회 뒤 closed 확인 포함 | 17단계 모두 통과, exit 0 | true |
| 탭 겹침 수정 | 맨 아래까지 스크롤했을 때 마지막 글자(보낸 길 목록·갱신 시각)가 하단 탭 위에 있음 | 390×1240, 갱신 시각 하단 1115px < 탭 상단 1171px; 탭 배경 불투명 | true |
| 스크린샷 | `server/docs/screens/v03/`에 도착·지난 길 2장 | `04-arrived-tabs-fixed.png`(도착 문구·탭 겹침 없음), `05-past-route.png`('지난 길' 뱃지, 도착 문구·시각 없음) | true |
| 서버 종료 | 8790 서버 종료 | 종료 후 health 응답 없음 | true |

## 변경 파일 (server/ 안)
`src/app.js`(PATCH 409, 철회 시 closed, `closed` 필터 허용), `public/child/{index.html,app.js,style.css}`, `scripts/demo-car.js`, `test/api.test.js`, 이 문서, 스크린샷 2장.

## 한계
- 겹침은 스크롤 끝 기준으로 확인했다. 스크롤 중간에는 고정 탭이 글자를 덮는 것이 정상 동작이다.
- 스크린샷은 데스크톱 Chrome 390px 폭 에뮬레이션이며 실제 폰은 확인하지 못했다.
- (사용자 요청으로 추가) `closed` 길도 PATCH 시 `409 invalid_state`. 계약 문서(`docs/api_contract.md`)는 아직 arrived만 적혀 있어 갱신이 필요하다(server/ 밖이라 미수정).
- 목소리 엔드포인트·녹음 코드는 하위 호환으로 남겼다(메뉴만 숨김).

# v0.3.1 보안 검증: 연결 숫자 (2026-10-22, 작업 L3)

| 항목 | expected | observed | passed |
|---|---|---|---|
| `node --test server/test` | 기존 34 + 신규 8 전부 통과 | 42개 중 42개 통과, 실패 0 | true |
| 10분 만료 | 9분 59초는 `201`, 10분 1ms 뒤는 `404 invalid_code` (가족 생성 응답의 숫자 포함) | 테스트 통과(가짜 시계 주입) | true |
| 1회용 | 연결 성공 뒤 같은 숫자 `404 invalid_code`, 자녀 1명만 | 테스트 통과 | true |
| 실패 5회 | 같은 숫자 4번 `404`, 5번째 `429 too_many_attempts`, 그 뒤도 `429`; 없는 숫자도 동일; 다른 숫자는 영향 없음 | 테스트 통과 | true |
| 재발급 | 차만 `201 {pairCode, expiresAt}`(키 2개), 만료 약 10분, 옛 숫자 무효, 새 숫자로 연결 | 테스트 통과 | true |
| 자녀 토큰 재발급 | `403 forbidden` (토큰 없음 401, 다른 가족 차 404) | 테스트 통과 | true |
| 만료·소진 뒤 재발급 | 만료 후·연결 후에도 새 숫자로 연결 | 테스트 통과 | true |
| 자녀 웹 안내 | `invalid_code`: "숫자가 맞지 않거나 10분이 지났어요…", `too_many_attempts`: "여러 번 틀려서…" (한국어, 새 숫자 안내) | 코드 포함을 테스트로 확인. 브라우저 화면 직접 확인은 하지 않음 | true (코드), 화면 미확인 |
| `demo-car.js --base http://localhost:8790 --withdraw --location` (새 임시 `DB_PATH`) | exit 0 | 17단계 완료 | true |
| 서버 종료 | 8790 서버 종료 | taskkill 후 LISTEN 없음 | true |

## 변경 파일 (server/ 안)
`src/app.js`(만료·1회용·실패 카운트·`POST /api/families/:id/pair-code`, 테스트용 `now` 주입), `public/child/app.js`(오류 코드별 한국어 안내), `test/pair.test.js`(신규)·`test/index.js`, `test/api.test.js`(기존 테스트 1건: 두 번째 자녀는 재발급 숫자로 연결), 이 문서.

## 해석·한계
- '같은 숫자로 실패 5회'는 **제출된 숫자 문자열별**로 센다(없는 숫자 포함). 실패 횟수는 메모리에만 있어 서버 재시작 시 초기화된다. 서로 다른 숫자를 계속 찍어 보는 공격(IP별 제한)은 이번 범위 밖이다.
- 5번째 실패 때 해당 숫자를 가진 가족이 있으면 그 숫자를 무효화(null)한다. 이전 db.json의 옛 가족(만료 시각 없음)은 만료로 취급되므로 재발급이 필요하다.
- 가족 생성 응답 형식은 그대로(`expiresAt` 미추가). 계약 파일은 읽기만 했다.

# v0.3.1 보안 보완: 접속 주소별 대입 방어 (2026-10-22, 작업 L4)

L3의 '같은 숫자 5회'는 매번 다른 숫자를 넣는 대입을 막지 못해, `docs/api_contract.md`의 '대입 방어(메인 정정)'대로 주소별 제한을 추가했다.

| 항목 | expected | observed | passed |
|---|---|---|---|
| `node --test server/test` | 전부 통과 | 48개 중 48개 통과, 실패 0 (api 35 + pair 13; 문구 분리 후 재실행. 이전 실행은 47로 출력됐고 차이는 원인 미확인) | true |
| 서로 다른 틀린 숫자 10회 | 10회까지 `404 invalid_code`, 그 뒤 **맞는 숫자도** `429 too_many_attempts` | 테스트 통과 | true |
| 10분 뒤 해제 | 막힌 지 9분 59초는 계속 429, 10분 뒤 새 숫자로 `201` | 테스트 통과(가짜 시계) | true |
| 다른 주소 | 막힌 주소와 무관하게 `201` | 테스트 통과 | true |
| 성공 시 카운트 | 9회 실패 후 성공해도 횟수 유지 → 다음 실패 1번에 막힘 | 테스트 통과 | true |
| 10분 창 | 창 밖(10분 이상 전) 실패는 합산 안 함 | 테스트 통과 | true |
| X-Forwarded-For | 헤더를 바꿔도 같은 소켓 주소로 센다 | 테스트 통과 | true |
| 기본 설정(실제 소켓 주소) | 같은 주소 10회 실패 후 429 | 테스트 통과 | true |
| 기존 숫자별 5회 규칙 | 그대로 | 기존 테스트 통과 | true |
| `demo-car.js --base http://localhost:8790 --withdraw --location` (새 임시 `DB_PATH`) | exit 0 | 17단계 완료, exit 0 | true |
| 서버 종료 | 8790 서버 종료 | 처음 종료 시도(bash PID)는 실패해 PowerShell로 LISTEN 프로세스를 종료, 이후 health 응답 없음 | true |

## 구현 (server/src/app.js)
- 소켓 `req.socket.remoteAddress`만 사용. `createApp({ remoteAddress })`로 주소를, 기존 `now`로 시간을 주입(시험용).
- 메모리 `Map<주소, {fails: [시각], blockedUntil}>`. 실패(잘못된 형식·없는/만료 숫자·숫자별 5회 초과)마다 기록, 10분 창 안 10번째 실패 시 10분 차단. 차단 중 요청은 본문을 읽기 전에 429.
- 메모리 관리: `/api/pair` 호출마다 창 밖 항목·해제된 항목을 정리하고, 항목이 10,000개를 넘으면 오래된 것부터 버림.

## 한계
- 이름(`name`) 검증 실패(400)는 연결 실패로 세지 않는다. 서버를 다시 켜면 카운트 초기화(메모리).
- 리버스 프록시 뒤에서는 모든 요청이 프록시 주소로 보여 한 주소로 합산된다(X-Forwarded-For 불신 지시에 따른 결과). 단일 서버 데모 기준.
- (문구 분리 완료) 주소 차단 429는 `error`는 계약대로 `too_many_attempts`, 추가 필드 `reason:"address"`를 붙인다(숫자별 429에는 없음). 자녀 웹은 `reason`이 `address`면 "이 폰에서 여러 번 틀려서 잠시 멈췄어요. 10분 뒤에 다시 입력해 주세요."를 보여 준다. 코드 포함은 테스트로 확인했고 브라우저 화면은 확인하지 않았다. `reason`은 계약 밖 추가 필드라 api_contract.md 반영은 메인 결정 사항.
- IPv6/NAT로 여러 사용자가 한 주소를 공유하면 함께 막힐 수 있다.

| closed PATCH 차단 | accepted/deferred/declined 모두 `409 invalid_state`, 상태는 closed 유지 | 신규 테스트 통과, `node --test server/test` 전체 48개 중 48개 통과(그 사이 다른 작업의 변경으로 테스트 수가 늘어 있었음) | true |

---

# v0.4.1 보안 보완: 전체 실패 예산 · 숫자 기록 한도 · 목적지 조회 가림 (2026-10-22, 작업 S)

시작 전 `git diff HEAD -- server/`: 출력 없음(커밋 이후 server/ 변경 없음). 계약 파일(`docs/api_contract.md`)은 읽기만 했다.

| 항목 | expected | observed | passed |
|---|---|---|---|
| `node --test server/test` | 전부 통과 | 56개 중 56개 통과, 실패 0 (기존 48 + 신규 8) | true |
| 분산 주소 200회 실패 | 주소마다 5회씩 200회까지 `404`, 그 뒤 처음 보는 주소의 **맞는 숫자도** `429 too_many_attempts` + `reason:"global"` | 테스트 통과 | true |
| 전체 예산 해제 | 9분 59초는 계속 429, 10분 뒤 새 가족 숫자로 `201` | 테스트 통과(가짜 시계) | true |
| 199회 | 막지 않음(맞는 숫자 `201`) | 테스트 통과 | true |
| IPv6 /64 합산 | 같은 /64의 서로 다른 주소(표기만 달라도) 10회 합산 → `429 reason:"address"`, 다른 /64는 `201` | 테스트 통과 | true |
| IPv4 매핑 주소 | `::ffff:192.0.2.9`와 `192.0.2.9`를 같은 주소로 합산 | 테스트 통과 | true |
| pairFails 만료 | 4번 틀린 숫자는 마지막 실패 10분 뒤 기록이 사라져 다시 4번까지 `404`, 5번째 `429` | 테스트 통과 | true |
| pairFails 최대 1만 개 | 넘으면 오래된 것부터 버림 | 코드로 구현(`MAX_TRACKED_CODES`). **1만 개 초과 동작은 테스트하지 않음** | 미검증 |
| 발급 반복 상한 | 생성기를 항상 충돌하게 주입(`randomCode`) → 정확히 50번 시도 뒤 `503 unavailable`, 가족 미생성, 이후 정상 | 테스트 통과(시도 횟수 50 확인, db.json 가족 2개) | true |
| 받을 사람에서 빠진 자녀 | `GET destinations`에서 `arrived`→`accepted`, `arrivedAt`→`null`, 목록은 그대로, `?status=arrived`는 0건 | 테스트 통과 | true |
| 동의 만료 | 받을 사람이던 자녀에게도 가려지고, 차에는 계속 `arrived`, 기간 해제 시 다시 보임 | 테스트 통과 | true |
| 받을 사람인 자녀·차 | 그대로 `arrived` + `arrivedAt` | 테스트 통과 | true |
| 자녀 웹 문구 | `reason:"global"`이면 한국어 안내 | 코드 포함을 테스트로 확인. 브라우저 화면은 확인하지 않음 | true(코드 한정) |
| `demo-car.js --base http://localhost:8790 --withdraw --location` (새 임시 `DB_PATH`) | exit 0 | 17단계 완료, exit 0 | true |
| 서버 종료 | 8790 서버 종료 | LISTEN 프로세스 종료 후 health 응답 없음 | true |

## 구현
- `src/app.js`: 전체 실패 예산(`MAX_GLOBAL_FAILS=200`, 10분 창, 초과 시 10분간 `POST /api/pair` 전체 429 `reason:"global"`; 차단 검사는 주소 검사보다 먼저). 실패는 주소별 기록과 같은 지점에서 함께 센다.
- IPv6는 앞 4묶음(/64)으로 묶는 `addrKey`(`::` 축약·0 생략·대소문자 정규화, `::ffff:a.b.c.d`는 IPv4로).
- `pairFails`: `{n, at}`로 바꿔 마지막 실패 10분 뒤 만료, 호출마다 정리, 최대 1만 개.
- `issuePairCode`: 최대 50번 시도 후 `503 unavailable`. `createApp({ randomCode })` 주입 추가(시험용, 기본은 `crypto.randomInt`). 가족 생성은 숫자 발급이 성공한 뒤에 목록에 넣는다.
- `GET /api/families/:id/destinations`: 자녀 호출 시 recipients에 없거나 동의 기간이 끝났으면 `arrived`→`accepted`, `arrivedAt`→`null`(저장값은 불변).
- `public/child/app.js`: `reason:"global"` 한국어 안내 추가. `test/pair.test.js`: 신규 8건.

## 한계
- `consentActive`는 기존대로 실제 시계(`Date.now`)를 쓰므로 동의 만료 시험은 과거 `until`로 만들었다.
- 전체 예산은 서버 전체 공유라, 공격이 계속되면 정상 사용자도 10분 막힌다(계약 의도). 메모리에만 있어 재시작 시 초기화.
- `503` 문구와 `reason:"global"`은 계약에 `reason`이 이미 쓰이는 방식과 같게 추가했다. 503 응답 본문은 `{error:"unavailable", message}`.
