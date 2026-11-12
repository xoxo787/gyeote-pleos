import React from 'react';
import {AbsoluteFill, OffthreadVideo, Sequence, Series, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {CarSide} from './CarSide';
import {along, enter, kf, OUT, P, pathOf, Pill, Reveal, Rise, SANS, SERIF, SHADOW, SHADOW_SMALL, World} from './kit2';
import {Answer, APP, APP_NAME, AppScreen, Cable, CHARGER, Charger, FRONT_GLASS, Ground, GROUND, HEAD, lead, Marked, ORIGIN, Paper, REAR_DOOR, REAR_GLASS, Roll, Shell, Stat, Tile} from './parts';

/** Lengths in frames (30 fps). */
export const LEN = {title: 120, problem: 260, join: 360, play: 520, close: 250, real: 396, rules: 300, end: 130};
export const FILM_LENGTH = Object.values(LEN).reduce((a, b) => a + b, 0);

const Ornaments: React.FC<{cx: number; cy: number; scale: number}> = ({cx, cy, scale}) => (
  <World cx={cx} cy={cy} scale={scale} depth={0.5}>
    <div style={{position: 'absolute', left: 938, top: -200, fontFamily: SERIF, fontWeight: 600, fontSize: 420, lineHeight: 1, color: P.ornament, opacity: 0.3}}>P</div>
    <div style={{position: 'absolute', left: 2285, top: 410, fontFamily: SERIF, fontStyle: 'italic', fontWeight: 600, fontSize: 320, lineHeight: 1, color: P.ornament, opacity: 0.26}}>%</div>
  </World>
);

const GearPill: React.FC<{roll?: number}> = ({roll = 0}) => (
  <div style={{position: 'absolute', left: 452, top: 548}}>
    <Pill><span style={{color: P.soft, fontSize: 17}}>기어</span><Roll from="P" to="D" roll={roll} size={26} toColor="#E3566B" /></Pill>
  </div>
);

// ───────────────────────── title ─────────────────────────
export const Title: React.FC = () => {
  const frame = useCurrentFrame();
  const total = LEN.title;
  const cx = 960 + lead(frame, total) * (frame > 60 ? 1 : 0);
  return (
    <Shell total={total} label="">
      <AbsoluteFill>
        <World cx={cx} cy={540} scale={1}>
          <Paper />
          <div style={{position: 'absolute', left: 170, top: 250}}>
            <Rise frame={frame} start={6} style={{fontSize: 22, fontWeight: 640, color: P.soft, letterSpacing: 0.2, whiteSpace: 'nowrap'}}>Pleos Connect · 정차 전용 앱</Rise>
            <div style={{...HEAD, fontSize: 176, marginTop: 14}}><Reveal frame={frame} start={12} step={2} fade={10} text={APP_NAME} /></div>
            <div style={{fontSize: 40, fontWeight: 620, color: P.ink, letterSpacing: '-0.03em', marginTop: 20, whiteSpace: 'nowrap'}}>
              <Reveal frame={frame} start={34} step={0.8} text="충전하는 동안, 창문 스위치가 " />
              <Marked frame={frame} start={49} markAt={62} text="퀴즈 버저" />
              <Reveal frame={frame} start={54} step={0.8} text="가 됩니다." />
            </div>
          </div>
          <div style={{position: 'absolute', left: 980, top: 660, transform: `translateX(${(1 - enter(frame, 20, 34)) * 520}px) scale(0.66)`, transformOrigin: '0 0', opacity: enter(frame, 20, 12)}}>
            <CarSide />
          </div>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 01 problem ─────────────────────────
const PHONES: [number, number, string][] = [[800, 412, P.glassEdge], [950, 366, P.gold], [1100, 366, '#E3566B'], [1250, 412, '#8E96E8']];

export const Problem: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const total = LEN.problem;
  const cx = 1262 + lead(frame, total);
  const scale = kf(frame, [[0, 0.9], [total, 0.94]], (t) => t);
  return (
    <Shell total={total} index="01" title="충전하는 동안">
      <AbsoluteFill>
        <Ornaments cx={cx} cy={540} scale={scale} />
        <World cx={cx} cy={540} scale={scale}>
          <Paper />
          <Ground />
          <Charger />
          <Cable />
          <div style={{position: 'absolute', left: ORIGIN.x, top: ORIGIN.y}}><CarSide /></div>
          <GearPill />
          <svg style={{position: 'absolute', left: 0, top: 0, overflow: 'visible'}} width={10} height={10}>
            {PHONES.map(([x, y], i) => (
              <line key={i} x1={x} y1={y + 44} x2={x} y2={y + 44 + (496 - y - 44) * enter(frame, 22 + i * 7, 16)} stroke={P.ink} strokeWidth={1} strokeDasharray="2 5" opacity={0.5} />
            ))}
          </svg>
          {PHONES.map(([x, y, tint], i) => {
            const pop = spring({frame: frame - 18 - i * 7, fps, config: {damping: 12, stiffness: 150}});
            const bob = Math.sin((frame + i * 17) / 16) * 4;
            return (
              <div key={i} style={{position: 'absolute', left: x - 44, top: y - 44 + bob, width: 88, height: 88, borderRadius: 44, background: P.white, border: `1px solid ${P.hair}`, boxShadow: SHADOW_SMALL, transform: `scale(${pop})`, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                <div style={{width: 30, height: 52, borderRadius: 7, background: P.night, padding: 4, boxSizing: 'border-box', display: 'flex', flexDirection: 'column', gap: 3}}>
                  <div style={{height: 18, borderRadius: 3, background: tint}} />
                  <div style={{height: 4, borderRadius: 2, background: P.dim, width: '80%'}} />
                  <div style={{height: 4, borderRadius: 2, background: P.dim, width: '55%', opacity: 0.6}} />
                </div>
              </div>
            );
          })}
          <div style={{position: 'absolute', left: 372, top: 92}}>
            <div style={{...HEAD, fontSize: 80}}><Reveal frame={frame} start={16} text="같은 차에 앉아," /></div>
            <div style={{...HEAD, fontSize: 80}}>
              <Marked frame={frame} start={27} markAt={52} text="각자의" />
              <Reveal frame={frame} start={31} text=" 화면을 봅니다" />
            </div>
          </div>
          <Rise frame={frame} start={84} style={{position: 'absolute', left: 1800, top: 250, width: 440}}>
            <Stat figure={<>약 18<span style={{fontFamily: SANS, fontSize: 40, fontWeight: 760, marginLeft: 4}}>분</span></>} label="초급속 충전 10→80%" note="800V 전기차 기준 · 현대자동차 아이오닉 5 소개 자료" />
          </Rise>
          <Rise frame={frame} start={104} style={{position: 'absolute', left: 1800, top: 478, width: 440}}>
            <Stat figure="53.7%" label="충전하는 동안 차 안에 머뭅니다" note="영상·음악 31.4% + 휴식 22.3% · 충전 앱 이용자 1,532명 조사(2021년 보도)" />
          </Rise>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 02 join ─────────────────────────
const TAG = {x: 1300, y: 438, w: 196, h: 92};
const JOIN = {tap: 106, send: 140, land: 178};
const SIGNAL: [number, number][][] = [
  [[REAR_GLASS.x, REAR_GLASS.y - 34], [REAR_GLASS.x, 410], [1250, 330], [1500, 330]],
  [[1500, 330], [2150, 330], [2350, APP.y], [APP.x - APP.w / 2, APP.y]],
];

export const Join: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const total = LEN.join;
  const cx = kf(frame, [[0, 1020], [56, 1020], [80, 950], [150, 954], [176, 3350], [292, 3350], [322, 2150], [total, 2150]]) + lead(frame, total);
  const cy = kf(frame, [[0, 548], [56, 540], [80, 455], [150, 455], [176, 540], [292, 536], [322, 620], [total, 620]]);
  const scale = kf(frame, [[0, 0.9], [56, 0.92], [80, 1.8], [150, 1.87], [176, 1.0], [292, 1.04], [322, 0.5], [total, 0.512]]);

  const drop = spring({frame: frame - JOIN.tap, fps, config: {damping: 15, stiffness: 110}});
  const pushed = kf(frame, [[JOIN.tap - 5, 0], [JOIN.tap, 1], [JOIN.tap + 12, 0]], OUT);
  const ring = kf(frame, [[JOIN.tap - 1, 0], [JOIN.tap + 24, 1]], OUT);
  const roll = kf(frame, [[JOIN.tap + 6, 0], [JOIN.tap + 20, 1]], OUT);
  const t = kf(frame, [[JOIN.send, 0], [JOIN.land, 1]]);
  const dot = along(t, SIGNAL);
  const joined = spring({frame: frame - JOIN.land, fps, config: {damping: 13, stiffness: 140}});
  const isIn = frame >= JOIN.land;
  const tiles: Tile[] = [
    {status: '창문을 내리면 참가', kind: 'rest'},
    {status: '창문을 내리면 참가', kind: 'rest'},
    isIn ? {status: '참가!', kind: 'in', open: 0.34, stars: 0, pop: joined} : {status: '창문을 내리면 참가', kind: 'rest', open: 0.34 * drop},
    {status: '창문을 내리면 참가', kind: 'rest'},
  ];

  return (
    <Shell total={total} travels={[[57, 80], [151, 176], [293, 322]]} index="02" title="창문이 버저">
      <AbsoluteFill>
        <Ornaments cx={cx} cy={cy} scale={scale} />
        <World cx={cx} cy={cy} scale={scale}>
          <Paper />
          <Ground />
          <Charger />
          <Cable />
          <div style={{position: 'absolute', left: ORIGIN.x, top: ORIGIN.y}}><CarSide rearDrop={0.44 * drop} /></div>
          <GearPill />

          <div style={{position: 'absolute', left: 372, top: 150, opacity: 1 - enter(frame, 50, 10)}}>
            <div style={{...HEAD, fontSize: 92}}><Reveal frame={frame} start={12} text="차를 세운 채로," /></div>
            <Rise frame={frame} start={28} style={{marginTop: 14, fontSize: 30, fontWeight: 560, color: P.soft, letterSpacing: -0.5, whiteSpace: 'nowrap'}}>기어는 P, 충전기는 꽂혀 있습니다.</Rise>
          </div>

          <div style={{position: 'absolute', left: 540, top: 268}}>
            <div style={{...HEAD, fontSize: 56}}><Reveal frame={frame} start={84} text="뒷좌석 창문을" /></div>
            <div style={{...HEAD, fontSize: 56}}>
              <Marked frame={frame} start={93} markAt={JOIN.tap + 6} text="한 칸" />
              <Reveal frame={frame} start={97} text=" 내리면" />
            </div>
          </div>

          <div style={{position: 'absolute', left: REAR_DOOR.x - 60, top: REAR_DOOR.y - 20, opacity: enter(frame, 86, 14), transform: `translateY(${(1 - enter(frame, 86, 16)) * 10}px)`}}>
            <div style={{position: 'relative', display: 'flex', alignItems: 'center', gap: 9, background: P.white, borderRadius: 999, padding: '7px 13px 7px 9px', boxShadow: SHADOW_SMALL, fontSize: 12.5, fontWeight: 640, color: P.ink, whiteSpace: 'nowrap'}}>
              <span style={{position: 'relative', width: 30, height: 18, borderRadius: 6, background: '#EEF0F6', border: `1px solid ${P.hair}`}}>
                <span style={{position: 'absolute', left: 5, right: 5, top: 3.5 + 4 * pushed, height: 6, borderRadius: 3, background: P.ink}} />
                <span style={{position: 'absolute', left: 15 - (8 + 26 * ring), top: 9 - (8 + 26 * ring), width: (8 + 26 * ring) * 2, height: (8 + 26 * ring) * 2, borderRadius: '50%', border: `1.5px solid ${P.gold}`, opacity: ring > 0 && ring < 1 ? 1 - ring : 0}} />
              </span>
              창문 스위치
            </div>
          </div>

          <svg style={{position: 'absolute', left: 0, top: 0, overflow: 'visible', opacity: enter(frame, 92, 14)}} width={10} height={10}>
            <path d={`M ${REAR_GLASS.x + 70} ${REAR_GLASS.y - 20} L ${TAG.x - TAG.w / 2 + 2} ${TAG.y + 8}`} stroke={P.ink} strokeWidth={1} strokeDasharray="2 5" opacity={0.6} />
            <circle cx={REAR_GLASS.x + 68} cy={REAR_GLASS.y - 19} r={4} fill={P.white} stroke={P.ink} strokeWidth={1.4} />
          </svg>
          <div style={{position: 'absolute', left: TAG.x - TAG.w / 2, top: TAG.y - TAG.h / 2, width: TAG.w, height: TAG.h, boxSizing: 'border-box', opacity: enter(frame, 94, 16), transform: `translateY(${(1 - enter(frame, 94, 18)) * 12}px)`, background: P.white, border: `1px solid ${P.hair}`, borderRadius: 16, boxShadow: SHADOW, display: 'flex', alignItems: 'center', padding: '0 18px', gap: 14}}>
            <div style={{flex: 1}}>
              <div style={{fontSize: 14, fontWeight: 660, color: P.ink, letterSpacing: -0.2}}>창문 위치</div>
              <div style={{fontSize: 11, fontWeight: 600, color: P.faint, marginTop: 4, whiteSpace: 'nowrap'}}>차가 알려 주는 값</div>
            </div>
            <Roll from="0" to="1" roll={roll} size={54} />
          </div>

          <svg style={{position: 'absolute', left: 0, top: 0, overflow: 'visible'}} width={10} height={10}>
            <path d={pathOf(SIGNAL)} fill="none" stroke={P.ink} strokeWidth={1.6} strokeLinecap="round" pathLength={1} strokeDasharray="1 1" strokeDashoffset={1 - t} opacity={0.55} />
            {frame >= JOIN.send && frame < JOIN.land + 3 && (
              <>
                <circle cx={dot[0]} cy={dot[1]} r={18} fill={P.gold} opacity={0.2} />
                <circle cx={dot[0]} cy={dot[1]} r={7} fill={P.gold} />
              </>
            )}
            <circle cx={APP.x - APP.w / 2} cy={APP.y} r={5.5} fill={P.white} stroke={P.ink} strokeWidth={1.5} opacity={isIn ? 1 : 0} />
          </svg>

          <div style={{position: 'absolute', left: APP.x - APP.w / 2 + 4, top: 96}}>
            <div style={{...HEAD, fontSize: 78}}>
              <Reveal frame={frame} start={JOIN.land + 4} text="그 자리가 " />
              <Marked frame={frame} start={JOIN.land + 10} markAt={JOIN.land + 22} text="참가" />
              <Reveal frame={frame} start={JOIN.land + 13} text="합니다" />
            </div>
          </div>
          <AppScreen tiles={tiles} kicker="충전 중 78% · 배터리가 1% 오를 때마다 새 문제가 와요" headline={<>함께할 자리는<br />창문을 한 칸 내려 주세요</>}
            sub={<>창문은 한 칸이면 돼요 · 올릴 때는 천천히<br />기어를 옮기면 바로 닫혀요</>} button="시작" buttonIn={enter(frame, JOIN.land + 26, 16)} />

          <div style={{position: 'absolute', left: 2150 - 1100, top: 1196, width: 2200, textAlign: 'center'}}>
            <div style={{...HEAD, fontSize: 112}}><Reveal frame={frame} start={318} step={1.2} text="네 자리 모두, 각자의 스위치로" /></div>
          </div>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 03 play ─────────────────────────
const PLAY = {toCar: 82, lower: 122, toReveal: 196, toCharger: 292, tick: 336, toBuzz: 376, go: 430, win: 456};
const QUESTION = '지금 이 차의 배터리는 절반보다 많이 남아 있다.';

export const Play: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const total = LEN.play;
  const stops: [number, number, number, number][] = [
    [0, APP.x, 540, 1.0], [PLAY.toCar, APP.x, 540, 1.03],
    [PLAY.toCar + 22, 1010, 520, 1.5], [PLAY.toReveal, 1014, 520, 1.55],
    [PLAY.toReveal + 22, APP.x, 540, 1.0], [PLAY.toCharger, APP.x, 540, 1.03],
    [PLAY.toCharger + 22, 1760, 610, 1.9], [PLAY.toBuzz, 1764, 610, 1.97],
    [PLAY.toBuzz + 22, APP.x, 540, 1.0], [total, APP.x, 540, 1.04],
  ];
  const cx = kf(frame, stops.map((s) => [s[0], s[1]])) + lead(frame, total);
  const cy = kf(frame, stops.map((s) => [s[0], s[2]]));
  const scale = kf(frame, stops.map((s) => [s[0], s[3]]));

  const lowered = spring({frame: frame - PLAY.lower, fps, config: {damping: 15, stiffness: 110}});
  const raised = spring({frame: frame - (PLAY.toCharger + 4), fps, config: {damping: 18, stiffness: 90}});
  const buzz = spring({frame: frame - PLAY.win, fps, config: {damping: 13, stiffness: 150}});
  const glassDrop = frame < PLAY.toBuzz ? lowered * (1 - raised) : frame >= PLAY.win ? buzz : 0;
  const tick = kf(frame, [[PLAY.tick, 0], [PLAY.tick + 16, 1]], OUT);
  const revealPop = spring({frame: frame - (PLAY.toReveal + 20), fps, config: {damping: 12, stiffness: 150}});

  // what the app shows, by phase
  let screen: React.ComponentProps<typeof AppScreen>;
  if (frame < PLAY.toReveal + 12) {
    const answered = frame >= PLAY.lower + 10;
    screen = {
      tiles: [
        {status: '그대로 두면 X', kind: 'idle', stars: 1},
        {status: '쉬는 자리', kind: 'rest'},
        answered ? {status: 'O를 골랐어요', kind: 'o', open: 0.34, stars: 1} : {status: '그대로 두면 X', kind: 'idle', stars: 1},
        {status: '쉬는 자리', kind: 'rest'},
      ],
      kicker: '4번째 판 · 차가 낸 문제', kickerColor: P.cream, headline: QUESTION, headlineSize: 46,
      sub: '맞으면 창문을 한 칸 내리고, 아니면 그대로 두세요.', bar: 1 - frame / (PLAY.toReveal + 12),
    };
  } else if (frame < PLAY.toBuzz + 12) {
    screen = {
      tiles: [
        {status: '아쉬워요', kind: 'miss', stars: 1},
        {status: '쉬는 자리', kind: 'rest'},
        {status: '★ 맞혔어요', kind: 'win', open: 0.34 * (1 - raised), stars: 2, pop: revealPop},
        {status: '쉬는 자리', kind: 'rest'},
      ],
      kicker: QUESTION, kickerColor: P.star, headline: 'O · 맞는 말이에요', headlineSize: 60, sub: '지금 배터리는 78%예요.',
    };
  } else if (frame < PLAY.go) {
    screen = {
      tiles: [{status: '준비됐어요', kind: 'idle', stars: 1}, {status: '쉬는 자리', kind: 'rest'}, {status: '준비됐어요', kind: 'idle', stars: 2}, {status: '쉬는 자리', kind: 'rest'}],
      kicker: '5번째 판 · 번개 버저', kickerColor: P.cream, headline: '준비…', headlineSize: 84, sub: '‘지금!’이 뜨면 창문을 한 칸 내리세요.',
    };
  } else if (frame < PLAY.win) {
    screen = {
      tiles: [{status: '지금!', kind: 'go', stars: 1}, {status: '쉬는 자리', kind: 'rest'}, {status: '지금!', kind: 'go', stars: 2}, {status: '쉬는 자리', kind: 'rest'}],
      kicker: '5번째 판 · 번개 버저', kickerColor: P.cream, headline: '지금!', headlineSize: 132, headlineColor: P.star, sub: '가장 먼저 내린 자리가 별을 받아요.', bar: 1 - (frame - PLAY.go) / 90,
    };
  } else {
    screen = {
      tiles: [{status: '준비됐어요', kind: 'idle', stars: 1}, {status: '쉬는 자리', kind: 'rest'}, {status: '★ 가장 빨랐어요', kind: 'win', open: 0.34 * buzz, stars: 3, pop: buzz}, {status: '쉬는 자리', kind: 'rest'}],
      kicker: '번개 버저', kickerColor: P.star, headline: '뒤 왼쪽이 가장 빨랐어요', headlineSize: 54,
    };
  }

  const headC = PLAY.toReveal + 26;
  const headE = PLAY.toBuzz + 26;
  return (
    <Shell total={total} travels={[[PLAY.toCar + 1, PLAY.toCar + 22], [PLAY.toReveal + 1, PLAY.toReveal + 22], [PLAY.toCharger + 1, PLAY.toCharger + 22], [PLAY.toBuzz + 1, PLAY.toBuzz + 22]]} index="03" title="문제와 버저">
      <AbsoluteFill>
        <Ornaments cx={cx} cy={cy} scale={scale} />
        <World cx={cx} cy={cy} scale={scale}>
          <Paper />
          <Ground />
          <Charger roll={tick} />
          <Cable />
          <div style={{position: 'absolute', left: ORIGIN.x, top: ORIGIN.y}}><CarSide rearDrop={0.44 * glassDrop} /></div>
          <GearPill />

          {/* above the app: one headline per visit */}
          <div style={{position: 'absolute', left: APP.x - APP.w / 2 + 4, top: 96}}>
            {frame < PLAY.toReveal && (
              <div style={{...HEAD, fontSize: 78}}>
                <Reveal frame={frame} start={16} text="차가 " /><Marked frame={frame} start={20} markAt={38} text="문제" /><Reveal frame={frame} start={23} text="를 냅니다" />
              </div>
            )}
            {frame >= PLAY.toReveal && frame < PLAY.toBuzz && (
              <div style={{...HEAD, fontSize: 78}}>
                <Reveal frame={frame} start={headC} text="맞힌 자리가 " /><Marked frame={frame} start={headC + 8} markAt={headC + 20} text="별" /><Reveal frame={frame} start={headC + 9} text="을 받습니다" />
              </div>
            )}
            {frame >= PLAY.toBuzz && (
              <div style={{...HEAD, fontSize: 78}}>
                <Reveal frame={frame} start={headE} text="세 판마다 " /><Marked frame={frame} start={headE + 6} markAt={headE + 20} text="번개 버저" />
              </div>
            )}
          </div>

          {/* at the car */}
          <div style={{position: 'absolute', left: 470, top: 226}}>
            <div style={{...HEAD, fontSize: 60}}><Reveal frame={frame} start={PLAY.toCar + 26} text="내리면 " /><Marked frame={frame} start={PLAY.toCar + 31} markAt={PLAY.lower + 8} text="O," /></div>
            <div style={{...HEAD, fontSize: 60}}><Reveal frame={frame} start={PLAY.toCar + 36} text="그대로 두면 X" /></div>
          </div>
          <Rise frame={frame} start={PLAY.lower + 12} style={{position: 'absolute', left: REAR_GLASS.x + 96, top: 404, visibility: frame > PLAY.toReveal + 22 ? 'hidden' : 'visible'}}>
            <Answer seat="뒤 왼쪽" mark="O" note="창문을 한 칸 내림" />
          </Rise>
          <Rise frame={frame} start={PLAY.lower + 34} style={{position: 'absolute', left: FRONT_GLASS.x - 250, top: 438, visibility: frame > PLAY.toReveal + 22 ? 'hidden' : 'visible'}}>
            <Answer seat="운전석" mark="X" note="그대로 둠" />
          </Rise>

          {/* at the charger */}
          <div style={{position: 'absolute', left: 1800, top: 492}}>
            <div style={{...HEAD, fontSize: 44}}><Reveal frame={frame} start={PLAY.toCharger + 26} text="배터리가" /></div>
            <div style={{...HEAD, fontSize: 44}}><Marked frame={frame} start={PLAY.toCharger + 32} markAt={PLAY.tick + 6} text="1% 오르면" /></div>
            <Rise frame={frame} start={PLAY.tick + 10} style={{marginTop: 12, fontSize: 21, fontWeight: 600, color: P.soft, letterSpacing: -0.4, whiteSpace: 'nowrap'}}>다음 문제가 옵니다.</Rise>
          </div>

          <AppScreen {...screen} />
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 04 close ─────────────────────────
const CLOSE = {shift: 52, toApp: 100};

export const Close: React.FC = () => {
  const frame = useCurrentFrame();
  const total = LEN.close;
  const cx = kf(frame, [[0, 1040], [CLOSE.toApp, 1040], [CLOSE.toApp + 22, APP.x], [total, APP.x]]) + lead(frame, total);
  const cy = kf(frame, [[0, 540], [CLOSE.toApp, 540], [CLOSE.toApp + 22, 540], [total, 540]]);
  const scale = kf(frame, [[0, 0.92], [CLOSE.toApp, 0.96], [CLOSE.toApp + 22, 1.0], [total, 1.04]]);
  const unplug = 1 - enter(frame, 18, 26);
  const gear = kf(frame, [[CLOSE.shift, 0], [CLOSE.shift + 14, 1]], OUT);
  const roll = kf(frame, [[CLOSE.shift + 16, 0], [CLOSE.toApp + 20, 1]], (t) => t * t);
  const headB = CLOSE.toApp + 26;
  const dim: Tile[] = ['', '', '', ''].map(() => ({status: '', kind: 'rest'}));
  return (
    <Shell total={total} travels={[[CLOSE.toApp + 1, CLOSE.toApp + 22]]} index="04" title="출발하면 닫힘">
      <AbsoluteFill>
        <Ornaments cx={cx} cy={cy} scale={scale} />
        <World cx={cx} cy={cy} scale={scale}>
          <Paper />
          <Ground />
          <Charger from="80" to="80" />
          <Cable plugged={unplug} />
          <div style={{position: 'absolute', left: ORIGIN.x - 260 * roll, top: ORIGIN.y}}><CarSide charging={unplug} /></div>
          <div style={{position: 'absolute', left: -260 * roll, top: 0}}><GearPill roll={gear} /></div>

          <div style={{position: 'absolute', left: 372, top: 150}}>
            <div style={{...HEAD, fontSize: 92}}><Reveal frame={frame} start={14} text="기어를 " /><Marked frame={frame} start={19} markAt={CLOSE.shift + 6} text="옮기면," /></div>
            <Rise frame={frame} start={30} style={{marginTop: 14, fontSize: 30, fontWeight: 560, color: P.soft, letterSpacing: -0.5, whiteSpace: 'nowrap'}}>P를 벗어나는 그 순간</Rise>
          </div>

          <div style={{position: 'absolute', left: APP.x - APP.w / 2 + 4, top: 96}}>
            <div style={{...HEAD, fontSize: 78}}>
              <Reveal frame={frame} start={headB} text="놀이는 " /><Marked frame={frame} start={headB + 5} markAt={headB + 18} text="즉시" /><Reveal frame={frame} start={headB + 8} text=" 닫힙니다" />
            </div>
          </div>
          {/* What is really seen in D: the car's own lock screen covers the app (emulator capture 08). */}
          <AppScreen tiles={dim} kicker="차의 화면 잠금" kickerColor="#EF767A" headline="주행 중에는 사용할 수 없습니다." headlineSize={46}
            sub={<>앱도 같은 순간 놀이를 닫고,<br />지금까지 모은 별 4개를 저장합니다.</>} />
          <Rise frame={frame} start={headB + 34} style={{position: 'absolute', left: APP.x - APP.w / 2 + 4, top: APP.y + APP.h / 2 + 34}}>
            <Pill style={{fontSize: 22, padding: '12px 22px'}}><span style={{width: 8, height: 8, borderRadius: 4, background: P.gold}} />차의 화면 잠금과 앱의 종료, 두 겹입니다 · 이후의 창문 조작은 받지 않습니다</Pill>
          </Rise>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 05 real screens ─────────────────────────
/** Clips cut from one screen recording of the emulator while the integration script drove the Virtual Console. [file, caption, first frame, frames] */
const CLIPS: [string, string, number, number][] = [
  ['c1-join', '창문으로 참가', 9, 81],
  ['c2-reveal', 'O를 고른 자리가 별', 12, 84],
  ['c3-buzzer', '번개 버저', 18, 84],
  ['c4-drive', '주행 중에는 차가 화면을 잠금', 27, 93],
];
const CLIP_AT = 24;
const clipStart = (i: number) => CLIP_AT + CLIPS.slice(0, i).reduce((sum, c) => sum + c[3], 0);
const REAL_LENGTH = clipStart(CLIPS.length) + 30;

export const Real: React.FC = () => {
  const frame = useCurrentFrame();
  const total = LEN.real;
  const cx = 960 + lead(frame, total);
  const index = Math.max(0, CLIPS.findIndex((_, i) => frame < clipStart(i + 1)));
  const shown = frame >= clipStart(CLIPS.length) ? CLIPS.length - 1 : index;
  return (
    <Shell total={total} index="05" title="실제 화면" label="Pleos Connect 에뮬레이터 화면 녹화 · 이미지 v2.1.0">
      <AbsoluteFill>
        <World cx={cx} cy={540} scale={1}>
          <Paper />
          <div style={{position: 'absolute', left: 150, top: 132}}>
            <div style={{...HEAD, fontSize: 80}}><Marked frame={frame} start={14} markAt={30} text="실제로" /><Reveal frame={frame} start={18} text=" 동작합니다" /></div>
          </div>
          <div style={{position: 'absolute', left: 150, top: 268, width: 1188, height: 668, borderRadius: 26, background: P.white, padding: 12, boxSizing: 'border-box', boxShadow: SHADOW, border: `1px solid ${P.hair}`}}>
            <div style={{position: 'relative', width: '100%', height: '100%', borderRadius: 16, overflow: 'hidden', background: P.night}}>
              {CLIPS.map(([file, , first, length], i) => (
                <Sequence key={file} from={i === 0 ? 0 : clipStart(i)} durationInFrames={i === 0 ? clipStart(1) : i === CLIPS.length - 1 ? length + 400 : length} layout="none">
                  <OffthreadVideo muted src={staticFile(`clips/${file}.mp4`)} trimBefore={first} style={{position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover'}} />
                </Sequence>
              ))}
            </div>
          </div>
          <div style={{position: 'absolute', left: 150, top: 960, display: 'flex', gap: 12, whiteSpace: 'nowrap'}}>
            {CLIPS.map(([, caption], i) => (
              <Rise key={caption} frame={frame} start={30 + i * 5}>
                <Pill style={{background: i === shown ? P.ink : P.white, color: i === shown ? P.white : P.soft, borderColor: i === shown ? P.ink : P.hair}}>
                  <span style={{fontFamily: SERIF, fontStyle: 'italic', fontSize: 19}}>{i + 1}</span>{caption}
                </Pill>
              </Rise>
            ))}
          </div>
          <Rise frame={frame} start={40} style={{position: 'absolute', left: 1410, top: 268, width: 360}}><Stat figure="18/18" label="에뮬레이터 통합 검증" note="Virtual Console로 차량 값을 바꿔 가며 확인" /></Rise>
          <Rise frame={frame} start={52} style={{position: 'absolute', left: 1410, top: 494, width: 360}}><Stat figure="65" label="단위 테스트 통과" note="놀이 규칙 전부를 시계 없이 재현" /></Rise>
          <Rise frame={frame} start={64} style={{position: 'absolute', left: 1410, top: 720, width: 360}}><Stat figure="3" label="차량 권한" note="기어 · 창문 위치 · 배터리. 모두 읽기 전용" /></Rise>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── 06 rules ─────────────────────────
const RULES: [string, string, React.ReactNode][] = [
  ['내리는 동작만 입력', '올리는 동작은 놀이에 쓰지 않습니다. 끼임은 창문이 올라갈 때 생기기 때문입니다.',
    <path key="a" d="M 24 8 V 36 M 12 26 L 24 38 L 36 26" fill="none" stroke={P.ink} strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" />],
  ['재촉하지 않습니다', '다음 문제는 창문이 올라올 때까지 기다립니다. 올리는 데는 시간 제한도 보상도 없습니다.',
    <g key="b" fill="none" stroke={P.ink} strokeWidth={3.5} strokeLinecap="round"><circle cx={24} cy={24} r={16} /><path d="M 24 14 V 24 L 31 29" /></g>],
  ['앱은 창문을 움직이지 않습니다', '차량 신호는 읽기만 합니다. 기어가 P를 벗어나면 즉시 닫힙니다.',
    <g key="c" fill="none" stroke={P.ink} strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round"><path d="M 6 24 C 12 13, 36 13, 42 24 C 36 35, 12 35, 6 24 Z" /><circle cx={24} cy={24} r={5.5} fill={P.gold} stroke="none" /></g>],
];
const SIGNALS = ['기어', '창문 위치 × 4', '배터리 %', '충전 스위치', '충전구', '주행 가능 거리', '누적 주행거리'];

export const Rules: React.FC = () => {
  const frame = useCurrentFrame();
  const total = LEN.rules;
  const cx = 960 + lead(frame, total);
  return (
    <Shell total={total} index="06" title="안전 규칙" label="">
      <AbsoluteFill>
        <World cx={cx} cy={540} scale={kf(frame, [[0, 1], [total, 1.025]], (t) => t)}>
          <Paper />
          <div style={{position: 'absolute', left: 150, top: 150}}>
            <div style={{...HEAD, fontSize: 80}}><Reveal frame={frame} start={14} text="안전은 " /><Marked frame={frame} start={19} markAt={36} text="규칙" /><Reveal frame={frame} start={22} text="으로 넣었습니다" /></div>
          </div>
          {RULES.map(([title, body, icon], i) => (
            <Rise key={title} frame={frame} start={40 + i * 12} distance={26} style={{position: 'absolute', left: 150 + i * 548, top: 330, width: 520, height: 330, boxSizing: 'border-box', background: P.white, border: `1px solid ${P.hair}`, borderRadius: 26, boxShadow: SHADOW, padding: '34px 36px'}}>
              <div style={{display: 'flex', alignItems: 'center', gap: 16}}>
                <span style={{fontFamily: SERIF, fontStyle: 'italic', fontSize: 30, color: P.faint}}>{i + 1}</span>
                <svg width={48} height={48} viewBox="0 0 48 48">{icon}</svg>
              </div>
              <div style={{marginTop: 26, fontSize: 33, fontWeight: 780, color: P.ink, letterSpacing: '-0.035em', whiteSpace: 'nowrap'}}>{title}</div>
              <div style={{marginTop: 14, fontSize: 21, fontWeight: 540, color: P.soft, lineHeight: 1.55, letterSpacing: -0.3}}>{body}</div>
            </Rise>
          ))}
          <Rise frame={frame} start={110} style={{position: 'absolute', left: 150, top: 740, fontSize: 19, fontWeight: 660, color: P.soft, letterSpacing: 0.2, whiteSpace: 'nowrap'}}>Pleos Vehicle SDK에서 읽는 신호</Rise>
          <div style={{position: 'absolute', left: 150, top: 786, display: 'flex', gap: 12, whiteSpace: 'nowrap'}}>
            {SIGNALS.map((name, i) => (
              <Rise key={name} frame={frame} start={118 + i * 5}>
                <Pill style={{fontSize: 23, padding: '13px 24px', background: i < 4 ? P.ink : P.white, color: i < 4 ? P.white : P.ink, borderColor: i < 4 ? P.ink : P.hair}}>{name}</Pill>
              </Rise>
            ))}
          </div>
          <Rise frame={frame} start={170} style={{position: 'absolute', left: 150, top: 874, fontSize: 18, fontWeight: 560, color: P.faint, whiteSpace: 'nowrap'}}>
            에뮬레이터에서 확인한 내용입니다. 실차에서의 신호 지연과 한국어 음성은 확인하지 못했습니다.
          </Rise>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── end ─────────────────────────
export const End: React.FC = () => {
  const frame = useCurrentFrame();
  const total = LEN.end;
  const cx = 960 + lead(frame, total + 400);
  return (
    <Shell total={total + 400} label="">
      <AbsoluteFill style={{opacity: 1 - enter(frame, total - 22, 20)}}>
        <World cx={cx} cy={540} scale={1}>
          <Paper />
          <div style={{position: 'absolute', left: 0, top: 330, width: 1920, textAlign: 'center'}}>
            <div style={{...HEAD, fontSize: 150}}><Reveal frame={frame} start={16} step={2} fade={10} text={APP_NAME} /></div>
            <div style={{marginTop: 22, fontSize: 38, fontWeight: 620, color: P.ink, letterSpacing: '-0.03em'}}>
              <Reveal frame={frame} start={36} step={0.8} text="충전하는 동안, 창문이 " /><Marked frame={frame} start={48} markAt={60} text="버저" /><Reveal frame={frame} start={50} step={0.8} text="가 됩니다." />
            </div>
            <Rise frame={frame} start={66} style={{marginTop: 46, fontSize: 21, fontWeight: 600, color: P.soft, letterSpacing: 0.2}}>Pleos Connect · 정차 전용 앱 · 기어를 옮기면 즉시 닫힙니다</Rise>
          </div>
        </World>
      </AbsoluteFill>
    </Shell>
  );
};

// ───────────────────────── the whole film ─────────────────────────
export const Film: React.FC = () => (
  <AbsoluteFill style={{background: P.paper}}>
    <Series>
      <Series.Sequence durationInFrames={LEN.title}><Title /></Series.Sequence>
      <Series.Sequence durationInFrames={LEN.problem}><Problem /></Series.Sequence>
      <Series.Sequence durationInFrames={LEN.join}><Join /></Series.Sequence>
      <Series.Sequence durationInFrames={LEN.play}><Play /></Series.Sequence>
      <Series.Sequence durationInFrames={LEN.close}><Close /></Series.Sequence>
      <Series.Sequence durationInFrames={LEN.real}><Real /></Series.Sequence>
      <Series.Sequence durationInFrames={LEN.rules}><Rules /></Series.Sequence>
      <Series.Sequence durationInFrames={LEN.end}><End /></Series.Sequence>
    </Series>
  </AbsoluteFill>
);
