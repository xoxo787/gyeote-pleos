'use strict';
// 「곁에」 데모 서버 — docs/api_contract.md 구현. 내장 모듈만 사용한다.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { openDb } = require('./db');

const MAX_AUDIO_BYTES = 2 * 1024 * 1024;
const MAX_AUDIO_SECONDS = 30;
const MAX_JSON_BYTES = 64 * 1024;
const MAX_EVENTS_PER_FAMILY = 500;
const PAIR_CODE_TTL_MS = 10 * 60 * 1000; // v0.3.1: 연결 숫자는 10분 뒤 만료
const MAX_PAIR_FAILS = 5; // v0.3.1: 같은 숫자로 5번 실패하면 무효
const PAIR_ADDR_WINDOW_MS = 10 * 60 * 1000; // v0.3.1: 접속 주소별 실패를 세는 창
const MAX_PAIR_ADDR_FAILS = 10; // 창 안에 실패 10회면 그 주소를 PAIR_ADDR_BLOCK_MS 동안 막는다
const PAIR_ADDR_BLOCK_MS = 10 * 60 * 1000;
const MAX_TRACKED_ADDRS = 10000; // 메모리 상한(넘으면 가장 오래된 항목부터 버림)
const MAX_GLOBAL_FAILS = 200; // v0.4.1: 서버 전체에서 창 안에 실패 200회면 PAIR_ADDR_BLOCK_MS 동안 전체 차단
const MAX_TRACKED_CODES = 10000; // v0.4.1: 숫자별 실패 기록 상한
const MAX_ISSUE_TRIES = 50; // v0.4.1: 숫자 발급 반복 상한
const AUDIO_MIMES = ['audio/wav', 'audio/mp4'];
const EVENT_KIND = { departed: 'shareDeparture', arrived: 'shareArrival', alert: 'shareAlerts' };
const DEST_STATUS = ['pending', 'accepted', 'deferred', 'declined', 'arrived', 'closed'];
const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const MIME = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json; charset=utf-8',
};

class ApiError extends Error {
  constructor(status, code, message, extra) { super(message); this.status = status; this.code = code; this.extra = extra; }
}
const bad = (m) => new ApiError(400, 'bad_request', m);
const unauthorized = (m = '로그인 정보가 필요해요.') => new ApiError(401, 'unauthorized', m);
const forbidden = (m) => new ApiError(403, 'forbidden', m);
const invalidState = (m) => new ApiError(409, 'invalid_state', m);
const notFound = (m = '찾을 수 없어요.') => new ApiError(404, 'not_found', m);

const rid = (p) => `${p}_${crypto.randomBytes(6).toString('hex')}`;
const token = () => crypto.randomBytes(24).toString('base64url');
const nowIso = () => new Date().toISOString();

function isoOrThrow(v, field) {
  if (typeof v !== 'string' || !/^\d{4}-\d{2}-\d{2}T/.test(v) || Number.isNaN(Date.parse(v))) {
    throw bad(`${field}은(는) ISO-8601 시각이어야 해요.`);
  }
  return new Date(v).toISOString();
}
function str(v, field, min, max, { optional = false } = {}) {
  if (v === undefined || v === null) {
    if (optional) return '';
    throw bad(`${field}을(를) 입력해 주세요.`);
  }
  if (typeof v !== 'string') throw bad(`${field}은(는) 글자여야 해요.`);
  const t = v.trim();
  if (t.length < min || t.length > max) throw bad(`${field}은(는) ${min}~${max}자로 입력해 주세요.`);
  return t;
}
function num(v, field, lo, hi) {
  if (typeof v !== 'number' || !Number.isFinite(v) || v < lo || v > hi) {
    throw bad(`${field}은(는) ${lo}~${hi} 사이 숫자여야 해요.`);
  }
  return v;
}
function readLocation(v) {
  if (v === undefined || v === null) return null;
  if (typeof v !== 'object') throw bad('location 형식이 맞지 않아요.');
  return { lat: num(v.lat, 'lat', -90, 90), lng: num(v.lng, 'lng', -180, 180) };
}

function createApp({ dbPath = path.join(__dirname, '..', 'data', 'db.json'), corsOrigin = '*', now = Date.now, pairCodeTtlMs = PAIR_CODE_TTL_MS,
  randomCode = () => crypto.randomInt(0, 1000000), remoteAddress = (req) => req.socket.remoteAddress || 'unknown' } = {}) {
  const db = openDb(dbPath);
  const d = db.data;

  // ---- 조회 도우미 -------------------------------------------------------
  const family = (id) => d.families.find((f) => f.id === id);
  function defaultConsent(f) {
    return { shareDeparture: false, shareArrival: false, shareAlerts: false, shareLocation: false, shareRouteArrival: false,
      recipients: [], until: null, updatedAt: f.createdAt };
  }
  // v0.2에서 저장된 가족에는 shareRouteArrival이 없으므로 false로 채워서 쓴다.
  const consentOf = (f) => ({ shareRouteArrival: false, ...f.consent });
  const destOut = (x) => ({ ...x, arrivedAt: x.arrivedAt || null });
  function consentActive(c) { return !c.until || Date.parse(c.until) > Date.now(); }

  // 토큰 -> 호출자. 실패하면 401.
  function who(req) {
    const m = /^Bearer\s+(\S+)$/.exec(req.headers.authorization || '');
    if (!m) throw unauthorized();
    const t = m[1];
    const f = d.families.find((x) => x.parentToken === t);
    if (f) return { role: 'car', familyId: f.id };
    const c = d.children.find((x) => x.token === t);
    if (c) return { role: 'child', familyId: c.familyId, childId: c.id };
    throw unauthorized('토큰이 맞지 않아요.');
  }
  // 이 가족의 차·자녀만 통과. 다른 가족이면 존재 여부도 알리지 않도록 404.
  function inFamily(req, familyId, roles) {
    const me = who(req);
    const f = family(familyId);
    if (!f || me.familyId !== familyId) throw notFound('가족을 찾을 수 없어요.');
    if (roles && !roles.includes(me.role)) {
      throw forbidden(roles.length === 1 && roles[0] === 'car' ? '부모님 차에서만 바꿀 수 있어요.' : '이 화면에서는 할 수 없어요.');
    }
    return { me, f };
  }

  // ---- 본문 읽기 ---------------------------------------------------------
  function readBody(req, limit) {
    return new Promise((resolve, reject) => {
      const declared = Number(req.headers['content-length']);
      if (Number.isFinite(declared) && declared > limit) {
        req.resume();
        return reject(new ApiError(413, 'too_large', '파일이 너무 커요. 2MB 이하로 보내 주세요.'));
      }
      const chunks = []; let size = 0; let done = false;
      req.on('data', (c) => {
        if (done) return;
        size += c.length;
        if (size > limit) { done = true; chunks.length = 0; reject(new ApiError(413, 'too_large', '파일이 너무 커요.')); return; }
        chunks.push(c);
      });
      req.on('end', () => { if (!done) { done = true; resolve(Buffer.concat(chunks)); } });
      req.on('error', (e) => { if (!done) { done = true; reject(e); } });
    });
  }
  async function readJson(req) {
    const buf = await readBody(req, MAX_JSON_BYTES);
    if (buf.length === 0) return {};
    try {
      const v = JSON.parse(buf.toString('utf8'));
      if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error('not object');
      return v;
    } catch { throw bad('JSON 본문을 읽을 수 없어요.'); }
  }

  // ---- 응답 ---------------------------------------------------------------
  function send(res, status, body, headers = {}) {
    const isBuf = Buffer.isBuffer(body);
    const payload = body === undefined ? null : isBuf ? body : JSON.stringify(body);
    res.writeHead(status, {
      ...(payload === null ? {} : { 'Content-Type': isBuf ? 'application/octet-stream' : 'application/json; charset=utf-8' }),
      'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...headers,
    });
    res.end(payload);
  }

  function publicEvent(ev, c) {
    const out = { ...ev };
    if (!c.shareLocation) delete out.location;
    return out;
  }

  // ---- 라우트 -------------------------------------------------------------
  const routes = [];
  const route = (method, pattern, fn) => routes.push({ method, re: new RegExp(`^${pattern}$`), fn });
  const ID = '([A-Za-z0-9_]+)';
  const INVALID_CODE = '코드가 맞지 않아요. 부모님 차 화면의 6자리 숫자를 다시 확인해 주세요. 10분이 지났거나 이미 쓴 숫자일 수도 있어요.';

  route('GET', '/health', async () => ({ status: 200, body: { ok: true } }));

  // ---- 연결 숫자(v0.3.1): 10분 만료 · 1회용 · 같은 숫자 실패 5회면 무효 -----------------
  // 숫자별 실패 횟수는 메모리에만 둔다(서버를 다시 켜면 처음부터).
  // v0.4.1: 숫자 -> { n, at(마지막 실패 시각) }. 마지막 실패 10분 뒤 만료, 최대 MAX_TRACKED_CODES개.
  const pairFails = new Map();
  const failsOf = (code, t) => {
    const e = pairFails.get(code);
    if (!e) return 0;
    if (t - e.at >= PAIR_ADDR_WINDOW_MS) { pairFails.delete(code); return 0; }
    return e.n;
  };
  function bumpFails(code, t) {
    const n = failsOf(code, t) + 1;
    pairFails.delete(code); // 다시 넣어 Map 순서를 최신으로
    pairFails.set(code, { n, at: t });
    for (const k of pairFails.keys()) { if (pairFails.size <= MAX_TRACKED_CODES) break; pairFails.delete(k); }
    return n;
  }
  function prunePairFails(t) {
    for (const [k, e] of pairFails) { if (t - e.at >= PAIR_ADDR_WINDOW_MS) pairFails.delete(k); }
  }
  const tooMany = () => new ApiError(429, 'too_many_attempts', '여러 번 틀려서 이 숫자는 더 쓸 수 없어요. 부모님 차에서 새 숫자를 받아 주세요.');
  const pairCodeActive = (x) => typeof x.pairCode === 'string' && Date.parse(x.pairCodeExpiresAt) > now();
  // 접속 주소별 제한: 소켓 원격 주소만 믿는다(X-Forwarded-For 불신). 메모리에만 둔다.
  // addr -> { fails: [실패 시각...], blockedUntil }
  const pairAddrs = new Map();
  function prunePairAddrs(t) {
    for (const [a, e] of pairAddrs) {
      e.fails = e.fails.filter((x) => t - x < PAIR_ADDR_WINDOW_MS);
      if (e.fails.length === 0 && e.blockedUntil <= t) pairAddrs.delete(a);
    }
    // Map은 넣은 순서를 지키므로 상한을 넘으면 앞(오래된 것)부터 버린다.
    for (const a of pairAddrs.keys()) { if (pairAddrs.size <= MAX_TRACKED_ADDRS) break; pairAddrs.delete(a); }
  }
  // v0.4.1: IPv6는 /64(앞 4묶음) 단위로 묶고, IPv4로 매핑된 주소(::ffff:a.b.c.d)는 IPv4로 본다.
  function addrKey(a) {
    a = String(a).toLowerCase().replace(/%.*$/, '');
    const m4 = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/.exec(a);
    if (m4) return m4[1];
    if (!a.includes(':')) return a;
    const [head, tail] = a.split('::');
    const h = head ? head.split(':') : [];
    const tl = tail === undefined ? [] : tail ? tail.split(':') : [];
    const groups = tail === undefined ? h : [...h, ...Array(Math.max(0, 8 - h.length - tl.length)).fill('0'), ...tl];
    if (groups.length !== 8) return a;
    return `${groups.slice(0, 4).map((g) => g.replace(/^0+(?=.)/, '')).join(':')}::/64`;
  }
  // 서버 전체 실패 예산(여러 주소로 나눠 대입하는 공격 방어)
  let globalFails = [];
  let globalBlockedUntil = 0;
  const globalBlocked = (t) => globalBlockedUntil > t;
  function globalFail(t) {
    globalFails = globalFails.filter((x) => t - x < PAIR_ADDR_WINDOW_MS);
    globalFails.push(t);
    if (globalFails.length >= MAX_GLOBAL_FAILS) { globalBlockedUntil = t + PAIR_ADDR_BLOCK_MS; globalFails = []; }
  }
  const tooManyGlobal = () => new ApiError(429, 'too_many_attempts', '지금 연결 시도가 너무 많아요. 잠시 뒤(10분 뒤)에 다시 해 주세요.', { reason: 'global' });
  function addrBlocked(addr, t) {
    const e = pairAddrs.get(addr);
    return !!e && e.blockedUntil > t;
  }
  function addrFail(addr, t) {
    let e = pairAddrs.get(addr);
    if (!e) { e = { fails: [], blockedUntil: 0 }; pairAddrs.set(addr, e); }
    e.fails = e.fails.filter((x) => t - x < PAIR_ADDR_WINDOW_MS);
    e.fails.push(t);
    if (e.fails.length >= MAX_PAIR_ADDR_FAILS) { e.blockedUntil = t + PAIR_ADDR_BLOCK_MS; e.fails = []; }
  }
  // error 코드는 계약대로 too_many_attempts, 주소 차단은 reason:'address'를 덧붙여 자녀 웹이 문구를 가른다.
  const fail = (addr, t) => { addrFail(addr, t); globalFail(t); };
  const tooManyAddr = () => new ApiError(429, 'too_many_attempts', '이 기기에서 여러 번 틀려서 잠시 쓸 수 없어요. 10분 뒤에 다시 해 보세요.', { reason: 'address' });
  function issuePairCode(f) {
    const t = now();
    let code = null;
    for (let i = 0; i < MAX_ISSUE_TRIES && code === null; i++) {
      const c = String(randomCode()).padStart(6, '0');
      if (!d.families.some((x) => x.pairCode === c) && failsOf(c, t) < MAX_PAIR_FAILS) code = c;
    }
    if (code === null) throw new ApiError(503, 'unavailable', '지금은 새 숫자를 만들 수 없어요. 잠시 뒤에 다시 해 주세요.');
    f.pairCode = code;
    f.pairCodeExpiresAt = new Date(now() + pairCodeTtlMs).toISOString();
    pairFails.delete(code);
    return { pairCode: code, expiresAt: f.pairCodeExpiresAt };
  }

  route('POST', '/api/families', async (req) => {
    const b = await readJson(req);
    const parentName = str(b.parentName, 'parentName', 1, 30);
    const f = { id: rid('fam'), parentName, pairCode: null, pairCodeExpiresAt: null, createdAt: nowIso(), parentToken: token() };
    f.consent = defaultConsent(f);
    const { pairCode } = issuePairCode(f); // 실패(503)하면 가족을 만들지 않는다
    d.families.push(f);
    db.save();
    return { status: 201, body: { familyId: f.id, pairCode, parentToken: f.parentToken } };
  });

  route('POST', `/api/families/${ID}/pair-code`, async (req, m) => {
    const { f } = inFamily(req, m[1], ['car']);
    const out = issuePairCode(f);
    db.save();
    return { status: 201, body: out };
  });

  route('POST', '/api/pair', async (req) => {
    const t = now();
    const addr = addrKey(remoteAddress(req));
    prunePairAddrs(t);
    prunePairFails(t);
    if (globalBlocked(t)) { req.resume(); throw tooManyGlobal(); }
    if (addrBlocked(addr, t)) { req.resume(); throw tooManyAddr(); }
    const b = await readJson(req);
    const name = str(b.name, 'name', 1, 20);
    if (typeof b.pairCode !== 'string' || !/^\d{6}$/.test(b.pairCode)) {
      fail(addr, t);
      throw new ApiError(404, 'invalid_code', INVALID_CODE);
    }
    const code = b.pairCode;
    if (failsOf(code, t) >= MAX_PAIR_FAILS) { fail(addr, t); throw tooMany(); }
    const f = d.families.find((x) => x.pairCode === code);
    if (!f || !pairCodeActive(f)) {
      fail(addr, t);
      const n = bumpFails(code, t);
      if (n >= MAX_PAIR_FAILS) {
        // 5번째 실패: 이 숫자를 쓰던 가족이 있으면 숫자를 무효로 만들고 429를 돌려준다.
        if (f) { f.pairCode = null; f.pairCodeExpiresAt = null; db.save(); }
        throw tooMany();
      }
      throw new ApiError(404, 'invalid_code', INVALID_CODE);
    }
    const c = { id: rid('chd'), familyId: f.id, name, token: token() };
    d.children.push(c);
    f.pairCode = null; f.pairCodeExpiresAt = null; // 1회용
    pairFails.delete(code);
    db.save();
    // parentName은 계약에 없는 추가 필드(자녀 화면 문구용).
    return { status: 201, body: { childId: c.id, familyId: f.id, childToken: c.token, parentName: f.parentName } };
  });

  // 계약 밖 추가: 차가 연결된 자녀를 골라 recipients에 넣을 수 있도록 목록을 준다(토큰은 내보내지 않음).
  route('GET', `/api/families/${ID}/children`, async (req, m) => {
    inFamily(req, m[1], ['car']);
    return { status: 200, body: d.children.filter((c) => c.familyId === m[1]).map((c) => ({ id: c.id, name: c.name })) };
  });

  route('GET', `/api/families/${ID}/consent`, async (req, m) => {
    const { f } = inFamily(req, m[1]);
    return { status: 200, body: consentOf(f) };
  });

  route('PUT', `/api/families/${ID}/consent`, async (req, m) => {
    const { f } = inFamily(req, m[1], ['car']);
    const b = await readJson(req);
    const next = consentOf(f);
    for (const k of ['shareDeparture', 'shareArrival', 'shareAlerts', 'shareLocation', 'shareRouteArrival']) {
      if (b[k] !== undefined) {
        if (typeof b[k] !== 'boolean') throw bad(`${k}은(는) true 또는 false여야 해요.`);
        next[k] = b[k];
      }
    }
    if (b.recipients !== undefined) {
      if (!Array.isArray(b.recipients) || b.recipients.length > 20) throw bad('recipients는 자녀 id 목록이어야 해요.');
      for (const id of b.recipients) {
        if (!d.children.some((c) => c.id === id && c.familyId === f.id)) throw bad('연결되지 않은 자녀가 들어 있어요.');
      }
      next.recipients = [...new Set(b.recipients)];
    }
    if (b.until !== undefined) next.until = b.until === null ? null : isoOrThrow(b.until, 'until');
    next.updatedAt = nowIso();
    f.consent = next;
    // 현재 위치 공유를 끄면 이미 저장된 좌표도 바로 지운다.
    if (!next.shareLocation) {
      for (const e of d.events) if (e.familyId === f.id) delete e.location;
    }
    db.save();
    return { status: 200, body: next };
  });

  route('POST', `/api/families/${ID}/events`, async (req, m) => {
    const { f } = inFamily(req, m[1], ['car']);
    const b = await readJson(req);
    if (!Object.hasOwn(EVENT_KIND, b.type)) throw bad('type은 departed, arrived, alert 중 하나여야 해요.');
    const at = isoOrThrow(b.at, 'at');
    const summary = str(b.summary, 'summary', 1, 200);
    const loc = readLocation(b.location);
    const c = consentOf(f);
    // v0.3: 도착 소식에 destinationId가 있으면 '자녀가 보낸 길' 도착으로 본다.
    let dest = null;
    if (b.destinationId !== undefined && b.destinationId !== null) {
      if (b.type !== 'arrived') throw bad('destinationId는 도착(arrived) 소식에만 쓸 수 있어요.');
      if (typeof b.destinationId !== 'string') throw bad('destinationId는 글자여야 해요.');
      dest = d.destinations.find((x) => x.id === b.destinationId && x.familyId === f.id);
      if (!dest) throw notFound('목적지를 찾을 수 없어요.');
      if (dest.status !== 'accepted') throw invalidState('부모님이 받은 길이 아니거나 이미 도착한 길이에요.');
    }
    // 동의 안 된 종류, 알릴 사람이 없음, 기간이 끝남 -> 저장하지 않는다.
    const kindOn = dest ? (c.shareRouteArrival || c.shareArrival) : c[EVENT_KIND[b.type]];
    if (!kindOn || c.recipients.length === 0 || !consentActive(c)) {
      throw new ApiError(403, 'consent_required', '부모님이 이 소식을 나누기로 정하지 않았어요.');
    }
    const ev = { id: rid('evt'), familyId: f.id, type: b.type, at, summary };
    if (dest) ev.destinationId = dest.id;
    if (loc && c.shareLocation) ev.location = loc; // 위치 동의가 없으면 좌표는 버린다.
    d.events.push(ev);
    if (dest) { dest.status = 'arrived'; dest.arrivedAt = at; dest.updatedAt = nowIso(); }
    const mine = d.events.filter((e) => e.familyId === f.id);
    if (mine.length > MAX_EVENTS_PER_FAMILY) {
      const drop = new Set(mine.slice(0, mine.length - MAX_EVENTS_PER_FAMILY).map((e) => e.id));
      d.events = d.events.filter((e) => !drop.has(e.id));
    }
    db.save();
    return { status: 201, body: publicEvent(ev, c) };
  });

  route('GET', `/api/families/${ID}/events`, async (req, m, url) => {
    const { me, f } = inFamily(req, m[1], ['child']);
    const c = consentOf(f);
    if (!c.recipients.includes(me.childId)) throw forbidden('부모님이 아직 이 소식을 열어두지 않았어요.');
    if (!consentActive(c)) throw forbidden('부모님이 정한 기간이 끝났어요.');
    let list = d.events.filter((e) => e.familyId === f.id);
    const since = url.searchParams.get('since');
    if (since !== null) {
      const s = Date.parse(isoOrThrow(since, 'since'));
      list = list.filter((e) => Date.parse(e.at) > s);
    }
    list.sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
    return { status: 200, body: list.map((e) => publicEvent(e, c)) };
  });

  route('POST', `/api/families/${ID}/destinations`, async (req, m) => {
    const { me, f } = inFamily(req, m[1], ['child']);
    const b = await readJson(req);
    const t = nowIso();
    const dest = {
      id: rid('dst'), familyId: f.id, fromChildId: me.childId,
      name: str(b.name, 'name', 1, 60), address: str(b.address, 'address', 0, 120, { optional: true }),
      lat: num(b.lat, 'lat', -90, 90), lng: num(b.lng, 'lng', -180, 180),
      note: str(b.note, 'note', 0, 200, { optional: true }),
      status: 'pending', arrivedAt: null, createdAt: t, updatedAt: t,
    };
    d.destinations.push(dest);
    db.save();
    return { status: 201, body: dest };
  });

  route('GET', `/api/families/${ID}/destinations`, async (req, m, url) => {
    const { me, f } = inFamily(req, m[1]);
    const status = url.searchParams.get('status');
    if (status !== null && !DEST_STATUS.includes(status)) throw bad('status 값이 맞지 않아요.');
    // v0.4.1: 도착 사실은 동의한 받을 사람에게만. 아닌 자녀에게는 arrived를 accepted로, arrivedAt을 null로 가린다.
    const c = consentOf(f);
    const hideArrival = me.role === 'child' && !(c.recipients.includes(me.childId) && consentActive(c));
    const view = (x) => (hideArrival && x.status === 'arrived' ? { ...destOut(x), status: 'accepted', arrivedAt: null } : destOut(x));
    return { status: 200, body: d.destinations.filter((x) => x.familyId === f.id).map(view).filter((x) => status === null || x.status === status) };
  });

  route('PATCH', `/api/destinations/${ID}`, async (req, m) => {
    const me = who(req);
    const dest = d.destinations.find((x) => x.id === m[1]);
    if (!dest || dest.familyId !== me.familyId) throw notFound('목적지를 찾을 수 없어요.');
    if (me.role !== 'car') throw forbidden('부모님 차에서만 정할 수 있어요.');
    const b = await readJson(req);
    if (!['accepted', 'deferred', 'declined'].includes(b.status)) throw bad('status는 accepted, deferred, declined 중 하나여야 해요.');
    // v0.3.1: 이미 도착했거나 지난 길(closed)은 되돌릴 수 없다.
    if (dest.status === 'arrived' || dest.status === 'closed') throw invalidState('이미 끝난 길은 바꿀 수 없어요.');
    dest.status = b.status;
    dest.updatedAt = nowIso();
    db.save();
    return { status: 200, body: destOut(dest) };
  });

  route('POST', `/api/families/${ID}/messages`, async (req, m, url) => {
    const { me, f } = inFamily(req, m[1], ['child']);
    const mime = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
    if (!AUDIO_MIMES.includes(mime)) throw bad('오디오는 audio/wav 또는 audio/mp4만 보낼 수 있어요.');
    const dur = Number(url.searchParams.get('durationSec'));
    if (!Number.isFinite(dur) || dur <= 0 || dur > MAX_AUDIO_SECONDS) throw bad(`durationSec은 0 초과 ${MAX_AUDIO_SECONDS}초 이하여야 해요.`);
    const body = await readBody(req, MAX_AUDIO_BYTES);
    if (body.length === 0) throw bad('오디오가 비어 있어요.');
    const msg = { id: rid('msg'), familyId: f.id, fromChildId: me.childId, mime, durationSec: dur, createdAt: nowIso(), playedAt: null };
    fs.mkdirSync(db.audioDir, { recursive: true });
    fs.writeFileSync(path.join(db.audioDir, `${msg.id}.bin`), body);
    d.messages.push(msg);
    db.save();
    return { status: 201, body: msg };
  });

  route('GET', `/api/families/${ID}/messages`, async (req, m, url) => {
    const { f } = inFamily(req, m[1], ['car']);
    const unplayed = url.searchParams.get('unplayed') === '1';
    return { status: 200, body: d.messages.filter((x) => x.familyId === f.id && (!unplayed || x.playedAt === null)) };
  });

  function carMessage(req, id) {
    const me = who(req);
    const msg = d.messages.find((x) => x.id === id);
    if (!msg || msg.familyId !== me.familyId) throw notFound('음성 메시지를 찾을 수 없어요.');
    if (me.role !== 'car') throw forbidden('부모님 차에서만 들을 수 있어요.');
    return msg;
  }
  route('GET', `/api/messages/${ID}/audio`, async (req, m) => {
    const msg = carMessage(req, m[1]);
    let bytes;
    try { bytes = fs.readFileSync(path.join(db.audioDir, `${msg.id}.bin`)); } catch { throw notFound('음성 파일을 찾을 수 없어요.'); }
    return { status: 200, body: bytes, headers: { 'Content-Type': msg.mime } };
  });
  route('PATCH', `/api/messages/${ID}`, async (req, m) => {
    const msg = carMessage(req, m[1]);
    const b = await readJson(req);
    if (b.played !== true) throw bad('played: true만 보낼 수 있어요.');
    if (!msg.playedAt) msg.playedAt = nowIso();
    db.save();
    return { status: 200, body: msg };
  });

  // 동의 철회: 이벤트(와 안에 든 좌표)를 즉시 삭제한다. 도착 사실도 공유 데이터이므로
  // 도착한 길은 closed(지난 길)로 바꾸고 도착 시각을 지운다(v0.3.1).
  route('DELETE', `/api/families/${ID}/shared-data`, async (req, m) => {
    const { f } = inFamily(req, m[1], ['car']);
    d.events = d.events.filter((e) => e.familyId !== f.id);
    for (const x of d.destinations) {
      if (x.familyId === f.id && x.status === 'arrived') { x.status = 'closed'; x.arrivedAt = null; x.updatedAt = nowIso(); }
    }
    db.save();
    return { status: 204 };
  });

  // ---- 정적 파일(자녀용 웹) ------------------------------------------------
  function serveStatic(res, pathname) {
    if (pathname === '/') { res.writeHead(302, { Location: '/child/' }); res.end(); return true; }
    if (pathname === '/child') { res.writeHead(301, { Location: '/child/' }); res.end(); return true; }
    if (!pathname.startsWith('/child/')) return false;
    let rel;
    try { rel = decodeURIComponent(pathname); } catch { send(res, 400, { error: 'bad_request', message: '주소가 올바르지 않아요.' }); return true; }
    if (rel.endsWith('/')) rel += 'index.html';
    const file = path.resolve(PUBLIC_DIR, `.${rel}`);
    if (!file.startsWith(PUBLIC_DIR + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      send(res, 404, { error: 'not_found', message: '찾을 수 없어요.' }); return true;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'",
    });
    fs.createReadStream(file).pipe(res);
    return true;
  }

  // ---- 서버 ---------------------------------------------------------------
  const server = http.createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', corsOrigin);
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,PATCH,DELETE,OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    res.setHeader('Access-Control-Max-Age', '600');
    res.setHeader('Vary', 'Origin');
    try {
      if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && serveStatic(res, url.pathname)) return;
      for (const r of routes) {
        if (r.method !== req.method) continue;
        const m = r.re.exec(url.pathname);
        if (!m) continue;
        const out = await r.fn(req, m, url);
        send(res, out.status, out.body, out.headers);
        return;
      }
      throw notFound('없는 주소예요.');
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.status === 413) res.setHeader('Connection', 'close');
        send(res, e.status, { error: e.code, message: e.message, ...e.extra });
        return;
      }
      console.error('서버 오류:', e && e.message);
      send(res, 500, { error: 'server_error', message: '잠시 문제가 생겼어요. 다시 시도해 주세요.' });
    }
  });
  server.db = db;
  return server;
}

module.exports = { createApp, MAX_AUDIO_BYTES };
