'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createApp } = require('../src/app');

// 테스트 임시 DB는 시스템 Temp 대신 저장소 안 server/.tmp-test/ (git 제외)에 만든다.
const TEST_TMP = path.join(__dirname, '..', '.tmp-test');
fs.mkdirSync(TEST_TMP, { recursive: true });

let server; let base;
test.before(async () => {
  const dir = fs.mkdtempSync(path.join(TEST_TMP, 'gyeote-test-'));
  server = createApp({ dbPath: path.join(dir, 'db.json') });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.after(() => new Promise((r) => server.close(r)));

async function call(method, p, { token, json, body, headers } = {}) {
  const h = { ...(headers || {}) };
  if (token) h.Authorization = `Bearer ${token}`;
  let payload = body;
  if (json !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(json); }
  const res = await fetch(base + p, { method, headers: h, body: payload });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  return { status: res.status, data, res };
}

// 가족 1개 + 자녀 1명을 만들어 돌려준다. 동의는 아직 아무것도 켜지 않은 상태.
async function setup(parentName = '아버지') {
  const fam = (await call('POST', '/api/families', { json: { parentName } })).data;
  const kid = (await call('POST', '/api/pair', { json: { pairCode: fam.pairCode, name: '딸' } })).data;
  return { fam, kid, car: fam.parentToken, child: kid.childToken, fid: fam.familyId };
}
const consentAll = (kid, extra = {}) => ({ shareDeparture: true, shareArrival: true, shareAlerts: true, recipients: [kid.childId], ...extra });
const ev = (type, extra = {}) => ({ type, at: new Date().toISOString(), summary: '아버지가 잘 도착하셨어요', ...extra });

test('GET /health', async () => {
  const r = await call('GET', '/health');
  assert.equal(r.status, 200);
  assert.deepEqual(r.data, { ok: true });
});

test('CORS: 사전 요청 204, 응답 헤더 포함', async () => {
  const r = await call('OPTIONS', '/api/families');
  assert.equal(r.status, 204);
  assert.equal(r.res.headers.get('access-control-allow-origin'), '*');
  assert.match(r.res.headers.get('access-control-allow-headers'), /Authorization/);
  assert.match(r.res.headers.get('access-control-allow-methods'), /PUT/);
});

test('POST /api/families: 정상 201, 6자리 코드·토큰', async () => {
  const r = await call('POST', '/api/families', { json: { parentName: '어머니' } });
  assert.equal(r.status, 201);
  assert.match(r.data.pairCode, /^\d{6}$/);
  assert.ok(r.data.parentToken.length >= 20 && r.data.familyId);
});

test('POST /api/families: 이름 없음·잘못된 JSON은 400 bad_request', async () => {
  let r = await call('POST', '/api/families', { json: {} });
  assert.equal(r.status, 400); assert.equal(r.data.error, 'bad_request');
  r = await call('POST', '/api/families', { json: { parentName: 'x'.repeat(31) } });
  assert.equal(r.status, 400);
  r = await call('POST', '/api/families', { body: '{깨진', headers: { 'Content-Type': 'application/json' } });
  assert.equal(r.status, 400); assert.equal(r.data.error, 'bad_request');
});

test('POST /api/pair: 정상 201 / 틀린 코드 404 invalid_code / 이름 없음 400', async () => {
  const fam = (await call('POST', '/api/families', { json: { parentName: '아버지' } })).data;
  let r = await call('POST', '/api/pair', { json: { pairCode: fam.pairCode, name: '딸' } });
  assert.equal(r.status, 201);
  assert.ok(r.data.childId && r.data.childToken);
  assert.equal(r.data.familyId, fam.familyId);
  r = await call('POST', '/api/pair', { json: { pairCode: '000000x', name: '딸' } });
  assert.equal(r.status, 404); assert.equal(r.data.error, 'invalid_code');
  r = await call('POST', '/api/pair', { json: { pairCode: fam.pairCode, name: '' } });
  assert.equal(r.status, 400);
});

test('동의 조회: 기본값은 모두 false, 토큰 없으면 401, 다른 가족은 404', async () => {
  const a = await setup(); const b = await setup('어머니');
  let r = await call('GET', `/api/families/${a.fid}/consent`, { token: a.child });
  assert.equal(r.status, 200);
  assert.equal(r.data.shareDeparture, false); assert.equal(r.data.shareArrival, false);
  assert.equal(r.data.shareAlerts, false); assert.equal(r.data.shareLocation, false);
  assert.deepEqual(r.data.recipients, []); assert.equal(r.data.until, null);
  r = await call('GET', `/api/families/${a.fid}/consent`);
  assert.equal(r.status, 401); assert.equal(r.data.error, 'unauthorized');
  r = await call('GET', `/api/families/${a.fid}/consent`, { token: 'nope' });
  assert.equal(r.status, 401);
  r = await call('GET', `/api/families/${a.fid}/consent`, { token: b.car });
  assert.equal(r.status, 404);
});

test('PUT consent: 차는 200(부분 갱신), 자녀 토큰은 403', async () => {
  const s = await setup();
  let r = await call('PUT', `/api/families/${s.fid}/consent`, { token: s.child, json: { shareArrival: true } });
  assert.equal(r.status, 403); assert.equal(r.data.error, 'forbidden');
  r = await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { shareArrival: true, recipients: [s.kid.childId] } });
  assert.equal(r.status, 200);
  assert.equal(r.data.shareArrival, true); assert.equal(r.data.shareDeparture, false);
  r = await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { shareDeparture: true } });
  assert.equal(r.data.shareArrival, true, '부분 갱신이라 앞서 켠 값이 유지된다');
  assert.equal(r.data.shareDeparture, true);
  const stored = (await call('GET', `/api/families/${s.fid}/consent`, { token: s.child })).data;
  assert.equal(stored.shareDeparture, true);
});

test('PUT consent: 잘못된 값은 400', async () => {
  const s = await setup();
  const put = (json) => call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json });
  assert.equal((await put({ shareArrival: 'yes' })).status, 400);
  assert.equal((await put({ recipients: ['chd_nobody'] })).status, 400);
  assert.equal((await put({ until: 'tomorrow' })).status, 400);
  assert.equal((await put({ recipients: 'x' })).status, 400);
});

test('GET /children: 차만 볼 수 있고 토큰은 나오지 않는다', async () => {
  const s = await setup();
  let r = await call('GET', `/api/families/${s.fid}/children`, { token: s.car });
  assert.equal(r.status, 200);
  assert.deepEqual(r.data, [{ id: s.kid.childId, name: '딸' }]);
  r = await call('GET', `/api/families/${s.fid}/children`, { token: s.child });
  assert.equal(r.status, 403);
});

test('이벤트: 동의 없으면 403 consent_required, 저장도 안 된다', async () => {
  const s = await setup();
  let r = await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived') });
  assert.equal(r.status, 403); assert.equal(r.data.error, 'consent_required');
  // 도착만 허락하면 출발은 여전히 403
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { shareArrival: true, recipients: [s.kid.childId] } });
  r = await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('departed') });
  assert.equal(r.status, 403); assert.equal(r.data.error, 'consent_required');
  r = await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived') });
  assert.equal(r.status, 201);
  const list = (await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data;
  assert.equal(list.length, 1); assert.equal(list[0].type, 'arrived');
});

test('이벤트: 알릴 사람이 없거나 기간이 끝났으면 403 consent_required', async () => {
  const s = await setup();
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { shareArrival: true } });
  let r = await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived') });
  assert.equal(r.status, 403, '받는 사람이 없으면 저장하지 않는다');
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { recipients: [s.kid.childId], until: '2020-01-01T00:00:00Z' } });
  r = await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived') });
  assert.equal(r.status, 403); assert.equal(r.data.error, 'consent_required');
});

test('이벤트: 자녀 토큰으로 보내면 403, 입력 검증 400', async () => {
  const s = await setup();
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: consentAll(s.kid) });
  const url = `/api/families/${s.fid}/events`;
  assert.equal((await call('POST', url, { token: s.child, json: ev('arrived') })).status, 403);
  assert.equal((await call('POST', url, { token: s.car, json: ev('crashed') })).status, 400);
  assert.equal((await call('POST', url, { token: s.car, json: ev('arrived', { at: 'yesterday' }) })).status, 400);
  assert.equal((await call('POST', url, { token: s.car, json: ev('arrived', { summary: '' }) })).status, 400);
  assert.equal((await call('POST', url, { token: s.car, json: ev('arrived', { location: { lat: 999, lng: 0 } }) })).status, 400);
});

test('shareLocation=false면 좌표를 저장하지 않는다', async () => {
  const s = await setup();
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: consentAll(s.kid, { shareLocation: false }) });
  const r = await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived', { location: { lat: 37.5, lng: 127.0 } }) });
  assert.equal(r.status, 201);
  assert.equal(r.data.location, undefined);
  const list = (await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data;
  assert.equal(list[0].location, undefined);
  const raw = fs.readFileSync(server.db.file, 'utf8');
  assert.ok(!raw.includes('37.5'), 'db.json에도 좌표가 없다');
});

test('shareLocation=true면 좌표를 저장하고, 끄는 순간 지운다', async () => {
  const s = await setup();
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: consentAll(s.kid, { shareLocation: true }) });
  const r = await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived', { location: { lat: 37.5, lng: 127.0 } }) });
  assert.deepEqual(r.data.location, { lat: 37.5, lng: 127 });
  let list = (await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data;
  assert.deepEqual(list[0].location, { lat: 37.5, lng: 127 });
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { shareLocation: false } });
  list = (await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data;
  assert.equal(list[0].location, undefined);
  assert.ok(!fs.readFileSync(server.db.file, 'utf8').includes('"lat": 37.5'));
});

test('GET events: recipients에 없는 자녀는 403, since 필터, 차 토큰은 403', async () => {
  const s = await setup();
  // v0.3.1: 연결 숫자는 1회용이므로 두 번째 자녀는 새 숫자를 받아 연결한다.
  const code2 = (await call('POST', `/api/families/${s.fid}/pair-code`, { token: s.car })).data.pairCode;
  const other = (await call('POST', '/api/pair', { json: { pairCode: code2, name: '아들' } })).data;
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: consentAll(s.kid) });
  const t1 = '2026-10-22T09:00:00.000Z'; const t2 = '2026-10-22T10:00:00.000Z';
  await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('departed', { at: t1 }) });
  await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived', { at: t2 }) });
  let r = await call('GET', `/api/families/${s.fid}/events`, { token: other.childToken });
  assert.equal(r.status, 403); assert.equal(r.data.error, 'forbidden');
  r = await call('GET', `/api/families/${s.fid}/events?since=${t1}`, { token: s.child });
  assert.equal(r.status, 200); assert.equal(r.data.length, 1); assert.equal(r.data[0].at, t2);
  r = await call('GET', `/api/families/${s.fid}/events?since=bad`, { token: s.child });
  assert.equal(r.status, 400);
  r = await call('GET', `/api/families/${s.fid}/events`, { token: s.car });
  assert.equal(r.status, 403);
});

test('DELETE shared-data: 차만 204, 자녀 403, 삭제 후 이벤트 0건', async () => {
  const s = await setup();
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: consentAll(s.kid, { shareLocation: true }) });
  await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('departed', { location: { lat: 37.1, lng: 127.1 } }) });
  await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived') });
  assert.equal((await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data.length, 2);
  assert.equal((await call('DELETE', `/api/families/${s.fid}/shared-data`, { token: s.child })).status, 403);
  assert.equal((await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data.length, 2, '자녀의 삭제 시도는 효과 없음');
  const r = await call('DELETE', `/api/families/${s.fid}/shared-data`, { token: s.car });
  assert.equal(r.status, 204);
  assert.equal((await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data.length, 0);
  assert.ok(!fs.readFileSync(server.db.file, 'utf8').includes('37.1'), '좌표도 디스크에서 사라졌다');
});

test('목적지: 자녀가 보내면 201 pending, 입력 검증 400, 차 토큰은 403', async () => {
  const s = await setup();
  const url = `/api/families/${s.fid}/destinations`;
  const good = { name: '서울병원', address: '서울시 종로구', lat: 37.58, lng: 127.0, note: '진료 예약 10시' };
  let r = await call('POST', url, { token: s.child, json: good });
  assert.equal(r.status, 201);
  assert.equal(r.data.status, 'pending'); assert.equal(r.data.fromChildId, s.kid.childId);
  assert.equal((await call('POST', url, { token: s.child, json: { ...good, name: '' } })).status, 400);
  assert.equal((await call('POST', url, { token: s.child, json: { ...good, lat: '37' } })).status, 400);
  assert.equal((await call('POST', url, { token: s.child, json: { ...good, lng: 181 } })).status, 400);
  assert.equal((await call('POST', url, { token: s.car, json: good })).status, 403);
  assert.equal((await call('POST', url, { json: good })).status, 401);
});

test('목적지 조회·수락: status 필터, PATCH는 차만', async () => {
  const s = await setup();
  const url = `/api/families/${s.fid}/destinations`;
  const d1 = (await call('POST', url, { token: s.child, json: { name: 'A', address: '', lat: 37, lng: 127, note: '' } })).data;
  await call('POST', url, { token: s.child, json: { name: 'B', address: '', lat: 37, lng: 127, note: '' } });
  assert.equal((await call('GET', `${url}?status=pending`, { token: s.car })).data.length, 2);
  assert.equal((await call('GET', `${url}?status=nope`, { token: s.car })).status, 400);
  assert.equal((await call('PATCH', `/api/destinations/${d1.id}`, { token: s.child, json: { status: 'accepted' } })).status, 403);
  assert.equal((await call('PATCH', `/api/destinations/${d1.id}`, { token: s.car, json: { status: 'pending' } })).status, 400);
  const r = await call('PATCH', `/api/destinations/${d1.id}`, { token: s.car, json: { status: 'accepted' } });
  assert.equal(r.status, 200); assert.equal(r.data.status, 'accepted');
  assert.equal((await call('GET', `${url}?status=pending`, { token: s.child })).data.length, 1);
  assert.equal((await call('PATCH', '/api/destinations/dst_none', { token: s.car, json: { status: 'accepted' } })).status, 404);
  const x = await setup('이웃');
  assert.equal((await call('PATCH', `/api/destinations/${d1.id}`, { token: x.car, json: { status: 'declined' } })).status, 404, '다른 가족은 건드릴 수 없다');
});

const wav = (n) => Buffer.alloc(n, 1);

test('음성 메시지: 업로드 201 → 차가 목록·오디오·재생 표시', async () => {
  const s = await setup();
  const url = `/api/families/${s.fid}/messages`;
  const bytes = Buffer.from('RIFF....WAVEfmt demo-bytes');
  let r = await call('POST', `${url}?durationSec=3.5`, { token: s.child, body: bytes, headers: { 'Content-Type': 'audio/wav' } });
  assert.equal(r.status, 201);
  assert.equal(r.data.mime, 'audio/wav'); assert.equal(r.data.durationSec, 3.5); assert.equal(r.data.playedAt, null);
  const id = r.data.id;
  assert.equal((await call('GET', `${url}?unplayed=1`, { token: s.car })).data.length, 1);
  assert.equal((await call('GET', url, { token: s.child })).status, 403);
  const audio = await fetch(`${base}/api/messages/${id}/audio`, { headers: { Authorization: `Bearer ${s.car}` } });
  assert.equal(audio.status, 200);
  assert.equal(audio.headers.get('content-type'), 'audio/wav');
  assert.deepEqual(Buffer.from(await audio.arrayBuffer()), bytes);
  assert.equal((await call('GET', `/api/messages/${id}/audio`, { token: s.child })).status, 403);
  r = await call('PATCH', `/api/messages/${id}`, { token: s.car, json: { played: true } });
  assert.equal(r.status, 200); assert.ok(r.data.playedAt);
  assert.equal((await call('GET', `${url}?unplayed=1`, { token: s.car })).data.length, 0);
  assert.equal((await call('PATCH', `/api/messages/${id}`, { token: s.car, json: { played: false } })).status, 400);
});

test('음성 메시지: 2MB 초과는 413 too_large, 정확히 2MB는 통과', async () => {
  const s = await setup();
  const url = `/api/families/${s.fid}/messages?durationSec=10`;
  const h = { 'Content-Type': 'audio/wav' };
  const big = await call('POST', url, { token: s.child, body: wav(2 * 1024 * 1024 + 1), headers: h });
  assert.equal(big.status, 413); assert.equal(big.data.error, 'too_large');
  const ok = await call('POST', url, { token: s.child, body: wav(2 * 1024 * 1024), headers: h });
  assert.equal(ok.status, 201);
});

test('음성 메시지: 형식·길이·빈 본문 검증', async () => {
  const s = await setup();
  const base0 = `/api/families/${s.fid}/messages`;
  const wavH = { 'Content-Type': 'audio/wav' };
  assert.equal((await call('POST', `${base0}?durationSec=5`, { token: s.child, body: wav(10), headers: { 'Content-Type': 'text/plain' } })).status, 400);
  assert.equal((await call('POST', `${base0}?durationSec=31`, { token: s.child, body: wav(10), headers: wavH })).status, 400);
  assert.equal((await call('POST', `${base0}?durationSec=30`, { token: s.child, body: wav(10), headers: wavH })).status, 201);
  assert.equal((await call('POST', `${base0}`, { token: s.child, body: wav(10), headers: wavH })).status, 400);
  assert.equal((await call('POST', `${base0}?durationSec=5`, { token: s.child, body: wav(0), headers: wavH })).status, 400);
  assert.equal((await call('POST', `${base0}?durationSec=5`, { token: s.car, body: wav(10), headers: wavH })).status, 403);
  assert.equal((await call('POST', `${base0}?durationSec=5`, { token: s.child, body: wav(10), headers: { 'Content-Type': 'audio/mp4; codecs=mp4a' } })).status, 201);
});

test('자녀용 웹: /child/ 정적 파일과 경로 이탈 차단', async () => {
  let r = await call('GET', '/child/');
  assert.equal(r.status, 200);
  assert.match(r.res.headers.get('content-type'), /text\/html/);
  r = await call('GET', '/child/..%2f..%2fsrc%2fapp.js');
  assert.equal(r.status, 404);
  r = await call('GET', '/child/%2e%2e/src/app.js');
  assert.equal(r.status, 404);
  r = await call('GET', '/없는경로');
  assert.equal(r.status, 404); assert.equal(r.data.error, 'not_found');
});

// ---- v0.3: 자녀가 보낸 길의 도착 ---------------------------------------------
async function sendRoute(s, name = '서울중앙병원') {
  return (await call('POST', `/api/families/${s.fid}/destinations`, {
    token: s.child, json: { name, address: '서울', lat: 37.5, lng: 127, note: '' },
  })).data;
}
const accept = (s, dst) => call('PATCH', `/api/destinations/${dst.id}`, { token: s.car, json: { status: 'accepted' } });
const arrive = (s, extra = {}) => call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('arrived', extra) });
const allow = (s, json) => call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { recipients: [s.kid.childId], ...json } });

test('v0.3 동의: shareRouteArrival 기본 false, 부분 갱신, 자녀 토큰은 403', async () => {
  const s = await setup();
  assert.equal((await call('GET', `/api/families/${s.fid}/consent`, { token: s.child })).data.shareRouteArrival, false);
  assert.equal((await call('PUT', `/api/families/${s.fid}/consent`, { token: s.child, json: { shareRouteArrival: true } })).status, 403);
  assert.equal((await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { shareRouteArrival: 'on' } })).status, 400);
  const r = await allow(s, { shareRouteArrival: true });
  assert.equal(r.data.shareRouteArrival, true); assert.equal(r.data.shareArrival, false);
});

test('v0.3 도착: 동의가 꺼져 있으면 403 consent_required, 목적지는 그대로', async () => {
  const s = await setup();
  const dst = await sendRoute(s); await accept(s, dst);
  await allow(s, { shareDeparture: true });
  const r = await arrive(s, { destinationId: dst.id });
  assert.equal(r.status, 403); assert.equal(r.data.error, 'consent_required');
  const list = (await call('GET', `/api/families/${s.fid}/destinations`, { token: s.car })).data;
  assert.equal(list[0].status, 'accepted'); assert.equal(list[0].arrivedAt, null);
});

test('v0.3 도착: 다른 가족 목적지·없는 id는 404, 그 목적지는 바뀌지 않는다', async () => {
  const s = await setup(); const o = await setup('이웃');
  const theirs = await sendRoute(o); await accept(o, theirs);
  await allow(s, { shareRouteArrival: true });
  let r = await arrive(s, { destinationId: theirs.id });
  assert.equal(r.status, 404); assert.equal(r.data.error, 'not_found');
  r = await arrive(s, { destinationId: 'dst_none' });
  assert.equal(r.status, 404);
  const mine = (await call('GET', `/api/families/${o.fid}/destinations`, { token: o.car })).data;
  assert.equal(mine[0].status, 'accepted');
});

test('v0.3 도착: pending·declined·이미 arrived는 409 invalid_state', async () => {
  const s = await setup();
  await allow(s, { shareRouteArrival: true });
  const p = await sendRoute(s, 'A');
  let r = await arrive(s, { destinationId: p.id });
  assert.equal(r.status, 409); assert.equal(r.data.error, 'invalid_state');
  await call('PATCH', `/api/destinations/${p.id}`, { token: s.car, json: { status: 'declined' } });
  assert.equal((await arrive(s, { destinationId: p.id })).status, 409);
  const q = await sendRoute(s, 'B'); await accept(s, q);
  assert.equal((await arrive(s, { destinationId: q.id })).status, 201);
  assert.equal((await arrive(s, { destinationId: q.id })).status, 409, '두 번째 도착은 거절');
  assert.equal((await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data.length, 1, '거절된 시도는 저장되지 않는다');
});

test('v0.3 도착: 성공 시 목적지 arrived·arrivedAt, 이벤트에 destinationId, 자녀 조회에도 포함', async () => {
  const s = await setup();
  await allow(s, { shareRouteArrival: true });
  const dst = await sendRoute(s); await accept(s, dst);
  const at = '2026-10-22T10:30:00.000Z';
  const r = await arrive(s, { destinationId: dst.id, at });
  assert.equal(r.status, 201); assert.equal(r.data.destinationId, dst.id);
  const dests = (await call('GET', `/api/families/${s.fid}/destinations?status=arrived`, { token: s.child })).data;
  assert.equal(dests.length, 1); assert.equal(dests[0].status, 'arrived'); assert.equal(dests[0].arrivedAt, at);
  assert.equal((await call('GET', `/api/families/${s.fid}/destinations?status=accepted`, { token: s.child })).data.length, 0);
  const evs = (await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data;
  assert.equal(evs.length, 1); assert.equal(evs[0].destinationId, dst.id);
});

test('v0.3 도착: 옛 shareArrival만 켜도 destinationId 도착이 된다', async () => {
  const s = await setup();
  await allow(s, { shareArrival: true });
  const dst = await sendRoute(s); await accept(s, dst);
  assert.equal((await arrive(s, { destinationId: dst.id })).status, 201);
});

test('v0.3 도착: shareRouteArrival만 켜면 destinationId 없는 도착은 기존대로 403', async () => {
  const s = await setup();
  await allow(s, { shareRouteArrival: true });
  const r = await arrive(s);
  assert.equal(r.status, 403); assert.equal(r.data.error, 'consent_required');
  await allow(s, { shareArrival: true });
  assert.equal((await arrive(s)).status, 201);
  assert.equal((await call('GET', `/api/families/${s.fid}/events`, { token: s.child })).data[0].destinationId, undefined);
});

test('v0.3 도착: destinationId 형식 오류·다른 종류 이벤트는 400, 받는 사람 없으면 403', async () => {
  const s = await setup();
  const dst = await sendRoute(s); await accept(s, dst);
  await call('PUT', `/api/families/${s.fid}/consent`, { token: s.car, json: { shareRouteArrival: true, shareDeparture: true } });
  assert.equal((await arrive(s, { destinationId: dst.id })).status, 403, '받는 사람이 없으면 저장하지 않는다');
  await allow(s, {});
  assert.equal((await arrive(s, { destinationId: 123 })).status, 400);
  const dep = await call('POST', `/api/families/${s.fid}/events`, { token: s.car, json: ev('departed', { destinationId: dst.id }) });
  assert.equal(dep.status, 400);
});

test('v0.3 호환: v0.2 형식으로 저장된 가족도 shareRouteArrival=false, arrivedAt=null로 읽힌다', async () => {
  const s = await setup();
  const fam = server.db.data.families.find((f) => f.id === s.fid);
  delete fam.consent.shareRouteArrival;
  const dst = await sendRoute(s);
  delete server.db.data.destinations.find((x) => x.id === dst.id).arrivedAt;
  assert.equal((await call('GET', `/api/families/${s.fid}/consent`, { token: s.car })).data.shareRouteArrival, false);
  assert.equal((await call('GET', `/api/families/${s.fid}/destinations`, { token: s.car })).data[0].arrivedAt, null);
});

// ---- v0.3.1 ---------------------------------------------------------------------
test('v0.3.1 arrived 길은 차의 PATCH로 되돌릴 수 없다 (409 invalid_state)', async () => {
  const s = await setup();
  await allow(s, { shareRouteArrival: true });
  const dst = await sendRoute(s); await accept(s, dst);
  assert.equal((await arrive(s, { destinationId: dst.id })).status, 201);
  for (const status of ['accepted', 'deferred', 'declined']) {
    const r = await call('PATCH', `/api/destinations/${dst.id}`, { token: s.car, json: { status } });
    assert.equal(r.status, 409); assert.equal(r.data.error, 'invalid_state');
  }
  const after = (await call('GET', `/api/families/${s.fid}/destinations`, { token: s.car })).data[0];
  assert.equal(after.status, 'arrived'); assert.ok(after.arrivedAt);
});

test('v0.3.1 동의 철회: arrived 길은 closed·arrivedAt=null, 다른 상태·다른 가족은 그대로', async () => {
  const s = await setup(); const o = await setup('이웃');
  await allow(s, { shareRouteArrival: true }); await allow(o, { shareRouteArrival: true });
  const done = await sendRoute(s, 'A'); await accept(s, done); await arrive(s, { destinationId: done.id });
  const waiting = await sendRoute(s, 'B'); await accept(s, waiting);
  const theirs = await sendRoute(o, 'C'); await accept(o, theirs); await arrive(o, { destinationId: theirs.id });
  assert.equal((await call('DELETE', `/api/families/${s.fid}/shared-data`, { token: s.car })).status, 204);
  const mine = (await call('GET', `/api/families/${s.fid}/destinations`, { token: s.child })).data;
  const a = mine.find((x) => x.id === done.id);
  assert.equal(a.status, 'closed'); assert.equal(a.arrivedAt, null);
  assert.equal(mine.find((x) => x.id === waiting.id).status, 'accepted');
  assert.equal((await call('GET', `/api/families/${s.fid}/destinations?status=closed`, { token: s.car })).data.length, 1);
  const other = (await call('GET', `/api/families/${o.fid}/destinations`, { token: o.car })).data[0];
  assert.equal(other.status, 'arrived'); assert.ok(other.arrivedAt);
});

test('v0.3.1 자녀 웹: 목소리 메뉴 숨김, 지난 길 표시 코드 포함', async () => {
  const html = (await call('GET', '/child/')).data;
  assert.ok(!/data-tab="voice"/.test(html), '하단 탭에 목소리 버튼이 없어야 한다');
  assert.ok(!html.includes('>목소리<'));
  const js = (await call('GET', '/child/app.js')).data;
  assert.ok(js.includes("closed: '지난 길'"));
});

test('v0.3.1 closed(지난 길)도 차의 PATCH로 되돌릴 수 없다 (409 invalid_state)', async () => {
  const s = await setup();
  await allow(s, { shareRouteArrival: true });
  const dst = await sendRoute(s); await accept(s, dst);
  await arrive(s, { destinationId: dst.id });
  await call('DELETE', `/api/families/${s.fid}/shared-data`, { token: s.car });
  for (const status of ['accepted', 'deferred', 'declined']) {
    const r = await call('PATCH', `/api/destinations/${dst.id}`, { token: s.car, json: { status } });
    assert.equal(r.status, 409); assert.equal(r.data.error, 'invalid_state');
  }
  assert.equal((await call('GET', `/api/families/${s.fid}/destinations`, { token: s.car })).data[0].status, 'closed');
});
