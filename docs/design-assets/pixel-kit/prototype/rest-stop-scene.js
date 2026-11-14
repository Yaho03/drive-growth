// 7차 · 휴게소 연출 — 르르르가 트럭 운전석에서 잠든 밤
// 순수 함수: RestStopScene.render(t, {variant, reduced}) → 340×237 캔버스 (×4 = 1360×948)
// t는 초. 0–6초 첫 재생, 6초 이후는 10초 반복 구간(이음매 없음).
(function () {
'use strict';
const BW = 340, BH = 237, LOOP = 10, INTRO = 6;
const WW = 420, WH = 262, G = 228;      // 전경 월드
const MX = 60, MY = 30;                  // 패럴랙스 여백

// ── 공용 팔레트(르르르) + 밤에 더한 색 ─────────────────────────────
const RU = { K:'#1c1e2c', b:'#e8d6b4', p:'#f4c49c', d:'#d2aa78', W:'#f6f6f2', B:'#68a0f0', M:'#346cd6', N:'#243e8c', L:'#aad6f6' };
const NIGHT = {
  sky0:'#080a18', sky1:'#0c0e22', sky2:'#11142c', sky3:'#181a3a', sky4:'#221f46',
  n1:'#13172d', n2:'#151935', n3:'#1b2042', n4:'#262b54', n5:'#2f3566', n6:'#3a4070',
  rain1:'#2a3066', rain2:'#46509a', rain3:'#6a74b8', drop:'#8ea0d8', dropHi:'#c8d4f4',
  amber1:'#ffd98a', amber2:'#f2b25c', amber3:'#b8743a',
  pink1:'#ff8ab8', pink2:'#c0508a', pink3:'#5a2a50',
  cyan1:'#8af0ff', cyan2:'#3fb8d8', cyan3:'#2a8aa6',
  win1:'#f0e2b0', win2:'#b8a676',
  blanket1:'#e0946a', blanket2:'#c06450', blanket3:'#984840', blanket4:'#763636', blanket5:'#5a2a32', blanketLine:'#b87a62',
  seat1:'#6b4060', seat2:'#4a2e46', seat3:'#33203a',
  truck1:'#eceef6', truck2:'#cfd3e4', truck3:'#9399b8', truck4:'#6a7092',
  zee:'#c8ccf4', steam:'#8e94c0', bar:'#04050a'
};
const C = NIGHT;

// ── 유틸 ──────────────────────────────────────────────────────────
const B4 = [0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5];
const bay = (x, y) => (B4[((y & 3) << 2) | (x & 3)] + 0.5) / 16;
function rnd(i){ let t = (i * 2654435761 + 0x9e3779b9) | 0; t = Math.imul(t ^ (t >>> 16), 0x21f0aaad); t = Math.imul(t ^ (t >>> 15), 0x735a2d97); return ((t ^ (t >>> 15)) >>> 0) / 4294967296; }
const clamp = (v, a, b) => v < a ? a : v > b ? b : v;
const lerp = (a, b, u) => a + (b - a) * u;
const mod = (a, n) => ((a % n) + n) % n;
const eio = u => u < .5 ? 4*u*u*u : 1 - Math.pow(-2*u + 2, 3) / 2;
const eout = u => 1 - Math.pow(1 - u, 3);
const sine = u => -(Math.cos(Math.PI * u) - 1) / 2;
function hex(h){ return [parseInt(h.slice(1,3),16), parseInt(h.slice(3,5),16), parseInt(h.slice(5,7),16)]; }
function tint(h, m){ const c = hex(h); return '#' + c.map((v,i)=>clamp(Math.round(v*m[i]),0,255).toString(16).padStart(2,'0')).join(''); }
function mk(w, h){ const c = document.createElement('canvas'); c.width = w; c.height = h; const x = c.getContext('2d', { willReadFrequently: true }); x.imageSmoothingEnabled = false; return { c, x, w, h }; }
function R(g, x, y, w, h, c){ if (w <= 0 || h <= 0) return; g.fillStyle = c; g.fillRect(Math.round(x), Math.round(y), Math.round(w), Math.round(h)); }
function P(g, x, y, c){ g.fillStyle = c; g.fillRect(Math.round(x), Math.round(y), 1, 1); }
function vgrad(g, x, y, w, h, cols){
  for (let j = 0; j < h; j++){
    const u = j / Math.max(1, h - 1) * (cols.length - 1), i = Math.min(cols.length - 2, Math.floor(u)), f = u - i;
    R(g, x, y + j, w, 1, cols[i]);
    if (f > 0.02){ g.fillStyle = cols[i + 1]; for (let k = 0; k < w; k++) if (bay(x + k, y + j) < f) g.fillRect(x + k, y + j, 1, 1); }
  }
}
function dith(g, x, y, w, h, c, dens){ g.fillStyle = c; for (let j = 0; j < h; j++) for (let i = 0; i < w; i++){ const d = typeof dens === 'function' ? dens(i, j) : dens; if (bay(x + i, y + j) < d) g.fillRect(x + i, y + j, 1, 1); } }
function disc(g, cx, cy, r, c){ for (let j = -r; j <= r; j++){ const hw = Math.floor(Math.sqrt(r*r - j*j + r*0.6)); R(g, cx - hw, cy + j, hw*2 + 1, 1, c); } }
function ellFill(g, cx, cy, rx, ry, c){ for (let j = -ry; j <= ry; j++){ const hw = Math.round(rx * Math.sqrt(Math.max(0, 1 - (j*j)/(ry*ry + 0.5)))); R(g, cx - hw, cy + j, hw*2 + 1, 1, c); } }
function drawMap(g, rows, x, y, pal, shift){
  for (let r = 0; r < rows.length; r++){ const row = rows[r], sx = shift ? shift(r) : 0;
    for (let c = 0; c < row.length; c++){ const ch = row[c]; if (ch === '.') continue; const col = pal[ch]; if (!col) continue; g.fillStyle = col; g.fillRect(x + c + sx, y + r, 1, 1); } }
}

// ── 빛: 디더링 + 단계 색 (블러 없음) ─────────────────────────────
function lightPass(g, w, h, lights){
  if (!lights.length) return;
  const img = g.getImageData(0, 0, w, h), d = img.data;
  for (const L of lights){
    if (L.str <= 0.001) continue;
    const c = L.rgb || (L.rgb = hex(L.color)), st = L.steps || 3;
    const x0 = Math.max(0, Math.floor(L.bx0)), x1 = Math.min(w, Math.ceil(L.bx1)), y0 = Math.max(0, Math.floor(L.by0)), y1 = Math.min(h, Math.ceil(L.by1));
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++){
      const f = L.f(x, y); if (f <= 0) continue;
      const q = Math.floor(Math.min(1, f) * st + bay(x, y)) / st; if (q <= 0) continue;
      const k = q * L.str, i = (y * w + x) * 4;
      d[i] = Math.min(255, d[i] + c[0]*k); d[i+1] = Math.min(255, d[i+1] + c[1]*k); d[i+2] = Math.min(255, d[i+2] + c[2]*k);
    }
  }
  g.putImageData(img, 0, 0);
}
const radial = (cx, cy, r, color, str, steps) => ({ bx0:cx-r, bx1:cx+r, by0:cy-r, by1:cy+r, color, str, steps, f:(x,y)=>{ const dx=x-cx, dy=y-cy; return 1 - Math.sqrt(dx*dx + dy*dy)/r; } });
const ell = (cx, cy, rx, ry, color, str, steps) => ({ bx0:cx-rx, bx1:cx+rx, by0:cy-ry, by1:cy+ry, color, str, steps, f:(x,y)=>{ const dx=(x-cx)/rx, dy=(y-cy)/ry; return 1 - Math.sqrt(dx*dx + dy*dy); } });
const cone = (x, y, len, w0, w1, color, str, steps) => ({ bx0:x-w1, bx1:x+w1, by0:y, by1:y+len, color, str, steps, f:(px,py)=>{ const v=(py-y)/len; if (v<0||v>1) return 0; const hw=w0+(w1-w0)*v, hz=1-Math.abs(px-x)/hw; return hz<=0 ? 0 : Math.min(1, hz*1.5)*(1-v*0.7); } });

// 디더 디졸브: src를 dst 위로 k만큼 (cell = 디더 칸 크기)
function dissolve(dst, src, k, cell){
  if (k <= 0) return; if (k >= 1){ dst.x.drawImage(src.c, 0, 0); return; }
  const a = dst.x.getImageData(0, 0, BW, BH), b = src.x.getImageData(0, 0, BW, BH), da = a.data, db = b.data;
  for (let y = 0; y < BH; y++) for (let x = 0; x < BW; x++){
    if (bay((x / cell) | 0, (y / cell) | 0) < k){ const i = (y*BW + x)*4; da[i]=db[i]; da[i+1]=db[i+1]; da[i+2]=db[i+2]; }
  }
  dst.x.putImageData(a, 0, 0);
}

// ── 르르르 (보내 준 프레임에서 그대로 뽑은 3–30행) ────────────────
const RU_SRC = [
"..............................KKK...............",
".............KKK.............KWWbK..............",
"............KWWbK............KbbbK..............",
"............KbbbK............KbbbK..............",
"............KbbbK.............KKK...............",
".............KKKK.............KpK...............",
"..............KpK.............KpK...............",
"..............KpK.............KppK..............",
"..............KppK..KKKKKK....KppK..............",
"..............KppKKWWWWWWbKKK.KppK..............",
"...............KpKWbbbbbbbbbdKKppK..............",
"..............KKppKbbbbbbbbbbdKppK..............",
".............KpKKKKbbbbbbbbbbbKKKK..............",
".............KbbbbbbbbKbbbbbbbKdK...............",
"............KbbbbbbbbKKKbbbbbKKKdK..............",
"...........KpbbbbbbbbbKbbbbbbbKbpdK.............",
"...........KbbbbbbbbbbbbbbbbbbbbbdK.............",
"...........pbbbbbbpppbbbKbKbKbbbbpK.............",
"..........KpbbbbbbbbbbbbbKbKbbbbbpdK............",
"..........KpKKbbbbbbbbbbbbbbbbbbbpdK............",
"..........KKKKKKbbbbbbbbbbbbbbbbbKKK............",
"..........KpppKBKbbbbbKKKKKKKbbbKbbK............",
".........KpbpppKBKKKKKLLLLLLBKKKbbbbK...........",
".........KpbpppKBBKBLLBMMMMMMBBKbbbbK...........",
".........KpbpppKBKBBBMMBBBBBBMMKbbbbK...........",
".........KpbpppKKKBBMBBBBBBBBBBKbbbbK...........",
".........KpppppKKKBBBBBBBBBBBBBBKbbK............",
"..........KpppKpKNKBBBBBBBBBBBBBKKK............."
];
const RU_TOP = 3, HEAD_LAST = 23;
// 잠든 얼굴: + 눈 → ‿ 감은 눈 (같은 그림체, 이 부분만 더함)
function sleepRows(){
  const g = RU_SRC.map(r => r.split(''));
  const set = (c, r, ch) => { g[r - RU_TOP][c] = ch; };
  [[22,16],[21,17],[22,17],[23,17],[22,18],[30,16],[29,17],[30,17],[31,17],[30,18]].forEach(([c,r]) => set(c, r, 'b'));
  [[20,17],[23,17],[21,18],[22,18],[28,17],[31,17],[29,18],[30,18]].forEach(([c,r]) => set(c, r, 'K'));
  return g.map(r => r.join(''));
}
const RU_SLEEP = sleepRows();
const RU_NIGHT = {}; for (const k in RU) RU_NIGHT[k] = tint(RU[k], [0.84, 0.82, 0.92]);
const PAW = [".KKKK.", "KppppK", "KppdpK", ".KKKK."];
const ZEE = {
  4: ["####", "..#.", ".#..", "####"],
  5: ["#####", "...#.", "..#..", ".#...", "#####"]
};
const ORNAMENT = ["..OOO.", ".OLLOO", "OOOOOO", ".K..K."];

// ── 전경(휴게소 주차장) 레이어 ───────────────────────────────────
function L(){ const l = mk(WW + MX*2, WH + MY*2); l.x.translate(MX, MY); return l; }
function buildWide(variant){
  const sky = L(), g = sky.x;
  vgrad(g, -MX, -MY, WW + MX*2, 170, [C.sky0, C.sky1, C.sky2, C.sky3, C.sky4]);
  R(g, -MX, 140, WW + MX*2, 200, C.sky4);
  for (let i = 0; i < 9; i++){ const cx = rnd(i*7)*520 - 40, cy = 26 + rnd(i*7+1)*60, w = 40 + rnd(i*7+2)*70;
    dith(g, cx, cy, w, 6, '#1a1d3c', (x, y) => 0.45 * (1 - Math.abs(x - w/2)/(w/2)) * (1 - Math.abs(y - 3)/3)); }

  const mtn = L();
  { const m = mtn.x;
    for (let x = -MX; x < WW + MX; x++){ const top = Math.round(106 + 7*Math.sin(x*0.027) + 4*Math.sin(x*0.083 + 1.3)); R(m, x, top, 1, 40, '#111430'); }
    for (let x = -MX; x < WW + MX; x++){ const top = Math.round(118 + 4*Math.sin(x*0.05 + 2) + 2*Math.sin(x*0.17)); R(m, x, top, 1, 30, '#0f1229'); } }

  const hwy = L(); { const h = hwy.x;
    R(h, -MX, 124, WW + MX*2, 1, '#343a6e'); R(h, -MX, 125, WW + MX*2, 4, '#232852'); R(h, -MX, 129, WW + MX*2, 1, '#181c3c');
    for (let x = -MX; x < WW + MX; x += 34) R(h, x, 130, 3, 14, '#1a1e3e'); }

  const trees = L(); { const t = trees.x;
    for (let x = -MX; x < WW + MX; x++){ const top = Math.round(138 + 3*Math.sin(x*0.21) + 2*Math.sin(x*0.53 + 1) + (rnd(x+999) > 0.85 ? -2 : 0)); R(t, x, top, 1, 60, '#0c0f24'); } }

  const store = L(); { const s = store.x, x0 = 262;
    R(s, x0, 142, 142, 46, '#1b1d3a'); R(s, x0 - 2, 140, 146, 2, '#2a2d52'); R(s, x0 - 2, 142, 146, 1, '#20234a');
    for (let x = x0 + 4; x < x0 + 138; x++){ P(s, x, 146, ((x >> 1) & 1) ? C.pink1 : C.pink2); R(s, x, 147, 1, 3, (x % 6 === 0) ? C.pink3 : C.pink2); P(s, x, 150, ((x >> 1) & 1) ? C.pink2 : C.pink1); }
    R(s, x0 + 2, 153, 138, 2, '#2a2d52');
    R(s, x0 + 6, 157, 130, 29, C.win1);
    for (let x = x0 + 6; x < x0 + 136; x += 13) R(s, x, 157, 1, 29, '#7a6c4e');
    for (let y = 162; y < 186; y += 6) dith(s, x0 + 7, y, 128, 2, C.win2, 0.6);
    R(s, x0 + 96, 164, 14, 22, '#f8f0d4'); R(s, x0 + 102, 164, 1, 22, '#c8b890');
    R(s, x0, 186, 142, 2, '#232650'); }

  const ground = L(); { const gg = ground.x;
    R(gg, -MX, 186, WW + MX*2, WH, C.n1);
    R(gg, -MX, 186, WW + MX*2, 1, '#323866'); R(gg, -MX, 187, WW + MX*2, 2, '#262a4c');
    dith(gg, -MX, 189, WW + MX*2, 6, '#191d38', (x, y) => 0.5 - y*0.08);
    for (let k = 0; k < 10; k++){ const x0 = 30 + k*52; for (let y = 196; y < 254; y++) P(gg, Math.round(x0 - (y - 196)*0.24), y, (y & 1) ? '#3a4070' : '#30366a'); }
    R(gg, -MX, 196, WW + MX*2, 1, '#262b54');
    for (let i = 0; i < 260; i++) P(gg, rnd(i*3+11)*WW, 190 + rnd(i*3+12)*70, '#1a1e3a'); }

  const truck = L(); buildTruck(truck.x, variant); buildLamps(truck.x);

  const fg = L(); { const f = fg.x;
    for (let x = -MX; x < WW + MX; x++){ if (x > 150 && x < 300) continue; const top = Math.round(250 + 3*Math.sin(x*0.3) + 2*Math.sin(x*0.71)); R(f, x, top, 1, 40, '#07080f'); }
    R(f, 300, 236, 4, 30, '#141730'); R(f, 300, 236, 4, 1, '#262a4c'); R(f, 300, 240, 4, 2, C.amber3); }

  return { sky, mtn, hwy, trees, store, ground, truck, fg };
}
function buildTruck(g, variant){
  // 그림자
  dith(g, 118, G - 2, 140, 6, '#0a0c1a', (x, y) => 0.9 - Math.abs(x - 70)/90);
  // 차대·적재함
  R(g, 128, G - 14, 118, 4, '#22253f');
  R(g, 124, G - 21, 82, 6, '#5a6080'); R(g, 124, G - 21, 82, 1, '#7d84a6'); R(g, 124, G - 16, 82, 1, '#3e4262'); R(g, 124, G - 19, 80, 1, C.M);
  R(g, 120, G - 18, 4, 3, '#3e4262');
  // 실은 작은 차
  R(g, 136, G - 29, 60, 8, '#4a6478'); R(g, 136, G - 29, 60, 1, '#6a8698'); R(g, 150, G - 35, 32, 6, '#4a6478'); R(g, 151, G - 36, 30, 1, '#6a8698');
  R(g, 153, G - 34, 12, 4, '#141a2e'); R(g, 167, G - 34, 12, 4, '#141a2e'); P(g, 154, G - 34, '#3a4a6a');
  R(g, 194, G - 27, 2, 2, '#c8ccd8'); R(g, 136, G - 27, 2, 2, '#7a3040');
  disc(g, 148, G - 22, 3, '#14162a'); disc(g, 184, G - 22, 3, '#14162a');
  R(g, 158, G - 29, 1, 8, '#b89040'); R(g, 176, G - 29, 1, 8, '#b89040');
  // 운전석
  const top = G - 50;
  for (let y = top; y < G - 12; y++){
    const xr = y < G - 34 ? Math.round(236 + (y - top)*0.5) : 246;
    R(g, 204, y, xr - 204, 1, C.truck2);
  }
  R(g, 206, top, 30, 1, '#fff4dc'); R(g, 204, top + 1, 1, 37, C.truck1); R(g, 205, top + 1, 30, 1, '#f2e8d8');
  for (let y = G - 34; y < G - 12; y++) P(g, 245, y, C.truck3);
  R(g, 204, G - 14, 42, 2, C.truck3);
  // 창문 (안쪽 계기판 불빛 + 김)
  for (let y = G - 46; y < G - 35; y++){ const xr = Math.round(234 + (y - (G - 46))*0.5); R(g, 218, y, xr - 218, 1, '#123047'); }
  dith(g, 218, G - 46, 22, 11, '#2a6078', (x, y) => 0.15 + y*0.07);
  if (variant === 'blanket'){ for (let x = 222; x < 234; x++){ const h = Math.round(4 - Math.abs(x - 228)*0.5); R(g, x, G - 36 - h, 1, h, '#0c1a26'); } }
  else {
    R(g, 223, G - 37, 11, 2, '#0c1a26'); R(g, 224, G - 38, 9, 1, '#0c1a26');
    R(g, 225, G - 43, 1, 5, '#0c1a26'); R(g, 224, G - 45, 2, 2, '#0c1a26');
    R(g, 231, G - 42, 1, 4, '#0c1a26'); R(g, 231, G - 44, 2, 2, '#0c1a26');
  }
  R(g, 216, G - 47, 1, 34, C.truck3); R(g, 217, G - 47, 24, 1, C.truck3);
  R(g, 219, G - 31, 4, 1, C.truck4);
  // 옆면 띠
  R(g, 204, G - 28, 42, 3, C.M); R(g, 204, G - 28, 42, 1, C.B);
  // 지붕 경광등 (주차 중 꺼짐)
  R(g, 214, top - 1, 14, 1, C.truck4); R(g, 216, top - 4, 10, 3, C.amber3); R(g, 217, top - 4, 8, 1, C.amber2);
  // 앞
  R(g, 240, G - 14, 7, 3, '#4a4f6e'); R(g, 243, G - 24, 3, 3, '#c8ccd8'); R(g, 237, G - 45, 2, 7, '#2a2d48');
  // 바퀴
  [[148, 7], [184, 7], [230, 7]].forEach(([x, r]) => { disc(g, x, G - 6, r, '#14162a'); disc(g, x, G - 6, 3, '#5a6080'); P(g, x, G - 6, '#8a90b0'); });
  R(g, 222, G - 14, 16, 1, '#4a4f70');
}
function buildLamps(g){
  [[44, 118], [196, 116], [352, 120]].forEach(([x, top]) => {
    R(g, x - 10, top, 1, G + 4 - top, '#262a4c'); R(g, x - 9, top, 1, G + 4 - top, '#1b1f3e');
    R(g, x - 10, top, 9, 1, '#2f3566'); R(g, x - 4, top + 1, 8, 2, '#2a2f5c');
    R(g, x - 3, top + 3, 6, 1, C.amber1); R(g, x - 2, top + 4, 4, 1, C.amber2);
    R(g, x - 12, G + 2, 5, 2, '#1b1f3e');
  });
}
const LAMPS = [[44, 118], [196, 116], [352, 120]];

// ── 카메라 ────────────────────────────────────────────────────────
function wideCam(t){
  if (t < 2.45){ const u = eio(clamp(t / 2.45, 0, 1)); return { s:1, cx: lerp(170, 232, u), cy: lerp(118.5, 142.5, u) }; }
  if (t < 2.95){ const u = (t - 2.45)/0.5; return { s:2, cx: lerp(223, 226, u), cy: lerp(184, 186, u) }; }
  const u = clamp((t - 2.95)/0.8, 0, 1); return { s:3, cx: lerp(227.5, 229.5, u), cy: lerp(186.5, 188, u) };
}
function parallaxCam(t){
  if (t < 2.45){ const u = eio(clamp(t / 2.45, 0, 1)); return { x: lerp(0, 62, u), y: lerp(0, 24, u) }; }
  return { x: 62 + (t - 2.45)*3, y: 24 + (t - 2.45)*1 };
}

// ── 공유 캔버스 ───────────────────────────────────────────────────
let OUT, TMP, TMP2, WORLD, WIDE = {}, CAB = {}, VIG;
function ensure(){
  if (OUT) return;
  OUT = mk(BW, BH); TMP = mk(BW, BH); TMP2 = mk(BW, BH); WORLD = mk(WW, WH);
  VIG = new Float32Array(BW * BH);
  for (let y = 0; y < BH; y++) for (let x = 0; x < BW; x++){
    const dx = (x - BW/2)/(BW/2), dy = (y - BH/2)/(BH/2), dist = Math.sqrt(dx*dx*0.85 + dy*dy);
    const f = clamp((dist - 0.6)/0.55, 0, 1), q = Math.floor(f*4 + bay(x, y))/4;
    VIG[y*BW + x] = 1 - Math.min(1, q)*0.5;
  }
}
function vignette(g){
  const img = g.getImageData(0, 0, BW, BH), d = img.data;
  for (let i = 0, n = BW*BH; i < n; i++){ const v = VIG[i]; if (v === 1) continue; d[i*4] *= v; d[i*4+1] *= v; d[i*4+2] *= v * 1.04; }
  g.putImageData(img, 0, 0);
}
function rain(g, t, nFar, nNear, area){
  const H = BH + 24, Wd = BW + 60;
  g.fillStyle = C.rain1;
  for (let i = 0; i < nFar; i++){ const n = 7 + (i % 4), v = H*n/LOOP, y = mod(rnd(i*3+1)*H + v*t, H) - 12, x = mod(rnd(i*3+2)*Wd + y*0.14, Wd) - 30;
    if (area && !area(x, y)) continue; g.fillRect(Math.round(x), Math.round(y), 1, 3); }
  for (let i = 0; i < nNear; i++){ const n = 13 + (i % 5), v = H*n/LOOP, y = mod(rnd(i*5+7)*H + v*t, H) - 12, x = mod(rnd(i*5+8)*Wd + y*0.18, Wd) - 30;
    if (area && !area(x, y)) continue; R(g, x, y, 1, 6, C.rain2); P(g, x + 1, y + 6, C.rain3); }
}

// ── ① ② 휴게소 전경 → 트럭으로 ──────────────────────────────────
function drawWorld(t, variant){
  const key = variant; if (!WIDE[key]) WIDE[key] = buildWide(variant);
  const Lr = WIDE[key], g = WORLD.x, pc = parallaxCam(t);
  const lay = (l, p) => g.drawImage(l.c, -MX + Math.round(pc.x*(1 - p)), -MY + Math.round(pc.y*(1 - p)));
  const off = p => Math.round(pc.x*(1 - p));
  lay(Lr.sky, 0.12); lay(Lr.mtn, 0.28);
  lay(Lr.hwy, 0.4);
  { const hx = off(0.4), hy = Math.round(pc.y*0.6);
    for (let i = 0; i < 7; i++){ const v = 46 + rnd(i+40)*30, x = mod(rnd(i+20)*520 + v*t, 520) - 50 + hx; R(g, x, 125 + hy, 2, 1, '#ffe6a8'); P(g, x - 1, 125 + hy, '#9a8a6a'); }
    for (let i = 0; i < 6; i++){ const v = 40 + rnd(i+60)*30, x = mod(rnd(i+80)*520 - v*t, 520) - 50 + hx; R(g, x, 127 + hy, 2, 1, '#d8405a'); } }
  lay(Lr.trees, 0.55);
  lay(Lr.store, 0.74);
  lay(Lr.ground, 1);
  // 젖은 바닥 반사
  const sx = off(0.74), shim = (j) => Math.round(Math.sin(t*2.1 + j*0.9));
  for (let j = 0; j < 22; j++){ const dens = 0.5*(1 - j/22); g.fillStyle = '#4e4636';
    for (let x = 268; x < 398; x++) if (bay(x + shim(j) + sx, 190 + j) < dens) g.fillRect(x + sx + shim(j), 190 + j, 1, 1); }
  for (let j = 0; j < 10; j++){ const dens = 0.35*(1 - j/10); g.fillStyle = C.pink3;
    for (let x = 266; x < 400; x++) if (bay(x + shim(j+3), 212 + j) < dens) g.fillRect(x + sx + shim(j+3), 212 + j, 1, 1); }
  LAMPS.forEach(([lx], k) => { for (let j = 0; j < 30; j++){ const dens = 0.55*(1 - j/30); g.fillStyle = '#5a4a34';
    for (let x = -2; x <= 2; x++) if (bay(lx + x + shim(j+k), G + 6 + j) < dens*(1 - Math.abs(x)/3)) g.fillRect(lx + x + shim(j+k), G + 6 + j, 1, 1); } });
  // 물웅덩이 파문
  g.fillStyle = '#30366a';
  for (let i = 0; i < 16; i++){ const per = 1.2 + rnd(i+300)*0.9, a = mod(t + rnd(i+310)*per, per)/per; if (a > 0.8) continue;
    const cx = Math.round(rnd(i+320)*WW), cy = Math.round(196 + rnd(i+330)*60), rx = 1 + Math.round(a*4), ry = Math.max(1, Math.round(rx/3));
    for (let k = 0; k < 14; k++){ const an = k/14*Math.PI*2; g.fillRect(Math.round(cx + Math.cos(an)*rx), Math.round(cy + Math.sin(an)*ry), 1, 1); } }
  const amb = [];
  LAMPS.forEach(([lx, top]) => {
    amb.push(cone(lx, top + 5, G + 4 - top - 5, 3, 30, C.amber2, 0.26, 4));
    amb.push(ell(lx, G + 3, 54, 9, C.amber2, 0.3, 3));
  });
  amb.push(ell(333 + sx, 148, 86, 22, C.pink2, 0.24, 3));
  amb.push(ell(333 + sx, 194, 84, 12, C.win1, 0.14, 3));
  lightPass(g, WW, WH, amb);
  lay(Lr.truck, 1);
  lay(Lr.fg, 1.3);
  const glow = LAMPS.map(([lx, top]) => radial(lx, top + 3, 12, C.amber1, 0.55, 3));
  glow.push(radial(229, G - 41, 13, C.cyan2, 0.4, 3));
  lightPass(g, WW, WH, glow);
}
function blitView(dst, cam){
  const s = cam.s, sw = Math.ceil(BW/s), sh = Math.ceil(BH/s);
  const x = clamp(Math.round(cam.cx - BW/s/2), 0, WW - sw), y = clamp(Math.round(cam.cy - BH/s/2), 0, WH - sh);
  dst.x.drawImage(WORLD.c, x, y, sw, sh, 0, 0, sw*s, sh*s);
}
function drawWide(dst, t, o){
  drawWorld(t, o.variant);
  const cam = wideCam(t);
  blitView(dst, cam);
  const step = [[2.45, 1], [2.95, 2]];
  for (const [at, prevS] of step){
    if (t >= at && t < at + 0.16){ const pv = wideCam(at - 0.001); blitView(TMP2, { s: prevS, cx: pv.cx, cy: pv.cy }); dissolve(dst, TMP2, 1 - (t - at)/0.16, 1); }
  }
  rain(dst.x, t, 150, 46);
}

// ── ③ 운전석 안 ───────────────────────────────────────────────────
const CM = 3, CW = 122, CH = 88;            // 아트 1px = 버퍼 3px = 화면 12px
const pillarX = y => 84 + Math.floor((clamp(y, 7, 42) - 7)*0.35);
function isSide(x, y){ if (x < 6 || x > 72 || y < 8 || y > 41) return false; const cx = x < 8 ? 8 - x : (x > 70 ? x - 70 : 0), cy = y < 10 ? 10 - y : (y > 39 ? y - 39 : 0); return cx + cy <= 2; }
const isWind = (x, y) => y >= 7 && y <= 42 && x >= pillarX(y) + 3;
const isGlass = (x, y) => isSide(x, y) || isWind(x, y);
function AL(){ const l = mk(CW, CH); l.x.translate(CM, CM); return l; }
function blitArt(dst, l, ox, oy, origin){ const o = origin == null ? CM : origin; dst.drawImage(l.c, 0, 0, l.w, l.h, -o*3 + ox, -o*3 + oy, l.w*3, l.h*3); }

function plaid(x, y){
  const u = mod(x - 2, 8), v = mod(y - 1, 8), sx = u < 2, sy = v < 2;
  if (sx && sy) return C.blanket5; if (sx || sy) return C.blanket4;
  if (v === 5 && (x & 3) === 1) return C.blanketLine;
  return C.blanket3;
}
function buildCabin(){
  // 뒤: 벽·창틀·의자 (유리 자리는 비워 둠)
  const back = AL(), g = back.x;
  R(g, -3, -3, CW, CH, C.n2);
  R(g, -3, -3, CW, 9, C.n3); R(g, -3, 5, CW, 1, C.n4); R(g, 58, 2, 8, 2, '#2a2f5c'); R(g, 59, 2, 6, 1, '#353b6e');
  R(g, -3, 42, 84, CH, '#191d3b'); R(g, -3, 42, 84, 1, C.n6); R(g, -3, 43, 84, 1, C.n5); R(g, -3, 55, 84, 1, '#20254a');
  R(g, 8, 49, 4, 1, C.n5); R(g, 8, 50, 4, 1, '#20254a');
  R(g, 5, 7, 69, 36, '#2a2f5c'); R(g, 5, 42, 69, 1, '#3a4070');
  for (let y = -3; y < 46; y++) for (let x = -3; x < 119; x++) if (isGlass(x, y)) g.clearRect(x, y, 1, 1);
  for (let y = 4; y < 46; y++){ const xl = pillarX(y); R(g, xl, y, 3, 1, '#22274c'); P(g, xl, y, C.n5); }
  R(g, 76, 42, 46, CH, '#14172f');
  // 유리에 서린 김 + 맺힌 물방울
  for (let y = 26; y < 43; y++) for (let x = 0; x < 119; x++){ if (!isGlass(x, y)) continue; const dn = (y - 26)/16*0.5;
    if (bay(x, y) < dn*0.45) P(g, x, y, '#3a4070'); else if (bay(x, y) < dn) P(g, x, y, '#2a2f5c'); }
  for (let i = 0; i < 30; i++){ const x = Math.round(rnd(i+500)*116), y = Math.round(8 + rnd(i+510)*30); if (isGlass(x, y)) P(g, x, y, '#3a4478'); }
  // 젖힌 의자
  for (let y = 14; y < 70; y++){ const xl = Math.round(24 - (y - 14)*0.16), xr = Math.round(62 + (y - 14)*0.16), ins = y === 14 ? 2 : y === 15 ? 1 : 0;
    R(g, xl + ins, y, xr - xl - ins*2 + 1, 1, C.seat2); R(g, xl + ins, y, 2, 1, C.seat1); R(g, xr - ins - 1, y, 2, 1, C.seat3); P(g, xl + ins - 1, y, '#1c1424'); P(g, xr - ins + 1, y, '#1c1424'); }
  R(g, 26, 14, 35, 1, C.seat1);
  for (let y = 19; y < 68; y++){ P(g, 35 - Math.round((y - 14)*0.05), y, '#3d2540'); P(g, 51 + Math.round((y - 14)*0.05), y, '#3d2540'); }
  R(g, 37, 16, 1, 2, '#8a90a8'); R(g, 49, 16, 1, 2, '#8a90a8');
  R(g, 31, 8, 25, 8, C.seat2); R(g, 32, 7, 23, 1, '#1c1424'); R(g, 30, 9, 1, 6, '#1c1424'); R(g, 56, 9, 1, 6, '#1c1424');
  R(g, 32, 8, 23, 1, C.seat1); R(g, 31, 9, 2, 6, C.seat1); R(g, 31, 15, 25, 1, C.seat3); R(g, 54, 9, 2, 6, C.seat3);
  R(g, 10, 60, 74, 10, C.seat2); R(g, 10, 60, 74, 1, C.seat1); R(g, 10, 69, 74, 1, C.seat3);
  R(g, -3, 70, 80, CH, '#0e1124');

  // 앞: 계기판·운전대·종이컵
  const front = AL(), f = front.x;
  for (let x = 80; x < 119; x++){ const top = x < 86 ? 46 + Math.round((86 - x)*0.9) : Math.round(46 - (x - 86)*0.22);
    R(f, x, top - 1, 1, 1, '#140f24'); R(f, x, top, 1, 1, '#433c66'); R(f, x, top + 1, 1, 2, '#2e2848'); R(f, x, top + 3, 1, 61 - top - 3, '#221d38'); R(f, x, 60, 1, 2, '#140f24'); }
  R(f, 86, 47, 13, 1, '#3a3460'); R(f, 87, 48, 11, 5, '#0b0d1e');
  for (let k = 0; k < 5; k++){ P(f, 88 + k*2, 49, C.cyan1); P(f, 89 + k*2, 49, C.cyan3); P(f, 88 + k*2, 51, C.cyan3); P(f, 89 + k*2, 51, (k % 2) ? C.cyan2 : C.cyan3); }
  P(f, 93, 50, C.cyan2);
  for (let k = 0; k < 7; k++) R(f, 86 + k, 51 + Math.round(k*0.6), 1, 2, '#1e2036');
  for (let j = -10; j <= 10; j++){ const hw = Math.round(2.6*Math.sqrt(Math.max(0, 1 - (j/10.5)*(j/10.5)))), sx = Math.round(j*0.3), y = 50 + j, x0 = 84 - hw + sx, x1 = 84 + hw + sx;
    if (hw <= 1) R(f, x0, y, x1 - x0 + 1, 1, '#262840'); else { R(f, x0, y, 2, 1, '#3d4166'); R(f, x1 - 1, y, 2, 1, '#262840'); } }
  R(f, 103, 36, 5, 1, '#d6d0dc'); R(f, 102, 37, 7, 1, '#b8b2c2'); R(f, 103, 38, 5, 2, '#7a5040'); P(f, 104, 38, '#9a6a50'); R(f, 103, 40, 5, 1, '#b8b2c2'); R(f, 104, 41, 3, 1, '#9a94a8'); R(f, 102, 42, 7, 1, '#140f24');
  R(f, 95, 43, 3, 1, '#5a6080'); R(f, 96, 41, 1, 2, '#8a90a8');

  // 창밖 (가장 멀리, 가장 많이 움직임)
  const out = mk(190, 56), o = out.x; o.translate(30, 0);
  vgrad(o, -30, 0, 190, 46, ['#090b1c', '#0e1126', '#151836', '#1e1c42']);
  for (let x = -30; x < 160; x++){ const top = Math.round(31 + Math.sin(x*0.3) + (rnd(x+700) > 0.8 ? -1 : 0)); R(o, x, top, 1, 20, '#0d1024'); }
  R(o, -30, 26, 190, 1, '#343a6e'); R(o, -30, 27, 190, 1, '#232852');
  for (let x = -30; x < 160; x += 16) R(o, x, 28, 1, 3, '#161a36');
  R(o, 19, 29, 42, 1, '#2a2d52'); R(o, 20, 30, 40, 14, '#1b1d3a');
  for (let x = 22; x < 58; x++){ P(o, x, 31, ((x >> 1) & 1) ? C.pink1 : C.pink2); P(o, x, 32, (x % 5 === 0) ? C.pink3 : C.pink2); }
  R(o, 22, 35, 36, 8, C.win1); for (let x = 22; x < 58; x += 7) R(o, x, 35, 1, 8, '#8a7a5a'); dith(o, 23, 38, 34, 1, C.win2, 0.7); dith(o, 23, 41, 34, 1, C.win2, 0.7);
  R(o, -30, 43, 190, 13, C.n1); dith(o, 20, 44, 40, 4, '#4e4636', (x, y) => 0.5 - y*0.12); dith(o, 20, 48, 40, 2, C.pink3, 0.3);
  R(o, 9, 6, 1, 40, '#262a4c'); R(o, 9, 6, 5, 1, '#2f3566'); R(o, 11, 7, 4, 1, C.amber1); R(o, 12, 8, 2, 1, C.amber2);
  R(o, 70, 33, 12, 4, '#11142a'); R(o, 72, 31, 7, 2, '#11142a'); P(o, 81, 35, '#7a3040');
  R(o, 112, 9, 1, 37, '#262a4c'); R(o, 107, 9, 6, 1, '#2f3566'); R(o, 107, 10, 4, 1, C.amber1); R(o, 108, 11, 2, 1, C.amber2);

  const drops = AL(), zbuf = null;
  return { back, front, out, outDyn: mk(190, 56), drops, chars: {}, blankets: {}, paws: null };
}
function charLayer(tilt){
  if (CAB.chars[tilt]) return CAB.chars[tilt];
  const l = AL(); const head = HEAD_LAST - RU_TOP;
  drawMap(l.x, RU_SLEEP, 20, RU_TOP + 6, RU_NIGHT, r => r <= head ? Math.round(tilt*(head - r)/head) : 0);
  return CAB.chars[tilt] = l;
}
function pawLayer(){
  if (CAB.paws) return CAB.paws; const l = AL();
  drawMap(l.x, PAW, 31, 33, RU_NIGHT); drawMap(l.x, PAW, 48, 33, RU_NIGHT);
  return CAB.paws = l;
}
function blanketLayer(variant){
  if (CAB.blankets[variant]) return CAB.blankets[variant];
  const l = AL(), g = l.x, H = 86;
  let inside;
  if (variant === 'blanket'){
    inside = (x, y) => { if (y < 12) return false; const hw = y < 40 ? Math.round(24*Math.sqrt(clamp((y - 11)/29, 0, 1))) : Math.round(24 + (y - 40)*0.24); return Math.abs(x - 43) <= hw; };
  } else {
    inside = (x, y) => { const yt = 34 + Math.round(((x - 44)/24)*((x - 44)/24)*3); if (y < yt) return false; const xl = Math.round(21 - (y - 34)*0.12), xr = Math.round(66 + (y - 34)*0.14); return x >= xl && x <= xr; };
  }
  const topOf = {};
  for (let x = 0; x < 100; x++) for (let y = 0; y < H; y++) if (inside(x, y)){ topOf[x] = y; break; }
  for (let y = 0; y < H; y++) for (let x = 0; x < 100; x++){
    if (!inside(x, y)) continue;
    const edge = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1);
    if (edge){ P(g, x, y, '#2a1420'); continue; }
    const dt = y - topOf[x];
    let c = plaid(x, y);
    if (variant !== 'blanket'){ if (dt === 1) c = C.blanket1; else if (dt <= 3) c = C.blanket2; else if (dt === 4) c = '#7a3036'; }
    else if (dt === 1 && y < 30) c = C.blanket2;
    P(g, x, y, c);
  }
  const fold = (x0, y0, x1, y1) => { for (let k = 0; k <= 30; k++){ const u = k/30, x = Math.round(lerp(x0, x1, u)), y = Math.round(lerp(y0, y1, u)); if (inside(x, y) && inside(x + 1, y)) { P(g, x, y, '#7a3036'); P(g, x + 1, y, C.blanket2); } } };
  if (variant === 'blanket'){ fold(30, 30, 24, 80); fold(56, 32, 64, 80); fold(43, 14, 41, 22); P(g, 42, 11, '#2a1420'); P(g, 43, 11, '#2a1420'); P(g, 43, 10, '#2a1420'); }
  else { fold(30, 46, 24, 80); fold(58, 48, 64, 80); fold(44, 58, 43, 80); }
  return CAB.blankets[variant] = l;
}

function pose(lt, live){
  let tilt = 1, drop = 0;
  if (!live) return { tilt, drop };
  if (lt >= 2.9 && lt < 3.25) tilt = 0; else if (lt >= 3.25 && lt < 6.6) tilt = -1;
  if (lt >= 6.6 && lt < 7.9){ const u = (lt - 6.6)/1.3; tilt = u < 0.55 ? -1 : -2; drop = Math.round(sine(u)*3); }
  if (lt >= 7.9 && lt < 8.08){ tilt = 1; drop = -1; }
  return { tilt, drop };
}
const BREATH = LOOP/3;
const breathAt = lt => 0.5 - 0.5*Math.cos(2*Math.PI*lt/BREATH);
const ZEMIT = [0.4, 2.9, 5.4, 7.9], ZLIFE = 2.3;
const SWEEP = [1.5, 4.2];

function drawCabin(dst, lt, o){
  if (!CAB.back) Object.assign(CAB, buildCabin());
  const g = dst.x, red = !!o.reduced, live = !red && lt >= 0, anim = !red;
  const lm = mod(lt, LOOP);
  let ox = 0, oy = 0;
  if (anim){ const ph = 2*Math.PI*lm/LOOP; ox = Math.round(3*Math.sin(ph)); oy = Math.round(1.5*Math.sin(ph*2));
    if (lt < 0){ const u = eout(clamp((lt + 2.7)/2.7, 0, 1)); ox += Math.round((1 - u)*-9); oy += Math.round((1 - u)*5); } }
  const oo = { x: Math.round(ox*2.4), y: Math.round(oy*2.4) }, fo = { x: Math.round(ox*1.5), y: Math.round(oy*1.5) };
  R(g, 0, 0, BW, BH, '#0a0c1c');
  // 창밖
  const od = CAB.outDyn.x; od.clearRect(0, 0, 190, 56); od.drawImage(CAB.out.c, 0, 0);
  const tc = anim ? lm : 0;
  for (let i = 0; i < 5; i++){ const n = 2 + (i % 2), x = mod(rnd(i+900)*190 + 190*n*tc/LOOP, 190); P(od, x, 26, '#ffe2a0'); P(od, x - 1, 26, '#8a7a5a'); }
  for (let i = 0; i < 4; i++){ const n = 2 + (i % 2), x = mod(rnd(i+950)*190 - 190*n*tc/LOOP, 190); P(od, x, 27, '#d8405a'); }
  let sweep = 0;
  if (live && lm >= SWEEP[0] && lm < SWEEP[1]){
    sweep = (lm - SWEEP[0])/(SWEEP[1] - SWEEP[0]);
    const cx = Math.round(lerp(-10, 200, sweep)) ;
    R(od, cx - 12, 41, 12, 3, '#0e1022'); R(od, cx - 10, 39, 7, 2, '#0e1022'); R(od, cx, 42, 2, 1, '#fff2cc'); dith(od, cx + 2, 41, 10, 3, '#c8b890', (x, y) => 0.6 - x*0.06);
  }
  blitArt(g, CAB.outDyn, oo.x - 6, oo.y - 3, 30);
  if (anim) rain(g, lt, 110, 34);
  // 실내
  blitArt(g, CAB.back, ox, oy);
  const dg = CAB.drops.x; dg.clearRect(-3, -3, CW, CH);
  for (let i = 0; i < 18; i++){ const n = 1 + (i % 3), x0 = Math.round(rnd(i+600)*116), y = Math.round(8 + mod(rnd(i+610)*34 + 34*n*tc/LOOP, 34)), x = x0 + ((y >> 3) & 1);
    if (!isGlass(x, y)) continue; P(dg, x, y, C.drop); if (isGlass(x, y - 1)) P(dg, x, y - 1, C.dropHi);
    for (let k = 2; k < 5; k++) if (isGlass(x, y - k) && bay(x, y - k) < 0.7 - k*0.12) P(dg, x - ((y - k) >> 3 & 1) + ((y >> 3) & 1), y - k, '#3a4480'); }
  blitArt(g, CAB.drops, ox, oy);
  const br = breathAt(lt), bUp = Math.round(br*2), hUp = br > 0.72 ? 1 : 0;
  const ps = pose(lm, live);
  if (o.variant !== 'blanket') blitArt(g, charLayer(ps.tilt), ox, oy + ps.drop - hUp);
  blitArt(g, blanketLayer(o.variant), ox, oy - bUp);
  if (o.variant !== 'blanket') blitArt(g, pawLayer(), ox, oy - bUp);
  blitArt(g, CAB.front, fo.x, fo.y);
  // 대시보드 작은 차 장식 (움찔한 뒤 흔들림)
  let sway = 0; if (live && lm >= 7.9){ const a = lm - 7.9; sway = Math.round(3*Math.exp(-a*1.7)*Math.sin(a*6.5)); }
  { const bx = 93*3 + fo.x + sway, by = 37*3 + fo.y; const pal = { O: '#d8783a', L: '#8ab2dc', K: '#16182a' };
    for (let r = 0; r < 4; r++) for (let c = 0; c < 6; c++){ const ch = ORNAMENT[r][c]; if (ch === '.') continue; R(g, bx + c*3, by + r*3, 3, 3, r === 0 && ch === 'O' ? '#f2a45a' : pal[ch]); } }
  // 김
  if (anim){ const cx = 105*3 + fo.x, cy = 35*3 + fo.y;
    for (let i = 0; i < 7; i++){ const a = mod(lt/2 + i/7, 1), x = Math.round(cx + Math.sin(a*6 + i*1.7)*2.5 + a*4), y = Math.round(cy - a*26);
      const dn = (a < 0.15 ? a/0.15 : 1 - (a - 0.15)/0.85)*0.75; if (bay(x >> 1, y >> 1) < dn) R(g, x, y, 2, 2, C.steam); } }
  // Z
  if (anim){
    const zx0 = (o.variant === 'blanket' ? 52 : 56)*3 + ox, zy0 = (o.variant === 'blanket' ? 14 : 15)*3 + oy;
    for (const e of ZEMIT){ const age = mod(lt - e, LOOP); if (age >= ZLIFE) continue; const a = age/ZLIFE;
      const glyph = a < 0.35 ? ZEE[4] : ZEE[5], cs = a < 0.35 ? 2 : 3;
      const x = Math.round(zx0 + a*30 + Math.sin(a*5)*4), y = Math.round(zy0 - a*46);
      const dn = a < 0.12 ? a/0.12 : a > 0.6 ? 1 - (a - 0.6)/0.4 : 1;
      for (let r = 0; r < glyph.length; r++) for (let c = 0; c < glyph[r].length; c++){ if (glyph[r][c] !== '#') continue; if (bay(c + r*3, r) < dn) R(g, x + c*cs, y + r*cs, cs, cs, C.zee); }
    }
  }
  // 빛 (가로등·간판·계기판 + 지나가는 헤드라이트)
  const lights = [
    radial(36 + oo.x - 6, 21 + oo.y - 3, 230, C.amber2, 0.2, 4),
    radial(36 + oo.x - 6, 21 + oo.y - 3, 18, C.amber1, 0.5, 3),
    radial(324 + oo.x - 6, 30 + oo.y - 3, 16, C.amber1, 0.45, 3),
    ell(117 + oo.x - 6, 114 + oo.y - 3, 80, 26, C.win1, 0.12, 3),
    ell(118, 110, 170, 90, C.pink2, 0.09, 3),
    radial(93*3 + fo.x, 50*3 + fo.y, 190, C.cyan2, 0.26, 4),
    radial(93*3 + fo.x, 50*3 + fo.y, 26, C.cyan1, 0.22, 3)
  ];
  if (sweep > 0){ const X = lerp(-140, 480, sine(sweep)), env = Math.sin(Math.PI*sweep);
    lights.push({ bx0: X - 140, bx1: X + 140, by0: 0, by1: BH, color: '#fff0d0', str: 0.36*env, steps: 4, f: (x, y) => 1 - Math.abs(x - X + (y - 118)*0.4)/62 }); }
  lightPass(g, BW, BH, lights);
}

// ── 공개 API ──────────────────────────────────────────────────────
function wrap(t){ return t < INTRO ? t : INTRO + mod(t - INTRO, LOOP); }
function bars(tt, reduced){ if (reduced) return 0; if (tt < 4.6) return 24; if (tt < 5.2) return Math.round(24*(1 - eio((tt - 4.6)/0.6))/2)*2; return 0; }
function render(t, opts){
  ensure();
  const o = Object.assign({ variant: 'rururu', reduced: false }, opts || {});
  const g = OUT.x;
  if (o.reduced){ drawCabin(OUT, mod(t, LOOP), o); vignette(g); return OUT.c; }
  const tt = wrap(t), lt = tt - INTRO;
  if (tt < 3.75) drawWide(OUT, tt, o);
  if (tt >= 3.3){
    if (tt < 3.75){ drawCabin(TMP, lt, o); dissolve(OUT, TMP, sine((tt - 3.3)/0.45), 3); }
    else drawCabin(OUT, lt, o);
  }
  vignette(g);
  const b = bars(tt, false); if (b){ R(g, 0, 0, BW, b, C.bar); R(g, 0, BH - b, BW, b, C.bar); }
  return OUT.c;
}
function cardProgress(t, reduced){ if (reduced) return 1; const tt = wrap(t); return clamp((tt - 5.0)/0.8, 0, 1); }
function soundCues(t, reduced){
  const tt = wrap(t), lt = tt - INTRO, lm = mod(lt, LOOP), list = [];
  if (!reduced) list.push('빗소리 (계속)');
  list.push('휴게소 재즈 (아주 작게)');
  if (!reduced && tt >= 2.0 && tt < 2.9) list.push('와이퍼 한 번');
  if (!reduced && lt >= 0 && lm >= SWEEP[0] && lm < SWEEP[1]) list.push('지나가는 차');
  const cab = reduced || tt >= 3.5; const bp = mod(reduced ? t : lt, BREATH)/BREATH;
  if (cab && bp > 0.3 && bp < 0.62) list.push('작은 숨소리');
  return list;
}
window.RestStopScene = {
  render, wrap, bars, cardProgress, soundCues,
  WIDTH: BW, HEIGHT: BH, SCALE: 4, LOOP, INTRO,
  CHARACTER_PALETTE: RU, NIGHT_COLORS: NIGHT,
  TIMING: { wide: [0, 2], toTruck: [2, 3.5], zoomSteps: [2.45, 2.95], dissolveToCabin: [3.3, 3.75], cabin: [3.5, 6], barsOpen: [4.6, 5.2], card: [5.0, 5.8], loop: [6, 16],
    loopMoments: { breath: BREATH, zEvery: 2.5, headlight: SWEEP, toss: 2.9, nod: [6.6, 7.9], jolt: 7.9, ornament: [7.9, 9.8] } }
};
})();
