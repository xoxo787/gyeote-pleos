#!/usr/bin/env node
'use strict';
// PT 리허설·영상 촬영용: 차 앱 역할을 흉내 내어 서버를 순서대로 호출한다.
//   가족 만들기 -> (자녀 연결) -> 동의(보낸 길 도착만) -> 자녀가 보낸 길 수락 -> destinationId로 도착 -> (음성 메시지 확인) -> (철회)
// 사용: node server/scripts/demo-car.js [--base URL] [--wait-child 초] [--pace 밀리초] [--location] [--withdraw]
//   --wait-child N : 진짜 자녀 웹이 연결될 때까지 N초 기다림. 없으면 데모 자녀를 스스로 연결(자동 모드).
//   --pace N       : 단계 사이 쉬는 시간(촬영용, 기본 0)
//   --location     : 현재 위치 공유까지 동의하고 좌표를 함께 보냄(기본은 도착 소식만)
//   --withdraw     : 마지막에 동의를 거두고(shared-data 삭제) 이벤트가 0건인지 확인
const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf(`--${name}`); return i === -1 ? def : (args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : true); };
const BASE = String(opt('base', process.env.BASE_URL || 'http://localhost:8787')).replace(/\/$/, '');
const WAIT_CHILD = Number(opt('wait-child', 0)) || 0;
const PACE = Number(opt('pace', 0)) || 0;
const WITH_LOCATION = args.includes('--location');
const WITHDRAW = args.includes('--withdraw');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let step = 0;
const say = (msg) => console.log(`[${String(++step).padStart(2, '0')}] ${msg}`);

async function http(method, p, { token, json } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (json !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + p, { method, headers, body: json === undefined ? undefined : JSON.stringify(json) });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  return { status: res.status, data };
}
function must(r, status, what) {
  if (r.status !== status) {
    throw new Error(`${what} 실패: 기대 ${status}, 실제 ${r.status} ${JSON.stringify(r.data)}`);
  }
  return r.data;
}

(async () => {
  console.log(`곁에 데모 차(흉내) -> ${BASE}\n`);
  must(await http('GET', '/health'), 200, '서버 상태');

  // 1. 가족 만들기 (부모 = 차)
  const fam = must(await http('POST', '/api/families', { json: { parentName: '아버지' } }), 201, '가족 만들기');
  const car = fam.parentToken; const fid = fam.familyId;
  say(`가족을 만들었어요. 자녀 연결 숫자: ${fam.pairCode}  (토큰은 화면에 찍지 않아요)`);
  await sleep(PACE);

  // 2. 자녀 연결
  let children; let kidToken = null;
  if (WAIT_CHILD > 0) {
    say(`자녀 웹(${BASE}/child/)에서 위 숫자를 입력해 주세요. 최대 ${WAIT_CHILD}초 기다려요...`);
    const until = Date.now() + WAIT_CHILD * 1000;
    do {
      children = must(await http('GET', `/api/families/${fid}/children`, { token: car }), 200, '자녀 목록');
      if (children.length) break;
      await sleep(1000);
    } while (Date.now() < until);
    if (!children.length) throw new Error('자녀가 연결되지 않았어요(시간 초과).');
    say(`자녀 '${children[0].name}' 님이 연결됐어요.`);
  } else {
    kidToken = must(await http('POST', '/api/pair', { json: { pairCode: fam.pairCode, name: '딸 지은' } }), 201, '데모 자녀 연결').childToken;
    children = must(await http('GET', `/api/families/${fid}/children`, { token: car }), 200, '자녀 목록');
    say(`데모 자녀 '${children[0].name}' 님을 연결했어요(자동 모드).`);
  }
  await sleep(PACE);

  // 3. 동의 (주차 중 부모가 직접 누르는 단계) — 핵심은 '보낸 길 도착' 하나뿐
  const consent = must(await http('PUT', `/api/families/${fid}/consent`, {
    token: car,
    json: { shareRouteArrival: true, shareDeparture: false, shareArrival: false, shareAlerts: false, shareLocation: WITH_LOCATION, recipients: children.map((c) => c.id), until: null },
  }), 200, '동의 저장');
  say(`부모님이 동의했어요: 보낸 길 도착=${consent.shareRouteArrival} 위치=${consent.shareLocation} 받는 사람=${consent.recipients.length}명`);
  await sleep(PACE);

  // 3-1. 동의 안 한 종류는 막힌다는 것을 보여 주는 두 줄
  const blocked = await http('POST', `/api/families/${fid}/events`, {
    token: car, json: { type: 'alert', at: new Date().toISOString(), summary: '타이어 바람이 부족할 수 있어요' },
  });
  if (blocked.status !== 403 || blocked.data.error !== 'consent_required') throw new Error(`동의 없는 경고 소식이 막히지 않았어요: ${blocked.status}`);
  say('동의하지 않은 소식(차 안내 요약)은 서버가 거절했어요: 403 consent_required');
  const plain = await http('POST', `/api/families/${fid}/events`, {
    token: car, json: { type: 'arrived', at: new Date().toISOString(), summary: '아버지가 도착하셨어요' },
  });
  if (plain.status !== 403 || plain.data.error !== 'consent_required') throw new Error(`길 없는 평소 도착이 막히지 않았어요: ${plain.status}`);
  say('보낸 길과 이어지지 않은 평소 도착 소식도 서버가 거절했어요: 403 consent_required');
  await sleep(PACE);

  // 4. 자녀가 길을 보낸다 (자동 모드면 데모 자녀가 하나 보낸다)
  const loc = (lat, lng) => (WITH_LOCATION ? { location: { lat, lng } } : {});
  const destsOf = async (status) => must(await http('GET', `/api/families/${fid}/destinations?status=${status}`, { token: car }), 200, `${status} 목적지`);
  let pending = await destsOf('pending');
  if (!pending.length && WAIT_CHILD === 0) {
    must(await http('POST', `/api/families/${fid}/destinations`, {
      token: kidToken,
      json: { name: '서울중앙병원', address: '서울 종로구 대학로 101', lat: 37.5796, lng: 126.999, note: '10시 진료예요. 천천히 오세요' },
    }), 201, '자녀의 목적지 전송');
    say("자동 모드: 자녀가 '서울중앙병원' 가는 길을 보냈어요.");
    pending = await destsOf('pending');
  } else if (!pending.length) {
    say('대기 중인 길이 없어요. 자녀 웹의 \'보낸 길\'에서 하나 보내 주세요(최대 60초 기다려요).');
    const until = Date.now() + 60000;
    while (!pending.length && Date.now() < until) { await sleep(1500); pending = await destsOf('pending'); }
  }
  if (!pending.length) throw new Error('받을 길이 없어요.');
  const dest = pending[0];
  say(`차 안 안내: "딸이 ${dest.name} 가는 길을 보내왔어요. 안내를 시작할까요?"`);
  await sleep(PACE);

  // 4-1. 아직 수락 전인 길로는 도착을 보낼 수 없다
  const early = await http('POST', `/api/families/${fid}/events`, {
    token: car, json: { type: 'arrived', at: new Date().toISOString(), summary: '너무 일찍 보낸 도착', destinationId: dest.id },
  });
  if (early.status !== 409 || early.data.error !== 'invalid_state') throw new Error(`수락 전 도착이 막히지 않았어요: ${early.status}`);
  say('아직 수락하지 않은 길의 도착은 서버가 거절했어요: 409 invalid_state');

  // 5. 부모가 "응"
  const acc = must(await http('PATCH', `/api/destinations/${dest.id}`, { token: car, json: { status: 'accepted' } }), 200, '목적지 수락');
  say(`부모님이 "응"이라고 하셨어요 -> 길 상태: ${acc.status}`);
  await sleep(PACE);

  // 6. 도착 — destinationId로 이어서 보낸다
  const arr = must(await http('POST', `/api/families/${fid}/events`, {
    token: car, json: { type: 'arrived', at: new Date().toISOString(), summary: `아버지가 ${dest.name}에 잘 도착하셨어요`, destinationId: dest.id, ...loc(37.5796, 126.999) },
  }), 201, '도착 소식');
  if (arr.destinationId !== dest.id) throw new Error('도착 소식에 destinationId가 저장되지 않았어요.');
  say(`도착 소식을 보냈어요: "${arr.summary}"`);
  const done = (await destsOf('arrived')).find((x) => x.id === dest.id);
  if (!done || done.status !== 'arrived' || !done.arrivedAt) throw new Error('길 상태가 arrived로 바뀌지 않았어요.');
  say(`길 상태가 바뀌었어요: ${done.status} (${done.arrivedAt})`);
  if (kidToken) {
    const seen = must(await http('GET', `/api/families/${fid}/events`, { token: kidToken }), 200, '자녀 소식 조회');
    if (!seen.some((e) => e.destinationId === dest.id)) throw new Error('자녀가 destinationId가 담긴 도착 소식을 받지 못했어요.');
    say('자녀 화면에서도 같은 길의 도착 소식이 보여요.');
  }
  const again = await http('POST', `/api/families/${fid}/events`, {
    token: car, json: { type: 'arrived', at: new Date().toISOString(), summary: '두 번째 도착', destinationId: dest.id },
  });
  if (again.status !== 409) throw new Error(`같은 길의 두 번째 도착이 막히지 않았어요: ${again.status}`);
  say('같은 길로 도착을 한 번 더 보내도 서버가 거절했어요: 409 invalid_state');
  await sleep(PACE);

  // 7. 자녀가 남긴 목소리
  const msgs = must(await http('GET', `/api/families/${fid}/messages?unplayed=1`, { token: car }), 200, '목소리 목록');
  say(msgs.length ? `자녀가 남긴 목소리 ${msgs.length}개가 있어요(정차 중 재생).` : '자녀가 남긴 목소리는 아직 없어요.');
  await sleep(PACE);

  // 7-1. 도착한 길은 차가 PATCH로 되돌릴 수 없다 (v0.3.1)
  const undo = await http('PATCH', `/api/destinations/${dest.id}`, { token: car, json: { status: 'accepted' } });
  if (undo.status !== 409 || undo.data.error !== 'invalid_state') throw new Error(`도착한 길의 되돌리기가 막히지 않았어요: ${undo.status}`);
  say('도착한 길을 수락 상태로 되돌리려 해도 서버가 거절했어요: 409 invalid_state');

  // 8. (선택) 철회
  if (WITHDRAW) {
    must(await http('DELETE', `/api/families/${fid}/shared-data`, { token: car }), 204, '동의 철회');
    const closed = (await destsOf('closed')).find((x) => x.id === dest.id);
    if (!closed || closed.arrivedAt !== null) throw new Error('철회 뒤 도착한 길이 closed(arrivedAt=null)로 바뀌지 않았어요.');
    say('도착한 길이 지난 길(closed)로 바뀌고 도착 시각이 지워졌어요.');
    if (kidToken) {
      const left = must(await http('GET', `/api/families/${fid}/events`, { token: kidToken }), 200, '철회 뒤 소식 조회');
      if (left.length !== 0) throw new Error(`철회 뒤에도 소식이 ${left.length}건 남았어요.`);
      say('동의를 거두고 전해진 소식을 지웠어요. 자녀 화면의 소식: 0건');
    } else {
      say('동의를 거두고 전해진 소식을 지웠어요. 자녀 웹은 다음 새로고침 때 비어 있어요.');
    }
  }

  console.log(`\n완료. 자녀 화면: ${BASE}/child/  (연결 숫자 ${fam.pairCode})`);
})().catch((e) => { console.error(`\n데모 실패: ${e.message}`); process.exit(1); });
