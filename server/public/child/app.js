'use strict';
// 자녀용 웹 — 바닐라 JS. 모든 글자는 textContent로만 넣는다(서버에서 온 값을 HTML로 해석하지 않음).
(function () {
  const POLL_MS = 10000;
  const MAX_REC_SEC = 30;
  const WAV_RATE = 16000;
  const KEY = 'gyeote.child';
  const $ = (id) => document.getElementById(id);

  let session = null;
  let pollTimer = null;
  let lastConsent = null;
  let currentTab = 'route';

  // ---- 저장(브라우저가 막으면 메모리에서만) ----------------------------------
  function loadSession() { try { return JSON.parse(localStorage.getItem(KEY)); } catch { return null; } }
  function saveSession(s) { try { s ? localStorage.setItem(KEY, JSON.stringify(s)) : localStorage.removeItem(KEY); } catch { /* 무시 */ } }

  // ---- 서버 호출 ----------------------------------------------------------
  async function api(method, path, { json, body, headers, auth = true } = {}) {
    const h = { ...(headers || {}) };
    if (auth && session) h.Authorization = `Bearer ${session.token}`;
    let payload = body;
    if (json !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(json); }
    let res;
    try { res = await fetch(path, { method, headers: h, body: payload }); }
    catch { throw { status: 0, message: '서버에 닿지 않아요. 잠시 뒤 다시 해 주세요.' }; }
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = null; }
    if (!res.ok) throw { status: res.status, code: data && data.error, message: (data && data.message) || '잠시 문제가 생겼어요.', reason: data && data.reason };
    return data;
  }

  function say(el, text, kind) { el.textContent = text || ''; el.className = `msg${kind ? ` ${kind}` : ''}`; }

  // ---- 화면 전환 ----------------------------------------------------------
  function showPair() {
    stopPolling();
    $('view-home').hidden = true; $('view-pair').hidden = false; $('btn-leave').hidden = true;
  }
  function showHome() {
    $('view-pair').hidden = true; $('view-home').hidden = false; $('btn-leave').hidden = false;
    $('home-title').textContent = `${session.parentName || '부모님'}께\n길을 보내 드려요`;
    $('home-title').style.whiteSpace = 'pre-line';
    refresh();
    startPolling();
  }
  function selectTab(name) {
    currentTab = name;
    for (const t of ['route', 'voice', 'news']) $(`tab-${t}`).hidden = t !== name;
    document.querySelectorAll('.tabs button').forEach((b) => {
      const on = b.dataset.tab === name;
      b.classList.toggle('on', on);
      if (on) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
    });
  }

  // ---- 연결 -----------------------------------------------------------------
  $('form-pair').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('pair-msg');
    say(msg, '');
    const code = $('in-code').value.trim();
    const name = $('in-name').value.trim();
    if (!/^\d{6}$/.test(code)) return say(msg, '숫자 6자리를 입력해 주세요.');
    if (!name) return say(msg, '이름을 알려 주세요.');
    try {
      const r = await api('POST', '/api/pair', { json: { pairCode: code, name }, auth: false });
      session = { token: r.childToken, familyId: r.familyId, childId: r.childId, name, parentName: r.parentName };
      saveSession(session);
      $('in-code').value = '';
      showHome();
    } catch (err) { say(msg, pairErrorText(err)); }
  });
  // v0.3.1: 숫자는 10분 뒤 만료·1회용·5번 틀리면 무효. 서버 문구와 별개로 한국어 안내를 고정한다.
  function pairErrorText(err) {
    if (err.code === 'invalid_code') return '숫자가 맞지 않거나 10분이 지났어요. 이미 쓴 숫자일 수도 있어요. 부모님 차 화면에서 새 숫자를 받아 주세요.';
    if (err.code === 'too_many_attempts' && err.reason === 'global') return '지금 연결을 시도하는 사람이 너무 많아요. 10분 뒤에 다시 입력해 주세요.';
    if (err.code === 'too_many_attempts' && err.reason === 'address') return '이 폰에서 여러 번 틀려서 잠시 멈췄어요. 10분 뒤에 다시 입력해 주세요.';
    if (err.code === 'too_many_attempts') return '여러 번 틀려서 이 숫자는 더 쓸 수 없어요. 부모님 차 화면에서 새 숫자를 받아 주세요.';
    return err.message;
  }
  $('in-code').addEventListener('input', (e) => { e.target.value = e.target.value.replace(/\D/g, '').slice(0, 6); });

  $('btn-leave').addEventListener('click', () => {
    if (!confirm('이 폰의 연결을 끊을까요? (부모님 차의 연결은 그대로예요)')) return;
    session = null; saveSession(null); lastConsent = null; showPair();
  });
  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => selectTab(b.dataset.tab)));

  // ---- 조회(10초 폴링) --------------------------------------------------------
  function startPolling() { stopPolling(); pollTimer = setInterval(() => { if (!document.hidden) refresh(); }, POLL_MS); }
  function stopPolling() { if (pollTimer) clearInterval(pollTimer); pollTimer = null; }
  function dropSession() { session = null; saveSession(null); showPair(); say($('pair-msg'), '연결이 풀렸어요. 숫자를 다시 입력해 주세요.'); }

  async function refresh() {
    if (!session) return;
    try {
      lastConsent = await api('GET', `/api/families/${session.familyId}/consent`);
      renderScope(lastConsent);
    } catch (err) { if (err.status === 401 || err.status === 404) return dropSession(); }
    await Promise.all([loadEvents(), loadDestinations()]);
  }

  const fmtTime = (iso) => new Date(iso).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: 'numeric', minute: '2-digit' });

  // 평소 운행 소식(출발/도착)은 부모님이 켠 경우에만 보조 영역으로 보인다.
  const routeOn = (c) => (c.shareRouteArrival || c.shareArrival) && c.recipients.includes(session.childId);
  const newsOn = (c) => (c.shareDeparture || c.shareArrival) && c.recipients.includes(session.childId);

  function renderScope(c) {
    const ul = $('scope-list');
    ul.textContent = '';
    const items = [[routeOn(c) ? '보낸 길에 도착하면 알려 드려요' : '보낸 길 도착 소식은 아직 닫혀 있어요', routeOn(c)]];
    if (newsOn(c)) items.push(['평소 운행 소식 받는 중', true]);
    if (c.shareLocation) items.push(['부모님이 알려 주신 곳 받는 중', true]);
    for (const [label, on] of items) {
      const li = document.createElement('li');
      li.textContent = label;
      if (!on) li.className = 'off';
      ul.appendChild(li);
    }
    const mine = c.recipients.includes(session.childId);
    let note = mine ? '' : '아직 부모님이 이 폰으로 소식을 열어두지 않았어요. ';
    note += c.until ? `${fmtTime(c.until)}까지 전해져요. ` : '';
    note += '부모님은 언제든 끌 수 있고, 끄면 전해진 소식도 바로 사라져요.';
    $('scope-note').textContent = note;
    // 보조 영역 탭: 평소 소식이 열려 있을 때만 보인다.
    const show = newsOn(c);
    $('tabbtn-news').hidden = !show;
    if (!show && currentTab === 'news') selectTab('route');
  }

  async function loadEvents() {
    if (!lastConsent || !newsOn(lastConsent)) { $('timeline').textContent = ''; return; }
    const msg = $('news-msg'); const ol = $('timeline');
    try {
      const all = await api('GET', `/api/families/${session.familyId}/events`);
      const list = all.filter((ev) => !ev.destinationId); // 보낸 길 도착은 '보낸 길'에서 보여 준다.
      ol.textContent = '';
      for (const ev of list.slice().reverse()) {
        const li = document.createElement('li');
        const t = document.createElement('div'); t.className = 't'; t.textContent = fmtTime(ev.at);
        const s = document.createElement('div'); s.className = 's';
        const kind = document.createElement('span'); kind.className = 'kind';
        kind.textContent = { departed: '출발', arrived: '도착', alert: '차 소식' }[ev.type] || '소식';
        s.appendChild(kind); s.appendChild(document.createTextNode(ev.summary));
        li.append(t, s);
        if (ev.location) {
          const loc = document.createElement('div'); loc.className = 'loc';
          loc.textContent = `부모님이 알려 주신 곳 · ${ev.location.lat.toFixed(4)}, ${ev.location.lng.toFixed(4)}`;
          li.appendChild(loc);
        }
        ol.appendChild(li);
      }
      say(msg, list.length ? '' : '아직 전해진 소식이 없어요.', 'soft');
      $('news-updated').textContent = `${new Date().toLocaleTimeString('ko-KR', { hour: 'numeric', minute: '2-digit', second: '2-digit' })}에 새로 가져왔어요`;
    } catch (err) {
      if (err.status === 401) return dropSession();
      ol.textContent = '';
      say(msg, err.status === 403 ? '지금은 열려 있는 소식이 없어요.' : err.message, 'soft');
    }
  }

  // ---- 목적지 --------------------------------------------------------------
  const DEST_LABEL = {
    pending: '기다리는 중', accepted: '수락하셨어요', arrived: '도착', closed: '지난 길',
    deferred: '다음에 하실게요', declined: '이번에는 괜찮대요',
  };
  // 이름 뒤에 붙는 '이/가' — 받침이 있으면 '이'.
  function iga(word) {
    const ch = word.charCodeAt(word.length - 1) - 0xac00;
    return ch >= 0 && ch <= 11171 && ch % 28 !== 0 ? '이' : '가';
  }
  const arrivalSentence = (d) => {
    const who = session.parentName || '부모님';
    return `${who}${iga(who)} ${d.name}에 잘 도착하셨어요`;
  };

  async function loadDestinations() {
    try {
      const list = await api('GET', `/api/families/${session.familyId}/destinations`);
      const ul = $('dest-list'); ul.textContent = '';
      for (const d of list.slice().reverse()) {
        const li = document.createElement('li');
        if (d.status === 'arrived' || d.status === 'closed') li.className = d.status;
        const left = document.createElement('div');
        const n = document.createElement('div'); n.className = 'n';
        n.textContent = d.status === 'arrived' ? arrivalSentence(d) : d.name;
        const a = document.createElement('div'); a.className = 'a';
        a.textContent = d.status === 'arrived' && d.arrivedAt ? `${fmtTime(d.arrivedAt)} 도착` : `${fmtTime(d.createdAt)} 보냈어요`;
        left.append(n, a);
        const b = document.createElement('span'); b.className = `badge ${d.status}`; b.textContent = DEST_LABEL[d.status] || d.status;
        li.append(left, b); ul.appendChild(li);
      }
      if (!list.length) { const li = document.createElement('li'); li.textContent = '아직 보낸 길이 없어요. 위에서 첫 길을 보내 보세요.'; ul.appendChild(li); }
      renderArrival(list);
      $('dest-updated').textContent = `${new Date().toLocaleTimeString('ko-KR', { hour: 'numeric', minute: '2-digit', second: '2-digit' })}에 새로 가져왔어요`;
    } catch (err) { if (err.status === 401) dropSession(); /* 그 밖에는 다음 주기에 다시 */ }
  }

  // 가장 최근에 도착한 길 하나를 맨 위에 크게 보여 준다.
  function renderArrival(list) {
    const arrived = list.filter((d) => d.status === 'arrived' && d.arrivedAt)
      .sort((x, y) => Date.parse(y.arrivedAt) - Date.parse(x.arrivedAt))[0];
    $('arrival-banner').hidden = !arrived;
    if (!arrived) return;
    $('arrival-text').textContent = arrivalSentence(arrived);
    $('arrival-time').textContent = `${fmtTime(arrived.arrivedAt)}에 닿았어요`;
  }

  document.querySelectorAll('.ex').forEach((b) => b.addEventListener('click', () => {
    $('d-name').value = b.dataset.name; $('d-address').value = b.dataset.address;
    $('d-lat').value = b.dataset.lat; $('d-lng').value = b.dataset.lng;
    say($('dest-msg'), '');
  }));

  $('form-dest').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('dest-msg');
    const lat = Number($('d-lat').value.trim()); const lng = Number($('d-lng').value.trim());
    if (!$('d-name').value.trim()) return say(msg, '가실 곳을 적어 주세요.');
    if (!$('d-lat').value.trim() || !$('d-lng').value.trim()) {
      $('d-lat').closest('details').open = true;
      return say(msg, '위에서 예시 장소를 고르거나, 좌표를 직접 넣어 주세요.');
    }
    if (!Number.isFinite(lat) || lat < -90 || lat > 90) return say(msg, '위도는 -90에서 90 사이 숫자예요.');
    if (!Number.isFinite(lng) || lng < -180 || lng > 180) return say(msg, '경도는 -180에서 180 사이 숫자예요.');
    try {
      await api('POST', `/api/families/${session.familyId}/destinations`, {
        json: { name: $('d-name').value.trim(), address: $('d-address').value.trim(), lat, lng, note: $('d-note').value.trim() },
      });
      say(msg, '부모님 차로 보냈어요. 받으시면 안내가 시작되고, 도착하면 여기에 알려 드려요.', 'ok');
      $('form-dest').reset();
      loadDestinations();
    } catch (err) { if (err.status === 401) return dropSession(); say(msg, err.message); }
  });

  // ---- 목소리 녹음 (v0.3.1: 메뉴를 숨긴 기능. 하위 호환으로 코드만 남김) -----------------------------------------------------------
  let recorder = null; let recStream = null; let recChunks = []; let recTimer = null; let recStart = 0;
  let wavBlob = null; let wavSec = 0; let previewUrl = null;

  const mmss = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
  function setRecUi(state) {
    const rec = state === 'recording';
    $('btn-rec').setAttribute('aria-pressed', String(rec));
    $('rec-label').textContent = rec ? '녹음 멈추기' : '녹음 시작';
    $('btn-rec').hidden = state === 'ready';
    $('rec-preview').hidden = state !== 'ready';
    $('rec-actions').hidden = state !== 'ready';
  }
  function tick() {
    const sec = (Date.now() - recStart) / 1000;
    $('rec-time').textContent = `${mmss(Math.min(sec, MAX_REC_SEC))} / ${mmss(MAX_REC_SEC)}`;
    if (sec >= MAX_REC_SEC) stopRec();
  }
  async function startRec() {
    const msg = $('voice-msg'); say(msg, '');
    if (!navigator.mediaDevices || !window.MediaRecorder) return say(msg, '이 브라우저에서는 녹음을 할 수 없어요.');
    try { recStream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
    catch { return say(msg, '마이크를 쓸 수 있게 허락해 주세요.'); }
    recChunks = [];
    recorder = new MediaRecorder(recStream);
    recorder.ondataavailable = (ev) => { if (ev.data && ev.data.size) recChunks.push(ev.data); };
    recorder.onstop = onRecStopped;
    recorder.start();
    recStart = Date.now();
    recTimer = setInterval(tick, 200);
    setRecUi('recording'); tick();
  }
  function stopRec() {
    clearInterval(recTimer); recTimer = null;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    if (recStream) recStream.getTracks().forEach((t) => t.stop());
  }
  async function onRecStopped() {
    const msg = $('voice-msg');
    try {
      const raw = new Blob(recChunks, { type: recorder.mimeType || 'audio/webm' });
      const wav = await toWav(raw);
      wavBlob = wav.blob; wavSec = wav.seconds;
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = URL.createObjectURL(wavBlob);
      $('rec-preview').src = previewUrl;
      $('rec-time').textContent = `${mmss(wavSec)} 녹음됨`;
      setRecUi('ready'); say(msg, '들어 보고 마음에 들면 보내 주세요.', 'soft');
    } catch {
      setRecUi('idle'); $('rec-time').textContent = `0:00 / ${mmss(MAX_REC_SEC)}`;
      say(msg, '녹음을 처리하지 못했어요. 다시 한 번 해 볼까요?');
    }
  }
  // 브라우저 녹음(webm/mp4 등)을 계약 형식인 16kHz 모노 WAV로 바꾼다. 30초면 약 0.96MB.
  async function toWav(blob) {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    const ctx = new Ctx();
    try {
      const decoded = await ctx.decodeAudioData(await blob.arrayBuffer());
      const seconds = Math.min(decoded.duration, MAX_REC_SEC);
      const frames = Math.max(1, Math.floor(seconds * WAV_RATE));
      const off = new OfflineAudioContext(1, frames, WAV_RATE);
      const src = off.createBufferSource(); src.buffer = decoded; src.connect(off.destination); src.start();
      const pcm = (await off.startRendering()).getChannelData(0);
      const buf = new ArrayBuffer(44 + pcm.length * 2); const v = new DataView(buf);
      const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
      w(0, 'RIFF'); v.setUint32(4, 36 + pcm.length * 2, true); w(8, 'WAVE'); w(12, 'fmt ');
      v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
      v.setUint32(24, WAV_RATE, true); v.setUint32(28, WAV_RATE * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
      w(36, 'data'); v.setUint32(40, pcm.length * 2, true);
      for (let i = 0; i < pcm.length; i++) { const x = Math.max(-1, Math.min(1, pcm[i])); v.setInt16(44 + i * 2, x < 0 ? x * 0x8000 : x * 0x7fff, true); }
      return { blob: new Blob([buf], { type: 'audio/wav' }), seconds: Math.max(0.1, Math.round(seconds * 10) / 10) };
    } finally { ctx.close(); }
  }

  $('btn-rec').addEventListener('click', () => { if (recTimer) stopRec(); else startRec(); });
  $('btn-redo').addEventListener('click', () => {
    wavBlob = null; $('rec-preview').removeAttribute('src');
    $('rec-time').textContent = `0:00 / ${mmss(MAX_REC_SEC)}`; say($('voice-msg'), ''); setRecUi('idle');
  });
  $('btn-send').addEventListener('click', async () => {
    const msg = $('voice-msg');
    if (!wavBlob) return;
    $('btn-send').disabled = true;
    try {
      await api('POST', `/api/families/${session.familyId}/messages?durationSec=${wavSec}`, { body: wavBlob, headers: { 'Content-Type': 'audio/wav' } });
      wavBlob = null; $('rec-preview').removeAttribute('src');
      $('rec-time').textContent = `0:00 / ${mmss(MAX_REC_SEC)}`; setRecUi('idle');
      say(msg, '목소리를 보냈어요. 부모님이 차를 세우시면 들려 드려요.', 'ok');
    } catch (err) { if (err.status === 401) return dropSession(); say(msg, err.message); }
    finally { $('btn-send').disabled = false; }
  });

  // ---- 시작 -----------------------------------------------------------------
  session = loadSession();
  if (session && session.token && session.familyId) showHome(); else showPair();
})();
