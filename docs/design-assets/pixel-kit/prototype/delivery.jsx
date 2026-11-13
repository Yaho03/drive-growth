// 5차 · 탁송 연출(Claude Design 원본: delivery-core/scenes/highway.jsx)을 시제품용으로 옮긴 것.
// 바꾼 점: 차 20종·마을 그림·주차 칸은 시제품 것(window.DV_SPRITES/DV_IMG/DV_SLOTS)을 쓰고, 차종·등급·목적지·거리·보상은 props(d)로 받는다.
// 차 밑 테두리는 뺐고(2026-11-13 결정) 손님 차도 산 차처럼 왼쪽 도로에서 들어온다. 미리보기 전용 재생기·트럭 시트는 뺐다.
// 5차 · 탁송 연출 — shared core. Same structure as 4차 motion-piece.jsx: every piece is a pure function of t.
(() => {
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const Easing = {
  easeOutCubic: t => 1 - Math.pow(1 - t, 3),
  easeInQuad: t => t * t,
  easeOutBack: t => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); },
  easeInOut: t => t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2,
};
const SW = 1360, SH = 948;
const seg = (t, a, b) => clamp((t - a) / (b - a), 0, 1);
const lerp = (a, b, p) => a + (b - a) * p;
const MOTION = {
  enter: (t, a, b) => Easing.easeOutCubic(seg(t, a, b)),
  pop: (t, a, b) => Easing.easeOutBack(seg(t, a, b)),
  drop: (t, a, b) => Easing.easeInQuad(seg(t, a, b)),
  inout: (t, a, b) => Easing.easeInOut(seg(t, a, b)),
};
const rnd = (i, s) => { const x = Math.sin(i * 127.1 + (s || 1) * 311.7) * 43758.5453; return x - Math.floor(x); };
const snap = (v, g) => Math.round(v / (g || 4)) * (g || 4);
// 게임 시제품(prototype-isometric.html)과 같은 글꼴·카드색
const FPX = "'Noto Sans KR',system-ui,sans-serif";
const FUI = "'Noto Sans KR',system-ui,sans-serif";
const UI = { bg: '#151a28', card: 'rgba(18,22,34,.94)', card2: '#1d2335', line: '#2c3349', fg: '#ffffff', mut: '#aab3c5', accent: '#2f6fe8', ok: '#7ee2a6', okBg: '#1f3a2c', warn: '#ffd36b', warnBg: '#3d3216', info: '#9cc2ff', infoBg: '#1e2f57' };
const C = {
  ink: '#0b0e1f', navy0: '#141a33', navy: '#1d2547', navy2: '#2a3463', slate: '#3b4a85', slate2: '#56679f', mist: '#8796c4', mist2: '#c3cce6',
  white: '#f4f5fa', blue: '#2f6bff', blue2: '#4f8cff', blue3: '#9cc1ff', blueD: '#1b3fb0', pink: '#ff4fa3', pink2: '#ff8cc6', pinkD: '#b02a74',
  gold: '#ffc83d', gold2: '#ffe38a', goldD: '#d98a1c', bronze: '#8a4a12', red: '#e8483f', orange: '#ff8a5c', green: '#46c46a', lime: '#a5e86a',
  greenD: '#2b8a5a', dirt: '#6e5338', wood: '#a07a50', wood2: '#d9b380', gray0: '#4a4a5a', gray: '#7a7a8c', gray2: '#b5b5c3', tire: '#1f1f2a',
};
const RARITY = { common: '#d6dce6', uncommon: '#68a0f0', rare: '#f08caa', legend: '#fcdc64' };
// real car sprites from the game (native px, face ↘)
const SPRITES = window.DV_SPRITES;   // 시제품의 차 20종: {id: [data URI, w, h]}

// iso → svg strings
const pj = (x, y, z, k) => [(x - y) * k, (x + y) * k / 2 - z * k];
const pts = (arr, k) => arr.map(a => pj(a[0], a[1], a[2], k).map(v => +v.toFixed(2)).join(',')).join(' ');
const pg = (arr, k, f) => `<polygon points="${pts(arr, k)}" fill="${f}"/>`;
const box = (x, y, z, w, d, h, k, c) =>
  pg([[x, y + d, z], [x + w, y + d, z], [x + w, y + d, z + h], [x, y + d, z + h]], k, c[1]) +
  pg([[x + w, y, z], [x + w, y + d, z], [x + w, y + d, z + h], [x + w, y, z + h]], k, c[2]) +
  pg([[x, y, z + h], [x + w, y, z + h], [x + w, y + d, z + h], [x, y + d, z + h]], k, c[0]);
const yF = (yp, x1, x2, z1, z2, k, f) => pg([[x1, yp, z1], [x2, yp, z1], [x2, yp, z2], [x1, yp, z2]], k, f);
const xF = (xp, y1, y2, z1, z2, k, f) => pg([[xp, y1, z1], [xp, y2, z1], [xp, y2, z2], [xp, y1, z2]], k, f);
const gq = (x1, y1, x2, y2, z, k, f) => pg([[x1, y1, z], [x2, y1, z], [x2, y2, z], [x1, y2, z]], k, f);
const flat = s => `<g stroke="none">${s}</g>`;
const svgWrap = (vb, w, h, body) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="${vb}" shape-rendering="crispEdges"><g stroke="${C.ink}" stroke-width="1" stroke-linejoin="round">${body}</g></svg>`;

// cars (stand-in for 2차 sheet; faces ↘ = +x)
const CARS = {
  hatch: { L: 7, Wd: 4, ch: 1.5, cab: [1.3, 4.0, 1.7, 0.35], body: [C.lime, C.green, C.greenD] },
  sedan: { L: 8.6, Wd: 4, ch: 1.3, cab: [2.0, 4.4, 1.5, 0.4], body: [C.blue3, C.blue2, C.blueD] },
  coupe: { L: 8.6, Wd: 4, ch: 1.1, cab: [2.8, 3.3, 1.2, 0.5], body: [C.pink2, C.pink, C.pinkD], stripe: C.white },
  gt: { L: 9, Wd: 4.2, ch: 1.1, cab: [3.0, 3.1, 1.1, 0.55], body: [C.gold2, C.gold, C.goldD], stripe: C.ink, spoiler: true },
  van: { L: 8, Wd: 4, ch: 1.6, cab: [1.0, 5.6, 1.8, 0.3], body: [C.wood2, C.wood, C.dirt] },
};
function carSVG(type) {
  const k = 4, c = CARS[type], L = c.L, Wd = c.Wd, z0 = 0.5, zt = z0 + c.ch;
  const [cx, cw, chh, ins] = c.cab; let s = '';
  if (c.spoiler) s += box(0.2, 0.7, zt, 0.4, 0.4, 0.8, k, [C.gray0, C.tire, C.tire]);
  s += box(0, 0, z0, L, Wd, c.ch, k, c.body);
  if (c.spoiler) s += box(0.2, Wd - 1.1, zt, 0.4, 0.4, 0.8, k, [C.gray0, C.tire, C.tire]) + box(-0.2, 0, zt + 0.8, 1.2, Wd, 0.3, k, c.body);
  s += box(cx, ins, zt, cw, Wd - 2 * ins, chh, k, c.body);
  s += yF(Wd - ins, cx + 0.4, cx + cw - 0.4, zt + 0.25, zt + chh - 0.2, k, C.navy2);
  s += flat(yF(Wd - ins, cx + 0.6, cx + 1.1, zt + chh - 0.5, zt + chh - 0.3, k, C.blue3));
  s += xF(cx + cw, ins + 0.3, Wd - ins - 0.3, zt + 0.2, zt + chh - 0.2, k, C.slate);
  if (c.stripe) s += flat(yF(Wd, 0.4, L - 0.4, z0 + c.ch * 0.42, z0 + c.ch * 0.68, k, c.stripe));
  s += xF(L, 0.4, 1.3, zt - 0.6, zt - 0.2, k, C.gold2) + xF(L, Wd - 1.3, Wd - 0.4, zt - 0.6, zt - 0.2, k, C.gold2);
  [1.0, L - 2.5].forEach(wx => { s += box(wx, Wd - 0.3, 0, 1.5, 0.45, 1.35, k, [C.gray0, C.tire, C.tire]) + flat(yF(Wd + 0.15, wx + 0.5, wx + 1.0, 0.45, 0.95, k, C.gray2)); });
  return svgWrap('-20 -20 58 48', 58, 48, s);
}

// shop / destination map (shared viewBox so layers stack 1:1). 1 map unit = 2.5 truck units.
const MK = 8, MS = 5, MVB = '-84 -40 200 144';
const mpos = (x, y, z) => { const p = pj(x, y, z || 0, MK); return [(p[0] + 84) * MS, (p[1] + 40) * MS]; };
const slotLines = (x0, y1, y2, w, th, col) => gq(x0, y1, x0 + th, y2, 0, MK, col) + gq(x0 + w - th, y1, x0 + w, y2, 0, MK, col) + gq(x0, y1, x0 + w, y1 + th, 0, MK, col);
const trees = (list, k) => list.map(([x, y]) => box(x + 0.5, y + 0.5, 0, 0.4, 0.4, 0.7, k, [C.wood, C.dirt, C.bronze]) + box(x, y, 0.7, 1.4, 1.4, 1.5, k, [C.lime, C.green, C.greenD])).join('');
function mapLayer(name) {
  const k = MK; let s = '';
  if (name === 'shop') {
    s += box(0, 0, -0.6, 14, 10, 0.6, k, [C.green, C.greenD, C.dirt]);
    s += gq(4.8, 0.6, 13.6, 9.6, 0, k, C.gray0) + gq(4, 2.2, 4.8, 3.2, 0, k, C.gray2);
    let l = ''; for (let i = 0; i < 5; i++) l += slotLines(5.2 + i * 1.6, 1.0, 3.8, 1.6, 0.14, C.white);
    for (let i = 0; i < 2; i++) l += slotLines(5.2 + i * 1.6, 6.0, 8.8, 1.6, 0.14, C.white);
    for (let i = 0; i < 3; i++) l += slotLines(9.2 + i * 1.4, 6.0, 8.8, 1.4, 0.14, C.white);
    s += flat(l);
    s += box(1, 1, 0, 3, 4, 4.2, k, [C.white, C.mist2, C.mist]) + box(0.8, 0.8, 4.2, 3.4, 4.4, 0.5, k, [C.orange, C.red, C.pinkD]);
    s += xF(4, 2.3, 3.1, 0, 1.3, k, C.slate) + xF(4, 1.3, 4.7, 3.3, 3.9, k, C.blue);
    [1.1, 2.6].forEach(z => { s += yF(5, 1.5, 2.3, z, z + 0.7, k, C.blue3) + yF(5, 2.7, 3.5, z, z + 0.7, k, C.blue3); });
    s += xF(4, 1.3, 2.0, 1.6, 2.3, k, C.blue3) + xF(4, 3.4, 4.3, 1.6, 2.3, k, C.blue3);
    s += box(1, 5.4, 0, 3, 2.4, 2.0, k, [C.gray2, C.gray, C.gray0]) + box(0.9, 5.3, 2.0, 3.2, 2.6, 0.35, k, [C.blue2, C.blue, C.blueD]);
    s += xF(4, 5.7, 7.5, 0, 1.5, k, C.mist2) + flat(xF(4, 5.7, 7.5, 0.5, 0.6, k, C.gray) + xF(4, 5.7, 7.5, 1.0, 1.1, k, C.gray));
    s += trees([[0.3, 8.4], [2.4, 8.7], [12.4, 0.0]], k);
  } else if (name === 'dest') {
    s += box(0, 0, -0.6, 14, 10, 0.6, k, [C.green, C.greenD, C.dirt]);
    s += gq(5.6, 4.2, 13.6, 9.6, 0, k, C.gray0);
    let l = ''; for (let i = 0; i < 4; i++) l += slotLines(6 + i * 1.6, 6.2, 9.0, 1.6, 0.14, C.white);
    s += flat(l);
    s += box(1.2, 0.8, 0, 8.4, 3, 3.4, k, [C.white, C.mist2, C.mist]) + box(1.0, 0.6, 3.4, 8.8, 3.4, 0.45, k, [C.lime, C.green, C.greenD]);
    for (let i = 0; i < 6; i++) { const x = 1.8 + i * 1.3; s += yF(3.8, x, x + 0.8, 1.9, 2.8, k, C.blue3); if (i !== 3) s += yF(3.8, x, x + 0.8, 0.5, 1.4, k, C.blue3); }
    s += yF(3.8, 5.7, 6.7, 0, 1.5, k, C.slate) + box(5.2, 3.8, 3.4, 2, 0.3, 1.3, k, [C.white, C.mist2, C.mist]) + flat(yF(4.1, 5.7, 6.5, 3.75, 4.45, k, C.gold2));
    s += box(10.2, 0.8, 0, 0.2, 0.2, 4.6, k, [C.gray2, C.gray, C.gray0]) + box(10.4, 0.8, 3.8, 1.2, 0.1, 0.7, k, [C.blue2, C.blue2, C.blue]);
    s += trees([[0.4, 5.6], [0.6, 8.2], [3.0, 8.6], [12.2, 0.6]], k);
  } else if (name.indexOf('glow') === 0) {
    const col = name.slice(5);
    s += flat(slotLines(6.8, 6.0, 8.8, 1.6, 0.34, col) + gq(7.0, 6.2, 8.2, 8.8, 0, k, col).replace('fill="', 'fill-opacity=".35" fill="'));
  }
  return svgWrap(MVB, 200, 144, s);
}
const coinSVG = () => `<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 12 12" shape-rendering="crispEdges"><circle cx="6" cy="6" r="5.6" fill="${C.ink}"/><circle cx="6" cy="6" r="4.6" fill="${C.goldD}"/><circle cx="5.6" cy="5.6" r="3.8" fill="${C.gold}"/><rect x="5" y="3" width="2" height="6" fill="${C.gold2}"/></svg>`;
const boxIconSVG = () => { const k = 4; return svgWrap('-14 -12 30 26', 30, 26, box(0, 0, 0, 3.4, 3.4, 2.6, k, [C.wood2, C.wood, C.dirt]) + flat(gq(1.4, 0, 2.0, 3.4, 2.6, k, C.gold2) + yF(3.4, 1.4, 2.0, 0, 2.6, k, C.gold))); };

// ── truck (original design, ~110×75 px, faces ↘). Units: k=4. x along length, y across, z up.
// bed deck: x 0..10, z 1.2..1.6; cab x 10.2..13.6. Pivot for tilt = bed front bottom (10, *, 1.2).
const TK = 5.6, TVB = '-52 -16 132 72', TW = 132, TH = 72, TOX = 52, TOY = 16;
const tp = (x, y, z) => { const p = pj(x, y, z, TK); return [p[0] + TOX, p[1] + TOY]; };
const TBODY = {
  chassis: () => box(0.6, 0.8, 0.6, 9.8, 3.4, 0.6, TK, [C.gray0, C.tire, C.tire]) + box(0.2, 0.5, 0.45, 0.4, 4.0, 0.45, TK, [C.gray2, C.gray, C.gray0]),
  wheels: f => [1.3, 3.1, 10.9].map(wx => box(wx, 4.5, 0, 1.6, 0.5, 1.6, TK, [C.gray0, C.tire, C.tire]) + flat(yF(5.0, wx + 0.45, wx + 1.15, 0.45, 1.15, TK, C.gray2) + (f ? yF(5.01, wx + 0.75, wx + 1.15, 0.45, 0.8, TK, C.gray0) : yF(5.01, wx + 0.45, wx + 0.85, 0.8, 1.15, TK, C.gray0)))).join(''),
  bed: () => box(0, 0, 1.2, 10, 5, 0.4, TK, [C.slate, C.navy2, C.navy]) + flat([1.3, 2.4, 3.5].map(y => gq(0.6, y, 9.4, y + 0.14, 1.6, TK, C.navy2)).join('') + gq(0, 0.3, 0.45, 4.7, 1.6, TK, C.slate2)) + box(0, 0, 1.6, 10, 0.3, 0.3, TK, [C.slate2, C.navy2, C.navy]),
  bedNear: () => box(0, 4.7, 1.6, 10, 0.3, 0.3, TK, [C.slate2, C.navy2, C.navy]) + box(9.5, 0.3, 1.6, 0.4, 4.4, 1.5, TK, [C.slate2, C.navy2, C.navy]) + flat(xF(9.9, 0.8, 4.2, 2.3, 2.5, TK, C.slate)),
  cab: () => box(10.2, 0, 0.8, 3.4, 5, 4.2, TK, [C.white, C.mist2, C.mist]) + box(13.6, 0.3, 0.55, 0.35, 4.4, 0.65, TK, [C.gray2, C.gray, C.gray0])
    + xF(13.6, 0.5, 4.5, 3.1, 4.7, TK, C.navy2) + flat(xF(13.6, 0.9, 1.5, 4.1, 4.5, TK, C.blue3)) + yF(5, 11.7, 13.3, 3.1, 4.7, TK, C.navy2) + flat(yF(5, 12.0, 12.4, 4.1, 4.5, TK, C.blue3))
    + xF(13.6, 1.7, 3.3, 1.4, 2.4, TK, C.gray0) + xF(13.6, 0.5, 1.3, 1.5, 2.1, TK, C.gold2) + xF(13.6, 3.7, 4.5, 1.5, 2.1, TK, C.gold2)
    + flat(yF(5, 11.5, 11.6, 1.0, 4.7, TK, C.mist)) + box(13.0, 5.0, 3.0, 0.3, 0.35, 0.9, TK, [C.gray2, C.gray, C.gray0]),
  stripe: col => flat(yF(5, 10.3, 13.5, 1.7, 2.6, TK, col) + xF(13.6, 4.5, 4.95, 1.7, 2.6, TK, col) + xF(13.6, 0.05, 0.5, 1.7, 2.6, TK, col)),
  beacon: f => box(11.2, 1.5, 5.0, 1.4, 2.0, 0.15, TK, [C.gray2, C.gray, C.gray0]) + box(11.4, 1.7, 5.15, 1.0, 1.6, 0.55, TK, f ? [C.gold2, C.goldD, C.gold] : [C.gold2, C.gold, C.goldD]) + flat(f ? xF(12.4, 1.8, 2.5, 5.25, 5.6, TK, C.white) : yF(3.3, 11.5, 12.1, 5.25, 5.6, TK, C.white)),
  strap: (type, col, wide) => {
    const c0 = 1, yw = 4.66, w = wide ? 0.25 : 0;
    return [c0 + 1.0, c0 + 8 - 2.5].map(wx => yF(yw, wx + 0.15 - w, wx + 1.35 + w, 1.6, 2.7 + w, TK, col) + box(wx - 0.35 - w, 4.7, 1.9, 0.3 + w, 0.3, 0.4, TK, [col, col, col]) + box(wx + 1.55, 4.7, 1.9, 0.3 + w, 0.3, 0.4, TK, [col, col, col])).join('');
  },
};
const tSVG = body => svgWrap(TVB, TW, TH, body);
const truckMini = () => tSVG(TBODY.chassis() + TBODY.wheels(0) + TBODY.bed() + TBODY.bedNear() + TBODY.cab() + TBODY.stripe(C.blue) + TBODY.beacon(0));
// x/z-preserving tilt in the x–z plane (a>0 = front up). Affine stand-in for a 3D rotation; exact on the long axis and height.
const tiltM = a => { const c = Math.cos(a), s = Math.sin(a); return `matrix(${(c - s / 2).toFixed(4)},${(-1.25 * s).toFixed(4)},${s.toFixed(4)},${(s / 2 + c).toFixed(4)},0,0)`; };
const BED_TILT = Math.asin(1.2 / 10); // rear edge touches ground when slid back
const BED_SLIDE = -4;

// rasterizer: svg string → real pixels
const PXC = {}; const PXL = new Set();
function raster(id, mk, w, h) {
  if (id in PXC) return PXC[id];
  PXC[id] = null;
  const img = new Image();
  img.onload = () => { const cv = document.createElement('canvas'); cv.width = w; cv.height = h; const x = cv.getContext('2d'); x.imageSmoothingEnabled = false; x.drawImage(img, 0, 0, w, h); PXC[id] = cv.toDataURL(); PXL.forEach(f => f()); };
  img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(mk());
  return null;
}
function Pix({ id, mk, w, h, s, style }) {
  const [, force] = React.useState(0);
  const url = raster(id, mk, w, h);
  React.useEffect(() => { const l = () => force(n => n + 1); PXL.add(l); if (!url && PXC[id]) l(); return () => PXL.delete(l); }, [id, !!url]);
  const st = Object.assign({ width: w * s, height: h * s, display: 'block' }, style);
  return url ? <img src={url} alt="" style={Object.assign({ imageRendering: 'pixelated' }, st)} /> : <div style={st}></div>;
}
const Car = ({ type, s = 4, mirror, style }) => <Pix id={'car-' + type} mk={() => carSVG(type)} w={58} h={48} s={s} style={Object.assign({ transform: mirror ? 'scaleX(-1)' : undefined }, style)} />;
// real sprite, positioned by its ground-footprint centre (cx, cy) in parent px
const CarImg = ({ k, s, cx, cy, mirror, o = 1, extra = '' }) => {
  const [src, w, h] = SPRITES[k];
  return <img src={src} alt="" style={{ position: 'absolute', left: cx - w * s / 2, top: cy - h * s * 0.6, width: w * s, height: h * s, imageRendering: 'pixelated', opacity: o, transform: (mirror ? 'scaleX(-1) ' : '') + extra }} />;
};
const CAR_L = 8; // car footprint length in truck units (fits the 10-unit bed)
const Coin = ({ s = 4, style }) => <Pix id="coin" mk={coinSVG} w={12} h={12} s={s} style={style} />;
const MapL = ({ name, style }) => <Pix id={'map-' + name} mk={() => mapLayer(name)} w={200} h={144} s={MS} style={Object.assign({ position: 'absolute', left: 0, top: 0 }, style)} />;
const TL = ({ name, mk, S, style }) => <Pix id={'tr-' + name} mk={() => tSVG(mk())} w={TW} h={TH} s={S} style={Object.assign({ position: 'absolute', left: 0, top: 0 }, style)} />;

// Truck. car = {type, u (rear x on bed, truck units), z (hop)} rides the bed group.
function Truck({ carScale, S = 4, slide = 0, tilt = 0, lean = 0, wheel = 0, beacon = 0, stripe = C.blue, car, strapCol = C.gray2, strapP = 0, glow = 0 }) {
  const pv = tp(10, 0, 1.2), sl = pj(slide, 0, 0, TK), lp = tp(11.6, 0, 0);
  const cp = car ? tp(car.u + CAR_L / 2, 2.5, 1.6 + (car.z || 0)) : null;
  const strapS = strapP > 0 && strapP < 1 ? 1 + 0.25 * (1 - strapP) : 1;
  return (
    <div style={{ position: 'absolute', left: 0, top: 0, width: TW * S, height: TH * S, transform: lean ? tiltM(lean) : undefined, transformOrigin: `${lp[0] * S}px ${lp[1] * S}px` }}>
      <TL name="chassis" mk={TBODY.chassis} S={S} />
      <TL name={'wheels' + wheel} mk={() => TBODY.wheels(wheel)} S={S} />
      <div style={{ position: 'absolute', left: 0, top: 0, width: TW * S, height: TH * S, transform: `translate(${sl[0] * S}px,${sl[1] * S}px) ${tilt ? tiltM(tilt) : ''}`, transformOrigin: `${pv[0] * S}px ${pv[1] * S}px` }}>
        <TL name="bed" mk={TBODY.bed} S={S} />
        {car ? <CarImg k={car.type} s={carScale || S / 1.5} cx={cp[0] * S} cy={cp[1] * S} /> : null}
        <TL name="bedNear" mk={TBODY.bedNear} S={S} />
        {car && strapP > 0 ? <TL name={'strap-' + car.type + strapCol} mk={() => TBODY.strap(car.type, strapCol)} S={S} style={{ opacity: seg(strapP, 0, 0.3), transform: `scaleY(${strapS})`, transformOrigin: `0 ${tp(5, 5, 1.6)[1] * S}px` }} /> : null}
        {car && glow > 0 ? <TL name={'strapW-' + car.type + strapCol} mk={() => TBODY.strap(car.type, C.white, true)} S={S} style={{ opacity: glow * 0.85 }} /> : null}
      </div>
      <TL name="cab" mk={TBODY.cab} S={S} />
      <TL name={'stripe' + stripe} mk={() => TBODY.stripe(stripe)} S={S} />
      <TL name={'beacon' + beacon} mk={() => TBODY.beacon(beacon)} S={S} />
    </div>
  );
}
// beacon: 2 frames, 0.6 s each (slow, no strobe). wheels: 2 frames while rolling.
const beaconF = (t, on = true) => on ? Math.floor(t / 0.6) % 2 : 0;
const wheelF = (t, rolling) => rolling ? Math.floor(t / 0.09) % 2 : 0;

// particles & UI (same vocabulary as 4차)
const Sq = ({ x, y, s, c, o = 1, r = 0 }) => <div style={{ position: 'absolute', left: snap(x - s / 2), top: snap(y - s / 2), width: s, height: s, background: c, opacity: o, transform: r ? `rotate(${r}deg)` : undefined }}></div>;
const Star = ({ x, y, s, c, o = 1 }) => (
  <div style={{ position: 'absolute', left: snap(x - s * 1.5), top: snap(y - s * 1.5), width: s * 3, height: s * 3, opacity: o }}>
    <div style={{ position: 'absolute', left: 0, top: s, width: s * 3, height: s, background: c }}></div>
    <div style={{ position: 'absolute', left: s, top: 0, width: s, height: s * 3, background: c }}></div>
  </div>
);
function burst(t, t0, n, cx, cy, rad, cols, seed, big) {
  if (t < t0 || t > t0 + 1) return null;
  const p = MOTION.enter(t, t0, t0 + 0.7), o = 1 - seg(t, t0 + 0.4, t0 + 0.95), out = [];
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2 + rnd(i, seed) * 0.4, d = rad * (0.55 + 0.45 * rnd(i, seed + 1)) * p;
    const x = cx + Math.cos(a) * d, y = cy + Math.sin(a) * d * 0.72, c = cols[i % cols.length], s = big && i % 3 === 0 ? 8 : 4;
    out.push(i % 2 ? <Star key={seed + '-' + i} x={x} y={y} s={s} c={c} o={o} /> : <Sq key={seed + '-' + i} x={x} y={y} s={s * 2} c={c} o={o} />);
  }
  return out;
}
function dust(t, t0, n, cx, cy, spread, seed) {
  if (t < t0 || t > t0 + 0.7) return null; const out = [];
  for (let i = 0; i < n; i++) { const p = MOTION.enter(t, t0, t0 + 0.6), side = i % 2 ? 1 : -1; out.push(<Sq key={'du' + seed + i} x={cx + side * p * (20 + rnd(i, seed) * spread)} y={cy - p * (6 + rnd(i, seed + 1) * 34)} s={8 + Math.floor(rnd(i, seed + 2) * 2) * 4} c={i % 3 ? C.mist2 : C.gray2} o={1 - seg(t, t0 + 0.3, t0 + 0.65)} />); }
  return out;
}
const Diamond = ({ cx, cy, hw, hh, col, sw = 4, o = 1, sc = 1, fill = 'none' }) => (
  <svg width={hw * 2 + 20} height={hh * 2 + 20} style={{ position: 'absolute', left: cx - hw - 10, top: cy - hh - 10, opacity: o, transform: `scale(${sc})`, overflow: 'visible' }} shapeRendering="crispEdges">
    <polygon points={`${hw + 10},10 ${hw * 2 + 10},${hh + 10} ${hw + 10},${hh * 2 + 10} 10,${hh + 10}`} fill={fill} stroke={col} strokeWidth={sw} vectorEffect="non-scaling-stroke" />
  </svg>
);
const Btn = ({ label, primary, coin, style, sc = 1, o = 1, onClick }) => React.createElement(onClick ? 'button' : 'div', { onClick, style: Object.assign({ border: 0, cursor: onClick ? 'pointer' : 'default', pointerEvents: o > 0.5 ? 'auto' : 'none', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, height: 60, padding: '0 22px', borderRadius: 14, whiteSpace: 'nowrap', background: primary ? UI.accent : UI.line, color: UI.fg, font: `700 22px ${FUI}`, transform: `scale(${sc})`, opacity: o }, style) },
  <span>{label}</span>, coin ? <span style={{ display: 'flex', alignItems: 'center', gap: 6, font: `400 20px ${FPX}`, color: C.gold2 }}><Coin s={2} />{coin}</span> : null);
const Chip = ({ label, bg, fg, sc = 1, o = 1, border }) => <div style={{ padding: '6px 14px', borderRadius: 10, background: bg, color: fg, font: `400 18px ${FPX}`, transform: `scale(${sc})`, opacity: o, boxShadow: border ? `inset 0 0 0 2px ${border}` : undefined }}>{label}</div>;
const Sfx = ({ t, cues, on }) => {
  const fired = React.useRef({ t: -1, done: new Set() });   // 큐를 지날 때 window.sfx(글귀)를 한 번씩 부른다
  if (t < fired.current.t) fired.current.done = new Set(); fired.current.t = t;
  cues.forEach((c, i) => { if (t >= c[0] && !fired.current.done.has(i)) { fired.current.done.add(i); if (window.sfx) window.sfx(c[1]); } });
  if (!on) return null; let a = null; cues.forEach(c => { if (t >= c[0] && t < c[0] + 0.9) a = c; });
  return a ? <div style={{ position: 'absolute', left: 28, bottom: 24, padding: '6px 12px', borderRadius: 8, background: 'rgba(11,14,31,.8)', boxShadow: `inset 0 0 0 2px ${C.slate}`, color: C.mist2, font: `400 16px ${FPX}`, opacity: 1 - seg(t, a[0] + 0.7, a[0] + 0.9) }}>♪ SFX 자리 · {a[1]}</div> : null;
};
const HudChip = ({ children, style }) => <div style={Object.assign({ display: 'flex', alignItems: 'center', gap: 10, height: 48, padding: '0 18px', borderRadius: 14, background: 'rgba(18,22,34,.9)', color: UI.fg, font: `700 20px ${FPX}` }, style)}>{children}</div>;
const SkipChip = ({ o = 1, onClick }) => <button onClick={onClick} style={{ position: 'absolute', right: 28, top: 28, opacity: o, border: 0, background: 'none', padding: 0, cursor: 'pointer', pointerEvents: o > 0.5 ? 'auto' : 'none' }}><HudChip style={{ gap: 8 }}>건너뛰기 <span style={{ color: C.blue3 }}>▶▶</span></HudChip></button>;
const Backdrop = () => (
  <div style={{ position: 'absolute', inset: 0, background: C.navy0 }}>
    {Array.from({ length: 26 }).map((_, i) => <Sq key={i} x={rnd(i, 40) * SW} y={rnd(i, 41) * 360} s={4} c={C.mist} o={0.2 + rnd(i, 42) * 0.35} />)}
  </div>
);

window.DV = { UI, SPRITES, CarImg, CAR_L, clamp, Easing, SW, SH, seg, lerp, MOTION, rnd, snap, FPX, FUI, C, RARITY, pj, pts, pg, box, yF, xF, gq, flat, svgWrap, CARS, carSVG,
  MK, MS, mpos, mapLayer, coinSVG, boxIconSVG, TK, TW, TH, TOX, TOY, tp, TBODY, tSVG, truckMini, tiltM, BED_TILT, BED_SLIDE,
  Pix, Car, Coin, MapL, TL, Truck, beaconF, wheelF, Sq, Star, burst, dust, Diamond, Btn, Chip, Sfx, HudChip, SkipChip, Backdrop, PIECES: {}, SCENES: [] };
})();

// 5차 · 탁송 — ① 출발 (수익/구매) ③ 도착 (구매·가게 앞 / 구매·차고 가득 / 수익·목적지)
// 배경은 게임 시제품의 마을 그림(isoTown), 차는 게임 차 스프라이트를 그대로 쓴다.
(() => {
const D = window.DV;
const { UI, CarImg, CAR_L, seg, lerp, MOTION, rnd, snap, FPX, FUI, C, RARITY, Truck, Pix, Coin, boxIconSVG, BED_TILT, BED_SLIDE, TOX, TOY, TK, beaconF, wheelF, Sq, Star, burst, dust, Btn, Sfx, HudChip, SkipChip } = D;
const SM = 2;                    // 시제품은 차 그림을 2배로 그린다
const TS = SM * 1.5;             // truck drawn 1.5× car scale
const CAM = { town: [1.3, 265], dest: [1.6, 432] };
let Z = 1.3, CY = 265;
const AX = TK * TS, AY = TK * TS / 2;
const LANE_Y = 774;              // lower (↘) lane centre; yellow centre dashes ≈ 740 + 0.5x
const lane = s => [s * AX, LANE_Y + s * AY];
const tOrigin = s => [s * AX + 2.5 * AX, 715 + s * AY - 2.5 * AY];
const toScr = (p, z = 0) => [p[0] * Z, (p[1] - z * AX - CY) * Z];
const ST = 15, SG = ST + BED_SLIDE - CAR_L - 0.2 + CAR_L / 2; // truck stop, car ground stop (centre)
const SLOTS = window.DV_SLOTS;   // 시제품 주차 칸 10개
const slotC = i => [SLOTS[i][0], SLOTS[i][1] - 7];
const carS = k => (k === 'casper' ? 88 : 100) / D.SPRITES[k][1];

const RING = { common: '2px dashed #d6dce6', uncommon: '2px solid #68a0f0', rare: '4px double #f08caa', legend: '3px solid #fcdc64' };
const Ring = ({ i, r, o = 1, sc = 1, glow }) => <div style={{ position: 'absolute', left: SLOTS[i][0] - 41, top: SLOTS[i][1] + 13 - 12, width: 83, height: 25, borderRadius: '50%', border: RING[r], boxSizing: 'border-box', transform: `rotate(-29deg) scale(${sc})`, opacity: o, boxShadow: glow ? `0 0 ${glow}px ${RARITY[r]}` : undefined }}></div>;
const Parked = ({ list }) => list.map(([i, k]) => { const [src, w, h] = D.SPRITES[k]; return <img key={'pk' + i} src={src} alt="" style={{ position: 'absolute', left: SLOTS[i][0] - w, top: SLOTS[i][1] - 7 - h, width: w * SM, height: h * SM, transform: 'scaleX(-1)' }} />; });   // 시제품 가게 화면과 같은 자리·크기, 테두리 없음
const World = ({ bg, z, cy, children }) => (
  <div style={{ position: 'absolute', left: 0, top: -cy * z, width: 1536, height: 1024, transform: `scale(${z})`, transformOrigin: '0 0' }}>
    <img src={bg} alt="" style={{ position: 'absolute', left: 0, top: 0, width: 1536, height: 1024, display: 'block' }} />
    {children}
  </div>
);
const TruckAt = ({ s, ...rest }) => { const p = tOrigin(s); return <div style={{ position: 'absolute', left: p[0] - TOX * TS, top: p[1] - TOY * TS }}><Truck S={TS} carScale={SM} {...rest} /></div>; };
const GCar = ({ k, s, z = 0, at, mirror }) => { const p = at || lane(s); return <CarImg k={k} s={SM} cx={p[0]} cy={p[1] - z * AX} mirror={mirror} />; };

const Band = ({ t, t0, children, bg = UI.accent, fg = '#fff' }) => {
  const p = MOTION.pop(t, t0, t0 + 0.3);
  return <div style={{ position: 'absolute', left: 0, right: 0, top: 110, display: 'flex', justifyContent: 'center', opacity: seg(t, t0, t0 + 0.08) }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 16, whiteSpace: 'nowrap', padding: '16px 36px', background: bg, color: fg, font: `900 40px ${FUI}`, borderRadius: 20, boxShadow: '0 8px 24px rgba(5,8,15,.45)', transform: `scale(${p})` }}>{children}</div>
  </div>;
};
const BottomBtn = ({ t, t0, label, onClick }) => <div style={{ position: 'absolute', left: 0, right: 0, bottom: 48, display: 'flex', justifyContent: 'center' }}><Btn label={label} primary sc={MOTION.pop(t, t0, t0 + 0.3)} o={seg(t, t0, t0 + 0.1)} style={{ width: 280 }} onClick={onClick} /></div>;
const KindChip = ({ buy }) => <span style={{ padding: '4px 12px', borderRadius: 16, background: buy ? UI.warnBg : UI.okBg, color: buy ? UI.warn : UI.ok, font: `700 17px ${FUI}` }}>{buy ? '구매 탁송' : '수익 탁송'}</span>;
const RarChip = ({ r, label }) => <span style={{ padding: '3px 12px', borderRadius: 16, border: RING[r], color: RARITY[r], font: `700 17px ${FUI}` }}>{label}</span>;
const JobChip = ({ buy, d, o = 1 }) => (
  <div style={{ position: 'absolute', left: 28, top: 28, opacity: o }}>
    <HudChip><KindChip buy={buy} />{buy ? <React.Fragment>{d.name} <RarChip r={d.tier} label={d.rarLabel} /></React.Fragment> : d.name + ' · 손님 차'}</HudChip>
  </div>
);

// car riding / leaving the bed. ub = rear x on the bed (truck units); centre past the ramp foot → bed group.
function carState(k, ub, ts, slide, z) {
  if (ub + CAR_L / 2 >= 0) return { bed: { type: k, u: ub, z } };
  return { ground: { k, s: ts + slide + ub + CAR_L / 2, z } };
}
const leanAt = (t, a) => t < a + 0.45 ? -0.07 * Math.sin(Math.PI * seg(t, a, a + 0.22)) + 0.03 * Math.sin(Math.PI * seg(t, a + 0.22, a + 0.4)) : 0;

function Depart({ t, buy, sfx = true, stripe = C.blue, d }) {
  const k = d.car, strapCol = RARITY[d.tier];
  Z = CAM.town[0]; CY = CAM.town[1];
  const ts = t < 1.9 ? lerp(-24, ST, MOTION.enter(t, 0, 0.6)) : lerp(ST, 80, MOTION.drop(t, 1.9, 2.5));
  const slide = BED_SLIDE * (MOTION.enter(t, 0.6, 0.82) - MOTION.inout(t, 1.7, 1.9));
  const tilt = BED_TILT * (MOTION.drop(t, 0.76, 1.0) - MOTION.enter(t, 1.6, 1.75));
  const ub0 = -CAR_L - 0.2, uf = 1;
  let cs = {};
  if (t >= 1.0) {
    const ub = lerp(ub0, uf, MOTION.inout(t, 1.0, 1.5));
    const z = 0.5 * Math.sin(Math.PI * seg(t, 1.1, 1.22)) + 0.7 * Math.sin(Math.PI * seg(t, 1.5, 1.62));
    cs = carState(k, ub, ts, slide, z);
  } else cs = { ground: { k, s: lerp(-26, SG, MOTION.enter(t, 0.2, 1.0)) } };   // 손님 차·산 차 모두 왼쪽 도로에서 들어온다(시제품엔 손님 차 주차 칸이 없음)
  const strapP = seg(t, 1.75, 1.9), glow = buy ? Math.sin(Math.PI * seg(t, 1.88, 2.18)) : 0, tc = RARITY[d.tier];
  const foot = toScr(lane(ts + BED_SLIDE)), front = toScr(lane(ST + 12)), mid = toScr(lane(ST + 5), 2.4);
  const twinkle = buy && t > 1.88 && t < 2.3 ? [0, 1, 2, 3].map(i => <Star key={'tw' + i} x={mid[0] + [-90, -30, 30, 80][i]} y={mid[1] + [-10, 14, -30, 4][i]} s={4} c={i % 2 ? '#fff' : tc} o={Math.sin(Math.PI * seg(t, 1.9 + i * 0.05, 2.2 + i * 0.05))} />) : null;
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: UI.bg }}>
      <World bg={window.DV_IMG.town} z={Z} cy={CY}>
        <Parked list={d.parked} />
        {cs.ground ? <GCar {...cs.ground} /> : null}
        <TruckAt s={ts} slide={slide} tilt={tilt} lean={leanAt(t, 0.5)} wheel={wheelF(t, t < 0.55 || t > 1.9)} beacon={beaconF(t - 1.9, t >= 1.9)} stripe={stripe} car={cs.bed} strapCol={strapCol} strapP={strapP} glow={glow} />
      </World>
      {dust(t, 0.5, 8, front[0], front[1], 60, 3)}
      {dust(t, 1.0, 12, foot[0], foot[1] + 20, 90, 7)}
      {twinkle}
      <JobChip buy={buy} d={d} o={seg(t, 0, 0.2) * (1 - seg(t, 1.95, 2.1))} />
      <SkipChip o={1 - seg(t, 1.95, 2.1)} onClick={d.onSkip} />
      <Band t={t} t0={1.95}><span>탁송 출발!</span><span style={{ color: UI.warn }}>· {d.dest} {d.km}km</span></Band>
      <BottomBtn t={t} t0={2.3} label="확인" onClick={d.onClose} />
      <Sfx t={t} cues={[[0.5, '끽'], [1.0, '경사판 쿵'], [1.12, '덜컹'], [1.75, '끈 착'], [1.9, '엔진 부릉']]} on={sfx} />
    </div>
  );
}

function Arrive({ t, buy, full, sfx = true, stripe = C.blue, d }) {
  const k = d.car, rc = RARITY[d.tier], strapCol = rc;
  [Z, CY] = buy ? CAM.town : CAM.dest;
  const ts = lerp(-24, ST, MOTION.enter(t, 0, 0.4));
  const slide = BED_SLIDE * (MOTION.enter(t, 0.4, 0.58) - MOTION.inout(t, 1.2, 1.45));
  const tilt = BED_TILT * (MOTION.drop(t, 0.5, 0.66) - MOTION.enter(t, 1.12, 1.25));
  const ub0 = -CAR_L - 0.2, uf = 1;
  let cs = {}, flyer = null, landed = false;
  const gp = lane(SG), sc1 = slotC(d.slot || 0);
  if (t < 1.05) cs = carState(k, lerp(uf, ub0, MOTION.inout(t, 0.66, 1.05)), ts, t < 0.66 ? 0 : slide, 0);
  else if (buy && !full) {
    const p = MOTION.inout(t, 1.1, 1.4), x = lerp(gp[0], sc1[0], p), y = lerp(gp[1], sc1[1] + 10, p) - 1.6 * AX * Math.sin(Math.PI * p);
    cs = { ground: { k, at: [x, y], mirror: p >= 0.5 } };
    landed = t >= 1.4;
  } else if (buy && full) {
    if (t < 1.1) cs = { ground: { k, s: SG } };
    else {
      const p = MOTION.inout(t, 1.1, 1.45), a = toScr(gp), b = [150, 52];
      const x = lerp(a[0], b[0], p), y = lerp(a[1], b[1], p) - 160 * Math.sin(Math.PI * p), sc = lerp(SM * Z, 0.25, p);
      if (p < 1) flyer = <CarImg k={k} s={sc} cx={x} cy={y} />;
    }
  } else cs = { ground: { k, s: SG } };
  const strapP = t < 0.6 ? 1 : 0;
  const slotS = toScr([SLOTS[1][0], SLOTS[1][1] + 13]), carSc = toScr(gp, 1);
  const parked = buy ? d.parked : [];
  const coins = [];
  if (!buy) for (let j = 0; j < 6; j++) {
    const t0 = 1.25 + j * 0.05, L2 = (t - t0) / 0.45; if (L2 < 0 || L2 > 1) continue;
    const e = MOTION.enter(t, t0, t0 + 0.45), s0 = [carSc[0] + (j - 2.5) * 18, carSc[1] - 40], c1 = [s0[0] + 160, s0[1] - 300 - j * 14], end = [1200, 52];
    const x = (1 - e) * (1 - e) * s0[0] + 2 * (1 - e) * e * c1[0] + e * e * end[0], y = (1 - e) * (1 - e) * s0[1] + 2 * (1 - e) * e * c1[1] + e * e * end[1];
    const spn = Math.abs(Math.cos(L2 * 9 + j)), sx = spn > 0.66 ? 1 : spn > 0.33 ? 0.6 : 0.25;
    coins.push(<div key={'co' + j} style={{ position: 'absolute', left: snap(x - 20), top: snap(y - 20), transform: `scaleX(${sx})` }}><Coin s={3.5} /></div>);
  }
  let hit = -9; for (let j = 0; j < 6; j++) if (t >= 1.7 + j * 0.05) hit = 1.7 + j * 0.05;
  const money = d.money + Math.round(d.reward * seg(t, 1.7, 1.95));
  const stampP = MOTION.drop(t, 1.1, 1.26), stampSq = t >= 1.26 ? 1 - 0.08 * Math.sin(Math.PI * seg(t, 1.26, 1.4)) : 1;
  const boxBump = full ? 1 + 0.18 * Math.sin(Math.PI * seg(t, 1.45, 1.6)) : 1;
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: UI.bg }}>
      <World bg={buy ? window.DV_IMG.town : window.DV_IMG.dest} z={Z} cy={CY}>
        {!buy ? <svg width="1536" height="1024" style={{ position: 'absolute', left: 0, top: 0 }}><polygon points="660,560 860,660 660,760 460,660" fill="rgba(255,255,255,.12)" stroke="rgba(255,255,255,.7)" strokeWidth="3" strokeDasharray="12 8" /></svg> : null}
        {!buy ? <div style={{ position: 'absolute', left: 540, top: 640, width: 240, textAlign: 'center', color: '#1c2a14', font: `700 18px ${FUI}` }}>{d.dest}<br />목적지 건물 자리(임시 그림)</div> : null}
        {landed ? <Ring i={d.slot || 0} r={d.tier} sc={1 + 0.06 * Math.sin((t - 1.4) * 6)} glow={10 + 8 * (1 - seg(t, 1.4, 1.8))} o={seg(t, 1.4, 1.46) * (1 - seg(t, 2.0, 2.4))} /> : null}   {/* 칸 테두리는 도착 순간에만 잠깐 */}
        <Parked list={parked} />
        {cs.ground ? <GCar {...cs.ground} /> : null}
        <TruckAt s={ts} slide={slide} tilt={tilt} lean={leanAt(t, 0.32)} wheel={wheelF(t, t < 0.36)} beacon={beaconF(t, t < 0.4 || t > 1.45)} stripe={stripe} car={cs.bed} strapCol={strapCol} strapP={strapP} />
      </World>
      {buy && !full ? dust(t, 1.4, 10, slotS[0], slotS[1], 70, 11) : null}
      {buy && !full ? burst(t, 1.42, 12, slotS[0], slotS[1] - 60, 120, [rc, '#fff'], 21) : null}
      {dust(t, 0.66, 10, toScr(lane(ST + BED_SLIDE))[0], toScr(lane(ST + BED_SLIDE))[1] + 20, 80, 13)}
      {flyer}
      <div style={{ position: 'absolute', left: 28, top: 28, display: 'flex', gap: 12, opacity: seg(t, 0, 0.2) }}>
        {full ? <HudChip style={{ transform: `scale(${boxBump})` }}><Pix id="boxicon" mk={boxIconSVG} w={30} h={26} s={1.5} />임시 보관함 <span style={{ color: UI.warn }}>{t >= 1.45 ? 1 : 0}</span></HudChip> : <HudChip><KindChip buy={buy} />{buy ? '우리 가게 도착' : d.dest + ' 도착'}</HudChip>}
      </div>
      {!buy ? <div style={{ position: 'absolute', right: 28, top: 28, transform: `scale(${1 + 0.12 * (1 - seg(t, hit, hit + 0.14))})` }}><HudChip style={{ height: 60, padding: '0 22px', gap: 12, fontSize: 28, fontWeight: 900 }}><Coin s={3} />{money.toLocaleString('ko-KR')}</HudChip></div> : null}
      {buy && !full ? <Band t={t} t0={1.5} bg={UI.card}><span>구매 탁송 도착</span><RarChip r={d.tier} label={d.name + ' · ' + d.rarLabel} /></Band> : null}
      {full ? <Band t={t} t0={1.55} bg={UI.card}><span style={{ font: `700 28px ${FUI}` }}>차고가 가득 차서 임시 보관함에 넣었어요</span></Band> : null}
      {!buy && t >= 1.1 ? <div style={{ position: 'absolute', left: 0, right: 0, top: 100, display: 'flex', justifyContent: 'center' }}>
        <div style={{ padding: '18px 40px', borderRadius: 16, background: '#fff8e8', color: C.red, font: `900 50px ${FUI}`, boxShadow: `inset 0 0 0 6px ${C.red}, inset 0 0 0 10px #fff8e8, inset 0 0 0 13px ${C.red}, 0 8px 24px rgba(5,8,15,.45)`, transform: `rotate(-7deg) scale(${lerp(1.8, 1, stampP) * stampSq})`, opacity: seg(t, 1.1, 1.16) }}>수익 탁송 완료</div>
      </div> : null}
      {!buy ? <div style={{ position: 'absolute', left: 0, right: 0, top: 222, display: 'flex', justifyContent: 'center', opacity: seg(t, 1.3, 1.45) }}><HudChip style={{ color: UI.warn, fontSize: 28, fontWeight: 900 }}>완료 보상 +{d.reward.toLocaleString('ko-KR')}</HudChip></div> : null}
      {coins}
      <BottomBtn t={t} t0={1.8} label="확인" onClick={d.onClose} />
      <Sfx t={t} cues={buy ? [[0.3, '끽'], [0.66, '스르륵'], [full ? 1.45 : 1.4, full ? '보관함 톡' : '착']] : [[0.3, '끽'], [0.66, '스르륵'], [1.1, '도장 쾅'], [1.7, '동전 짤랑']]} on={sfx} />
    </div>
  );
}

Object.assign(D.PIECES, {
  depart: (t, o) => <Depart t={t} buy={o.d.buy} d={o.d} sfx={o.sfx} stripe={o.stripe} />,
  arrive: (t, o) => <Arrive t={t} buy={o.d.buy} full={o.d.full} d={o.d} sfx={o.sfx} stripe={o.stripe} />,
});
})();

// 5차 · 탁송 — ② 고속도로 반복(6초, 이음매 없음) + 상태 정지 컷 + 움직임 줄이기 + 트럭 시트 + 재생기
(() => {
const D = window.DV;
const { UI, CarImg, seg, lerp, MOTION, rnd, snap, FPX, FUI, C, RARITY, CARS, box, yF, xF, gq, flat, svgWrap, Pix, Car, Truck, TL, TBODY, TW, TH, TOX, TOY, truckMini, BED_TILT, BED_SLIDE, Coin, Sfx, HudChip, SW, SH } = D;
const LOOP = 6, G = 256, V = G / LOOP, CARREL = 128; // ground 256 tu / loop; cars ±128 tu / loop → 1.5× / 0.5× truck speed
const S = 3, SW4 = 4.2, K = 16.8, PW = 1304, PH = 736; // truck/cars native×3, world sprites (k=4)×4.2 → 16.8 px per unit
const spr = (id, vb, body) => { const [x, y, w, h] = vb; return { id, vb, w, h, mk: () => svgWrap(`${x} ${y} ${w} ${h}`, w, h, body()) }; };
const SP = {
  dash: spr('dash', [-2, -1, 19, 11], () => flat(gq(0, 0, 4, 0.3, 0, 4, C.white))),
  joint: spr('joint', [-77, -1, 79, 41], () => flat(gq(0, 0, 0.3, 19, 0, 4, C.gray))),
  rail: spr('rail', [-3, -8, 22, 18], () => box(0.1, 0.25, 0, 0.3, 0.3, 1.4, 4, [C.gray2, C.gray, C.gray0]) + box(0, 0, 0.8, 4, 0.25, 0.6, 4, [C.white, C.mist2, C.mist])),
  wall: spr('wall', [-5, -23, 39, 42], () => box(0, 0, 0, 8, 0.5, 5, 4, [C.mist2, C.mist, C.slate2]) + flat(yF(0.5, 0.3, 7.6, 1.6, 1.75, 4, C.slate2) + yF(0.5, 0.3, 7.6, 3.2, 3.35, 4, C.slate2)) + box(7.6, 0.5, 0, 0.4, 0.3, 5.3, 4, [C.gray2, C.gray, C.gray0])),
  tree: spr('tree', [-14, -26, 28, 40], () => box(1.1, 1.1, 0, 0.8, 0.8, 2.2, 4, [C.wood, C.dirt, C.bronze]) + box(0, 0, 2.2, 3, 3, 2.6, 4, [C.lime, C.green, C.greenD]) + box(0.6, 0.6, 4.8, 1.8, 1.8, 1.4, 4, [C.lime, C.green, C.greenD])),
  sign: spr('sign', [-4, -38, 56, 58], () => box(1.2, 0.2, 0, 0.35, 0.3, 4.2, 4, [C.gray2, C.gray, C.gray0]) + box(10.4, 0.2, 0, 0.35, 0.3, 4.2, 4, [C.gray2, C.gray, C.gray0]) + box(0, -0.1, 4, 12, 0.3, 4.5, 4, [C.green, C.greenD, C.greenD]) + flat(yF(0.21, 0.4, 11.6, 4.3, 4.45, 4, C.white) + yF(0.21, 0.4, 11.6, 8.05, 8.2, 4, C.white) + yF(0.21, 0.4, 0.55, 4.3, 8.2, 4, C.white) + yF(0.21, 11.45, 11.6, 4.3, 8.2, 4, C.white))),
  hills: spr('hills', [-28, -30, 72, 64], () => box(0, 0, 0, 10, 6, 2.5, 4, [C.lime, C.green, C.greenD]) + box(2, 1, 2.5, 6, 4, 2, 4, [C.lime, C.green, C.greenD]) + box(3.5, 2, 4.5, 3, 2, 1.5, 4, [C.lime, C.green, C.greenD])),
  houses: spr('houses', [-14, -16, 46, 40], () => box(0, 0, 0, 3, 2.5, 2, 4, [C.white, C.mist2, C.mist]) + box(-0.2, -0.2, 2, 3.4, 2.9, 0.8, 4, [C.orange, C.red, C.pinkD]) + flat(yF(2.5, 0.8, 1.6, 0.6, 1.3, 4, C.blue3)) + box(4.5, 1, 0, 2.5, 2, 1.6, 4, [C.white, C.mist2, C.mist]) + box(4.3, 0.8, 1.6, 2.9, 2.4, 0.7, 4, [C.blue2, C.blue, C.blueD])),
  rest: spr('rest', [-36, -36, 92, 70], () => box(0, 0, 0, 12, 6, 4, 4, [C.white, C.mist2, C.mist]) + box(-0.3, -0.3, 4, 12.6, 6.6, 0.6, 4, [C.blue2, C.blue, C.blueD]) + flat([1, 3.6, 6.2, 8.8].map(x => yF(6, x, x + 1.8, 1.2, 3.0, 4, C.blue3)).join('')) + yF(6, 5.4, 6.6, 0, 2.2, 4, C.slate) + box(3, 6.4, 0, 0.5, 0.5, 0.8, 4, [C.wood2, C.wood, C.dirt]) + box(8, 6.4, 0, 0.5, 0.5, 0.8, 4, [C.wood2, C.wood, C.dirt])),
};
const Spr = ({ k, x, y, O }) => { const p = [O[0] + (x - y) * K, O[1] + (x + y) * K / 2], d = SP[k]; return <Pix id={'hw-' + k} mk={d.mk} w={d.w} h={d.h} s={SW4} style={{ position: 'absolute', left: Math.round(p[0] + d.vb[0] * SW4), top: Math.round(p[1] + d.vb[1] * SW4) }} />; };
// periodic copies of one sprite along x at row y. shift = world scroll for this layer.
function row(k, y, p, x0, shift, O, lo = -60, hi = 54) {
  const out = [], off = ((shift % p) + p) % p;
  for (let x = x0 - off - Math.ceil((x0 - (y + lo)) / p) * p; x < y + hi; x += p) out.push(<Spr key={k + y + '_' + x.toFixed(2)} k={k} x={x} y={y} O={O} />);
  return out;
}
const wrap = (v, lo, n) => lo + (((v - lo) % n) + n) % n;
const scrH = (O, x, y, z) => [O[0] + (x - y) * K, O[1] + (x + y) * K / 2 - z * K];
const quad = (O, x1, y1, x2, y2, f, key) => <polygon key={key} points={[[x1, y1], [x2, y1], [x2, y2], [x1, y2]].map(a => scrH(O, a[0], a[1], 0).join(',')).join(' ')} fill={f} />;

function HighwayPic({ t, still, mode, d }) {
  const rest = mode === 'wait';
  const O = rest ? [690, 40] : [690, 290];
  const lt = still ? 0 : ((t % LOOP) + LOOP) % LOOP, sc = lt * V;
  // joints every 32 tu → 1-art-px hop when a joint crosses a wheel (front, then rear)
  let hop = 0;
  if (!still && !rest) { const jo = ((-sc % 32) + 32) % 32; [[10.9, 12.5], [1.3, 4.7]].forEach(([a, b]) => { for (let j = -1; j < 2; j++) { const jx = jo + j * 32; if (jx >= a && jx <= b) hop = S; } }); }
  const truckX = rest ? 4 : 0, truckY = rest ? 26.5 : 7.5;
  const tp = scrH(O, truckX, truckY, 0);
  const carA = wrap(-30 + CARREL * lt / LOOP, 1.5 - 70, CARREL), carC = wrap(40 - CARREL * lt / LOOP, 14.6 - 70, CARREL);
  const signX = wrap(30 - sc, -1.4 - 72, G), signP = scrH(O, signX, -1.4, 8.05);
  const showCars = mode !== 'reduced';
  const carEl = (x, y, ty, key) => { const p = scrH(O, x + 4, y + 2, 0); return <CarImg key={key} k={ty} s={S / 1.5} cx={Math.round(p[0])} cy={Math.round(p[1])} />; };
  return (
    <div style={{ position: 'absolute', inset: 0, background: '#8fcb45', overflow: 'hidden' }}>
      {row('hills', -34, 32, 0, sc / 8, O, -60, 90)}
      {row('houses', -20, 32, 14, sc / 4, O, -60, 90)}
      {row('tree', -8, 16, 3, sc, O)}
      {row('wall', -3, 8, 0, sc, O)}
      <svg width={PW} height={PH} style={{ position: 'absolute', left: 0, top: 0 }} shapeRendering="crispEdges">
        {quad(O, -300, -2.5, 300, 0.5, '#e3c9b4', 'a')}{quad(O, -300, -2.5, 300, -2.2, '#c4a690', 'a2')}
        {quad(O, -300, 0.5, 300, 19.5, '#4f5266', 'b')}
        {quad(O, -300, 19.5, 300, 21.5, '#e3c9b4', 'c')}{quad(O, -300, 21.2, 300, 21.5, '#c4a690', 'c2')}
        {quad(O, -300, 0.5, 300, 0.8, C.white, 'd')}
        {quad(O, -300, 19.2, 300, 19.5, C.white, 'e')}
        {rest ? [quad(O, -300, 23, 300, 36, '#4f5266', 'f'), quad(O, -300, 25.2, 300, 25.5, C.white, 'g'), quad(O, -300, 32.6, 300, 32.9, C.white, 'h')] : null}
      </svg>
      {row('joint', 0.5, 32, 0, sc, O)}
      {row('dash', 6.68, 8, 0, sc, O)}
      {row('dash', 13.02, 8, 4, sc, O)}
      <Spr k="sign" x={signX} y={-1.4} O={O} />
      <div style={{ position: 'absolute', left: signP[0] + 8, top: signP[1] + 4, width: 128, transform: 'skewY(26.565deg)', transformOrigin: '0 0', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0, color: '#fff', font: `900 20px/1.15 ${FPX}` }}>
        <span>{d.dest}</span><span style={{ color: '#ffd36b' }}>{Math.max(0, d.km - d.done).toFixed(1)}km</span>
      </div>
      {showCars ? carEl(carA, 1.5, 'hyundai_casper', 'cA') : null}
      <div style={{ position: 'absolute', left: tp[0] - TOX * S, top: tp[1] - TOY * S - hop }}>
        <Truck S={S} wheel={still || rest ? 0 : Math.floor(lt / 0.1) % 2} beacon={rest || mode === 'reduced' ? 0 : Math.floor(lt / 0.6) % 2} car={{ type: d.car, u: 1 }} strapP={1} strapCol={RARITY[d.tier]} />
      </div>
      {showCars ? carEl(carC, 14.6, 'hyundai_staria_tourer', 'cC') : null}
      {row('rail', 21.7, 4, 0, sc, O)}
      {rest ? <Spr k="rest" x={20} y={24} O={O} /> : row('tree', 24.5, 16, 9, sc, O)}
      {rest ? row('tree', 36, 16, 5, 0, O) : null}
    </div>
  );
}

const ST = {
  run: { text: '학교까지', dist: true, fill: '#68a0f0', bg: UI.card },
  wait: { text: '도착 확인 대기 중 · 다음 주차 때 다시 확인해요', fill: '#6b7184', bg: '#2c3141', fg: '#c3c9d6' },
  due: { text: '학교까지', dist: true, fill: '#68a0f0', bg: UI.card, due: '남은 시간 3시간' },
  reduced: { text: '학교까지', dist: true, fill: '#68a0f0', bg: UI.card, chip: '움직임 줄이기 켜짐' },
};
function InfoBand({ mode, d }) {
  const s = ST[mode], done = d.done, total = d.km, pct = Math.min(1, done / total);
  return (
    <div style={{ position: 'absolute', left: 28, top: 28, width: PW, height: 132, boxSizing: 'border-box', padding: '20px 28px', borderRadius: 22, background: s.bg, display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        <div style={{ font: `900 30px ${FPX}`, color: s.fg || '#fff' }}>{s.dist ? d.dest + '까지' : s.text}{s.dist ? <span> · <span style={{ color: '#68a0f0' }}>{done}</span> / {total}km</span> : null}</div>
        <div style={{ flex: 1 }}></div>
        {s.due ? <div style={{ padding: '6px 16px', borderRadius: 16, background: UI.warnBg, color: UI.warn, font: `700 20px ${FPX}` }}>{d.due || s.due}</div> : null}
        {s.chip ? <div style={{ padding: '6px 16px', borderRadius: 16, background: UI.okBg, color: UI.ok, font: `700 18px ${FPX}` }}>{s.chip}</div> : null}
        <div style={{ padding: '6px 16px', borderRadius: 16, background: UI.okBg, color: UI.ok, font: `700 18px ${FPX}` }}>{d.kindLabel}</div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, font: `700 18px ${FUI}`, color: UI.mut }}>
        <span>우리 가게</span>
        <div style={{ position: 'relative', flex: 1, height: 12, borderRadius: 6, background: UI.line }}>
          <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: (pct * 100) + '%', borderRadius: 6, background: s.fill }}></div>
          <div style={{ position: 'absolute', left: `calc(${pct * 100}% - 34px)`, top: -36, filter: mode === 'wait' ? 'grayscale(1)' : undefined }}><Pix id="truckmini" mk={truckMini} w={TW} h={TH} s={0.62} /></div>
        </div>
        <span>{d.dest}</span>
      </div>
    </div>
  );
}
function Highway({ t, mode = 'run', still, d }) {
  return (
    <div style={{ position: 'absolute', inset: 0, background: UI.bg, overflow: 'hidden' }}>
      <div style={{ position: 'absolute', left: 28, top: 184, width: PW, height: PH, borderRadius: 22, overflow: 'hidden' }}>
        <HighwayPic t={t} still={still || mode === 'reduced' || mode === 'wait'} mode={mode} d={d} />

      </div>
      <InfoBand mode={mode} d={d} />
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 8, textAlign: 'center', font: `500 17px ${FUI}`, color: UI.mut }}>진행 막대는 주차할 때 확인된 운전 거리만 반영해요</div>
    </div>
  );
}

Object.assign(D.PIECES, { highway: (t, o) => <Highway t={t} mode={o.d.mode || 'run'} d={o.d} /> });
})();
