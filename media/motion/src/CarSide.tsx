import React from 'react';
import {P} from './kit2';

/**
 * The car in profile, facing left. A dark body on light paper so the silhouette reads at once.
 * Box: 1200 × 420. Ground is at y = 380. `rearDrop` lowers the rear-door glass (0..1 of its travel).
 */
export const CAR_BOX = {w: 1200, h: 420, ground: 380};
/** Useful points in the car's own coordinates. */
export const CAR_POINTS = {
  rearGlass: {x: 762, y: 128},
  rearDoorPanel: {x: 742, y: 236},
  port: {x: 1004, y: 216},
  roofTop: 66,
};

const BODY =
  'M 70 318 C 58 292, 58 262, 68 240 C 76 222, 90 211, 112 203 L 332 167 C 384 122, 424 93, 482 79 C 566 66, 782 62, 882 76 ' +
  'C 934 86, 1002 130, 1042 172 C 1066 181, 1083 196, 1087 216 L 1091 294 C 1091 312, 1083 322, 1067 322 ' +
  'L 995 322 A 97 97 0 0 0 805 322 L 345 322 A 97 97 0 0 0 155 322 Z';
const SURROUND = 'M 352 168 L 478 90 C 560 79, 800 76, 880 87 C 928 98, 972 132, 1000 168 Z';
const FRONT_GLASS = 'M 378 160 L 489 100 L 636 97 L 636 160 Z';
const REAR_GLASS = 'M 660 160 L 660 97 L 838 96 L 884 160 Z';
const QUARTER_GLASS = 'M 904 160 L 862 98 C 912 106, 952 132, 978 160 Z';

export const CarSide: React.FC<{rearDrop?: number; charging?: number}> = ({rearDrop = 0, charging = 1}) => {
  const wheels = [250, 900];
  return (
    <svg width={CAR_BOX.w} height={CAR_BOX.h} viewBox={`0 0 ${CAR_BOX.w} ${CAR_BOX.h}`} style={{overflow: 'visible'}}>
      <defs>
        <linearGradient id="car-body" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#343A72" />
          <stop offset="0.42" stopColor="#1E2148" />
          <stop offset="1" stopColor="#12142A" />
        </linearGradient>
        <linearGradient id="car-glass" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#E4F7FB" />
          <stop offset="1" stopColor="#9EDCEB" />
        </linearGradient>
        <radialGradient id="car-shadow" cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stopColor="rgba(30,34,90,0.34)" />
          <stop offset="0.6" stopColor="rgba(30,34,90,0.14)" />
          <stop offset="1" stopColor="rgba(30,34,90,0)" />
        </radialGradient>
        <clipPath id="car-rear-opening"><path d={REAR_GLASS} /></clipPath>
        <clipPath id="car-above-sill"><rect x={0} y={0} width={CAR_BOX.w} height={322} /></clipPath>
      </defs>

      <ellipse cx={585} cy={386} rx={590} ry={26} fill="url(#car-shadow)" />

      {/* wheel wells, then tyres */}
      <g clipPath="url(#car-above-sill)">
        {wheels.map((x) => <circle key={x} cx={x} cy={302} r={96} fill="#0A0B17" />)}
      </g>
      {wheels.map((x) => (
        <g key={x}>
          <circle cx={x} cy={302} r={78} fill="#0C0D1A" />
          <circle cx={x} cy={302} r={66} fill="none" stroke="#23264A" strokeWidth={2} />
          <circle cx={x} cy={302} r={53} fill="#DDE1EC" />
          {[0, 72, 144, 216, 288].map((a) => (
            <line key={a} x1={x} y1={302} x2={x + 49 * Math.cos(((a - 90) * Math.PI) / 180)} y2={302 + 49 * Math.sin(((a - 90) * Math.PI) / 180)} stroke="#A9AFC6" strokeWidth={11} strokeLinecap="round" />
          ))}
          <circle cx={x} cy={302} r={53} fill="none" stroke="#C2C7D8" strokeWidth={3} />
          <circle cx={x} cy={302} r={13} fill="#14162B" />
        </g>
      ))}

      {/* body */}
      <path d={BODY} fill="url(#car-body)" />
      <path d="M 345 322 L 805 322 L 805 300 C 660 292, 500 292, 345 300 Z" fill="#0C0D1B" />
      <path d="M 112 205 L 332 169" fill="none" stroke="rgba(255,255,255,0.22)" strokeWidth={2} strokeLinecap="round" />
      <path d="M 352 176 L 1040 180" fill="none" stroke="rgba(255,255,255,0.16)" strokeWidth={1.6} />

      {/* greenhouse */}
      <path d={SURROUND} fill="#0A0B17" />
      <path d={FRONT_GLASS} fill="url(#car-glass)" />
      <path d="M 430 140 L 492 106 L 540 105 L 470 148 Z" fill="#FFFFFF" opacity={0.42} />
      <g clipPath="url(#car-rear-opening)">
        <path d={REAR_GLASS} fill="#EEF1F8" />
        <path d="M 690 160 C 690 130, 716 116, 744 116 C 772 116, 790 132, 790 160 Z" fill="#CBD2E2" />
        <g transform={`translate(0 ${rearDrop * 64})`}>
          <path d={REAR_GLASS} fill="url(#car-glass)" />
          <path d="M 700 142 L 742 104 L 782 104 L 734 150 Z" fill="#FFFFFF" opacity={0.4} />
          <path d="M 660 97 L 838 96" stroke="#5DBFD4" strokeWidth={3} />
        </g>
      </g>
      <path d={QUARTER_GLASS} fill="url(#car-glass)" opacity={0.92} />

      {/* doors, handles, mirror */}
      <path d="M 352 174 C 340 220, 344 280, 353 318 M 648 166 L 648 318 M 890 168 L 886 206 C 852 214, 820 250, 810 302" fill="none" stroke="rgba(255,255,255,0.15)" strokeWidth={1.6} />
      <rect x={566} y={186} width={58} height={9} rx={4.5} fill="rgba(255,255,255,0.26)" />
      <rect x={776} y={186} width={58} height={9} rx={4.5} fill="rgba(255,255,255,0.26)" />
      <path d="M 372 152 l -34 -5 q -10 7 -4 19 l 33 6 q 8 -8 5 -20 z" fill="#1E2148" stroke="rgba(255,255,255,0.18)" strokeWidth={1.2} />

      {/* lamps */}
      <rect x={66} y={228} width={50} height={9} rx={4.5} fill="#FFF1B8" />
      <rect x={66} y={243} width={26} height={5} rx={2.5} fill="#FFF1B8" opacity={0.7} />
      <rect x={1062} y={204} width={27} height={11} rx={4} fill="#F0566E" />

      {/* charge port */}
      <circle cx={CAR_POINTS.port.x} cy={CAR_POINTS.port.y} r={13} fill="#0A0B17" stroke={P.gold} strokeWidth={2.5} opacity={charging} />
      <circle cx={CAR_POINTS.port.x} cy={CAR_POINTS.port.y} r={5} fill={P.gold} opacity={charging} />
    </svg>
  );
};
