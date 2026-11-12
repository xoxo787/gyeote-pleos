import React from 'react';
import {AbsoluteFill, Img, Series, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {CarSide} from './CarSide';
import {enter, kf, OUT, P, Pill, Reveal, Rise, SANS, SERIF, SHADOW, SHADOW_SMALL, World} from './kit2';
import {APP, car, Ground, HEAD, lead, Marked, ORIGIN, Paper, Roll, Shell, Stat} from './parts';

/**
 * The Passer film: same paper world and the same car as the other film, with the app drawn in Passer's own colours.
 * Wording on the app card is taken from the real screens (app/docs/screens).
 */
export const PLEN = {title: 120, problem: 240, handover: 330, tour: 440, back: 330, real: 300, rules: 290, end: 130};
export const PASSER_LENGTH = Object.values(PLEN).reduce((a, b) => a + b, 0);

/** Passer's palette (MainActivity.kt). */
const G = {bg: '#102525', surface: '#1C3736', light: '#F3F8F0', mint: '#A9E7CB', muted: '#B9CFC8', dark: '#132D28', peach: '#FFD9B5'};

const DOOR = car({x: 500, y: 250}); // middle of the driver's door
const TAIL = car({x: 1076, y: 210});

const Ornaments: React.FC<{cx: number; cy: number; scale: number}> = ({cx, cy, scale}) => (
  <World cx={cx} cy={cy} scale={scale} depth={0.5}>
    <div style={{position: 'absolute', left: 938, top: -200, fontFamily: SERIF, fontWeight: 600, fontSize: 420, lineHeight: 1, color: P.ornament, opacity: 0.3}}>P</div>
    <div style={{position: 'absolute', left: 2300, top: 420, fontFamily: SERIF, fontStyle: 'italic', fontWeight: 600, fontSize: 300, lineHeight: 1, color: P.ornament, opacity: 0.26}}>3′</div>
  </World>
);

const Gear: React.FC<{from?: string; to?: string; roll?: number; dx?: number}> = ({from = 'P', to = 'D', roll = 0, dx = 0}) => (
  <div style={{position: 'absolute', left: 452 + dx, top: 548}}>
    <Pill><span style={{color: P.soft, fontSize: 17}}>기어</span><Roll from={from} to={to} roll={roll} size={26} toColor={P.ink} /></Pill>
  </div>
);

// ---- the app card ----
const Card: React.FC<{children: React.ReactNode; right?: string}> = ({children, right = '정차 중 · Pleos 차량'}) => (
  <div style={{position: 'absolute', left: APP.x - APP.w / 2, top: APP.y - APP.h / 2, width: APP.w, height: APP.h, boxSizing: 'border-box', background: G.bg, borderRadius: 30, padding: '34px 44px', fontFamily: SANS,
    boxShadow: '0 2px 4px rgba(16,37,37,0.2), 0 40px 80px -24px rgba(20,50,60,0.45), 0 90px 160px -50px rgba(20,50,60,0.5)'}}>
    <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'baseline'}}>
      <span style={{color: G.light, fontSize: 24, fontWeight: 720, letterSpacing: -0.3}}>Passer</span>
      <span style={{color: G.muted, fontSize: 15, fontWeight: 560}}>{right}</span>
    </div>
    {children}
  </div>
);
const Kicker: React.FC<{children: React.ReactNode}> = ({children}) => <div style={{marginTop: 26, color: G.muted, fontSize: 18, fontWeight: 620}}>{children}</div>;
const Big: React.FC<{children: React.ReactNode; size?: number}> = ({children, size = 44}) => (
  <div style={{marginTop: 8, color: G.light, fontSize: size, fontWeight: 760, letterSpacing: '-0.03em', lineHeight: 1.22}}>{children}</div>
);
const Info: React.FC<{frame: number; start: number; label: string; value: React.ReactNode; tone?: 'peach'; style?: React.CSSProperties}> = ({frame, start, label, value, tone, style}) => {
  const a = enter(frame, start, 14);
  return (
    <div style={{background: tone === 'peach' ? G.peach : G.surface, borderRadius: 16, padding: '15px 20px', opacity: a, transform: `translateY(${(1 - a) * 12}px)`, ...style}}>
      <div style={{color: tone === 'peach' ? G.dark : G.muted, fontSize: 14, fontWeight: 620}}>{label}</div>
      <div style={{marginTop: 5, color: tone === 'peach' ? G.dark : G.light, fontSize: 24, fontWeight: 700, letterSpacing: -0.4}}>{value}</div>
    </div>
  );
};
const Buttons: React.FC<{frame: number; start: number; primary: string; secondary: string; press?: number; pressSecondary?: boolean}> = ({frame, start, primary, secondary, press, pressSecondary}) => {
  const a = enter(frame, start, 14);
  const ring = press === undefined ? 0 : kf(frame, [[press, 0], [press + 22, 1]], OUT);
  const target = pressSecondary ? 1 : 0;
  return (
    <div style={{display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginTop: 18, opacity: a, transform: `translateY(${(1 - a) * 12}px)`}}>
      {[primary, secondary].map((label, i) => (
        <div key={label} style={{position: 'relative', background: i === 0 ? G.mint : G.surface, color: i === 0 ? G.dark : G.light, borderRadius: 14, padding: '17px 0', textAlign: 'center', fontSize: 24, fontWeight: 720,
          transform: press !== undefined && i === target ? `scale(${1 - 0.04 * kf(frame, [[press - 4, 0], [press, 1], [press + 10, 0]], OUT)})` : undefined}}>
          {label}
          {press !== undefined && i === target && ring > 0 && ring < 1 && (
            <span style={{position: 'absolute', left: '50%', top: '50%', width: 60 + 240 * ring, height: 60 + 240 * ring, marginLeft: -(30 + 120 * ring), marginTop: -(30 + 120 * ring), borderRadius: '50%', border: `2.5px solid ${P.gold}`, opacity: 1 - ring}} />
          )}
        </div>
      ))}
    </div>
  );
};

// ───────────────────────── title ─────────────────────────
export const PTitle: React.FC = () => {
  const frame = useCurrentFrame();
  const total = PLEN.title;
  const cx = 960 + lead(frame, total) * (frame > 60 ? 1 : 0);
  return (
    <Shell total={total} label="">
      <AbsoluteFill>
        <World cx={cx} cy={540} scale={1}>
          <Paper />
          <div style={{position: 'absolute', left: 170, top: 250, whiteSpace: 'nowrap'}}>
            <Rise frame={frame} start={6} style={{fontSize: 22, fontWeight: 640, color: P.soft, letterSpacing: 0.2}}>Pleos Connect 차량용 앱</Rise>
            <div style={{fontFamily: SERIF, fontWeight: 600, fontSize: 210, lineHeight: 1.05, color: P.ink, letterSpacing: -4, marginTop: 6}}><Reveal frame={frame} start={12} step={2.2} fade={10} text="Passer" /></div>
            <div style={{fontSize: 44, fontWeight: 700, color: P.ink, letterSpacing: '-0.035em', marginTop: 12}}>
              <Reveal frame={frame} start={34} step={0.9} text="차를 넘겨받는 " /><Marked frame={frame} start={42} markAt={58} text="첫 3분" />
            </div>
          </div>
          <div style={{position: 'absolute', left: 980, top: 660, transform: `translateX(${(1 - enter(frame, 20, 34)) * 520}px) scale(0.66)`, transformOrigin: '0 0', opacity: enter(frame, 20, 12)}}>
            <CarSide charging={0} />
          </div>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 01 problem ─────────────────────────
const QUESTIONS: [number, number, string][] = [[610, 392, '배터리는 얼마나 남았지'], [1010, 346, '아까 경고등이 켜졌었나'], [1420, 408, '방향지시등은 어디지']];

const Person: React.FC<{color: string}> = ({color}) => (
  <svg width={64} height={64} viewBox="0 0 64 64"><circle cx={32} cy={23} r={11} fill={color} /><path d="M 12 56 C 12 42, 22 36, 32 36 C 42 36, 52 42, 52 56 Z" fill={color} /></svg>
);

export const PProblem: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const total = PLEN.problem;
  const cx = 1020 + lead(frame, total);
  const scale = kf(frame, [[0, 0.9], [total, 0.94]], (t) => t);
  const pass = kf(frame, [[40, 0], [84, 1]]);
  const keyX = 300 + (DOOR.x - 60 - 300) * pass;
  const keyY = 690 - 150 * Math.sin(Math.PI * pass);
  return (
    <Shell total={total} index="01" title="운전자가 바뀌는 순간">
      <AbsoluteFill>
        <Ornaments cx={cx} cy={540} scale={scale} />
        <World cx={cx} cy={540} scale={scale}>
          <Paper />
          <Ground />
          <div style={{position: 'absolute', left: ORIGIN.x, top: ORIGIN.y}}><CarSide charging={0} doorGlow={enter(frame, 84, 14) * (1 - enter(frame, 150, 20))} /></div>
          <Gear />
          {/* the driver before, the driver after, and the key between them */}
          <div style={{position: 'absolute', left: 190, top: 640, width: 96, height: 96, borderRadius: 48, background: P.white, border: `1px solid ${P.hair}`, boxShadow: SHADOW_SMALL, display: 'flex', alignItems: 'center', justifyContent: 'center', opacity: 1 - 0.55 * pass}}><Person color={P.faint} /></div>
          <div style={{position: 'absolute', left: DOOR.x - 48, top: 430 - 150, width: 96, height: 96, borderRadius: 48, background: P.white, border: `1px solid ${P.hair}`, boxShadow: SHADOW_SMALL, display: 'flex', alignItems: 'center', justifyContent: 'center', transform: `scale(${spring({frame: frame - 70, fps, config: {damping: 12}})})`}}><Person color={P.ink} /></div>
          <div style={{position: 'absolute', left: keyX, top: keyY, opacity: enter(frame, 36, 8) * (1 - enter(frame, 86, 10))}}>
            <svg width={56} height={30} viewBox="0 0 56 30"><circle cx={13} cy={15} r={10} fill="none" stroke={P.gold} strokeWidth={5} /><path d="M 23 15 H 52 M 42 15 V 24 M 49 15 V 21" fill="none" stroke={P.gold} strokeWidth={5} strokeLinecap="round" /></svg>
          </div>
          {QUESTIONS.map(([x, y, text], i) => {
            const pop = spring({frame: frame - 104 - i * 12, fps, config: {damping: 12, stiffness: 150}});
            return (
              <div key={text} style={{position: 'absolute', left: x, top: y + Math.sin((frame + i * 23) / 17) * 4, transform: `scale(${pop})`, transformOrigin: '50% 100%'}}>
                <Pill style={{fontSize: 24, padding: '12px 22px'}}>{text}<span style={{fontFamily: SERIF, fontSize: 30, lineHeight: '24px', color: P.gold}}>?</span></Pill>
              </div>
            );
          })}
          <div style={{position: 'absolute', left: 372, top: 92}}>
            <div style={{...HEAD, fontSize: 80}}><Reveal frame={frame} start={16} text="운전자가 바뀌어도," /></div>
            <div style={{...HEAD, fontSize: 80}}><Reveal frame={frame} start={28} text="차는 " /><Marked frame={frame} start={31} markAt={60} text="아무 말도" /><Reveal frame={frame} start={36} text=" 하지 않습니다" /></div>
          </div>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 02 handover ─────────────────────────
const HAND = {door: 30, toApp: 84, press: 268};

export const PHandover: React.FC = () => {
  const frame = useCurrentFrame();
  const total = PLEN.handover;
  const cx = kf(frame, [[0, 930], [HAND.toApp, 934], [HAND.toApp + 22, APP.x], [total, APP.x]]) + lead(frame, total);
  const cy = kf(frame, [[0, 520], [HAND.toApp, 520], [HAND.toApp + 22, 540], [total, 540]]);
  const scale = kf(frame, [[0, 1.2], [HAND.toApp, 1.25], [HAND.toApp + 22, 1.0], [total, 1.04]]);
  const glow = kf(frame, [[HAND.door, 0], [HAND.door + 12, 1], [HAND.door + 40, 1], [HAND.door + 54, 0.25]], OUT);
  const t0 = HAND.toApp + 26;
  const line = kf(frame, [[HAND.toApp - 8, 0], [HAND.toApp + 22, 1]]);
  return (
    <Shell total={total} travels={[[HAND.toApp + 1, HAND.toApp + 22]]} index="02" title="인계 30초">
      <AbsoluteFill>
        <Ornaments cx={cx} cy={cy} scale={scale} />
        <World cx={cx} cy={cy} scale={scale}>
          <Paper />
          <Ground />
          <div style={{position: 'absolute', left: ORIGIN.x, top: ORIGIN.y}}><CarSide charging={0} doorGlow={glow} /></div>
          <Gear />
          <div style={{position: 'absolute', left: 470, top: 262}}>
            <div style={{...HEAD, fontSize: 66}}><Reveal frame={frame} start={16} text="다음 사람이 " /><Marked frame={frame} start={22} markAt={HAND.door + 10} text="타면," /></div>
          </div>
          <Rise frame={frame} start={HAND.door + 8} style={{position: 'absolute', left: DOOR.x - 96, top: DOOR.y - 20, whiteSpace: 'nowrap'}}>
            <Pill style={{fontSize: 17, padding: '8px 16px'}}><span style={{width: 7, height: 7, borderRadius: 4, background: P.gold}} />운전석 문 열림 → 닫힘</Pill>
          </Rise>
          <svg style={{position: 'absolute', left: 0, top: 0, overflow: 'visible'}} width={10} height={10}>
            <path d={`M ${DOOR.x + 40} ${DOOR.y - 150} C 1500 330, 2200 ${APP.y}, ${APP.x - APP.w / 2} ${APP.y}`} fill="none" stroke={P.ink} strokeWidth={1.6} strokeLinecap="round" pathLength={1} strokeDasharray="1 1" strokeDashoffset={1 - line} opacity={0.5} />
          </svg>

          <div style={{position: 'absolute', left: APP.x - APP.w / 2 + 4, top: 96}}>
            <div style={{...HEAD, fontSize: 78}}><Reveal frame={frame} start={t0} text="차가 " /><Marked frame={frame} start={t0 + 4} markAt={t0 + 20} text="먼저" /><Reveal frame={frame} start={t0 + 7} text=" 자기 상태를 말합니다" /></div>
          </div>
          <Card>
            <Kicker>차를 넘겨받는 순간 · 정차 중</Kicker>
            <Big><Reveal frame={frame} start={t0 + 6} step={0.9} text="안녕하세요. 지금 제 상태를 말씀드릴게요." /></Big>
            <div style={{display: 'grid', gridTemplateColumns: '1.15fr 1fr', gap: 14, marginTop: 20}}>
              <Info frame={frame} start={t0 + 44} label="배터리" value="배터리 54% · 갈 수 있는 거리 약 210km" />
              <Info frame={frame} start={t0 + 56} label="누적 주행거리" value="12,345km" />
              <Info frame={frame} start={t0 + 68} label="지금 켜진 경고등" value="없어요." />
              <Info frame={frame} start={t0 + 80} label="문·트렁크·충전구" value="모두 닫혀 있어요" />
              <Info frame={frame} start={t0 + 92} label="최근 켜졌던 경고등" value="타이어 바람 1번 (오늘)" style={{gridColumn: '1 / 3'}} />
            </div>
            <Buttons frame={frame} start={t0 + 108} primary="인수했어요" secondary="처음 타요" press={HAND.press} pressSecondary />
          </Card>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 03 tour ─────────────────────────
const TOUR = {left: 44, right: 96, pass1: 126, toCar: 150, brake: 196, signal: 262, toDone: 318};
const WHEEL = {x: APP.x - APP.w / 2 - 190, y: 600};

export const PTour: React.FC = () => {
  const frame = useCurrentFrame();
  const total = PLEN.tour;
  const cx = kf(frame, [[0, APP.x - 110], [TOUR.toCar, APP.x - 110], [TOUR.toCar + 22, 1250], [TOUR.toDone, 1254], [TOUR.toDone + 22, APP.x], [total, APP.x]]) + lead(frame, total);
  const cy = kf(frame, [[0, 540], [TOUR.toCar, 540], [TOUR.toCar + 22, 560], [TOUR.toDone, 560], [TOUR.toDone + 22, 540], [total, 540]]);
  const scale = kf(frame, [[0, 0.96], [TOUR.toCar, 0.99], [TOUR.toCar + 22, 1.45], [TOUR.toDone, 1.5], [TOUR.toDone + 22, 1.0], [total, 1.04]]);
  const angle = kf(frame, [[TOUR.left - 26, 0], [TOUR.left, -450], [TOUR.right - 30, -450], [TOUR.right, 450], [TOUR.pass1 + 6, 450], [TOUR.pass1 + 26, 0]]);
  const leftDone = frame >= TOUR.left;
  const pass1 = frame >= TOUR.right + 4;
  const brake = kf(frame, [[TOUR.brake, 0], [TOUR.brake + 8, 1], [TOUR.brake + 40, 1], [TOUR.brake + 52, 0]], OUT);
  const blinking = frame >= TOUR.signal && frame < TOUR.toDone + 10;
  const blink = blinking && Math.floor((frame - TOUR.signal) / 10) % 2 === 0 ? 1 : 0;
  const done = frame >= TOUR.toDone + 12;
  const passed = frame >= TOUR.signal + 6 ? 3 : frame >= TOUR.brake + 6 ? 2 : pass1 ? 1 : 0;
  const thumb = 0.5 + (angle / 450) * 0.46;
  return (
    <Shell total={total} travels={[[TOUR.toCar + 1, TOUR.toCar + 22], [TOUR.toDone + 1, TOUR.toDone + 22]]} index="03" title="첫 3분 투어">
      <AbsoluteFill>
        <Ornaments cx={cx} cy={cy} scale={scale} />
        <World cx={cx} cy={cy} scale={scale}>
          <Paper />
          <Ground />
          <div style={{position: 'absolute', left: ORIGIN.x, top: ORIGIN.y}}><CarSide charging={0} brake={brake} blink={blink} /></div>
          <Gear />

          {/* at the car: the other two missions are done on the real controls */}
          <div style={{position: 'absolute', left: 840, top: 268}}>
            <div style={{...HEAD, fontSize: 58}}><Reveal frame={frame} start={TOUR.toCar + 26} text="브레이크도, 방향지시등도" /></div>
            <div style={{...HEAD, fontSize: 58}}><Marked frame={frame} start={TOUR.toCar + 40} markAt={TOUR.brake + 8} text="직접" /><Reveal frame={frame} start={TOUR.toCar + 43} text=" 해 봅니다" /></div>
          </div>
          <Rise frame={frame} start={TOUR.brake + 10} style={{position: 'absolute', left: 1490, top: 566, whiteSpace: 'nowrap'}}>
            <Pill style={{fontSize: 17, padding: '8px 16px'}}><span style={{fontFamily: SERIF, fontStyle: 'italic', fontSize: 19}}>2</span>브레이크를 밟음<span style={{color: P.gold, fontWeight: 800}}>✓</span></Pill>
          </Rise>
          <Rise frame={frame} start={TOUR.signal + 10} style={{position: 'absolute', left: 1490, top: 622, whiteSpace: 'nowrap'}}>
            <Pill style={{fontSize: 17, padding: '8px 16px'}}><span style={{fontFamily: SERIF, fontStyle: 'italic', fontSize: 19}}>3</span>방향지시등을 켬<span style={{color: P.gold, fontWeight: 800}}>✓</span></Pill>
          </Rise>

          {/* beside the app: the steering wheel turning with the gauge */}
          <div style={{position: 'absolute', left: WHEEL.x - 130, top: WHEEL.y - 130, width: 260, height: 260, opacity: enter(frame, 14, 16) * (1 - enter(frame, TOUR.toCar + 30, 4))}}>
            <svg width={260} height={260} viewBox="-130 -130 260 260" style={{filter: 'drop-shadow(0 24px 30px rgba(64,72,150,0.25))'}}>
              <g transform={`rotate(${angle})`}>
                <circle r={108} fill="none" stroke={P.ink} strokeWidth={20} />
                <path d="M -100 6 L -34 0 M 100 6 L 34 0 M 0 34 L 0 100" stroke={P.ink} strokeWidth={18} strokeLinecap="round" />
                <circle r={36} fill={P.ink} />
                <rect x={-7} y={-124} width={14} height={30} rx={4} fill={P.gold} />
              </g>
            </svg>
          </div>

          <div style={{position: 'absolute', left: APP.x - APP.w / 2 + 4, top: 96}}>
            {!done ? (
              <div style={{...HEAD, fontSize: 78}}><Marked frame={frame} start={18} markAt={TOUR.left} text="실제로" /><Reveal frame={frame} start={22} text=" 움직여야 통과합니다" /></div>
            ) : (
              <div style={{...HEAD, fontSize: 78}}><Reveal frame={frame} start={TOUR.toDone + 26} text="미션 3개, " /><Marked frame={frame} start={TOUR.toDone + 32} markAt={TOUR.toDone + 48} text="언제든" /><Reveal frame={frame} start={TOUR.toDone + 35} text=" 건너뛸 수 있습니다" /></div>
            )}
          </div>
          <Card right={`통과 ${passed} · 남음 ${3 - passed}`}>
            {!done ? (
              <>
                <Kicker>처음 3분 투어 · 미션 1 / 3</Kicker>
                <Big size={50}>핸들을 왼쪽 끝까지,<br />그다음 오른쪽 끝까지</Big>
                <div style={{marginTop: 10, color: G.muted, fontSize: 20, fontWeight: 540}}>양쪽 끝을 한 번씩 찍으면 통과예요. 어느 쪽부터 해도 괜찮아요.</div>
                <div style={{marginTop: 30, textAlign: 'center', color: G.light, fontSize: 64, fontWeight: 780, letterSpacing: -1}}>
                  {pass1 && frame >= TOUR.pass1 ? '통과 ✓' : `${Math.round(angle)}° · ${angle < -5 ? '왼쪽' : angle > 5 ? '오른쪽' : '가운데'}`}
                </div>
                <div style={{position: 'relative', marginTop: 22, height: 26, borderRadius: 13, background: G.surface}}>
                  <div style={{position: 'absolute', left: 0, top: 0, width: 110, height: 26, borderRadius: 13, background: G.mint, opacity: leftDone ? 1 : 0.25}} />
                  <div style={{position: 'absolute', right: 0, top: 0, width: 110, height: 26, borderRadius: 13, background: G.mint, opacity: pass1 ? 1 : 0.25}} />
                  <div style={{position: 'absolute', left: '50%', top: -5, width: 2, height: 36, background: G.muted}} />
                  <div style={{position: 'absolute', left: `calc(${thumb * 100}% - 26px)`, top: -13, width: 52, height: 52, borderRadius: 26, background: G.peach, boxShadow: '0 6px 14px rgba(0,0,0,0.35)'}} />
                </div>
                <div style={{display: 'flex', justifyContent: 'space-between', marginTop: 16, color: G.light, fontSize: 20, fontWeight: 640}}>
                  <span>{leftDone ? '왼쪽 끝 통과' : '왼쪽 끝 남음'}</span><span>{pass1 ? '오른쪽 끝 통과' : '오른쪽 끝 남음'}</span>
                </div>
              </>
            ) : (
              <>
                <Kicker>처음 3분 투어</Kicker>
                <Big size={72}>준비 끝났어요.<br />안전 운전하세요.</Big>
                <div style={{marginTop: 34, display: 'flex', gap: 14}}>
                  {['핸들 양쪽 끝', '브레이크', '방향지시등'].map((name, i) => (
                    <Rise key={name} frame={frame} start={TOUR.toDone + 30 + i * 8} style={{background: G.surface, color: G.light, borderRadius: 14, padding: '14px 22px', fontSize: 22, fontWeight: 660}}>
                      <span style={{color: G.mint, marginRight: 10}}>✓</span>{name}
                    </Rise>
                  ))}
                </div>
                <div style={{marginTop: 26, color: G.muted, fontSize: 19, fontWeight: 540}}>기어를 D로 바꾸면 투어는 바로 끝나요. 결과는 저장하지 않아요.</div>
              </>
            )}
          </Card>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 04 return ─────────────────────────
const BACK = {go: 22, stop: 118, toApp: 150};

export const PReturn: React.FC = () => {
  const frame = useCurrentFrame();
  const total = PLEN.back;
  const cx = kf(frame, [[0, 1040], [BACK.toApp, 1040], [BACK.toApp + 22, APP.x], [total, APP.x]]) + lead(frame, total);
  const cy = 540;
  const scale = kf(frame, [[0, 0.92], [BACK.toApp, 0.96], [BACK.toApp + 22, 1.0], [total, 1.04]]);
  const driving = kf(frame, [[BACK.go, 0], [BACK.go + 20, 1], [BACK.stop - 20, 1], [BACK.stop, 0]], (t) => t);
  const travelled = kf(frame, [[BACK.go, 0], [BACK.stop, 1]], (t) => t);
  const spin = travelled * -1500;
  const t0 = BACK.toApp + 26;
  return (
    <Shell total={total} travels={[[BACK.toApp + 1, BACK.toApp + 22]]} index="04" title="돌려주기">
      <AbsoluteFill>
        <Ornaments cx={cx} cy={cy} scale={scale} />
        <World cx={cx} cy={cy} scale={scale}>
          <Paper />
          <Ground />
          {/* the road slides under the car while it drives */}
          <div style={{position: 'absolute', left: 200, top: 832, width: 1700, height: 6, opacity: 0.55 * driving, backgroundImage: `repeating-linear-gradient(90deg, ${P.faint} 0 60px, transparent 60px 150px)`, backgroundPosition: `${travelled * 2600}px 0`}} />
          <div style={{position: 'absolute', left: ORIGIN.x, top: ORIGIN.y + Math.sin(frame / 3) * 1.2 * driving}}><CarSide charging={0} spin={spin} /></div>
          <Gear from={frame < BACK.stop - 6 ? 'P' : 'D'} to={frame < BACK.stop - 6 ? 'D' : 'P'} roll={frame < BACK.stop - 6 ? kf(frame, [[BACK.go - 10, 0], [BACK.go + 4, 1]], OUT) : kf(frame, [[BACK.stop - 6, 0], [BACK.stop + 8, 1]], OUT)} />
          <div style={{position: 'absolute', left: 372, top: 150}}>
            <div style={{...HEAD, fontSize: 92}}><Reveal frame={frame} start={14} text="운행을 마치고 " /><Marked frame={frame} start={22} markAt={BACK.stop} text="돌아오면," /></div>
          </div>
          <div style={{position: 'absolute', left: 1180, top: 372, display: 'flex', gap: 12, whiteSpace: 'nowrap'}}>
            <Rise frame={frame} start={BACK.go + 6}><Pill style={{fontSize: 20, padding: '10px 18px'}}><span style={{color: P.soft, fontSize: 16}}>누적</span><span style={{fontFamily: SERIF, fontSize: 28, lineHeight: '26px'}}>+{Math.round(32 * travelled)}km</span></Pill></Rise>
            <Rise frame={frame} start={BACK.go + 12}><Pill style={{fontSize: 20, padding: '10px 18px'}}><span style={{color: P.soft, fontSize: 16}}>배터리</span><span style={{fontFamily: SERIF, fontSize: 28, lineHeight: '26px'}}>{Math.round(54 - 13 * travelled)}%</span></Pill></Rise>
            <Rise frame={frame} start={BACK.go + 62}><Pill style={{fontSize: 20, padding: '10px 18px', borderColor: P.gold}}><span style={{width: 8, height: 8, borderRadius: 4, background: P.gold}} />타이어 경고등 켜짐</Pill></Rise>
          </div>

          <div style={{position: 'absolute', left: APP.x - APP.w / 2 + 4, top: 96}}>
            <div style={{...HEAD, fontSize: 78}}><Reveal frame={frame} start={t0} text="돌려줄 때는 " /><Marked frame={frame} start={t0 + 8} markAt={t0 + 24} text="차 상태만" /><Reveal frame={frame} start={t0 + 13} text=" 비교합니다" /></div>
          </div>
          <Card>
            <Kicker>차를 돌려줄 때 · 정차 중</Kicker>
            <Big>이번 운행을 정리했어요.</Big>
            <div style={{marginTop: 6, color: G.muted, fontSize: 20, fontWeight: 560}}>인수 때 → 지금</div>
            <div style={{display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginTop: 18}}>
              <Info frame={frame} start={t0 + 34} label="배터리 · 인수 때 → 지금" value="54% → 41%" />
              <Info frame={frame} start={t0 + 46} label="문·트렁크·충전구" value="모두 닫혀 있어요" />
              <Info frame={frame} start={t0 + 58} label="누적 주행거리" value="+32km" />
              <Info frame={frame} start={t0 + 70} label="창문" value="모두 닫혀 있어요." />
              <Info frame={frame} start={t0 + 86} label="새로 켜진 경고등" value="1개: 타이어 바람" tone="peach" style={{gridColumn: '1 / 3'}} />
            </div>
            <Buttons frame={frame} start={t0 + 100} primary="돌려줬어요" secondary="나중에" />
          </Card>
          <Rise frame={frame} start={t0 + 112} style={{position: 'absolute', left: APP.x - APP.w / 2 + 4, top: APP.y + APP.h / 2 + 34, whiteSpace: 'nowrap'}}>
            <Pill style={{fontSize: 22, padding: '12px 22px'}}><span style={{width: 8, height: 8, borderRadius: 4, background: P.gold}} />누가 어떻게 몰았는지는 저장하지 않습니다 · 기록은 차 안에만 남습니다</Pill>
          </Rise>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 05 real screens ─────────────────────────
const SHOTS: [string, string][] = [['p1-handover', '인계 카드'], ['p2-tour', '투어 미션 1'], ['p3-driving', '주행 중 화면'], ['p4-return', '돌려주기 카드']];
const SHOT_LEN = 62;

export const PReal: React.FC = () => {
  const frame = useCurrentFrame();
  const total = PLEN.real;
  const cx = 960 + lead(frame, total);
  const local = Math.max(0, frame - 24);
  const index = Math.min(SHOTS.length - 1, Math.floor(local / SHOT_LEN));
  return (
    <Shell total={total} index="05" title="실제 화면" label="Pleos Connect 에뮬레이터 실제 화면 캡처 · 이미지 v2.1.0">
      <AbsoluteFill>
        <World cx={cx} cy={540} scale={1}>
          <Paper />
          <div style={{position: 'absolute', left: 150, top: 132}}>
            <div style={{...HEAD, fontSize: 80}}><Marked frame={frame} start={14} markAt={30} text="실제로" /><Reveal frame={frame} start={18} text=" 동작합니다" /></div>
          </div>
          <div style={{position: 'absolute', left: 150, top: 268, width: 1188, height: 668, borderRadius: 26, background: P.white, padding: 12, boxSizing: 'border-box', boxShadow: SHADOW, border: `1px solid ${P.hair}`}}>
            <div style={{position: 'relative', width: '100%', height: '100%', borderRadius: 16, overflow: 'hidden', background: G.bg}}>
              {SHOTS.map(([file], i) => {
                const start = i * SHOT_LEN;
                const a = i === 0 ? 1 : enter(local, start, 12);
                const push = 1 + 0.035 * Math.max(0, Math.min(1, (local - start) / SHOT_LEN));
                return <Img key={file} src={staticFile(`passer/${file}.png`)} style={{position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', opacity: a, transform: `scale(${push})`}} />;
              })}
            </div>
          </div>
          <div style={{position: 'absolute', left: 150, top: 960, display: 'flex', gap: 12, whiteSpace: 'nowrap'}}>
            {SHOTS.map(([, caption], i) => (
              <Rise key={caption} frame={frame} start={30 + i * 5}>
                <Pill style={{background: i === index ? P.ink : P.white, color: i === index ? P.white : P.soft, borderColor: i === index ? P.ink : P.hair}}>
                  <span style={{fontFamily: SERIF, fontStyle: 'italic', fontSize: 19}}>{i + 1}</span>{caption}
                </Pill>
              </Rise>
            ))}
          </div>
          <Rise frame={frame} start={40} style={{position: 'absolute', left: 1410, top: 268, width: 360}}><Stat figure="58/59" label="에뮬레이터 통합 검증" note="인계 17/18 · 투어 16/16 · 돌려주기 14/14 · 화면 뒤 기록 11/11. 1건은 에뮬레이터에 한국어 음성이 없어서" /></Rise>
          <Rise frame={frame} start={52} style={{position: 'absolute', left: 1410, top: 540, width: 360}}><Stat figure="259" label="단위 테스트 통과" /></Rise>
          <Rise frame={frame} start={64} style={{position: 'absolute', left: 1410, top: 720, width: 360}}><Stat figure="65초" label="화면을 닫아도 기록" note="화면을 닫고 65초 뒤 켠 경고등이 이력에 남음" /></Rise>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 06 principles ─────────────────────────
const RULES: [string, string][] = [
  ['차 상태만 기록합니다', '시각·배터리·누적 거리·경고등·문·트렁크·충전구·창문만 남깁니다. 운전한 사람이나 운전 방식은 저장하지 않습니다.'],
  ['정차 중에만 보여 줍니다', '인계·투어·돌려주기는 기어 P에서만 나옵니다. 주행 중에는 입력 없는 큰 문장 한 장만 보입니다.'],
  ['차 안에만 남습니다', '기록은 앱의 암호화 저장소에만 있고 서버로 보내지 않습니다. 투어 결과는 저장하지 않습니다.'],
];
const SIGNALS = ['기어', '문·트렁크', '경고등', '배터리 %', '누적 주행거리', '핸들 각도', '브레이크', '방향지시등', '내비 목적지명'];

export const PRules: React.FC = () => {
  const frame = useCurrentFrame();
  const total = PLEN.rules;
  const cx = 960 + lead(frame, total);
  return (
    <Shell total={total} index="06" title="원칙" label="">
      <AbsoluteFill>
        <World cx={cx} cy={540} scale={kf(frame, [[0, 1], [total, 1.025]], (t) => t)}>
          <Paper />
          <div style={{position: 'absolute', left: 150, top: 150}}>
            <div style={{...HEAD, fontSize: 80}}><Marked frame={frame} start={14} markAt={34} text="사람은" /><Reveal frame={frame} start={18} text=" 평가하지 않습니다" /></div>
          </div>
          {RULES.map(([title, body], i) => (
            <Rise key={title} frame={frame} start={40 + i * 12} distance={26} style={{position: 'absolute', left: 150 + i * 548, top: 330, width: 520, height: 330, boxSizing: 'border-box', background: P.white, border: `1px solid ${P.hair}`, borderRadius: 26, boxShadow: SHADOW, padding: '34px 36px'}}>
              <span style={{fontFamily: SERIF, fontStyle: 'italic', fontSize: 38, color: P.gold}}>{i + 1}</span>
              <div style={{marginTop: 20, fontSize: 33, fontWeight: 780, color: P.ink, letterSpacing: '-0.035em', whiteSpace: 'nowrap'}}>{title}</div>
              <div style={{marginTop: 14, fontSize: 21, fontWeight: 540, color: P.soft, lineHeight: 1.55, letterSpacing: -0.3}}>{body}</div>
            </Rise>
          ))}
          <Rise frame={frame} start={110} style={{position: 'absolute', left: 150, top: 740, fontSize: 19, fontWeight: 660, color: P.soft, letterSpacing: 0.2, whiteSpace: 'nowrap'}}>Pleos SDK에서 읽는 신호</Rise>
          <div style={{position: 'absolute', left: 150, top: 786, display: 'flex', gap: 10, whiteSpace: 'nowrap'}}>
            {SIGNALS.map((name, i) => (
              <Rise key={name} frame={frame} start={118 + i * 5}>
                <Pill style={{fontSize: 21, padding: '12px 20px', background: i < 3 ? P.ink : P.white, color: i < 3 ? P.white : P.ink, borderColor: i < 3 ? P.ink : P.hair}}>{name}</Pill>
              </Rise>
            ))}
          </div>
          <Rise frame={frame} start={176} style={{position: 'absolute', left: 150, top: 872, fontSize: 18, fontWeight: 560, color: P.faint, whiteSpace: 'nowrap'}}>
            에뮬레이터에서 확인한 내용입니다. 실차 동작과 한국어 음성의 실제 발화는 확인하지 못했습니다.
          </Rise>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── end ─────────────────────────
export const PEnd: React.FC = () => {
  const frame = useCurrentFrame();
  const total = PLEN.end;
  const cx = 960 + lead(frame, total + 400);
  return (
    <Shell total={total + 400} label="">
      <AbsoluteFill style={{opacity: 1 - enter(frame, total - 22, 20)}}>
        <World cx={cx} cy={540} scale={1}>
          <Paper />
          <div style={{position: 'absolute', left: 0, top: 300, width: 1920, textAlign: 'center'}}>
            <div style={{fontFamily: SERIF, fontWeight: 600, fontSize: 190, lineHeight: 1.05, color: P.ink, letterSpacing: -4}}><Reveal frame={frame} start={16} step={2.2} fade={10} text="Passer" /></div>
            <div style={{marginTop: 14, fontSize: 40, fontWeight: 680, color: P.ink, letterSpacing: '-0.03em'}}>
              <Reveal frame={frame} start={36} step={0.9} text="차를 넘겨받는 " /><Marked frame={frame} start={44} markAt={58} text="첫 3분" />
            </div>
            <Rise frame={frame} start={66} style={{marginTop: 44, fontSize: 21, fontWeight: 600, color: P.soft, letterSpacing: 0.2}}>인계 30초 · 실제 조작 미션 · 차 상태만 전후 비교</Rise>
          </div>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

export const PasserFilm: React.FC = () => (
  <AbsoluteFill style={{background: P.paper}}>
    <Series>
      <Series.Sequence durationInFrames={PLEN.title}><PTitle /></Series.Sequence>
      <Series.Sequence durationInFrames={PLEN.problem}><PProblem /></Series.Sequence>
      <Series.Sequence durationInFrames={PLEN.handover}><PHandover /></Series.Sequence>
      <Series.Sequence durationInFrames={PLEN.tour}><PTour /></Series.Sequence>
      <Series.Sequence durationInFrames={PLEN.back}><PReturn /></Series.Sequence>
      <Series.Sequence durationInFrames={PLEN.real}><PReal /></Series.Sequence>
      <Series.Sequence durationInFrames={PLEN.rules}><PRules /></Series.Sequence>
      <Series.Sequence durationInFrames={PLEN.end}><PEnd /></Series.Sequence>
    </Series>
  </AbsoluteFill>
);
