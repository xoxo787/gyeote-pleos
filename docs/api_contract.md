# Passer 데모 서버 API 계약 v0.4

차량 앱(`app/`)과 서버·자녀 웹(`server/`)이 함께 따르는 계약이다. 바꿀 때는 이 파일을 먼저 고친다.

## 원칙
- **부모 동의가 우선이다.** 서버도 동의 상태를 다시 확인한다. 동의하지 않은 종류의 이벤트는 저장하지 않고 `403 consent_required`로 응답한다(차량 앱도 보내기 전에 막는다).
- 기본 공유는 **이벤트**(출발했어요/도착했어요)뿐이다. 좌표는 `share_location=true`일 때만 받는다.
- 데모용 인증: 역할별 무작위 토큰(`Authorization: Bearer <token>`). 실제 비밀값은 저장하지 않는다. 운영 단계에서는 HTTPS/TLS1.2+와 도메인을 쓴다(06 §1.5).
- 시간은 ISO-8601 UTC 문자열, JSON은 UTF-8. 저장소는 JSON 파일 하나(`server/data/db.json`, git 무시).
- 차량 앱은 non-GAS 환경을 전제로 **폴링**한다(권장 주기 10초, 주행 중에는 30초).

## 데이터
```text
Family      { id, parentName, pairCode(6자리)|null, pairCodeExpiresAt: ISO|null, createdAt }   // v0.4: 연결 숫자는 10분 만료·1회용
Consent     { shareDeparture: bool, shareArrival: bool, shareAlerts: bool, shareLocation: bool, shareRouteArrival: bool,
              recipients: [childId], until: ISO|null, updatedAt }        // 모두 기본 false
Child       { id, familyId, name, token }
Event       { id, familyId, type: "departed"|"arrived"|"alert", at, summary, location?: {lat,lng} }
Destination { id, familyId, fromChildId, name, address, lat, lng, note, status: "pending"|"accepted"|"deferred"|"declined"|"arrived"|"closed", arrivedAt: ISO|null, createdAt, updatedAt }
Message     { id, familyId, fromChildId, mime: "audio/wav"|"audio/mp4", durationSec, createdAt, playedAt|null }
```

## 엔드포인트
| 메서드·경로 | 호출자 | 요청 | 응답 |
|---|---|---|---|
| `POST /api/families` | 차 | `{parentName}` | `201 {familyId, pairCode, parentToken}` |
| `POST /api/pair` | 자녀 | `{pairCode, name}` | `201 {childId, familyId, childToken, parentName}` / `404 invalid_code` / `429 too_many_attempts` (v0.4 연결 숫자 보안 참고) |
| `POST /api/families/:id/pair-code` | **차만** (v0.4 추가) | — | `201 {pairCode, expiresAt}` — 새 연결 숫자 재발급(이전 숫자는 즉시 무효). 자녀 토큰이면 `403` |
| `GET /api/families/:id/consent` | 차·자녀 | — | `200 Consent` |
| `PUT /api/families/:id/consent` | **차만** | `Consent`(부분 갱신 허용) | `200 Consent` (자녀 토큰이면 `403`) |
| `POST /api/families/:id/events` | 차 | `{type, at, summary, location?}` | `201 Event` / `403 consent_required` (location은 shareLocation=false면 버리고 저장) |
| `GET /api/families/:id/events?since=ISO` | 자녀 | — | `200 [Event]` (recipients에 없는 자녀는 `403`) |
| `POST /api/families/:id/destinations` | 자녀 | `{name, address, lat, lng, note}` | `201 Destination(status=pending)` |
| `GET /api/families/:id/destinations?status=pending` | 차·자녀 | — | `200 [Destination]` |
| `PATCH /api/destinations/:id` | **차만** | `{status: "accepted"|"deferred"|"declined"}` | `200 Destination` |
| `POST /api/families/:id/messages` | 자녀 | 본문 = 오디오 바이트, `Content-Type` 헤더, `?durationSec=` (최대 30초, 2MB) | `201 Message` |
| `GET /api/families/:id/messages?unplayed=1` | 차 | — | `200 [Message]` |
| `GET /api/messages/:id/audio` | 차 | — | 오디오 바이트 |
| `PATCH /api/messages/:id` | 차 | `{played: true}` | `200 Message` |
| `DELETE /api/families/:id/shared-data` | **차만** | — | `204` — 동의 철회 시 이벤트·위치 즉시 삭제(06 §1.5 의무) |
| `GET /api/families/:id/children` | **차만** (v0.2 추가) | — | `200 [{id, name}]` — 동의 화면에서 recipients를 고르기 위함, 토큰 미포함 |
| `GET /child/` | 브라우저 | — | 자녀용 웹(정적 파일) |

v0.2 추가 규칙(서버 구현 기준, 2026-10-22):
- `POST /api/pair` 응답에 `parentName`이 포함된다(자녀 화면 문구용).
- `recipients`가 비어 있거나 `until`이 지나면 이벤트는 `403 consent_required`, 자녀 조회는 `403 forbidden`. 받을 사람이 없는 소식은 저장하지 않는다.
| `GET /health` | 누구나 | — | `200 {ok:true}` |

## v0.3 변경 — '자녀가 보낸 길' 도착 소식 (사용자 결정 2026-10-22)
핵심 공유는 **자녀가 보낸 목적지에 도착했을 때 한 번**뿐이다. 평소 운행 소식(shareDeparture/shareArrival)은 하위 호환으로 남기되 앱 기본 화면에서는 숨긴다.
- `Consent`에 `shareRouteArrival: bool`(기본 false) 추가. 부모가 켠다. 부분 갱신 허용.
- `Destination.status`에 `"arrived"` 추가, 필드 `arrivedAt: ISO|null` 추가.
- `POST /api/families/:id/events`의 `type:"arrived"`에 선택 필드 `destinationId`.
  - `destinationId`가 있으면: 해당 목적지가 같은 가족이고 `status=accepted`여야 한다(아니면 `409 invalid_state`, 없으면 `404 not_found`). 동의는 `shareRouteArrival || shareArrival`이 true여야 한다(아니면 `403 consent_required`). 성공 시 서버가 그 목적지를 `status="arrived", arrivedAt=at`로 바꾼다. Event에도 `destinationId`를 저장한다.
  - `destinationId`가 없으면 기존대로 `shareArrival` 필요.
  - recipients·until 규칙은 그대로.
- 자녀 웹: 보낸 길 목록에 상태(기다리는 중/수락/도착)를 보여 주고, 도착 이벤트는 "아버지가 서울 중앙병원에 잘 도착하셨어요"처럼 목적지 이름을 넣어 표시한다. 호칭은 pair 때 입력값/parentName을 쓴다.
- 오류 코드에 `invalid_state` 추가.
- (v0.3.1, 메인 결정) `arrived` 상태는 차의 PATCH로 되돌릴 수 없다(`409 invalid_state`). 동의 철회(`DELETE shared-data`) 시 도착 사실도 공유 데이터로 보고, 해당 목적지를 `status="closed"`, `arrivedAt=null`로 바꾼다(자녀 웹은 '지난 길'로 표시, 도착 시각·문구 없음).
- 자녀 음성 메시지는 앱에서 숨긴 기능이므로 자녀 웹에서도 메뉴를 숨긴다(엔드포인트는 하위 호환으로 유지).
- 경고등 기록은 **차 안에만** 저장한다(서버 전송 없음). shareAlerts는 하위 호환으로 남기되 앱에서 숨긴다.

## v0.4 변경 — 연결 숫자(pairCode) 보안 (사용자 요청 2026-10-22, 보안 검토 지적 반영)
6자리 숫자는 후보가 100만 개뿐이라 무차별 대입에 약하므로, 짧게 살아 있고 한 번만 쓰이며 반복 실패를 막는 비밀로 다룬다.
- **10분 만료**: 숫자는 발급 후 10분(`pairCodeExpiresAt`) 지나면 무효. 가족 생성 때 첫 숫자가 발급된다.
- **1회용**: 연결에 성공하면 그 숫자는 즉시 무효가 된다(`pairCode=null`). 자녀를 더 연결하려면 차에서 새 숫자를 받는다.
- **재발급**: `POST /api/families/:id/pair-code`(차만) → `201 {pairCode, expiresAt}`. 이전 숫자는 무효. 토큰 없음 `401`, 자녀 토큰 `403`, 다른 가족 `404`.
- **숫자별 실패 제한**: 같은 숫자 문자열로 5번 실패하면 그 숫자는 더 쓸 수 없고(가족이 있으면 무효화) `429 too_many_attempts`. 없는 숫자도 똑같이 센다.
- **주소별 실패 제한**: 접속 주소(소켓 원격 주소; `X-Forwarded-For`는 믿지 않음)별로 10분 안에 연결 실패 10회면 그 주소의 `POST /api/pair`를 10분간 `429 too_many_attempts`(맞는 숫자도 거절). 이때 응답에 `reason:"address"`를 덧붙여 자녀 웹이 문구를 가른다(숫자별 429에는 없음). 이름 검증 실패(400)는 세지 않는다.
- 만료·무효·없는 숫자는 모두 `404 invalid_code`로 같게 응답해 숫자의 존재 여부를 알리지 않는다.
- 실패 횟수는 서버 메모리에만 있다(재시작 시 초기화). 리버스 프록시 뒤에서는 한 주소로 합산되고, 한 주소를 여럿이 공유하면 함께 막힐 수 있다. 이전 db의 옛 가족(만료 시각 없음)은 만료로 취급하므로 차에서 재발급한다.
- 자녀 웹 문구: `invalid_code` "숫자가 맞지 않거나 10분이 지났어요…", `too_many_attempts`(숫자) "여러 번 틀려서…새 숫자", `reason:"address"` "이 폰에서 여러 번 틀려서 잠시 멈췄어요. 10분 뒤에 다시 입력해 주세요."
- (v0.4.1, 메인 — 보안 검토 반영) **전체 실패 예산**: 서버 전체에서 10분 안에 연결 실패 200회면 `POST /api/pair`를 10분간 `429 too_many_attempts`(`reason:"global"`)로 막는다(여러 주소 분산 대입 방어). IPv6 주소는 /64 단위로 묶어 센다. 숫자별 실패 기록은 10분 뒤 만료되고 최대 1만 개로 제한한다. 숫자 발급 반복은 최대 50회 시도 후 `503 unavailable`.
- (v0.4.1, 메인) **자녀의 목적지 조회도 동의 문을 지난다**: `GET /destinations`를 자녀가 부를 때 그 자녀가 recipients에 없거나 동의 기간이 끝났으면, `arrived` 상태는 `accepted`로, `arrivedAt`은 `null`로 가려서 돌려준다(목록 자체는 자녀가 보낸 길이므로 보여 준다). 도착 사실은 동의한 받을 사람에게만 보인다.
- 아직 하지 않은 것(운영 단계 과제): 차의 새 자녀 승인 단계, 8자리 이상 영숫자 숫자, 실패 카운트 영구 저장, 프록시 뒤 실제 클라이언트 주소 처리.

오류 형식: `{ "error": "<code>", "message": "<한국어 설명>" }`(주소 차단 429만 `reason` 추가). 코드: `bad_request`, `unauthorized`, `forbidden`, `consent_required`, `not_found`, `too_large`, `invalid_state`(409), `too_many_attempts`(429), `invalid_code`(404, 연결 숫자).

## 문구 톤 (사용자 결정 2026-10-22)
- 차량 기능 설명: 실용 — "타이어 공기압이 낮을 수 있어요. 안전한 곳에 세운 뒤 확인해 주세요."
- 가족 연결: 감성 — 자녀 화면 "아버지가 잘 도착하셨어요", 차 안 "딸이 병원 가는 길을 보내왔어요. 안내를 시작할까요?"
- 금지어: 감시, 추적, 위치 확인, 실버, 노인, 지킴이
