'use strict';
// v0.3.1 보안: 연결 숫자(pairCode) 10분 만료 · 1회용 · 실패 5회 무효 · 재발급.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApp } = require('../src/app');

const TEN_MIN = 10 * 60 * 1000;

// 시계를 조작할 수 있는 서버를 띄워 fn(call, tick)을 실행하고 끝나면 닫는다.
async function withServer(fn, opts = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gyeote-pair-'));
  let t = Date.now();
  const srv = createApp({ dbPath: path.join(dir, 'db.json'), now: () => t, ...opts });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  const call = async (method, p, { token, json, ...extra } = {}) => {
    const h = { ...(extra.headers || {}) };
    if (token) h.Authorization = `Bearer ${token}`;
    if (json !== undefined) h['Content-Type'] = 'application/json';
    const res = await fetch(base + p, { method, headers: h, body: json === undefined ? undefined : JSON.stringify(json) });
    const text = await res.text();
    return { status: res.status, data: text ? JSON.parse(text) : null };
  };
  try { await fn(call, (ms) => { t += ms; }, () => t); } finally { await new Promise((r) => srv.close(r)); }
}
const mkFamily = async (call, parentName = '아버지') => (await call('POST', '/api/families', { json: { parentName } })).data;
const pair = (call, pairCode, name = '딸') => call('POST', '/api/pair', { json: { pairCode, name } });

test('v0.3.1 연결 숫자: 9분 59초는 연결되고 10분이 지나면 404 invalid_code (가족 생성 응답의 숫자도 만료)', () => withServer(async (call, tick) => {
  const a = await mkFamily(call);
  tick(TEN_MIN - 1000);
  assert.equal((await pair(call, a.pairCode)).status, 201);
  const b = await mkFamily(call, '어머니');
  tick(TEN_MIN + 1);
  const r = await pair(call, b.pairCode, '아들');
  assert.equal(r.status, 404); assert.equal(r.data.error, 'invalid_code');
}));

test('v0.3.1 연결 숫자: 1회용 — 연결 성공 뒤 같은 숫자는 404, 자녀는 1명만', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  assert.equal((await pair(call, fam.pairCode)).status, 201);
  const r = await pair(call, fam.pairCode, '아들');
  assert.equal(r.status, 404); assert.equal(r.data.error, 'invalid_code');
  const kids = (await call('GET', `/api/families/${fam.familyId}/children`, { token: fam.parentToken })).data;
  assert.equal(kids.length, 1);
}));

test('v0.3.1 연결 숫자: 같은 숫자로 5번 실패하면 5번째에 429 too_many_attempts, 그 뒤에도 429', () => withServer(async (call, tick) => {
  const fam = await mkFamily(call);
  tick(TEN_MIN + 1); // 만료된 숫자로 계속 시도
  for (let i = 1; i <= 4; i++) {
    const r = await pair(call, fam.pairCode);
    assert.equal(r.status, 404, `시도 ${i}`); assert.equal(r.data.error, 'invalid_code');
  }
  let r = await pair(call, fam.pairCode);
  assert.equal(r.status, 429); assert.equal(r.data.error, 'too_many_attempts');
  r = await pair(call, fam.pairCode);
  assert.equal(r.status, 429); assert.equal(r.data.reason, undefined); // 숫자별 제한은 reason 없음
}));

test('v0.3.1 연결 숫자: 없는 숫자도 같은 숫자로 5번 틀리면 429, 다른 숫자는 영향 없음', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const wrong = fam.pairCode === '123456' ? '654321' : '123456';
  for (let i = 0; i < 4; i++) assert.equal((await pair(call, wrong)).status, 404);
  const r = await pair(call, wrong);
  assert.equal(r.status, 429); assert.equal(r.data.error, 'too_many_attempts');
  assert.equal((await pair(call, fam.pairCode)).status, 201);
}));

test('v0.3.1 재발급: 차만 201 {pairCode, expiresAt}, 새 숫자로 연결되고 옛 숫자는 무효', () => withServer(async (call, tick, clock) => {
  const fam = await mkFamily(call);
  tick(60 * 1000);
  const r = await call('POST', `/api/families/${fam.familyId}/pair-code`, { token: fam.parentToken });
  assert.equal(r.status, 201);
  assert.match(r.data.pairCode, /^\d{6}$/);
  assert.deepEqual(Object.keys(r.data).sort(), ['expiresAt', 'pairCode']);
  assert.equal(Date.parse(r.data.expiresAt), clock() + TEN_MIN);
  if (r.data.pairCode !== fam.pairCode) {
    const old = await pair(call, fam.pairCode);
    assert.equal(old.status, 404); assert.equal(old.data.error, 'invalid_code');
  }
  assert.equal((await pair(call, r.data.pairCode)).status, 201);
}));

test('v0.3.1 재발급: 자녀 토큰 403 forbidden, 토큰 없음 401, 다른 가족 차 404', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const kid = (await pair(call, fam.pairCode)).data;
  const other = await mkFamily(call, '이모');
  const url = `/api/families/${fam.familyId}/pair-code`;
  let r = await call('POST', url, { token: kid.childToken });
  assert.equal(r.status, 403); assert.equal(r.data.error, 'forbidden');
  assert.equal((await call('POST', url)).status, 401);
  assert.equal((await call('POST', url, { token: other.parentToken })).status, 404);
  assert.equal((await call('POST', url, { token: fam.parentToken })).status, 201);
}));

test('v0.3.1 재발급: 만료 후·연결 후(1회용 소진)에도 새 숫자로 계속 연결할 수 있다', () => withServer(async (call, tick) => {
  const fam = await mkFamily(call);
  tick(TEN_MIN + 60 * 1000);
  const url = `/api/families/${fam.familyId}/pair-code`;
  const r1 = await call('POST', url, { token: fam.parentToken });
  assert.equal(r1.status, 201);
  assert.equal((await pair(call, r1.data.pairCode)).status, 201);
  const r2 = await call('POST', url, { token: fam.parentToken });
  assert.equal(r2.status, 201);
  assert.equal((await pair(call, r2.data.pairCode, '아들')).status, 201);
}));

test('v0.3.1 자녀 웹: 만료·무효·횟수 초과 한국어 안내 코드 포함', () => withServer(async () => {
  const js = fs.readFileSync(path.join(__dirname, '..', 'public', 'child', 'app.js'), 'utf8');
  assert.match(js, /too_many_attempts/);
  assert.match(js, /10분이 지났어요/);
  assert.match(js, /새 숫자를 받아 주세요/);
  assert.match(js, /reason === 'address'/);
  assert.match(js, /10분 뒤에 다시 입력해 주세요/);
}));

// ---- 접속 주소별 대입 방어: 10분 창 안에 실패 10회면 그 주소를 10분간 429 -------------------
// 시험용 주소 주입: 요청 헤더 x-test-addr 값을 '소켓 원격 주소'처럼 쓴다(실제 서버는 socket.remoteAddress).
const byHeader = { remoteAddress: (req) => req.headers['x-test-addr'] || 'default' };
const pairFrom = (call, addr, pairCode, name = '딸', extra = {}) =>
  call('POST', '/api/pair', { json: { pairCode, name }, headers: { 'x-test-addr': addr, ...extra } });
const wrongCodes = (n) => Array.from({ length: n }, (_, i) => String(100000 + i * 7)); // 서로 다른 틀린 숫자

test('대입 방어: 서로 다른 틀린 숫자 10회 뒤에는 맞는 숫자도 429, 10분 뒤 풀리고 다른 주소는 영향 없음', () => withServer(async (call, tick) => {
  const fam = await mkFamily(call);
  const bad = wrongCodes(10).filter((c) => c !== fam.pairCode);
  for (let i = 0; i < 9; i++) {
    const r = await pairFrom(call, 'A', bad[i]);
    assert.equal(r.status, 404, `시도 ${i + 1}`); assert.equal(r.data.error, 'invalid_code');
  }
  // 10번째 실패는 404, 이제부터 막힘
  assert.equal((await pairFrom(call, 'A', bad[9] || '999999')).status, 404);
  let r = await pairFrom(call, 'A', fam.pairCode);
  assert.equal(r.status, 429); assert.equal(r.data.error, 'too_many_attempts'); assert.equal(r.data.reason, 'address');
  // 다른 주소는 영향 없이 맞는 숫자로 연결된다
  const other = await pairFrom(call, 'B', fam.pairCode);
  assert.equal(other.status, 201);
  // 막힌 동안(9분 59초)은 계속 429
  const fam2 = await mkFamily(call, '어머니');
  tick(TEN_MIN - 1000);
  r = await pairFrom(call, 'A', fam2.pairCode);
  assert.equal(r.status, 429);
  // 10분이 지나면 풀린다(숫자 자체는 만료 전 새 숫자로 확인)
  tick(2000);
  const fam3 = await mkFamily(call, '이모');
  assert.equal((await pairFrom(call, 'A', fam3.pairCode)).status, 201);
}, byHeader));

test('대입 방어: 9번 실패한 주소는 아직 맞는 숫자로 연결되고, 성공해도 실패 횟수는 유지된다', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const bad = wrongCodes(12).filter((c) => c !== fam.pairCode);
  for (let i = 0; i < 9; i++) assert.equal((await pairFrom(call, 'A', bad[i])).status, 404);
  assert.equal((await pairFrom(call, 'A', fam.pairCode)).status, 201); // 성공해도 9회 유지
  assert.equal((await pairFrom(call, 'A', bad[9])).status, 404); // 10번째 실패
  const fam2 = await mkFamily(call, '어머니');
  assert.equal((await pairFrom(call, 'A', fam2.pairCode)).status, 429);
}, byHeader));

test('대입 방어: 실패는 10분 창 안에서만 합산된다(오래된 실패는 잊는다)', () => withServer(async (call, tick) => {
  const bad = wrongCodes(20);
  for (let i = 0; i < 9; i++) assert.equal((await pairFrom(call, 'A', bad[i])).status, 404);
  tick(TEN_MIN + 1);
  for (let i = 9; i < 18; i++) assert.equal((await pairFrom(call, 'A', bad[i])).status, 404, `창 밖 실패는 잊음 ${i}`);
  const fam = await mkFamily(call);
  assert.equal((await pairFrom(call, 'A', fam.pairCode)).status, 201);
}, byHeader));

test('대입 방어: X-Forwarded-For는 믿지 않는다(헤더를 바꿔도 같은 주소로 센다)', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const bad = wrongCodes(10).filter((c) => c !== fam.pairCode);
  for (let i = 0; i < 10; i++) {
    const r = await pairFrom(call, 'A', bad[i] || '999999', '딸', { 'x-forwarded-for': `10.0.0.${i}` });
    assert.equal(r.status, 404);
  }
  const r = await pairFrom(call, 'A', fam.pairCode, '딸', { 'x-forwarded-for': '203.0.113.9' });
  assert.equal(r.status, 429); assert.equal(r.data.error, 'too_many_attempts');
}, byHeader));

test('대입 방어: 기본 설정(소켓 주소)에서도 같은 주소의 10회 실패는 막힌다', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const bad = wrongCodes(10).filter((c) => c !== fam.pairCode);
  for (let i = 0; i < 10; i++) assert.equal((await pair(call, bad[i] || '999999')).status, 404);
  const r = await pair(call, fam.pairCode);
  assert.equal(r.status, 429); assert.equal(r.data.error, 'too_many_attempts');
}));

// ---- v0.4.1: 전체 실패 예산 · IPv6 /64 · 숫자별 기록 만료 · 발급 반복 상한 ---------------------
const ALL_BAD = wrongCodes(300);

test('v0.4.1 전체 예산: 서로 다른 주소 분산 200회 실패 뒤에는 맞는 숫자도 429 reason:global, 10분 뒤 풀린다', () => withServer(async (call, tick) => {
  const fam = await mkFamily(call);
  const bad = ALL_BAD.filter((c) => c !== fam.pairCode);
  for (let i = 0; i < 200; i++) {
    const r = await pairFrom(call, `10.0.${Math.floor(i / 5)}.1`, bad[i]); // 주소마다 5회씩(주소별 한도 10 미만)
    assert.equal(r.status, 404, `시도 ${i + 1}`);
  }
  const r = await pairFrom(call, '203.0.113.77', fam.pairCode); // 처음 보는 주소, 맞는 숫자
  assert.equal(r.status, 429); assert.equal(r.data.error, 'too_many_attempts'); assert.equal(r.data.reason, 'global');
  tick(TEN_MIN - 1000);
  assert.equal((await pairFrom(call, '203.0.113.78', fam.pairCode)).status, 429);
  tick(2000);
  const fresh = await mkFamily(call, '어머니'); // 처음 숫자는 10분이 지나 만료됐으므로 새 가족
  assert.equal((await pairFrom(call, '203.0.113.79', fresh.pairCode)).status, 201);
}, byHeader));

test('v0.4.1 전체 예산: 199회까지는 막지 않는다', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const bad = ALL_BAD.filter((c) => c !== fam.pairCode);
  for (let i = 0; i < 199; i++) assert.equal((await pairFrom(call, `10.1.${Math.floor(i / 5)}.1`, bad[i])).status, 404);
  assert.equal((await pairFrom(call, '203.0.113.5', fam.pairCode)).status, 201);
}, byHeader));

test('v0.4.1 IPv6: 같은 /64의 다른 주소는 실패를 합산하고 다른 /64는 따로 센다', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const bad = ALL_BAD.filter((c) => c !== fam.pairCode);
  for (let i = 0; i < 10; i++) {
    assert.equal((await pairFrom(call, `2001:db8:1:2:${i}::${i + 1}`, bad[i])).status, 404);
  }
  let r = await pairFrom(call, '2001:0db8:0001:0002:ffff:ffff:ffff:ffff', fam.pairCode); // 같은 /64, 표기만 다름
  assert.equal(r.status, 429); assert.equal(r.data.reason, 'address');
  r = await pairFrom(call, '2001:db8:1:3::1', fam.pairCode); // 다른 /64
  assert.equal(r.status, 201);
}, byHeader));

test('v0.4.1 IPv6: IPv4로 매핑된 주소는 IPv4와 같은 주소로 센다', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const bad = ALL_BAD.filter((c) => c !== fam.pairCode);
  for (let i = 0; i < 5; i++) assert.equal((await pairFrom(call, '::ffff:192.0.2.9', bad[i])).status, 404);
  for (let i = 5; i < 10; i++) assert.equal((await pairFrom(call, '192.0.2.9', bad[i])).status, 404);
  assert.equal((await pairFrom(call, '192.0.2.9', fam.pairCode)).status, 429);
}, byHeader));

test('v0.4.1 숫자별 실패 기록: 마지막 실패 10분 뒤 만료 — 4번 틀린 숫자는 10분 뒤 다시 처음부터 센다', () => withServer(async (call, tick) => {
  const wrong = '424242';
  for (let i = 0; i < 4; i++) assert.equal((await pair(call, wrong)).status, 404);
  tick(TEN_MIN - 1000);
  assert.equal((await pair(call, wrong)).status, 429); // 5번째, 아직 유효한 기록
  // 다른 숫자: 4번 틀리고 10분 지나면 기록이 사라져 다시 4번은 404
  const other = '535353';
  for (let i = 0; i < 4; i++) assert.equal((await pairFrom(call, 'X', other)).status, 404);
  tick(TEN_MIN + 1);
  for (let i = 0; i < 4; i++) assert.equal((await pairFrom(call, 'Y', other)).status, 404, `만료 뒤 다시 센다 ${i + 1}`);
  assert.equal((await pairFrom(call, 'Y', other)).status, 429);
}, byHeader));

test('v0.4.1 숫자 발급: 50번 안에 못 찾으면 503 unavailable (가족도 만들어지지 않음), 주입한 생성기로 재현', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gyeote-issue-'));
  let calls = 0; let mode = 'same';
  const srv = createApp({ dbPath: path.join(dir, 'db.json'), randomCode: () => { calls++; return mode === 'same' ? 111111 : 222222 + calls; } });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  const post = async (p, body, token) => {
    const res = await fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body || {}) });
    return { status: res.status, data: await res.json() };
  };
  try {
    const a = await post('/api/families', { parentName: '아버지' });
    assert.equal(a.status, 201); assert.equal(a.data.pairCode, '111111');
    calls = 0;
    const b = await post('/api/families', { parentName: '어머니' }); // 항상 111111 -> 충돌만 반복
    assert.equal(b.status, 503); assert.equal(b.data.error, 'unavailable');
    assert.equal(calls, 50, '정확히 50번 시도 후 포기');
    mode = 'varied';
    const c = await post('/api/families', { parentName: '이모' });
    assert.equal(c.status, 201); // 503 뒤에도 서버는 정상
    const saved = JSON.parse(fs.readFileSync(path.join(dir, 'db.json'), 'utf8'));
    assert.equal(saved.families.length, 2, '503이었던 가족은 저장되지 않음');
  } finally { await new Promise((r) => srv.close(r)); }
});

// ---- v0.4.1: 자녀의 목적지 조회도 동의 문을 지난다 ---------------------------------------------
test('v0.4.1 목적지 조회: 받을 사람에서 빠진 자녀·동의 만료 자녀에게는 arrived를 accepted/arrivedAt null로 가린다', () => withServer(async (call) => {
  const fam = await mkFamily(call);
  const kidA = (await pair(call, fam.pairCode, '딸')).data;
  const code2 = (await call('POST', `/api/families/${fam.familyId}/pair-code`, { token: fam.parentToken })).data.pairCode;
  const kidB = (await pair(call, code2, '아들')).data;
  const fid = fam.familyId; const car = fam.parentToken;
  const put = (json) => call('PUT', `/api/families/${fid}/consent`, { token: car, json });
  await put({ shareArrival: true, shareRouteArrival: true, recipients: [kidA.childId] });
  const dst = (await call('POST', `/api/families/${fid}/destinations`, { token: kidA.childToken, json: { name: '병원', address: '서울', lat: 37.5, lng: 127, note: '' } })).data;
  assert.equal((await call('PATCH', `/api/destinations/${dst.id}`, { token: car, json: { status: 'accepted' } })).status, 200);
  const arr = await call('POST', `/api/families/${fid}/events`, { token: car, json: { type: 'arrived', at: new Date().toISOString(), summary: '도착', destinationId: dst.id } });
  assert.equal(arr.status, 201);
  const list = async (token, q = '') => (await call('GET', `/api/families/${fid}/destinations${q}`, { token })).data;
  // 차와 받을 사람인 자녀는 그대로 보인다
  let x = (await list(car))[0]; assert.equal(x.status, 'arrived'); assert.ok(x.arrivedAt);
  x = (await list(kidA.childToken))[0]; assert.equal(x.status, 'arrived'); assert.ok(x.arrivedAt);
  // 받을 사람이 아닌 자녀: 목록은 보이되 도착은 가려진다
  const hidden = await list(kidB.childToken);
  assert.equal(hidden.length, 1); assert.equal(hidden[0].status, 'accepted'); assert.equal(hidden[0].arrivedAt, null); assert.equal(hidden[0].name, '병원');
  assert.equal((await list(kidB.childToken, '?status=arrived')).length, 0);
  assert.equal((await list(kidB.childToken, '?status=accepted')).length, 1);
  assert.equal((await list(car))[0].status, 'arrived'); // 저장된 값은 그대로
  // 동의 기간이 끝나면 받을 사람이던 자녀에게도 가려진다
  await put({ until: new Date(Date.now() - 60 * 1000).toISOString() });
  x = (await list(kidA.childToken))[0]; assert.equal(x.status, 'accepted'); assert.equal(x.arrivedAt, null);
  assert.equal((await list(car))[0].status, 'arrived');
  // 기간을 다시 열면 보인다
  await put({ until: null });
  assert.equal((await list(kidA.childToken))[0].status, 'arrived');
}));

test('v0.4.1 자녀 웹: 전체 예산(reason global) 한국어 안내 코드 포함', () => {
  const js = fs.readFileSync(path.join(__dirname, '..', 'public', 'child', 'app.js'), 'utf8');
  assert.match(js, /reason === 'global'/);
  assert.match(js, /너무 많/);
});
