import React from 'react';
import {continueRender, delayRender, Easing, interpolate, staticFile} from 'remotion';

/**
 * Design system for the film, measured from the reference (frame analysis 2026-11-11):
 * light paper, one ink, hairlines, large soft shadows, small type with a lot of air,
 * a serif for numerals and ornaments, and the app itself as the only dark object.
 */
export const P = {
  paper: '#F1F3F8',
  white: '#FFFFFF',
  ink: '#14162B', // the app's own night colour, used here as the ink
  soft: '#6B7090',
  faint: '#A7ABC2',
  hair: '#E2E5EE',
  dot: '#D3D7E4',
  ornament: '#B4B9E6',
  gold: '#F5A800', // the single accent on paper
  glassLight: '#BDE7F1',
  glassEdge: '#58BDD3',
  // inside the app card (true app colours)
  night: '#14162B',
  panel: '#202342',
  cream: '#F4F1DE',
  dim: '#8A8FB5',
  star: '#FFD166',
  glass: '#7BDFF2',
};

export const SANS = 'Pretendard, "Malgun Gothic", sans-serif';
export const SERIF = '"Playfair Display", Georgia, serif';

export const SHADOW = '0 1px 2px rgba(20,22,43,0.05), 0 18px 36px -14px rgba(64,72,150,0.20), 0 56px 110px -34px rgba(64,72,150,0.26)';
export const SHADOW_SMALL = '0 1px 2px rgba(20,22,43,0.06), 0 10px 22px -10px rgba(64,72,150,0.24)';

if (typeof document !== 'undefined') {
  const faces: [string, string, FontFaceDescriptors][] = [
    ['Pretendard', 'fonts/PretendardVariable.woff2', {weight: '45 920'}],
    ['Playfair Display', 'fonts/playfair-display-latin-600-normal.woff2', {weight: '600', style: 'normal'}],
    ['Playfair Display', 'fonts/playfair-display-latin-600-italic.woff2', {weight: '600', style: 'italic'}],
  ];
  const handle = delayRender('Loading fonts');
  Promise.all(
    faces.map(([family, file, descriptors]) =>
      new FontFace(family, `url(${staticFile(file)}) format('woff2')`, descriptors).load().then((face) => document.fonts.add(face)),
    ),
  )
    .catch(() => undefined)
    .then(() => continueRender(handle));
}

/** Arrive fast, settle softly: used for anything entering. */
export const OUT = Easing.bezier(0.16, 1, 0.3, 1);
/** Camera moves: slow start, quick middle, soft landing. */
export const TRAVEL = Easing.bezier(0.72, 0, 0.14, 1);

export const kf = (frame: number, points: [number, number][], easing: (t: number) => number = TRAVEL) =>
  interpolate(
    frame,
    points.map((p) => p[0]),
    points.map((p) => p[1]),
    {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing},
  );

/** 0 → 1 over `length` frames starting at `start`, arriving fast. */
export const enter = (frame: number, start: number, length = 16) =>
  interpolate(frame, [start, start + length], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: OUT});

/** Camera space. `depth` below 1 makes a layer lag behind the camera, which reads as distance. */
export const World: React.FC<{cx: number; cy: number; scale: number; depth?: number; children: React.ReactNode}> = ({cx, cy, scale, depth = 1, children}) => (
  <div
    style={{
      position: 'absolute', left: 0, top: 0, width: 0, height: 0, transformOrigin: '0 0',
      transform: `translate(960px, 540px) scale(${1 + (scale - 1) * depth}) translate(${-cx * depth}px, ${-cy * depth}px)`,
    }}
  >
    {children}
  </div>
);

export const At: React.FC<{x: number; y: number; w: number; h: number; style?: React.CSSProperties; children?: React.ReactNode}> = ({x, y, w, h, style, children}) => (
  <div style={{position: 'absolute', left: x - w / 2, top: y - h / 2, width: w, height: h, ...style}}>{children}</div>
);

/** Rise-and-fade entrance for one block. */
export const Rise: React.FC<{frame: number; start: number; distance?: number; style?: React.CSSProperties; children: React.ReactNode}> = ({frame, start, distance = 14, style, children}) => {
  const a = enter(frame, start);
  return <div style={{opacity: a, transform: `translateY(${(1 - a) * distance}px)`, ...style}}>{children}</div>;
};

/** Text that arrives letter by letter with a soft leading edge, as in the reference. */
export const Reveal: React.FC<{frame: number; start: number; text: string; step?: number; fade?: number; style?: React.CSSProperties}> = ({frame, start, text, step = 1.1, fade = 7, style}) => (
  <span style={style}>
    {Array.from(text).map((ch, i) => {
      const a = interpolate(frame, [start + i * step, start + i * step + fade], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
      return (
        <span key={i} style={{opacity: a, filter: a < 1 ? `blur(${(1 - a) * 3}px)` : undefined, whiteSpace: 'pre'}}>
          {ch}
        </span>
      );
    })}
  </span>
);

/** The app's own window picture. `open` is 0..1 of the glass travel. */
export const Glass: React.FC<{w: number; h: number; open: number; glass: string; frame: string; strokeWidth?: number}> = ({w, h, open, glass, frame, strokeWidth}) => {
  const s = strokeWidth ?? Math.max(2, h * 0.035);
  const inset = s + 3;
  const top = inset + (h - 2 * inset) * open;
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`}>
      <rect x={inset} y={top} width={w - 2 * inset} height={Math.max(0, h - inset - top)} rx={h * 0.07} fill={glass} />
      <rect x={s / 2} y={s / 2} width={w - s} height={h - s} rx={h * 0.14} fill="none" stroke={frame} strokeWidth={s} />
    </svg>
  );
};

/** White pill with a hairline, for small facts. */
export const Pill: React.FC<{children: React.ReactNode; style?: React.CSSProperties}> = ({children, style}) => (
  <div
    style={{
      display: 'inline-flex', alignItems: 'center', gap: 10, background: P.white, border: `1px solid ${P.hair}`, borderRadius: 999,
      padding: '9px 18px', fontFamily: SANS, fontSize: 20, fontWeight: 620, color: P.ink, letterSpacing: -0.2, boxShadow: SHADOW_SMALL, whiteSpace: 'nowrap', ...style,
    }}
  >
    {children}
  </div>
);

/** Position along a chain of cubic Bezier segments, each given as 4 points. t runs 0..1 over the whole chain. */
export const along = (t: number, segments: [number, number][][]): [number, number] => {
  const n = segments.length;
  const scaled = Math.min(n - 1e-6, Math.max(0, t * n));
  const i = Math.floor(scaled);
  const u = scaled - i;
  const p = segments[i];
  const v = 1 - u;
  const b = [v * v * v, 3 * v * v * u, 3 * v * u * u, u * u * u];
  return [0, 1].map((k) => b[0] * p[0][k] + b[1] * p[1][k] + b[2] * p[2][k] + b[3] * p[3][k]) as [number, number];
};

export const pathOf = (segments: [number, number][][]) =>
  segments.map((p, i) => `${i === 0 ? `M ${p[0][0]} ${p[0][1]} ` : ''}C ${p[1][0]} ${p[1][1]}, ${p[2][0]} ${p[2][1]}, ${p[3][0]} ${p[3][1]}`).join(' ');
