import React from 'react';
import {AbsoluteFill, Easing, interpolate, useCurrentFrame} from 'remotion';
import {CameraMotionBlur} from '@remotion/motion-blur';
import {CAR_BOX, CAR_POINTS} from './CarSide';
import {enter, Glass, P, Reveal, SANS, SERIF, SHADOW, SHADOW_SMALL} from './kit2';

/** App name, decided by the participant on 2026-11-11. Title and end card read it from here. */
export const APP_NAME = '충전오락관';

// ---- one shared map of the world, so every scene puts the car, the charger and the app in the same place ----
export const ORIGIN = {x: 360, y: 430};
export const car = (p: {x: number; y: number}) => ({x: ORIGIN.x + p.x, y: ORIGIN.y + p.y});
export const GROUND = ORIGIN.y + CAR_BOX.ground;
export const REAR_GLASS = car(CAR_POINTS.rearGlass);
export const FRONT_GLASS = car({x: 520, y: 128});
export const REAR_DOOR = car(CAR_POINTS.rearDoorPanel);
export const PORT = car(CAR_POINTS.port);
export const CHARGER = {x: 1728, top: GROUND - 290, w: 78};
export const APP = {x: 3350, y: 600, w: 1180, h: 700};
const TILE = {w: 196, h: 280, gap: 18, x0: APP.x - APP.w / 2 + 46, y0: APP.y - APP.h / 2 + 61};

export const HEAD: React.CSSProperties = {fontFamily: SANS, fontWeight: 800, color: P.ink, letterSpacing: '-0.04em', lineHeight: 1.16, whiteSpace: 'nowrap'};

// ---- scene shell: paper, scene mark, and motion blur only while the camera travels ----
const LEAD_IN = 14;
const LEAD_OUT = 13;
const WHIP = 2700;

/** Horizontal camera offset that whips each scene in from the left and out to the right, hiding the cut. */
export const lead = (frame: number, total: number) =>
  interpolate(frame, [0, LEAD_IN], [-WHIP, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.bezier(0.16, 1, 0.3, 1)}) +
  interpolate(frame, [total - LEAD_OUT, total], [0, WHIP], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.bezier(0.7, 0, 0.84, 0)});

export const Shell: React.FC<{total: number; travels?: [number, number][]; index?: string; title?: string; label?: string; children: React.ReactNode}> = ({total, travels = [], index, title, label = '설명용 그림', children}) => {
  const frame = useCurrentFrame();
  const moving = frame <= LEAD_IN - 4 || frame >= total - LEAD_OUT || travels.some(([from, to]) => frame >= from && frame <= to);
  const furniture = Math.min(enter(frame, 6, 16), 1 - enter(frame, total - 14, 10));
  return (
    <AbsoluteFill style={{background: P.paper, fontFamily: SANS, overflow: 'hidden'}}>
      {moving ? (
        <CameraMotionBlur shutterAngle={200} samples={10}>
          {children}
        </CameraMotionBlur>
      ) : (
        children
      )}
      {index && (
        <div style={{position: 'absolute', left: 72, top: 60, display: 'flex', alignItems: 'center', gap: 16, opacity: furniture}}>
          <span style={{fontFamily: SERIF, fontStyle: 'italic', fontSize: 34, color: P.ink, lineHeight: 1}}>{index}</span>
          <span style={{width: 44, height: 1, background: P.ink, opacity: 0.35}} />
          <span style={{fontSize: 21, fontWeight: 640, color: P.ink, letterSpacing: -0.2}}>{title}</span>
        </div>
      )}
      {label && <div style={{position: 'absolute', right: 72, top: 68, fontSize: 16, fontWeight: 600, color: P.faint, letterSpacing: 0.3, opacity: furniture}}>{label}</div>}
    </AbsoluteFill>
  );
};

export const Paper: React.FC = () => (
  <div style={{position: 'absolute', left: -6000, top: -3000, width: 16000, height: 7600, backgroundImage: `radial-gradient(${P.dot} 1.6px, transparent 2px)`, backgroundSize: '46px 46px'}} />
);

export const Ground: React.FC = () => (
  <div style={{position: 'absolute', left: 120, top: GROUND, width: 1900, height: 1.5, background: `linear-gradient(90deg, transparent, ${P.faint} 12%, ${P.faint} 88%, transparent)`, opacity: 0.7}} />
);

/** A word with a gold marker stroke that draws in under it. */
export const Marked: React.FC<{frame: number; start: number; markAt: number; text: string}> = ({frame, start, markAt, text}) => (
  <span style={{backgroundImage: `linear-gradient(${P.gold}, ${P.gold})`, backgroundRepeat: 'no-repeat', backgroundPosition: '0 88%', backgroundSize: `${enter(frame, markAt, 18) * 100}% 0.22em`}}>
    <Reveal frame={frame} start={start} text={text} />
  </span>
);

/** Serif figure that rolls from one value to the next. `roll` is 0..1. */
export const Roll: React.FC<{from: string; to: string; roll: number; size: number; color?: string; toColor?: string}> = ({from, to, roll, size, color = P.ink, toColor = P.gold}) => (
  <div style={{height: size * 1.08, overflow: 'hidden', fontFamily: SERIF, fontSize: size, lineHeight: `${size * 1.08}px`, color}}>
    <div style={{transform: `translateY(${-size * 1.08 * roll}px)`}}>
      <div>{from}</div>
      <div style={{color: toColor}}>{to}</div>
    </div>
  </div>
);

export const Charger: React.FC<{from?: string; to?: string; roll?: number}> = ({from = '78', to = '79', roll = 0}) => {
  const frame = useCurrentFrame();
  return (
    <div style={{position: 'absolute', left: CHARGER.x - CHARGER.w / 2, top: CHARGER.top, width: CHARGER.w, height: GROUND - CHARGER.top, borderRadius: '16px 16px 4px 4px', background: P.white, border: `1px solid ${P.hair}`, boxShadow: SHADOW, display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 14, gap: 12}}>
      <div style={{width: 54, height: 66, borderRadius: 9, background: P.night, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center'}}>
        <Roll from={from} to={to} roll={roll} size={27} color={P.star} toColor={P.star} />
        <span style={{fontSize: 11, fontWeight: 700, color: P.dim, marginTop: 1}}>%</span>
      </div>
      <div style={{width: 40, height: 4, borderRadius: 2, background: P.gold, opacity: 0.55 + 0.45 * Math.sin(frame / 7)}} />
    </div>
  );
};

const CABLE = `M ${PORT.x + 12} ${PORT.y + 4} C ${PORT.x + 70} ${PORT.y + 50}, ${PORT.x + 96} ${GROUND - 8}, ${PORT.x + 190} ${GROUND - 8} C ${CHARGER.x - 90} ${GROUND - 8}, ${CHARGER.x - 44} ${GROUND - 40}, ${CHARGER.x - 39} ${GROUND - 96}`;

/** `plugged` 0..1 draws the cable from the charger side back to the car. */
export const Cable: React.FC<{plugged?: number}> = ({plugged = 1}) => {
  const frame = useCurrentFrame();
  return (
    <svg style={{position: 'absolute', left: 0, top: 0, overflow: 'visible'}} width={10} height={10}>
      <path d={CABLE} fill="none" stroke={P.ink} strokeWidth={6} strokeLinecap="round" pathLength={1} strokeDasharray="1 1" strokeDashoffset={-(1 - plugged)} />
      <path d={CABLE} fill="none" stroke={P.gold} strokeWidth={2} strokeLinecap="round" strokeDasharray="10 44" strokeDashoffset={frame * 2.6} opacity={plugged >= 1 ? 1 : 0} />
    </svg>
  );
};

// ---- the app screen, drawn in its own colours ----
export type TileKind = 'rest' | 'idle' | 'in' | 'o' | 'win' | 'miss' | 'out' | 'go';
export type Tile = {status: string; kind: TileKind; open?: number; stars?: number; pop?: number};
const SEATS = ['운전석', '조수석', '뒤 왼쪽', '뒤 오른쪽'];

const tileLook = (kind: TileKind) => {
  switch (kind) {
    case 'rest': return {fill: P.panel, stroke: 'transparent', text: P.dim, glass: '#5FA9BF', star: P.star};
    case 'idle': return {fill: P.panel, stroke: '#4A4F7C', text: P.cream, glass: P.glass, star: P.star};
    case 'in': return {fill: P.panel, stroke: P.glass, text: P.cream, glass: P.glass, star: P.star};
    case 'go': return {fill: P.panel, stroke: P.star, text: P.star, glass: P.glass, star: P.star};
    case 'o': return {fill: P.glass, stroke: P.glass, text: P.night, glass: P.night, star: P.night};
    case 'win': return {fill: P.star, stroke: P.star, text: P.night, glass: P.night, star: P.night};
    case 'miss': return {fill: P.panel, stroke: '#3A3E66', text: P.dim, glass: '#5FA9BF', star: P.star};
    case 'out': return {fill: P.panel, stroke: '#EF767A', text: '#EF767A', glass: '#5FA9BF', star: P.star};
  }
};

export const AppScreen: React.FC<{
  tiles: Tile[]; kicker?: string; kickerColor?: string; headline: React.ReactNode; headlineSize?: number; headlineColor?: string;
  sub?: React.ReactNode; button?: string; buttonIn?: number; bar?: number;
}> = ({tiles, kicker, kickerColor = P.glass, headline, headlineSize = 52, headlineColor = P.cream, sub, button, buttonIn = 1, bar}) => (
  <>
    <div style={{position: 'absolute', left: APP.x - APP.w / 2, top: APP.y - APP.h / 2, width: APP.w, height: APP.h, background: P.night, borderRadius: 30, boxShadow: '0 2px 4px rgba(20,22,43,0.2), 0 40px 80px -24px rgba(30,34,90,0.45), 0 90px 160px -50px rgba(30,34,90,0.5)'}} />
    {tiles.map((tile, i) => {
      const look = tileLook(tile.kind);
      const x = TILE.x0 + (i % 2) * (TILE.w + TILE.gap);
      const y = TILE.y0 + Math.floor(i / 2) * (TILE.h + TILE.gap);
      const pop = tile.pop ?? 1;
      return (
        <div key={i} style={{position: 'absolute', left: x, top: y, width: TILE.w, height: TILE.h, boxSizing: 'border-box', background: look.fill, borderRadius: 26, border: `2px solid ${look.stroke}`,
          transform: `scale(${0.955 + 0.045 * pop})`, boxShadow: tile.kind === 'in' || tile.kind === 'win' || tile.kind === 'o' ? `0 0 ${44 * pop}px ${tile.kind === 'win' ? 'rgba(255,209,102,0.3)' : 'rgba(123,223,242,0.28)'}` : 'none',
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12}}>
          <div style={{color: look.text, fontSize: 25, fontWeight: 700, letterSpacing: -0.4}}>{SEATS[i]}</div>
          <Glass w={112} h={78} open={tile.open ?? 0} glass={look.glass} frame={look.text} strokeWidth={2.5} />
          <div style={{color: look.text, fontSize: 18, fontWeight: 580, whiteSpace: 'nowrap'}}>{tile.status}</div>
          {tile.stars !== undefined && <div style={{color: look.star, fontSize: 24, fontWeight: 760}}>★ {tile.stars}</div>}
        </div>
      );
    })}
    <div style={{position: 'absolute', left: APP.x - 48, top: APP.y - 280, width: 590, height: 560, display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 18}}>
      {kicker && <div style={{color: kickerColor, fontSize: 20, fontWeight: 650, letterSpacing: -0.2}}>{kicker}</div>}
      <div style={{color: headlineColor, fontSize: headlineSize, fontWeight: 760, lineHeight: 1.24, letterSpacing: '-0.028em'}}>{headline}</div>
      {sub && <div style={{color: P.dim, fontSize: 20, fontWeight: 520, lineHeight: 1.55}}>{sub}</div>}
      {bar !== undefined && (
        <div style={{marginTop: 18, height: 12, borderRadius: 6, background: P.panel, overflow: 'hidden'}}>
          <div style={{width: `${Math.max(0, Math.min(1, bar)) * 100}%`, height: '100%', borderRadius: 6, background: P.glass}} />
        </div>
      )}
      {button && (
        <div style={{marginTop: 22, alignSelf: 'flex-start', background: P.star, color: P.night, fontSize: 27, fontWeight: 760, padding: '15px 48px', borderRadius: 18, opacity: buttonIn, transform: `translateY(${(1 - buttonIn) * 14}px)`}}>{button}</div>
      )}
    </div>
  </>
);

/** A small white card with one big serif figure. */
export const Stat: React.FC<{figure: React.ReactNode; label: string; note?: string; style?: React.CSSProperties}> = ({figure, label, note, style}) => (
  <div style={{background: P.white, border: `1px solid ${P.hair}`, borderRadius: 22, boxShadow: SHADOW, padding: '22px 28px 22px', boxSizing: 'border-box', ...style}}>
    <div style={{fontFamily: SERIF, fontSize: 72, lineHeight: 1, color: P.ink, letterSpacing: -1}}>{figure}</div>
    <div style={{marginTop: 12, fontSize: 21, fontWeight: 660, color: P.ink, letterSpacing: -0.4}}>{label}</div>
    {note && <div style={{marginTop: 8, fontSize: 15, fontWeight: 560, color: P.faint, lineHeight: 1.45}}>{note}</div>}
  </div>
);

/** Little white tag with a seat name and a big O or X. */
export const Answer: React.FC<{seat: string; mark: 'O' | 'X'; note: string}> = ({seat, mark, note}) => (
  <div style={{display: 'flex', alignItems: 'center', gap: 14, background: P.white, border: `1px solid ${P.hair}`, borderRadius: 16, boxShadow: SHADOW_SMALL, padding: '10px 18px 10px 16px', whiteSpace: 'nowrap'}}>
    <span style={{fontFamily: SERIF, fontSize: 46, lineHeight: 1, color: mark === 'O' ? P.gold : P.ink}}>{mark}</span>
    <span>
      <span style={{display: 'block', fontSize: 15, fontWeight: 680, color: P.ink, letterSpacing: -0.2}}>{seat}</span>
      <span style={{display: 'block', fontSize: 11.5, fontWeight: 600, color: P.faint, marginTop: 3}}>{note}</span>
    </span>
  </div>
);
