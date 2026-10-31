# Passer 데모 서버

`docs/api_contract.md` v0.1을 구현한 데모 서버 + 자녀용 웹. Node.js 내장 모듈만 쓴다(npm 설치 없음, Node 20 이상).

## 실행

```sh
node server/src/index.js          # http://localhost:8787  (PORT, HOST, DB_PATH, CORS_ORIGIN 환경 변수로 바꿈)
node --test server/test           # 계약 테스트 48개 (임시 폴더의 DB를 써서 실제 데이터를 건드리지 않음)
node server/scripts/demo-car.js   # 차 역할 흉내 (서버가 켜져 있어야 함)
```

- 저장: `server/data/db.json`(+ 음성 파일 `server/data/audio/`). `server/.gitignore`로 제외.
- 자녀용 웹: `http://localhost:8787/child/` (휴대폰 같은 와이파이에서는 `http://<PC 주소>:8787/child/`). 마이크 녹음은 브라우저 규칙상 `localhost` 또는 HTTPS에서만 동작한다.
- CORS는 로컬 데모용으로 기본 `*`. 운영 단계에서는 `CORS_ORIGIN`을 지정하고 HTTPS/TLS1.2+ 뒤에 둔다.

## 동의 규칙 (서버가 다시 확인)

| 상황 | 결과 |
|---|---|
| 동의하지 않은 종류(출발/도착/경고)의 이벤트 | `403 consent_required`, 저장 안 함 |
| `recipients`가 비어 있거나 `until`이 지남 | 이벤트 `403 consent_required` / 자녀 조회 `403 forbidden` |
| `shareLocation=false`인데 좌표를 보냄 | 이벤트는 저장하되 좌표는 버림. 나중에 `shareLocation`을 끄면 이미 저장된 좌표도 지움 |
| `PUT consent`, `PATCH destination`, `DELETE shared-data` | 차 토큰만. 자녀 토큰은 `403` |
| `DELETE shared-data` | 이벤트(와 좌표) 즉시 삭제 → 자녀 조회 0건 |
| 음성 | 2MB 초과 `413 too_large`, 30초 초과·형식 불일치 `400` |

## 엔드포인트 요약

토큰은 `Authorization: Bearer <token>`. 오류는 `{error, message}`.

| 경로 | 호출자 |
|---|---|
| `POST /api/families` → `{familyId, pairCode, parentToken}` | 누구나(차) |
| `POST /api/pair {pairCode, name}` → `{childId, familyId, childToken}` | 누구나(자녀) |
| `GET /api/families/:id/consent` | 차·자녀 |
| `PUT /api/families/:id/consent` | **차만** |
| `POST /api/families/:id/events` | 차 |
| `GET /api/families/:id/events?since=` | 자녀(recipients만) |
| `POST /api/families/:id/destinations` | 자녀 |
| `GET /api/families/:id/destinations?status=` | 차·자녀 |
| `PATCH /api/destinations/:id` | **차만** |
| `POST /api/families/:id/messages?durationSec=` (본문=오디오) | 자녀 |
| `GET /api/families/:id/messages?unplayed=1`, `GET /api/messages/:id/audio`, `PATCH /api/messages/:id` | 차 |
| `DELETE /api/families/:id/shared-data` | **차만** |
| `GET /child/`, `GET /health` | 누구나 |

### 계약에 덧붙인 것 (docs/api_contract.md에는 아직 없음 — 통합 시 반영 필요)
- `GET /api/families/:id/children` (차만): 연결된 자녀 `[{id,name}]`. 차가 `recipients`에 넣을 id를 알려면 필요하다. 토큰은 주지 않는다.
- `POST /api/pair` 응답에 `parentName` 추가(자녀 화면 문구용).
- 이벤트는 `recipients`가 비어 있으면 저장하지 않는다(받을 사람이 없는 소식을 쌓아 두면, 나중에 자녀를 추가했을 때 지난 소식이 보이기 때문).
- 자녀 웹은 브라우저 녹음(webm/mp4)을 16kHz 모노 WAV로 바꿔 `audio/wav`로 보낸다(계약 형식 유지, 30초 ≈ 0.96MB).

## 알려진 한계
- 연결 숫자(6자리) 대입 횟수 제한이 없다(데모용). 운영 전에 추가한다.
- 토큰이 `db.json`에 평문으로 저장된다(데모용). 운영에서는 해시·만료를 둔다.

## 데모 순서 (PT 리허설·촬영)

1. 서버 켜기: `node server/src/index.js`
2. **자동 모드**(혼자 리허설): `node server/scripts/demo-car.js`
   가족 생성 → 데모 자녀 연결 → 동의 → (동의 없는 경고는 403) → 출발 → 자녀가 보낸 목적지 수락 → 도착 → 음성 확인.
   `--withdraw`를 붙이면 마지막에 동의를 거두고 자녀 소식이 0건이 되는 것까지 보여 준다.
3. **실제 자녀 웹과 함께**(촬영): `node server/scripts/demo-car.js --wait-child 120 --pace 2000`
   터미널에 나온 6자리 숫자를 휴대폰(또는 폭 390px 브라우저)의 `/child/`에 입력 → 출발 소식이 타임라인에 뜸 → 스크립트가 기다리는 동안 `길 보내기`에서 예시 버튼으로 목적지 전송 → 스크립트가 수락 → 자녀 화면 배지가 `안내를 시작하셨어요`로 바뀜 → 도착 소식. `목소리` 탭에서 녹음해 보내면 `GET /messages?unplayed=1`에 나타난다.
   `--location`을 붙이면 현재 위치 공유 동의와 좌표 전송까지 보여 준다.
4. 시연 영상의 차 쪽 반응(TTS·길안내)은 앱(`app/`)의 몫이다. 이 스크립트는 서버 쪽 흐름만 흉내 낸다.

스크린샷(폭 390px): `server/docs/screens/`
