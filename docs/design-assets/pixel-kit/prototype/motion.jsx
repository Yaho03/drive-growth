// 4차 · 연출(Claude Design 원본: motion-piece.jsx)을 시제품용으로 옮긴 것.
// 바꾼 점: 우리 차 그림·이름·금액·레벨을 props(d)로 받고, 버튼은 실제로 눌리며, 미리보기 전용 Reel/MotionApp은 뺐다.
// 원본의 픽셀 글꼴(Galmuri)은 허용 CDN이 아니라 IBM Plex Sans KR로 대신한다.
const clamp = window.clamp || ((v, a, b) => Math.min(b, Math.max(a, v)));
const Easing = window.Easing || {
  easeOutCubic: t => 1 - Math.pow(1 - t, 3),
  easeInQuad: t => t * t,
  easeOutBack: t => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); },
};
const SW = 1360, SH = 948;
const seg = (t, a, b) => clamp((t - a) / (b - a), 0, 1);
const lerp = (a, b, p) => a + (b - a) * p;
const MOTION = {
  enter: (t, a, b) => Easing.easeOutCubic(seg(t, a, b)),
  pop: (t, a, b) => Easing.easeOutBack(seg(t, a, b)),
  drop: (t, a, b) => Easing.easeInQuad(seg(t, a, b)),
};
const rnd = (i, s) => { const x = Math.sin(i * 127.1 + (s || 1) * 311.7) * 43758.5453; return x - Math.floor(x); };
const snap = (v, g) => Math.round(v / (g || 4)) * (g || 4);
const FPX = "'IBM Plex Sans KR',system-ui,sans-serif";
const FUI = "'IBM Plex Sans KR',system-ui,sans-serif";
const C = {
  ink: '#0b0e1f', navy0: '#141a33', navy: '#1d2547', navy2: '#2a3463', slate: '#3b4a85', slate2: '#56679f', mist: '#8796c4', mist2: '#c3cce6',
  white: '#f4f5fa', blue: '#2f6bff', blue2: '#4f8cff', blue3: '#9cc1ff', blueD: '#1b3fb0', pink: '#ff4fa3', pink2: '#ff8cc6', pinkD: '#b02a74',
  gold: '#ffc83d', gold2: '#ffe38a', goldD: '#d98a1c', bronze: '#8a4a12', red: '#e8483f', orange: '#ff8a5c', green: '#46c46a', lime: '#a5e86a',
  greenD: '#2b8a5a', dirt: '#6e5338', wood: '#a07a50', wood2: '#d9b380', gray0: '#4a4a5a', gray: '#7a7a8c', gray2: '#b5b5c3', tire: '#1f1f2a',
};

// ── iso → svg strings ─────────────────────────────────────────────
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

const CARS = {
  hatch: { L: 7, Wd: 4, ch: 1.5, cab: [1.3, 4.0, 1.7, 0.35], body: [C.lime, C.green, C.greenD] },
  sedan: { L: 8.6, Wd: 4, ch: 1.3, cab: [2.0, 4.4, 1.5, 0.4], body: [C.blue3, C.blue2, C.blueD] },
  coupe: { L: 8.6, Wd: 4, ch: 1.1, cab: [2.8, 3.3, 1.2, 0.5], body: [C.pink2, C.pink, C.pinkD], stripe: C.white },
  gt: { L: 9, Wd: 4.2, ch: 1.1, cab: [3.0, 3.1, 1.1, 0.55], body: [C.gold2, C.gold, C.goldD], stripe: C.ink, spoiler: true },
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
const OurCar = ({ car, s = 5, style }) => <img src={car.src} alt="" style={Object.assign({ display: 'block', width: car.w * s, height: car.h * s, imageRendering: 'pixelated', filter: `hue-rotate(${car.hue || 0}deg)` }, style)} />;
const carAnchor = type => { const c = CARS[type]; return [(c.L / 2 - c.Wd / 2) * 4 + 20, (c.L / 2 + c.Wd / 2) * 2 + 20]; };

// map (shop) layers — shared viewBox so layers stack 1:1
const MK = 8, MS = 5, MVB = '-84 -40 200 144';
const mpos = (x, y, z) => { const p = pj(x, y, z, MK); return [(p[0] + 84) * MS, (p[1] + 40) * MS]; };
const slotLines = (x0, y1, y2, w, th, col) => gq(x0, y1, x0 + th, y2, 0, MK, col) + gq(x0 + w - th, y1, x0 + w, y2, 0, MK, col) + gq(x0, y1, x0 + w, y1 + th, 0, MK, col);
const WOOD = [C.wood2, C.wood, C.dirt];
const FRONT_PLANKS = (() => {
  const a = [];
  for (let i = 0; i < 6; i++) a.push({ b: [5 + i * 0.6, 9.28, 0, 0.55, 0.12, 1.1] });
  for (let j = 0; j < 6; j++) a.push({ b: [8.48, 5.6 + j * 0.63, 0, 0.12, 0.56, 1.1] });
  a.forEach(p => { p.o = p.b[0] + p.b[1]; p.c = mpos(p.b[0] + p.b[3] / 2, p.b[1] + p.b[4] / 2, 0.55); });
  return a.sort((x, y) => x.o - y.o);
})();
function mapLayer(name) {
  const k = MK; let s = '';
  if (name === 'ground') {
    s += box(0, 0, -0.6, 14, 10, 0.6, k, [C.green, C.greenD, C.dirt]);
    s += gq(4.8, 0.6, 13.6, 9.6, 0, k, C.gray0) + gq(4, 2.2, 4.8, 3.2, 0, k, C.gray2) + gq(5, 5.6, 8.6, 9.4, 0, k, C.dirt);
    let l = ''; for (let i = 0; i < 5; i++) l += slotLines(5.2 + i * 1.6, 1.0, 3.8, 1.6, 0.14, C.white);
    for (let i = 0; i < 3; i++) l += slotLines(9.2 + i * 1.4, 6.0, 8.8, 1.4, 0.14, C.slate2);
    s += flat(l);
  } else if (name === 'newLot') s += gq(5, 5.6, 8.6, 9.4, 0, k, C.gray0);
  else if (name.indexOf('slot') === 0) s += flat(slotLines(5.2 + (+name[4]) * 1.6, 6.0, 8.8, 1.6, 0.14, C.white));
  else if (name.indexOf('glow') === 0) s += flat(slotLines(5.2 + (+name[4]) * 1.6, 6.0, 8.8, 1.6, 0.34, C.gold2) + gq(5.4 + (+name[4]) * 1.6, 6.2, 6.6 + (+name[4]) * 1.6, 8.8, 0, k, 'rgba(255,227,138,.35)'));
  else if (name === 'bld1') {
    s += box(1, 1, 0, 3, 4, 2.4, k, [C.white, C.mist2, C.mist]) + box(0.8, 0.8, 2.4, 3.4, 4.4, 0.5, k, [C.orange, C.red, C.pinkD]);
    s += xF(4, 2.3, 3.1, 0, 1.3, k, C.slate) + xF(4, 1.3, 4.7, 1.6, 2.2, k, C.blue) + yF(5, 1.5, 2.3, 1.1, 1.8, k, C.blue3) + yF(5, 2.7, 3.5, 1.1, 1.8, k, C.blue3);
  } else if (name === 'bld2') {
    s += box(1, 1, 0, 3, 4, 4.2, k, [C.white, C.mist2, C.mist]) + box(0.8, 0.8, 4.2, 3.4, 4.4, 0.5, k, [C.orange, C.red, C.pinkD]);
    s += xF(4, 2.3, 3.1, 0, 1.3, k, C.slate) + xF(4, 1.3, 4.7, 3.3, 3.9, k, C.blue);
    [1.1, 2.6].forEach(z => { s += yF(5, 1.5, 2.3, z, z + 0.7, k, C.blue3) + yF(5, 2.7, 3.5, z, z + 0.7, k, C.blue3); });
    s += xF(4, 1.3, 2.0, 1.6, 2.3, k, C.blue3) + xF(4, 3.4, 4.3, 1.6, 2.3, k, C.blue3);
    s += box(1, 5.4, 0, 3, 2.4, 2.0, k, [C.gray2, C.gray, C.gray0]) + box(0.9, 5.3, 2.0, 3.2, 2.6, 0.35, k, [C.blue2, C.blue, C.blueD]);
    s += xF(4, 5.7, 7.5, 0, 1.5, k, C.mist2) + flat(xF(4, 5.7, 7.5, 0.5, 0.6, k, C.gray) + xF(4, 5.7, 7.5, 1.0, 1.1, k, C.gray));
  } else if (name === 'fenceBack') {
    for (let i = 0; i < 6; i++) s += box(5 + i * 0.6, 5.6, 0, 0.55, 0.12, 1.1, k, WOOD);
    for (let j = 0; j < 6; j++) s += box(5, 5.6 + j * 0.63, 0, 0.12, 0.56, 1.1, k, WOOD);
  } else if (name.indexOf('plank') === 0) { const b = FRONT_PLANKS[+name.slice(5)].b; s += box(b[0], b[1], b[2], b[3], b[4], b[5], k, WOOD); }
  else if (name === 'deco') {
    [[0.3, 8.4], [2.4, 8.7], [12.4, 0.0]].forEach(([x, y]) => { s += box(x + 0.5, y + 0.5, 0, 0.4, 0.4, 0.7, k, [C.wood, C.dirt, C.bronze]) + box(x, y, 0.7, 1.4, 1.4, 1.5, k, [C.lime, C.green, C.greenD]); });
  }
  return svgWrap(MVB, 200, 144, s);
}
const coinSVG = () => `<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 12 12" shape-rendering="crispEdges"><circle cx="6" cy="6" r="5.6" fill="${C.ink}"/><circle cx="6" cy="6" r="4.6" fill="${C.goldD}"/><circle cx="5.6" cy="5.6" r="3.8" fill="${C.gold}"/><rect x="5" y="3" width="2" height="6" fill="${C.gold2}"/></svg>`;
const keySVG = () => `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="10" viewBox="0 0 18 10" shape-rendering="crispEdges"><rect x="0" y="1" width="8" height="8" fill="${C.ink}"/><rect x="1" y="2" width="6" height="6" fill="${C.gold}"/><rect x="3" y="4" width="2" height="2" fill="${C.ink}"/><rect x="7" y="3" width="11" height="4" fill="${C.ink}"/><rect x="8" y="4" width="9" height="2" fill="${C.gold2}"/><rect x="13" y="6" width="2" height="3" fill="${C.ink}"/><rect x="16" y="6" width="2" height="3" fill="${C.ink}"/></svg>`;
const slotsSVG = n => { let s = ''; if (n < 0) { s += gq(0, 0, 4.8, 2.8, 0, 8, C.gray0); let l = ''; for (let i = 0; i < 3; i++) l += slotLines(i * 1.6, 0, 2.8, 1.6, 0.18, C.white); s += flat(l); } else s += flat(gq(n * 1.6 + 0.25, 0.25, n * 1.6 + 1.35, 2.8, 0, 8, C.gold)); return svgWrap('-26 -4 66 38', 66, 38, s); };

// ── rasterizer: svg string → real pixels ─────────────────────────
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
  React.useEffect(() => { const l = () => force(n => n + 1); PXL.add(l); return () => PXL.delete(l); }, []);
  const url = raster(id, mk, w, h);
  const st = Object.assign({ width: w * s, height: h * s, display: 'block' }, style);
  return url ? <img src={url} alt="" style={Object.assign({ imageRendering: 'pixelated' }, st)} /> : <div style={st}></div>;
}
const Car = ({ type, s = 6, style }) => <Pix id={'car-' + type} mk={() => carSVG(type)} w={58} h={48} s={s} style={style} />;
const Coin = ({ s = 4, style }) => <Pix id="coin" mk={coinSVG} w={12} h={12} s={s} style={style} />;
const MapL = ({ name, style }) => <Pix id={'map-' + name} mk={() => mapLayer(name)} w={200} h={144} s={MS} style={Object.assign({ position: 'absolute', left: 0, top: 0 }, style)} />;

// ── particles ────────────────────────────────────────────────────
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
function confetti(t, t0, n, seed, h = SH, cols) {
  if (t < t0) return null; const out = [], L = t - t0;
  cols = cols || [C.pink, C.blue2, C.gold, C.white, C.lime];
  for (let i = 0; i < n; i++) {
    const v = 160 + rnd(i, seed) * 170; let y = -40 + L * v - rnd(i, seed + 1) * 700;
    if (y < -40) continue; y = ((y + 40) % (h + 80)) - 40;
    const x = rnd(i, seed + 2) * SW + Math.sin(L * 2 + i) * 18, r = (Math.floor(L * 5 + rnd(i, seed + 3) * 4) % 4) * 90;
    out.push(<div key={'cf' + seed + i} style={{ position: 'absolute', left: snap(x), top: snap(y), width: i % 2 ? 8 : 12, height: i % 2 ? 12 : 8, background: cols[i % cols.length], transform: `rotate(${r}deg)` }}></div>);
  }
  return out;
}
function shakeAt(t, list) {
  let dx = 0, dy = 0;
  list.forEach(([t0, d, a], i) => { if (t >= t0 && t < t0 + d) { const amp = a * (1 - (t - t0) / d); dx += Math.sin((t - t0) * 70 + i) * amp; dy += Math.cos((t - t0) * 55 + i * 2) * amp; } });
  return [snap(dx, 2), snap(dy, 2)];
}
const Diamond = ({ cx, cy, hw, hh, col, sw = 4, o = 1, sc = 1, fill = 'none' }) => (
  <svg width={hw * 2 + 20} height={hh * 2 + 20} style={{ position: 'absolute', left: cx - hw - 10, top: cy - hh - 10, opacity: o, transform: `scale(${sc})`, overflow: 'visible' }} shapeRendering="crispEdges">
    <polygon points={`${hw + 10},10 ${hw * 2 + 10},${hh + 10} ${hw + 10},${hh * 2 + 10} 10,${hh + 10}`} fill={fill} stroke={col} strokeWidth={sw} vectorEffect="non-scaling-stroke" />
  </svg>
);
function Rays({ cx, cy, n, col, rot, o, sc = 1, r = 900 }) {
  const ws = []; const h = Math.PI / n * 0.55;
  for (let i = 0; i < n; i++) { const a = i * 2 * Math.PI / n; ws.push(`0,0 ${Math.cos(a - h) * r},${Math.sin(a - h) * r} ${Math.cos(a + h) * r},${Math.sin(a + h) * r}`); }
  return (
    <svg width={2} height={2} style={{ position: 'absolute', left: cx - 1, top: cy - 1, overflow: 'visible', opacity: o, transform: `rotate(${rot}deg) scale(${sc})` }} shapeRendering="crispEdges">
      {ws.map((p, i) => <polygon key={i} points={p} fill={col} />)}
    </svg>
  );
}
const Flash = ({ o, c = '#fff' }) => o > 0.001 ? <div style={{ position: 'absolute', inset: 0, background: c, opacity: o, pointerEvents: 'none' }}></div> : null;
// white flash: ≤0.13s above zero, never held (photosensitivity rule)
const flashAt = (t, t0, peak, dur = 0.13) => (t >= t0 && t < t0 + dur) ? peak * (1 - (t - t0) / dur) : 0;

// ── shared UI ────────────────────────────────────────────────────
const Btn = ({ label, primary, coin, style, sc = 1, o = 1, onClick, disabled }) => React.createElement(onClick ? 'button' : 'div', { onClick: disabled ? undefined : onClick, disabled, style: Object.assign({ border: 0, cursor: onClick && !disabled ? 'pointer' : 'default', filter: disabled ? 'grayscale(1) brightness(.7)' : undefined, pointerEvents: o > 0.5 ? 'auto' : 'none',  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10, height: 64, padding: '0 18px', borderRadius: 16, whiteSpace: 'nowrap', background: primary ? C.blue : C.navy2, boxShadow: primary ? `inset 0 -6px 0 ${C.blueD}` : `inset 0 -6px 0 ${C.ink}`, color: C.white, font: `700 22px ${FUI}`, transform: `scale(${sc})`, opacity: o }, style) },
    <span>{label}</span>,
    coin ? <span style={{ display: 'flex', alignItems: 'center', gap: 6, font: `400 20px ${FPX}`, color: C.gold2 }}><Coin s={2} />{coin}</span> : null);
const Chip = ({ label, bg, fg, sc = 1, o = 1, border }) => <div style={{ padding: '6px 14px', borderRadius: 10, background: bg, color: fg, font: `400 18px ${FPX}`, transform: `scale(${sc})`, opacity: o, boxShadow: border ? `inset 0 0 0 2px ${border}` : undefined }}>{label}</div>;
const Sfx = ({ t, cues, on }) => {
  const fired = React.useRef({ t: -1, done: new Set() });   // 큐를 지날 때 한 번씩 window.sfx(글귀)를 부른다. 시제품이 글귀를 소리로 바꾼다
  if (t < fired.current.t) fired.current.done = new Set(); fired.current.t = t;
  cues.forEach((c, i) => { if (t >= c[0] && !fired.current.done.has(i)) { fired.current.done.add(i); if (window.sfx) window.sfx(c[1]); } });
  if (!on) return null; let a = null; cues.forEach(c => { if (t >= c[0] && t < c[0] + 0.9) a = c; });
  return a ? <div style={{ position: 'absolute', left: 28, bottom: 24, padding: '6px 12px', borderRadius: 8, background: 'rgba(11,14,31,.8)', boxShadow: `inset 0 0 0 2px ${C.slate}`, color: C.mist2, font: `400 16px ${FPX}`, opacity: 1 - seg(t, a[0] + 0.7, a[0] + 0.9) }}>♪ SFX 자리 · {a[1]}</div> : null;
};
const HudChip = ({ children, style }) => <div style={Object.assign({ display: 'flex', alignItems: 'center', gap: 10, height: 48, padding: '0 18px', borderRadius: 14, background: 'rgba(29,37,71,.92)', boxShadow: `inset 0 0 0 2px ${C.slate}`, color: C.white, font: `400 20px ${FPX}` }, style)}>{children}</div>;
const Backdrop = () => (
  <div style={{ position: 'absolute', inset: 0, background: C.navy0 }}>
    <div style={{ position: 'absolute', left: 0, right: 0, top: 560, bottom: 0, background: C.navy }}></div>
    <svg width={SW} height={SH - 560} style={{ position: 'absolute', left: 0, top: 560, opacity: 0.35 }} shapeRendering="crispEdges">
      {[-6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6].map(i => <g key={i}><line x1={680 + i * 120} y1={0} x2={680 + i * 120 - 776} y2={388} stroke={C.slate} strokeWidth="2" /><line x1={680 + i * 120} y1={0} x2={680 + i * 120 + 776} y2={388} stroke={C.slate} strokeWidth="2" /></g>)}
    </svg>
    {Array.from({ length: 26 }).map((_, i) => <Sq key={i} x={rnd(i, 40) * SW} y={rnd(i, 41) * 520} s={4} c={C.mist} o={0.25 + rnd(i, 42) * 0.4} />)}
  </div>
);

// ── gacha ────────────────────────────────────────────────────────
const TIERS = {
  common: { col: C.white, car: 'hatch', name: '꼬마 해치', chip: '일반', band: '일반 등급', seq: [[C.white, 0.5]], W: 2.0, pity: '희귀 이상 보장까지 24회', ember: 10 },
  uncommon: { col: C.blue2, car: 'sedan', name: '시티 세단', chip: '고급', band: '고급 등급!', seq: [[C.blue2, 0.5]], W: 2.0, pity: '희귀 이상 보장까지 23회', ember: 14, isNew: true },
  rare: { col: C.pink, car: 'coupe', name: '네온 쿠페', chip: '희귀', band: '희귀 등급!', seq: [[C.blue2, 0.5], [C.pink, 1.4]], W: 2.0, pity: '희귀 이상 보장까지 1회', pityAfter: '다음 보장까지 30회', ember: 18, guaranteed: true },
  legend: { col: C.gold, car: 'gt', name: '골드 스톰 GT', chip: '전설', band: '★ 전설 등급! ★', seq: [[C.blue2, 0.5], [C.pink, 1.15], [C.gold, 1.85]], W: 2.4, pity: '희귀 이상 보장까지 22회', ember: 26, isNew: true },
};
const gachaTimes = (tier, reduced) => {
  if (reduced) return { W: 0.6, O: 1.25, R: 1.65, C: 1.8 };
  const W = TIERS[tier].W, O = W + 0.9, R = O + 0.55; return { W, O, R, C: R + 0.9 };
};
const UPFLASH = { rare: [0, 0.45], legend: [0, 0.35, 0.55] };
const UPSHAKE = { rare: [0, 10], legend: [0, 10, 18] };

function ResultCard({ t, tier, Ct, reduced, d }) {
  const T = TIERS[tier];
  const p = reduced ? seg(t, Ct, Ct + 0.3) : MOTION.enter(t, Ct, Ct + 0.35);
  if (p <= 0) return null;
  const st = (d, dur = 0.3) => reduced ? seg(t, Ct + 0.1, Ct + 0.4) : MOTION.pop(t, Ct + d, Ct + d + dur);
  const bandBg = { common: C.mist2, uncommon: C.blue2, rare: C.pink, legend: C.gold }[tier];
  const glow = tier === 'legend' ? 0.5 + 0.5 * Math.sin((t - Ct) * 3) : 0;
  const frame = {
    common: `0 0 0 2px ${C.slate}`,
    uncommon: `0 0 0 4px ${C.blue2}`,
    rare: `0 0 0 4px ${C.pink}, inset 0 0 0 8px ${C.navy}, inset 0 0 0 10px ${C.pink2}`,
    legend: `0 0 0 4px ${C.gold}, 0 0 0 8px ${C.goldD}, 0 0 0 ${16 + glow * 8}px rgba(255,200,61,${0.18 + glow * 0.12}), inset 0 0 0 8px ${C.navy}, inset 0 0 0 10px ${C.gold2}`,
  }[tier];
  return (
    <div style={{ position: 'absolute', left: 800, top: 196, width: 480, borderRadius: 24, background: C.navy, boxShadow: frame, opacity: p, transform: `translateX(${(1 - p) * (reduced ? 0 : 60)}px)`, overflow: 'visible' }}>
      <div style={{ margin: 18, height: 64, borderRadius: 12, background: bandBg, display: 'flex', alignItems: 'center', justifyContent: 'center', color: C.ink, font: `400 30px ${FPX}`, transform: `scale(${st(0.15)})` }}>{T.band}</div>
      <div style={{ padding: '6px 36px 34px', display: 'flex', flexDirection: 'column', gap: 18 }}>
        <div style={{ color: C.mist, font: `500 20px ${FUI}` }}>새 차가 차고에 들어왔어요</div>
        <div style={{ color: C.white, font: `700 44px ${FUI}`, letterSpacing: '-0.01em' }}>{d.name}</div>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <Chip label={T.chip} bg={bandBg} fg={C.ink} sc={st(0.25)} />
          <Chip label="Lv1" bg={C.navy2} fg={C.white} sc={st(0.33)} border={C.slate} />
          {d.isNew ? <Chip label="NEW · 도감 등록" bg={C.lime} fg={C.ink} sc={st(0.45, 0.35)} /> : null}
        </div>
        {d.guaranteed ? <div style={{ color: C.mist2, font: `500 18px ${FUI}`, opacity: st(0.5) }}>30회 보장으로 얻었어요 · 다음 보장은 30회부터 다시 세요</div> : null}
        <div style={{ height: 2, background: C.navy2, margin: '6px 0' }}></div>
        <div style={{ display: 'flex', gap: 12 }}>
          <Btn label="한 번 더 뽑기" coin={d.againPrice || d.price} primary sc={lerp(0.9, 1, st(0.55))} o={seg(t, Ct + 0.5, Ct + 0.6)} style={{ flex: 1.3 }} onClick={d.onAgain} disabled={!d.canAgain} />
          <Btn label="차고에서 보기" sc={lerp(0.9, 1, st(0.6))} o={seg(t, Ct + 0.55, Ct + 0.65)} style={{ flex: 1 }} onClick={d.onGarage} />
        </div>
      </div>
      {d.guaranteed ? <div style={{ position: 'absolute', right: -18, top: -22, padding: '10px 16px', background: C.gold, color: C.ink, font: `400 22px ${FPX}`, borderRadius: 8, boxShadow: `0 0 0 3px ${C.ink}, 6px 6px 0 ${C.ink}`, transform: `rotate(-6deg) scale(${st(0.5, 0.4)})` }}>보장 달성!</div> : null}
    </div>
  );
}

function Gacha({ t, tier, reduced, sfx = true, d }) {
  const T = TIERS[tier], tm = gachaTimes(tier, reduced), { W, O, R } = tm, Ct = tm.C;
  const seq = reduced ? [[T.col, 0.3]] : T.seq;
  let col = seq[0][0]; seq.forEach(s => { if (t >= s[1]) col = s[0]; });
  // light intensity with anticipation dip before each upgrade
  let I = reduced ? seg(t, 0.3, 0.55) : MOTION.enter(t, 0.5, 1.0) * (0.88 + 0.12 * Math.sin(t * 5));
  if (!reduced) seq.slice(1).forEach(s => { const u = s[1]; if (t < u && t > u - 0.25) I *= 1 - 0.55 * seg(t, u - 0.25, u); if (t >= u) I += 0.7 * (1 - seg(t, u, u + 0.4)); });
  const open = reduced ? MOTION.enter(t, O, R) : MOTION.drop(t, O, R);
  I *= 1 + open * 1.4;
  // shutter unit drop + landing
  const dropY = reduced ? 0 : -720 * (1 - MOTION.drop(t, 0.05, 0.42)) - 16 * Math.sin(Math.PI * seg(t, 0.42, 0.62));
  const squash = reduced ? 1 : 1 - 0.04 * Math.sin(Math.PI * seg(t, 0.42, 0.54));
  const unitO = reduced ? seg(t, 0.1, 0.4) : 1;
  const shakes = reduced ? [] : [[0.42, 0.22, 8]];
  if (!reduced) seq.slice(1).forEach((s, i) => { shakes.push([s[1] - 0.25, 0.25, 2], [s[1], 0.3, UPSHAKE[tier][i + 1]]); });
  if (!reduced) { if (tier === 'uncommon') shakes.push([R, 0.15, 4]); if (tier === 'rare') shakes.push([R, 0.25, 8]); if (tier === 'legend') shakes.push([R, 0.45, 18], [R + 0.45, 0.3, 10]); }
  const [sx, sy] = shakeAt(t, shakes);
  const cam = reduced ? 1 : 1 + 0.035 * MOTION.enter(t, 0.5, W) - 0.035 * MOTION.enter(t, O, R);
  const revealed = t >= R;
  const glide = reduced ? -230 : -230 * MOTION.enter(t, Ct, Ct + 0.5);
  const carX = 680 + glide, carY = 712;
  const anc = carAnchor(T.car);
  const carS = reduced ? 1 : lerp(0.2, 1, MOTION.pop(t, R, R + 0.5));
  const floatY = reduced ? 0 : Math.sin((t - R - 0.5) * 2.4) * 8 * MOTION.enter(t, R + 0.5, R + 1.0);
  const carO = reduced ? seg(t, R, R + 0.3) : (revealed ? 1 : 0);
  const rayCfg = { common: [[12, C.white, 0.08, 14]], uncommon: [[12, C.blue2, 0.28, 18]], rare: [[14, C.pink, 0.32, 20], [7, C.pink2, 0.16, -12]], legend: [[16, C.gold, 0.4, 20], [8, C.gold2, 0.3, -14]] }[tier];
  const rayS = MOTION.enter(t, R, R + 0.45);
  const tapP = seg(t, O - 0.05, O + 0.3);
  const promptO = t >= W && t < O + 0.1 ? seg(t, W, W + 0.25) * (1 - seg(t, O, O + 0.1)) * (0.45 + 0.55 * (0.5 + 0.5 * Math.cos((t - W) * Math.PI * 2 / 1.4))) : 0;
  const hudO = seg(t, 0.3, 0.55);
  const bgO = reduced ? seg(t, 0, 0.25) : seg(t, 0, 0.22);
  const sfxCues = reduced ? [[R - 0.4, '셔터 열림(약하게)']] : [[0.42, '셔터 쿵']].concat(seq.slice(1).map((s, i) => [s[1], '승급 차임 ' + (i + 1)]), [[O, '개봉 휘익'], [R, tier === 'legend' ? '전설 팡파르' : '공개 반짝']]);
  const sparkleN = { common: 10, uncommon: 18, rare: 26, legend: 32 }[tier];
  const burstCols = { common: [C.white, C.mist2], uncommon: [C.blue2, C.blue3, C.white], rare: [C.pink, C.pink2, C.white], legend: [C.gold, C.gold2, C.white, C.pink2] }[tier];
  const upFlash = !reduced && UPFLASH[tier] ? seq.slice(1).reduce((a, s, i) => a + (t >= s[1] && t < s[1] + 0.25 ? UPFLASH[tier][i + 1] * (1 - (t - s[1]) / 0.25) : 0), 0) : 0;
  const embers = [];
  if (t >= 0.5 && t < R + 0.1) for (let i = 0; i < T.ember; i++) {
    const ph = ((t - 0.5) * 0.7 + rnd(i, 3)) % 1, x = 680 + (rnd(i, 1) - 0.5) * 560 + Math.sin(ph * 6 + i) * 10;
    embers.push(<Sq key={'e' + i} x={x} y={600 - ph * 380} s={4 + Math.floor(rnd(i, 2) * 2) * 4} c={i % 3 ? col : C.white} o={clamp(I, 0, 1) * (ph < 0.1 ? ph * 10 : 1 - ph)} />);
  }
  const rings = [];
  if (!reduced && t >= 0.5 && t < R) for (let k = 0; k < 3; k++) {
    if (t - 0.5 < k * 0.3) continue; const ph = ((t - 0.5) / 0.9 + k / 3) % 1;
    rings.push(<Diamond key={'r' + k} cx={680} cy={712} hw={300} hh={150} col={col} sc={0.15 + ph * 1.1} o={(1 - ph) * clamp(I, 0, 1) * 0.7} />);
  }
  const twinkles = [];
  if (revealed) for (let i = 0; i < 10; i++) {
    const a = rnd(i, 60) * Math.PI * 2, d = 150 + rnd(i, 61) * 120;
    twinkles.push(<Star key={'tw' + i} x={carX + Math.cos(a) * d} y={carY - 110 + Math.sin(a) * d * 0.7} s={4} c={i % 2 ? col : C.white} o={Math.max(0, Math.sin((t - R) * 2.6 + i * 1.7)) * seg(t, R + 0.6, R + 0.9) * (reduced ? 0 : 1)} />);
  }
  const rim = { common: [[C.slate2, 4]], uncommon: [[C.blue2, 4]], rare: [[C.pink, 4], [C.pink2, 2, 0.86]], legend: [[C.gold, 6], [C.gold2, 2, 0.86], [C.goldD, 2, 1.12]] }[tier];
  const shutterSlats = [];
  for (let i = 0; i < 12; i++) shutterSlats.push(<div key={i} style={{ position: 'absolute', left: 0, right: 0, top: i * 29, height: 29, background: i % 2 ? C.gray : '#858598', boxShadow: `inset 0 3px 0 ${C.gray2}, inset 0 -3px 0 ${C.gray0}` }}></div>);
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: C.navy0 }}>
      <div style={{ position: 'absolute', inset: 0, opacity: 1 - bgO }}><div style={{ position: 'absolute', left: 180, top: 150 }}><ShopMap /></div></div>
      <div style={{ position: 'absolute', inset: 0, opacity: bgO, transform: `translate(${sx}px,${sy}px) scale(${cam})`, transformOrigin: '680px 450px' }}>
        <Backdrop />
        {/* floor spill */}
        {!revealed ? [[400, 960, 600, 0.45], [360, 1000, 650, 0.25], [320, 1040, 700, 0.12]].map(([a, b, y, o], i) => (
          <div key={'sp' + i} style={{ position: 'absolute', left: 0, top: 560, width: SW, height: y - 560 + open * 120, background: col, opacity: clamp(o * I, 0, 0.9), clipPath: `polygon(424px 0, 936px 0, ${b + open * 60}px 100%, ${a - open * 60}px 100%)` }}></div>
        )) : null}
        {rim.map(([c, w, k], i) => <Diamond key={'rim' + i} cx={carX} cy={712} hw={300 * (k || 1)} hh={150 * (k || 1)} col={revealed || i === 0 ? c : C.slate} sw={w} o={revealed ? 1 : (i === 0 ? 0.5 + 0.5 * clamp(I, 0, 1) : 0)} />)}
        {rings}
        {revealed && !reduced ? <div style={{ position: 'absolute', inset: 0, }}>
          {tier === 'legend' ? [280, 220, 160].map((r, i) => <div key={'h' + i} style={{ position: 'absolute', left: carX - r, top: carY - 110 - r * 0.9, width: r * 2, height: r * 1.8, borderRadius: '50%', background: C.gold, opacity: (0.08 + i * 0.05) * rayS * (0.85 + 0.15 * Math.sin(t * 2)) }}></div>) : null}
          {rayCfg.map(([n, c, o, sp], i) => <Rays key={'ray' + i} cx={carX} cy={carY - 110} n={n} col={c} o={o} rot={(t - R) * sp} sc={rayS} />)}
        </div> : null}
        {/* shutter unit */}
        {!revealed ? (
          <div style={{ position: 'absolute', left: 0, top: 0, width: SW, height: SH, opacity: unitO, transform: `translateY(${dropY}px) scaleY(${squash})`, transformOrigin: '680px 560px' }}>
            <div style={{ position: 'absolute', left: 400, top: 150, width: 560, height: 410, background: C.gray0, boxShadow: `0 0 0 4px ${C.ink}` }}></div>
            <div style={{ position: 'absolute', left: 424, top: 200, width: 512, height: 360, background: C.ink, overflow: 'hidden' }}>
              {[[0, 0.35], [70, 0.6], [150, 0.9]].map(([ins, o], i) => <div key={i} style={{ position: 'absolute', left: ins, right: ins, top: ins * 0.6, bottom: 0, background: i === 2 ? C.white : col, opacity: o * clamp(0.25 + open * 1.2, 0, 1) }}></div>)}
              <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 348, overflow: 'hidden' }}>
                <div style={{ position: 'absolute', left: 0, right: 0, top: -348 * open, height: 348 }}>
                  {shutterSlats}
                  <div style={{ position: 'absolute', left: 226, width: 60, top: 322, height: 12, background: C.gray2, boxShadow: `0 0 0 3px ${C.ink}` }}></div>
                </div>
              </div>
              <div style={{ position: 'absolute', left: 0, right: 0, top: 348 - 348 * open, height: 12 + 348 * open * 0, background: col, opacity: clamp(I, 0, 1) }}></div>
            </div>
            <div style={{ position: 'absolute', left: 392, top: 140, width: 576, height: 60, background: C.gray, boxShadow: `0 0 0 4px ${C.ink}, inset 0 -8px 0 ${C.gray0}, inset 0 6px 0 ${C.gray2}` }}></div>
            {[[404, 156], [944, 156]].map(([x, y], i) => <div key={i} style={{ position: 'absolute', left: x, top: y, width: 12, height: 12, background: C.gray2, boxShadow: `0 0 0 2px ${C.ink}` }}></div>)}
            <div style={{ position: 'absolute', left: 600, top: 156, width: 160, height: 28, background: C.blue, boxShadow: `0 0 0 3px ${C.ink}`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: C.white, font: `400 16px ${FPX}` }}>GARAGE</div>
          </div>
        ) : null}
        {/* landing dust */}
        {!reduced && t > 0.42 && t < 1.1 ? Array.from({ length: 18 }).map((_, i) => { const p = MOTION.enter(t, 0.42, 1.0), side = i % 2 ? 1 : -1; return <Sq key={'d' + i} x={680 + side * (280 + p * (60 + rnd(i, 7) * 140))} y={556 - p * (10 + rnd(i, 8) * 60)} s={8 + Math.floor(rnd(i, 9) * 2) * 4} c={i % 3 ? C.mist : C.slate2} o={1 - seg(t, 0.7, 1.05)} />; }) : null}
        {embers}
        {/* car */}
        <div style={{ position: 'absolute', left: carX - d.car.w * 5 * 0.45, top: carY - (d.car.h - 10) * 5 + floatY, opacity: carO, transform: `scale(${carS})`, transformOrigin: `${d.car.w * 5 * 0.45}px ${(d.car.h - 10) * 5}px` }}><OurCar car={d.car} s={5} /></div>
        {!reduced ? burst(t, R + 0.05, sparkleN, carX, carY - 110, tier === 'common' ? 200 : 330, burstCols, 11, tier !== 'common') : null}
        {tier === 'legend' && !reduced ? burst(t, R + 0.45, 32, carX, carY - 110, 460, [C.gold, C.gold2, C.white], 23, true) : null}
        {twinkles}
      </div>
      {tier === 'legend' && !reduced ? <div style={{ position: 'absolute', inset: 0 }}>{confetti(t, R + 0.1, 44, 5)}{confetti(t, R + 0.3, 14, 9, SH, [C.gold, C.gold2])}</div> : null}
      <Flash o={upFlash} c={col} />
      {!reduced ? <Flash o={flashAt(t, R, 0.85)} /> : null}
      {tier === 'legend' && !reduced ? <Flash o={t >= R + 0.45 && t < R + 0.7 ? 0.35 * (1 - (t - R - 0.45) / 0.25) : 0} c={C.gold} /> : null}
      {/* tap ripple + prompt */}
      {tapP > 0 && tapP < 1 ? <Diamond cx={680} cy={380} hw={90} hh={45} col={C.white} sw={4} sc={0.3 + tapP * 0.9} o={1 - tapP} /> : null}
      <div style={{ position: 'absolute', left: 0, right: 0, top: 820, textAlign: 'center', color: C.white, font: `400 30px ${FPX}`, opacity: promptO }}>화면을 눌러 셔터 올리기</div>
      {/* HUD */}
      <div style={{ position: 'absolute', left: 28, top: 28, display: 'flex', gap: 12, opacity: hudO }}>
        <HudChip><Coin s={2.5} /><span style={{ color: C.gold2 }}>{d.price}</span></HudChip>
        <HudChip style={{ color: C.mist2 }}>{t >= Ct ? d.pityAfter : d.pity}</HudChip>
        {reduced ? <HudChip style={{ color: C.lime }}>움직임 줄이기 켜짐</HudChip> : null}
      </div>
      <button onClick={d.onSkip} style={{ position: 'absolute', right: 28, top: 28, opacity: hudO * (1 - seg(t, Ct, Ct + 0.2)), border: 0, background: 'none', padding: 0, cursor: 'pointer', pointerEvents: t < Ct ? 'auto' : 'none' }}>
        <HudChip style={{ gap: 8 }}>건너뛰기 <span style={{ color: C.blue3 }}>▶▶</span></HudChip>
      </button>
      <ResultCard t={t} tier={tier} Ct={Ct} reduced={reduced} d={d} />
      <Sfx t={t} cues={sfxCues} on={sfx} />
    </div>
  );
}

// ── shop map ─────────────────────────────────────────────────────
function ShopMap({ lv = 1, fenced = true, plankT = -1, back = 0, newLot = 0, slotOn = [0, 0], glow = [0, 0], bSquash = [1, 1], bOrigin }) {
  return (
    <div style={{ position: 'relative', width: 1000, height: 720 }}>
      <MapL name="ground" />
      <MapL name="newLot" style={{ opacity: newLot }} />
      {[0, 1].map(i => <MapL key={'s' + i} name={'slot' + i} style={{ opacity: slotOn[i] }} />)}
      {[0, 1].map(i => <MapL key={'g' + i} name={'glow' + i} style={{ opacity: glow[i] }} />)}
      {fenced ? <MapL name="fenceBack" style={{ opacity: 1 - back, transform: `translateY(${back * 24}px)` }} /> : null}
      <div style={{ position: 'absolute', inset: 0, transform: `scale(${bSquash[0]},${bSquash[1]})`, transformOrigin: bOrigin || '560px 530px' }}>
        <MapL name="bld1" style={{ opacity: lv === 1 ? 1 : 0 }} />
        <MapL name="bld2" style={{ opacity: lv === 2 ? 1 : 0 }} />
      </div>
      {fenced ? FRONT_PLANKS.map((p, i) => {
        let tr = 'none', o = 1;
        if (plankT >= 0) {
          const t0 = i * 0.035, L = Math.max(0, plankT - t0), vx = (rnd(i, 30) - 0.35) * 320, vy = -(320 + rnd(i, 31) * 220), r = (rnd(i, 32) - 0.5) * 900;
          if (plankT >= t0) { tr = `translate(${vx * L}px,${vy * L + 0.5 * 1600 * L * L}px) rotate(${snap(r * L, 15)}deg)`; o = 1 - seg(L, 0.35, 0.6); }
        }
        return <MapL key={'p' + i} name={'plank' + i} style={{ transform: tr, transformOrigin: `${p.c[0]}px ${p.c[1]}px`, opacity: o }} />;
      }) : null}
      <MapL name="deco" />
    </div>
  );
}

function Expand({ t, sfx = true, d }) {
  const z = 1 + 0.16 * MOTION.enter(t, 0, 0.4);
  const fc = mpos(6.8, 7.5, 0.5);
  const sq = t < 1.45 ? [1 + 0.08 * seg(t, 1.3, 1.45), 1 - 0.15 * seg(t, 1.3, 1.45)] : [lerp(1.08, 1, MOTION.pop(t, 1.45, 1.75)), lerp(0.85, 1, MOTION.pop(t, 1.45, 1.75))];
  const lv = t >= 1.45 ? 2 : 1;
  const glowAt = i => { const a = 1.0 + i * 0.2; return t < a ? 0 : (t < a + 0.12 ? seg(t, a, a + 0.12) : lerp(1, 0.35, seg(t, a + 0.12, a + 0.6))); };
  const dust = [];
  if (t > 0.42 && t < 1.3) for (let i = 0; i < 30; i++) {
    const p = MOTION.enter(t, 0.42 + rnd(i, 50) * 0.15, 1.2), a = rnd(i, 51) * Math.PI * 2, d = 40 + rnd(i, 52) * 160;
    dust.push(<Sq key={'du' + i} x={fc[0] + Math.cos(a) * d * p} y={fc[1] + Math.sin(a) * d * 0.5 * p - p * 60} s={8 + Math.floor(rnd(i, 53) * 3) * 4} c={[C.wood2, C.wood, C.mist2][i % 3]} o={(1 - seg(t, 0.8, 1.25)) * 0.9} />);
  }
  const bo = '560px 530px';
  const bandP = MOTION.pop(t, 1.7, 2.0), btnP = MOTION.pop(t, 1.95, 2.25);
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: C.navy0 }}>
      <div style={{ position: 'absolute', left: 180, top: 150, width: 1000, height: 720, transform: `scale(${z})`, transformOrigin: `${fc[0]}px ${fc[1]}px` }}>
        <ShopMap lv={lv} plankT={t >= 0.4 ? t - 0.4 : -1} back={seg(t, 0.45, 0.75)} newLot={seg(t, 0.9, 1.1)} slotOn={[seg(t, 1.0, 1.1), seg(t, 1.2, 1.3)]} glow={[glowAt(0), glowAt(1)]} bSquash={sq} bOrigin={bo} />
        {dust}
        {burst(t, 1.47, 16, 560, 380, 170, [C.gold2, C.white], 70, true)}
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 96, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 14, opacity: seg(t, 1.7, 1.8) }}>
        <div style={{ padding: '14px 44px', background: C.blue, color: C.gold2, font: `400 56px ${FPX}`, borderRadius: 14, boxShadow: `0 0 0 4px ${C.ink}, inset 0 -8px 0 ${C.blueD}`, transform: `scale(${bandP})` }}>가게 Lv{d.lv}!</div>
        <div style={{ padding: '10px 24px', borderRadius: 12, background: 'rgba(29,37,71,.94)', boxShadow: `inset 0 0 0 2px ${C.slate}`, color: C.white, font: `400 28px ${FPX}`, transform: `translateY(${(1 - MOTION.enter(t, 1.8, 2.1)) * 16}px)`, opacity: seg(t, 1.8, 1.95) }}>주차 {d.from} → <span style={{ color: C.gold2 }}>{d.to}칸</span></div>
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 56, display: 'flex', justifyContent: 'center' }}>
        <Btn label="확인" primary sc={btnP} o={seg(t, 1.95, 2.05)} style={{ width: 280 }} onClick={d.onClose} />
      </div>
      {confetti(t, 1.75, 18, 77, SH, [C.gold, C.gold2, C.white])}
      <Sfx t={t} cues={[[0.4, '판자 탁탁'], [1.0, '칸 띵'], [1.45, '건물 뿅'], [1.7, '레벨업']]} on={sfx} />
    </div>
  );
}

// ── first grant ──────────────────────────────────────────────────
function FirstGrant({ t, sfx = true, d }) {
  const count = Math.round(d.money * MOTION.enter(t, 0.45, 1.15));
  const cards = [0, 1, 2].map(i => ({ p: MOTION.pop(t, i * 0.15, i * 0.15 + 0.4), o: seg(t, i * 0.15, i * 0.15 + 0.1), x: 250 + i * 300 }));
  const coins = [];
  for (let j = 0; j < 10; j++) {
    const L = t - (0.45 + j * 0.05); if (L < 0 || L > 0.7) continue;
    const vx = (rnd(j, 80) - 0.5) * 380, vy = -(560 + rnd(j, 81) * 240);
    const sp = Math.abs(Math.cos(L * 14 + j)), sx = sp > 0.66 ? 1 : sp > 0.33 ? 0.6 : 0.25;
    coins.push(<div key={'c' + j} style={{ position: 'absolute', left: snap(380 - 20 + vx * L), top: snap(410 + vy * L + 0.5 * 1700 * L * L), transform: `scaleX(${sx})`, opacity: 1 - seg(L, 0.5, 0.7) }}><Coin s={3.5} /></div>);
  }
  const hop = t < 0.95 ? 26 * Math.sin(Math.PI * seg(t, 0.75, 0.95)) : 12 * Math.sin(Math.PI * seg(t, 0.95, 1.12));
  const horn = MOTION.pop(t, 0.78, 1.0) * (1 - seg(t, 1.2, 1.3));
  const card = (i, children) => (
    <div key={'k' + i} style={{ position: 'absolute', left: cards[i].x, top: 290, width: 260, height: 360, borderRadius: 24, background: C.navy, boxShadow: `0 0 0 2px ${C.slate}`, transform: `translateY(${(1 - cards[i].p) * 160}px)`, opacity: cards[i].o, overflow: 'hidden' }}>{children}</div>
  );
  const label = (a, b) => <div style={{ position: 'absolute', left: 0, right: 0, top: 236, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}><div style={{ color: C.mist, font: `500 18px ${FUI}` }}>{a}</div><div style={{ color: C.white, font: `400 34px ${FPX}` }}>{b}</div></div>;
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden' }}>
      <Backdrop />
      <div style={{ position: 'absolute', left: 0, right: 0, top: 150, textAlign: 'center', color: C.white, font: `400 44px ${FPX}`, opacity: seg(t, 0, 0.25), transform: `translateY(${(1 - MOTION.enter(t, 0, 0.35)) * -20}px)` }}>가게 오픈 선물이 도착했어요</div>
      {card(0, [<div key="a" style={{ position: 'absolute', left: 82, top: 70 }}><Coin s={8} /></div>, <div key="b">{label('게임머니', count.toLocaleString('en-US'))}</div>])}
      {card(1, [<div key="a" style={{ position: 'absolute', left: 130 - d.car.w * 1.5, top: 215 - d.car.h * 3 + -hop }}><OurCar car={d.car} s={3} /></div>,
        <div key="k" style={{ position: 'absolute', left: 170, top: 168, transform: `rotate(${-20 + 20 * MOTION.pop(t, 0.55, 0.85)}deg)` }}><Pix id="key" mk={keySVG} w={18} h={10} s={4} /></div>,
        <div key="b">{label('첫 차', d.name)}</div>])}
      {card(2, [<div key="a" style={{ position: 'absolute', left: 31, top: 60 }}>
          <Pix id="slots" mk={() => slotsSVG(-1)} w={66} h={38} s={3} />
          {[0, 1, 2].map(n => <Pix key={n} id={'slotg' + n} mk={() => slotsSVG(n)} w={66} h={38} s={3} style={{ position: 'absolute', left: 0, top: 0, opacity: seg(t, 0.7 + n * 0.1, 0.78 + n * 0.1) * 0.75 }} />)}
        </div>, <div key="b">{label('주차 칸', '차고 3칸')}</div>])}
      {coins}
      {t > 0.6 && t < 1.05 ? [0, 1, 2].map(i => <Star key={'ks' + i} x={cards[1].x + 190 + [0, 40, -10][i]} y={290 + 170 + [-20, 4, 26][i]} s={4} c={C.gold2} o={Math.sin(Math.PI * seg(t, 0.6 + i * 0.08, 0.85 + i * 0.08))} />) : null}
      <div style={{ position: 'absolute', left: cards[1].x + 186, top: 300, padding: '8px 14px', borderRadius: 10, background: C.white, color: C.ink, font: `400 26px ${FPX}`, transform: `scale(${horn})`, transformOrigin: '0 100%', boxShadow: `0 0 0 3px ${C.ink}` }}>빵!</div>
      {confetti(t, 1.25, 26, 33)}
      <div style={{ position: 'absolute', left: 0, right: 0, top: 720, display: 'flex', justifyContent: 'center' }}>
        <Btn label="가게로 가기" primary sc={MOTION.pop(t, 1.45, 1.75)} o={seg(t, 1.45, 1.55)} style={{ width: 320 }} onClick={d.onClose} />
      </div>
      <Sfx t={t} cues={[[0, '카드 톡톡톡'], [0.45, '동전 짤랑'], [0.78, '경적 빵'], [1.25, '축하']]} on={sfx} />
    </div>
  );
}

// ── settlement coins ─────────────────────────────────────────────
const coinCount = amt => clamp(Math.round(amt / 84), 1, 12);
function Settle({ t, sfx = true, d }) {
  const ROWS = d.rows, start = d.start, end = [1180, 66], total = ROWS.reduce((a, r) => a + r[1], 0);
  const counts = ROWS.map(r => coinCount(r[1]));
  const list = [];
  ROWS.forEach((r, ri) => { for (let c = 0; c < counts[ri]; c++) list.push({ ri, t0: 0.15 + ri * 0.12 + c * 0.04, val: r[1] / counts[ri], sy: 360 + ri * 86, j: list.length }); });
  let arrived = 0, lastHit = -9;
  list.forEach(k => { if (t >= k.t0 + 0.5) { arrived += k.val; lastHit = Math.max(lastHit, k.t0 + 0.5); } });
  const bump = 1 + 0.12 * (1 - seg(t, lastHit, lastHit + 0.14));
  const money = Math.round(start + arrived);
  const press = t < 0.15 ? 1 - 0.06 * Math.sin(Math.PI * seg(t, 0, 0.15)) : 1;
  const sweep = seg(t, 1.05, 1.4);
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden' }}>
      <Backdrop />
      <div style={{ position: 'absolute', right: 28, top: 28, transform: `scale(${bump})`, transformOrigin: '50% 50%' }}>
        <HudChip style={{ height: 76, padding: '0 26px', gap: 14, overflow: 'hidden', position: 'relative' }}>
          <Coin s={3.5} /><span style={{ color: C.gold2, font: `400 34px ${FPX}` }}>{money.toLocaleString('en-US')}</span>
          {sweep > 0 && sweep < 1 ? <div style={{ position: 'absolute', top: 0, bottom: 0, left: -40 + sweep * 300, width: 24, background: C.white, opacity: 0.55, transform: 'skewX(-20deg)' }}></div> : null}
        </HudChip>
      </div>
      <div style={{ position: 'absolute', left: 340, top: 200, width: 680, borderRadius: 24, background: C.navy, boxShadow: `0 0 0 2px ${C.slate}`, padding: '34px 44px', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ color: C.white, font: `400 36px ${FPX}`, marginBottom: 10 }}>오늘의 정산</div>
        {ROWS.map((r, i) => {
          const gone = seg(t, 0.15 + i * 0.12, 0.15 + i * 0.12 + 0.3);
          return <div key={i} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', height: 72, padding: '0 22px', borderRadius: 14, background: C.navy2 }}>
            <span style={{ color: C.mist2, font: `500 24px ${FUI}` }}>{r[0]}</span>
            <span style={{ color: C.gold2, font: `400 30px ${FPX}`, opacity: 1 - gone * 0.55 }}>+{r[1]}</span>
          </div>;
        })}
        <div style={{ display: 'flex', justifyContent: 'space-between', padding: '14px 22px 8px', color: C.white, font: `400 30px ${FPX}` }}><span>합계</span><span style={{ color: C.gold }}>+{total.toLocaleString('en-US')}</span></div>
        <Btn label="확인" primary sc={press} style={{ marginTop: 8 }} o={1 - seg(t, 0.2, 0.4) * 0.5} />
      </div>
      {list.map(k => {
        const L = (t - k.t0) / 0.5; if (L < 0 || L > 1) return null;
        const e = MOTION.enter(t, k.t0, k.t0 + 0.5) * 0.4 + L * 0.6;
        const s0 = [930, k.sy], c1 = [s0[0] - 60 - (k.j % 3) * 30, Math.min(s0[1], end[1]) - 120 - (k.j % 4) * 30];
        const x = (1 - e) * (1 - e) * s0[0] + 2 * (1 - e) * e * c1[0] + e * e * end[0], y = (1 - e) * (1 - e) * s0[1] + 2 * (1 - e) * e * c1[1] + e * e * end[1];
        const sp = Math.abs(Math.cos(L * 9 + k.j)), sx = sp > 0.66 ? 1 : sp > 0.33 ? 0.6 : 0.25;
        return <div key={'sc' + k.j} style={{ position: 'absolute', left: snap(x - 20), top: snap(y - 20), transform: `scaleX(${sx})` }}><Coin s={3.5} /></div>;
      })}
      {t > 1.0 ? [0, 1, 2].map(i => <Star key={'ss' + i} x={1240 + [-90, 10, 60][i]} y={66 + [-30, 34, -18][i]} s={4} c={C.gold2} o={Math.sin(Math.PI * seg(t, 1.05 + i * 0.07, 1.4 + i * 0.07))} />) : null}
      <Sfx t={t} cues={[[0, '확인 톡'], [0.65, '동전 짤랑 ×12'], [1.05, '합계 반짝']]} on={sfx} />
    </div>
  );
}


// ── 시제품용 재생기 ───────────────────────────────────────────────
const PIECE_DUR = { gacha: null, grant: 1.85, settle: 1.55, expand: 2.3, depart: 2.5, arrive: 2.0, highway: null };   // highway는 반복이라 끝이 없다
function MotionPlayer({ kind, d, reduced, onEnd, inline }) {
  const [, tick] = React.useState(0);
  const box = React.useRef(null);
  const st = React.useRef({ t0: performance.now(), offset: 0, tapped: false, waitFrom: null });
  const tier = kind === 'gacha' ? d.tier : null;
  const tm = tier ? gachaTimes(tier, reduced) : null;
  const clockT = () => (performance.now() - st.current.t0) / 1000 + st.current.offset;
  let t = clockT();
  if (tm && !reduced && !st.current.tapped && t > tm.O - 0.08) {           // 탭 대기: 예고 장면 반복
    if (st.current.waitFrom === null) st.current.waitFrom = performance.now();
    if (performance.now() - st.current.waitFrom > 3000) { st.current.tapped = true; st.current.offset -= (t - (tm.O - 0.08)); t = tm.O - 0.08; }
    else t = tm.W + ((t - tm.W) % (tm.O - 0.1 - tm.W));
  }
  if (!tm && reduced && PIECE_DUR[kind]) t = PIECE_DUR[kind] + 0.2;
  React.useEffect(() => { let id; const f = () => { tick(n => n + 1); id = requestAnimationFrame(f); }; id = requestAnimationFrame(f); return () => cancelAnimationFrame(id); }, []);
  React.useEffect(() => { if (kind === 'settle' && t > PIECE_DUR.settle + 0.9 && !st.current.ended) { st.current.ended = true; onEnd && onEnd(); } });
  const tap = () => { if (tm && !st.current.tapped && clockT() > tm.W) { const cur = clockT(); st.current.tapped = true; st.current.offset += (tm.O - 0.08) - cur; } };
  const skip = () => { if (tm) { st.current.tapped = true; st.current.offset += (tm.C + 0.7) - clockT(); } else if (PIECE_DUR[kind]) st.current.offset += PIECE_DUR[kind] - clockT(); };
  const dd = Object.assign({}, d, { onSkip: skip });
  const body = kind === 'gacha' ? <Gacha t={t} tier={tier} reduced={reduced} d={dd} sfx={false} />
    : kind === 'grant' ? <FirstGrant t={t} d={dd} sfx={false} />
    : kind === 'settle' ? <Settle t={t} d={dd} sfx={false} />
    : kind === 'expand' ? <Expand t={t} d={dd} sfx={false} />
    : window.DV.PIECES[kind](t, { sfx: false, stripe: C.blue, d: dd });   // 5차 탁송 연출(delivery.jsx)
  if (inline) {   // 화면 안 카드에 넣을 때: 카드 너비에 맞춰 줄인다
    const si = (box.current ? box.current.clientWidth : SW) / SW;
    return <div ref={box} style={{ position: 'relative', width: '100%', aspectRatio: `${SW}/${SH}`, overflow: 'hidden', borderRadius: 22 }}><div style={{ position: 'absolute', left: 0, top: 0, width: SW, height: SH, transform: `scale(${si})`, transformOrigin: '0 0' }}>{body}</div></div>;
  }
  const s = Math.min(window.innerWidth / SW, window.innerHeight / SH);
  return (
    <div onClick={tap} style={{ position: 'fixed', inset: 0, zIndex: 40, background: C.navy0, overflow: 'hidden' }} role="dialog" aria-modal="true" aria-label="연출">
      <div style={{ position: 'absolute', left: (window.innerWidth - SW * s) / 2, top: (window.innerHeight - SH * s) / 2, width: SW, height: SH, transform: `scale(${s})`, transformOrigin: '0 0', overflow: 'hidden' }}>{body}</div>
    </div>
  );
}
let motionRoot = null, motionHost = null, motionOwned = false;
window.closeMotion = () => { if (motionRoot) { motionRoot.unmount(); motionRoot = null; } if (motionHost && motionOwned) motionHost.remove(); motionHost = null; };
window.playMotion = (kind, d, opts = {}) => {
  window.closeMotion();
  const reduced = opts.reduced ?? (window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches);
  motionOwned = !opts.host; motionHost = opts.host || document.createElement('div'); if (motionOwned) document.body.appendChild(motionHost);
  motionRoot = ReactDOM.createRoot(motionHost);
  motionRoot.render(<MotionPlayer kind={kind} d={d} reduced={reduced} onEnd={d.onClose} inline={!motionOwned} />);
};
window.MOTION_TIMES = { gachaTimes, TIERS };
