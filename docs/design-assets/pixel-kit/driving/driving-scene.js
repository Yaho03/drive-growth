/* 9차 · 주행 중 화면 — 픽셀 렌더러 (시간 t → 화면)
   DrivingScene.build(opts)          → 층별 이미지(dataURL) + 배치 정보 (opts 바뀔 때만 다시 그림)
   DrivingScene.pose(t, total, opts) → 그 시각의 층 상태(흔들림·깜빡임·귀·장식·반사)
   DrivingScene.draw(ctx, t, opts)   → 캔버스 한 장에 바로 그리기 (시제품 이식용)
   opts: { timeOfDay:'day'|'night', driver:'rrr'|'mechanic', layout:'wide'|'narrow', motion:'full'|'blinkOnly'|'still' } */
(function () {
  var LOOP = 12;
  var LAYOUT = {
    wide:   { unit: 4, Wu: 340, Hu: 237, tyFn: function () { return 92; } },
    narrow: { unit: 4, Wu: 210, Hu: 296, tyFn: function () { return 146; } }
  };
  // 르르르 원본 프레임에서 그대로 뽑은 색
  var RRR = { k: '#181828', t: '#e8d0b0', p: '#f0c098', s: '#d0a878', w: '#f0f0f0', b: '#68a0f0', m: '#3068d0', n: '#203888', l: '#a8d0f0' };
  var MECH = { k: '#181828', f: '#e2b48e', fs: '#c99a74', p: '#eaa083', h: '#4a3628', c: '#4f7f9a', cd: '#3a6278', cl: '#78a3b8', v: '#34466b', vd: '#26344f', vl: '#4d6290', patch: '#c9c4b8' };
  var TRUCK = {
    cab: '#c9c4b8', cabL: '#d6d2c7', cabD: '#aaa59a', cabDD: '#8c8880', front: '#bdb7ab',
    seal: '#2a2c34', stripe: '#4f7f9a', stripeL: '#6d98ae', stripeD: '#3d6378',
    grille: '#34363f', slat: '#4a4c56', lampHouse: '#3a3c45', lampGlass: '#a7b2b6', lampHi: '#c3cbcd',
    amber: '#b98b4a', amberD: '#8d6a3a', bumper: '#4a4c55', bumperL: '#62656f', plate: '#7d8088', tire: '#1f2027',
    mirror: '#2f313a', mirrorGlass: '#6f8790', mirrorGlassL: '#8aa0a8', wiper: '#22242b',
    wall: '#3d3f4c', liner: '#555869', rearSky: '#7f98a3', seat: '#5a5462', seatD: '#47414f', seatK: '#2e2a36', visor: '#6a6c78',
    dash: '#2e3039', dashL: '#454857', hood: '#24262e', wheel: '#2d2f3a', wheelL: '#4a4d5c',
    toy: '#c98a5a', toyD: '#a26c45', toyGlass: '#a8c2cc', spring: '#8a8d96'
  };
  var BG = {
    day: { sky: ['#8ea7b1', '#98afb6', '#a3b7b9', '#adbfbc', '#b6c4bd'], cloud: '#bccbc6', hill: '#7f988f', hill2: '#6d877c', tree: '#56705f', treeD: '#4a6353',
      grass: ['#6c8676', '#64806f', '#5b7767'], road: ['#62656a', '#5a5d63', '#52555b', '#4a4d53', '#43464c', '#3d4045'], edge: '#7f8280', shadow: '#36383d' },
    night: { sky: ['#0f1320', '#121827', '#161d2e', '#1a2235', '#1e273b'], cloud: null, hill: '#1c2433', hill2: '#18202d', tree: '#141b27', treeD: '#111722',
      grass: ['#1b2430', '#19212c', '#161e28'], road: ['#262a34', '#232731', '#20232c', '#1d2028', '#1a1d24', '#181a20'], edge: '#3c414d', shadow: '#121318', star: '#66708c' }
  };
  var TEAL = [63, 183, 176];

  function mk(w, h) { var c = document.createElement('canvas'); c.width = w; c.height = h; var x = c.getContext('2d'); x.imageSmoothingEnabled = false; return { c: c, x: x }; }
  function R(x, col, X, Y, w, h) { if (w <= 0 || h <= 0) return; x.fillStyle = col; x.fillRect(X, Y, w, h); }
  function P(x, col, X, Y) { x.fillStyle = col; x.fillRect(X, Y, 1, 1); }
  function dith(x, col, X, Y, w, h, ph, density) {
    x.fillStyle = col;
    for (var j = 0; j < h; j++) for (var i = 0; i < w; i++) {
      var a = X + i, b = Y + j;
      var on = density === 4 ? ((a + 2 * b + (ph || 0)) % 4 === 0) : ((a + b + (ph || 0)) % 2 === 0);
      if (on) x.fillRect(a, b, 1, 1);
    }
  }
  function map(x, rows, pal, X, Y) {
    for (var j = 0; j < rows.length; j++) for (var i = 0; i < rows[j].length; i++) {
      var ch = rows[j][i]; if (ch !== '.' && pal[ch]) P(x, pal[ch], X + i, Y + j);
    }
  }
  function blobCells(cx, cy, rx, ry) {
    var s = {};
    for (var y = Math.floor(cy - ry) - 1; y <= Math.ceil(cy + ry) + 1; y++)
      for (var X = Math.floor(cx - rx) - 1; X <= Math.ceil(cx + rx) + 1; X++) {
        var dx = (X + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry;
        if (dx * dx + dy * dy <= 1) s[X + ',' + y] = 1;
      }
    return s;
  }
  function blob(x, cx, cy, rx, ry, fill, outline, shadeFn) {
    var s = blobCells(cx, cy, rx, ry);
    Object.keys(s).forEach(function (k) { var p = k.split(','); var X = +p[0], Y = +p[1]; P(x, (shadeFn && shadeFn(X, Y)) || fill, X, Y); });
    if (outline) Object.keys(s).forEach(function (k) {
      var p = k.split(','); var X = +p[0], Y = +p[1];
      [[1, 0], [-1, 0], [0, 1], [0, -1]].forEach(function (d) { var kk = (X + d[0]) + ',' + (Y + d[1]); if (!s[kk]) P(x, outline, X + d[0], Y + d[1]); });
    });
    return s;
  }
  function line(x, col, x0, y0, x1, y1) {
    var dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, e = dx + dy;
    for (;;) { P(x, col, x0, y0); if (x0 === x1 && y0 === y1) break; var e2 = 2 * e; if (e2 >= dy) { e += dy; x0 += sx; } if (e2 <= dx) { e += dx; y0 += sy; } }
  }
  function hex(h) { return [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)]; }
  // 단계 색 보정: 낮은 살짝 어둡게, 밤은 청색 저조도
  function grade(c, night) {
    var x = c.getContext('2d'), im = x.getImageData(0, 0, c.width, c.height), d = im.data;
    for (var i = 0; i < d.length; i += 4) {
      if (!d[i + 3]) continue;
      if (night) { d[i] = d[i] * 0.34 + 6; d[i + 1] = d[i + 1] * 0.38 + 10; d[i + 2] = d[i + 2] * 0.48 + 22; }
      else { d[i] *= 0.9; d[i + 1] *= 0.9; d[i + 2] *= 0.91; }
    }
    x.putImageData(im, 0, 0);
  }
  // 밤: 계기판 청록 불빛이 아래에서 비춤 (디더 2단)
  function glow(c, y0, y1, x0, x1) {
    var x = c.getContext('2d'), im = x.getImageData(0, 0, c.width, c.height), d = im.data, W = c.width;
    for (var y = y0; y < y1; y++) for (var X = x0; X < x1; X++) {
      var i = (y * W + X) * 4; if (!d[i + 3]) continue;
      var lum = d[i] + d[i + 1] + d[i + 2]; if (lum < 150) continue;
      var near = (y - y0) / Math.max(1, y1 - y0);
      var a = ((X + y) % 2 === 0 ? 0.42 : 0.2) * (0.45 + 0.55 * near);
      d[i] = d[i] * (1 - a) + TEAL[0] * a; d[i + 1] = d[i + 1] * (1 - a) + TEAL[1] * a; d[i + 2] = d[i + 2] * (1 - a) + TEAL[2] * a;
    }
    x.putImageData(im, 0, 0);
  }

  var CW = 156;
  function geom(layout) {
    var L = LAYOUT[layout] || LAYOUT.wide, tx = Math.floor((L.Wu - CW) / 2), ty = L.tyFn();
    var ws = { x: tx + 18, y: ty + 13, w: 120, h: 50 };
    var hy = ty + 44;
    return { L: L, tx: tx, ty: ty, hy: hy, G: L.Hu - hy, vpx: tx + CW / 2, ws: ws, cx: tx + 107, headY: ty + 26, dashY: ty + 56, toyX: tx + 36, paxX: tx + 48 };
  }

  /* ---------- 배경 ---------- */
  function drawBg(x, g, night) {
    var B = night ? BG.night : BG.day, W = g.L.Wu, H = g.L.Hu, hy = g.hy;
    var bandH = Math.ceil(hy / B.sky.length);
    for (var i = 0; i < B.sky.length; i++) {
      R(x, B.sky[i], 0, i * bandH, W, bandH);
      if (i > 0) dith(x, B.sky[i], 0, i * bandH - 2, W, 2, 0, 2), dith(x, B.sky[i - 1], 0, i * bandH, W, 1, 1, 4);
    }
    if (night) {
      var seed = 7; for (var s = 0; s < 26; s++) { seed = (seed * 9301 + 49297) % 233280; var sx = seed % W; seed = (seed * 9301 + 49297) % 233280; var sy = seed % Math.max(1, hy - 18); P(x, B.star, sx, sy); }
    } else {
      [[0.12, 0.18, 9], [0.8, 0.12, 12], [0.55, 0.3, 7]].forEach(function (cl) {
        var cxp = Math.round(cl[0] * W), cyp = Math.round(cl[1] * hy);
        blob(x, cxp, cyp, cl[2], 2.2, B.cloud); blob(x, cxp + 3, cyp - 2, cl[2] * 0.5, 2, B.cloud);
      });
    }
    for (var X = 0; X < W; X++) {
      var h1 = Math.round(7 + 3 * Math.sin(X * 0.06) + 2 * Math.sin(X * 0.17 + 1.3));
      R(x, B.hill, X, hy - h1, 1, h1);
      var h2 = Math.round(3 + 2 * Math.sin(X * 0.11 + 2.1) + 1.5 * Math.sin(X * 0.31));
      R(x, B.hill2, X, hy - h2, 1, h2);
    }
    var gr = B.grass, gh = H - hy;
    for (var y = hy; y < H; y++) R(x, gr[Math.min(gr.length - 1, Math.floor((y - hy) / gh * gr.length))], 0, y, W, 1);
    for (var t = 0; t < 14; t++) {
      var side = t % 2 ? 1 : -1, k = Math.floor(t / 2);
      var px = side < 0 ? 4 + k * 5 + (k % 2) * 2 : W - 5 - k * 5 - (k % 2) * 2;
      var rh = 3 + (k * 7) % 3;
      blob(x, px, hy - rh + 1, 2.4 + (k % 2), rh, B.tree, null, function (X0, Y0) { return X0 > px ? B.treeD : null; });
    }
    var road = B.road, vp = g.vpx;
    for (var y2 = hy + 1; y2 < H; y2++) {
      var sc = y2 - hy, col = road[Math.min(road.length - 1, Math.floor(sc / gh * road.length))];
      var xl = Math.round(vp + ROAD.left * sc), xr = Math.round(vp + ROAD.right * sc);
      R(x, col, xl, y2, xr - xl, 1);
      var ew = Math.max(1, Math.round(ROAD.line * sc));
      R(x, B.edge, Math.round(vp + ROAD.edgeL * sc), y2, ew, 1); R(x, B.edge, Math.round(vp + ROAD.edgeR * sc) - ew, y2, ew, 1);
    }
    if (night) {
      for (var yh = g.ty + 112; yh < H; yh++) {
        var f = (yh - g.ty - 112) / Math.max(1, H - g.ty - 112), sp = 0.5 + f * 0.6;
        var hl = Math.round(g.vpx - CW * sp / 2), hw = Math.round(CW * sp);
        dith(x, '#2e2d2c', hl, yh, hw, 1, yh % 2, f < 0.5 ? 2 : 4);
      }
    }
    dith(x, B.shadow, g.tx - 2, g.ty + 114, CW + 4, 2, 0, 2);
    R(x, B.shadow, g.tx + 2, g.ty + 116, CW - 4, 5);
    dith(x, B.shadow, g.tx - 2, g.ty + 121, CW + 4, 2, 1, 2);
  }

  /* ---------- 움직이는 풍경 (원근 투영: 화면 y = 지평선 + G/Z, x = 소실점 + X·G/Z) ---------- */
  var ROAD = { left: -4.8, right: 2.6, edgeL: -4.5, edgeR: 2.3, lane: -1.5, line: 0.1, rail: 3.1, railH: 0.55 };
  var SCN = { v: 6, zMin: 0.5, span: 72, dash: 4, dashLen: 1.6, tree: 6, post: 3, pole: 24, speck: 1.5 };
  var LEVELS = [3, 4, 5, 6, 7, 8, 10, 12, 14, 17, 20, 24, 29, 35, 42, 50, 60, 72, 86, 104, 125];
  function drawRail(x, g, night) {
    var col = night ? '#2c313c' : '#9a9d98', hi = night ? '#373d4a' : '#b4b6b0';
    for (var sc = 1; sc < g.G * 1.2; sc += 0.2) {
      var X = Math.round(g.vpx + ROAD.rail * sc), Y = Math.round(g.hy + (1 - ROAD.railH) * sc), th = Math.max(1, Math.round(0.1 * sc));
      R(x, col, X, Y, Math.max(1, Math.round(0.6 * sc * 0.2)) + 1, th); P(x, hi, X, Y);
    }
  }
  function treeSprite(H, kind, night, far) {
    var P2 = night ? { a: '#141b27', b: '#10161f', c: '#1a2231', t: '#0e1219' } : { a: '#56705f', b: '#4a6353', c: '#66806d', t: '#4d463f' };
    if (far) P2 = night ? { a: '#18202d', b: '#161d29', c: '#1a2331', t: '#161d29' } : { a: '#6f8a7c', b: '#67826f', c: '#78917f', t: '#67826f' };
    var w = Math.max(3, Math.round(H * (kind ? 0.55 : 0.72))) | 1, s = mk(w, H), x = s.x, cxp = w / 2;
    var tw = Math.max(1, Math.round(H * 0.07)), th = Math.round(H * (kind ? 0.2 : 0.3));
    R(x, P2.t, Math.round(cxp - tw / 2), H - th, tw, th);
    if (kind) {
      for (var y = 0; y < H - th + 1; y++) {
        var f = y / (H - th), tier = (f * 3) % 1, hw = Math.max(0.6, (0.12 + 0.88 * f) * (0.55 + 0.45 * tier) * w / 2);
        var l = Math.round(cxp - hw), r = Math.round(cxp + hw);
        R(x, P2.a, l, y, r - l, 1); R(x, P2.b, Math.round(cxp + hw * 0.25), y, r - Math.round(cxp + hw * 0.25), 1);
        if (H > 14) dith(x, P2.c, l, y, Math.round(hw * 0.6), 1, 0, 4);
      }
    } else {
      var ry = (H - th) * 0.55, rx = w / 2 - 0.2, cy = ry;
      blob(x, cxp, cy, rx, ry, P2.a, null, function (X, Y) { return (X > cxp + rx * 0.2 && Y > cy - ry * 0.3) ? P2.b : (X < cxp - rx * 0.1 && Y < cy - ry * 0.2 && (X + Y) % 2 === 0 && H > 10 ? P2.c : null); });
      if (H > 20) { blob(x, cxp - rx * 0.45, cy + ry * 0.35, rx * 0.45, ry * 0.4, P2.a, null, function (X, Y) { return (X + Y) % 2 ? P2.b : null; }); }
    }
    return s.c;
  }
  function poleSprite(H, night) {
    var w = Math.max(3, Math.round(H * 0.32)), s = mk(w, H), x = s.x, pw = Math.max(1, Math.round(H * 0.025));
    var col = night ? '#262b36' : '#7c807f', lamp = night ? '#b79d63' : '#a9adaa';
    R(x, col, w - pw, 0, pw, H);
    R(x, col, 0, 0, w, Math.max(1, Math.round(H * 0.02)));
    R(x, lamp, 0, Math.max(1, Math.round(H * 0.02)), Math.max(1, Math.round(H * 0.08)), Math.max(1, Math.round(H * 0.025)));
    return s.c;
  }
  function levelFor(h) { var i = 0; while (i < LEVELS.length - 1 && LEVELS[i + 1] <= h) i++; return i; }
  function wrapZ(base, t) { return SCN.zMin + (((base + SCN.v * t - SCN.zMin) % SCN.span) + SCN.span) % SCN.span; }
  // t: 정규화된 반복 시간(0~LOOP). 화면 단위 좌표로 사각형·그림 목록을 돌려준다
  function scenery(t, g, night, still) {
    if (still) t = 0;
    var rects = [], sprites = [], G = g.G, hy = g.hy, vp = g.vpx, Hu = g.L.Hu;
    var dashCol = night ? '#3a3f4a' : '#8d8f8a', speckCol = night ? '#1a1c22' : '#4c4f55', postCol = night ? '#2a2e38' : '#8b8e8a';
    var i, Z, sc;
    for (i = 0; i * SCN.speck < SCN.span; i++) {
      Z = wrapZ(i * SCN.speck, t); sc = G / Z;
      var X = ROAD.left + 0.4 + ((i * 37) % 19) / 19 * (ROAD.right - ROAD.left - 0.8);
      var y = Math.round(hy + sc); if (y >= Hu || sc < 3) continue;
      rects.push([Math.round(vp + X * sc), y, Math.max(1, Math.round(0.2 * sc)), Math.max(1, Math.round(0.05 * sc)), speckCol]);
    }
    for (i = 0; i * SCN.dash < SCN.span; i++) {
      Z = wrapZ(i * SCN.dash, t);
      var y0 = Math.round(hy + G / (Z + SCN.dashLen)), y1 = Math.min(Hu, Math.round(hy + G / Z));
      for (var yy = y0; yy < y1; yy++) { var s2 = yy - hy + 0.5, lw = Math.max(1, Math.round(ROAD.line * s2)); rects.push([Math.round(vp + ROAD.lane * s2 - lw / 2), yy, lw, 1, dashCol]); }
    }
    for (i = 0; i * SCN.post < SCN.span; i++) {
      Z = wrapZ(i * SCN.post + 0.5, t); sc = G / Z;
      var px = Math.round(vp + ROAD.rail * sc), pyB = Math.round(hy + sc), pyT = Math.round(hy + (1 - ROAD.railH * 0.95) * sc);
      if (pyT >= Hu) continue;
      rects.push([px, pyT, Math.max(1, Math.round(0.07 * sc)), Math.max(1, pyB - pyT), postCol]);
    }
    var items = [];
    for (i = 0; i * SCN.tree < SCN.span; i++) {
      [[-6.6 - (i % 3) * 0.9, 1, 2.9 + (i % 2) * 0.8, i % 2], [4.6 + (i % 2) * 1.3, -1, 3.1 + ((i + 1) % 3) * 0.5, (i + 1) % 2]].forEach(function (d, k) {
        var z = wrapZ(i * SCN.tree + k * SCN.tree / 2, t); items.push({ z: z, X: d[0], h: d[2], kind: d[3], type: 'tree' });
      });
    }
    for (i = 0; i * SCN.pole < SCN.span; i++) items.push({ z: wrapZ(i * SCN.pole + 3, t), X: ROAD.rail + 0.5, h: 3.6, type: 'pole' });
    items.sort(function (a, b) { return b.z - a.z; });
    items.forEach(function (it) {
      var s3 = G / it.z, hpx = it.h * s3; if (hpx < 4) return;
      var lv = levelFor(hpx), H = LEVELS[lv], far = it.z > 40;
      var key = it.type === 'pole' ? 'p' + lv : 't' + it.kind + (far ? 'f' : '') + lv;
      var w = it.type === 'pole' ? Math.max(3, Math.round(H * 0.32)) : (Math.max(3, Math.round(H * (it.kind ? 0.55 : 0.72))) | 1);
      var xb = vp + it.X * s3, yb = Math.round(hy + s3);
      var left = it.type === 'pole' ? Math.round(xb - w) : Math.round(xb - w / 2);
      if (left > g.L.Wu || left + w < 0 || yb - H > Hu) return;
      sprites.push([key, left, yb - H, w, H]);
    });
    return { rects: rects, sprites: sprites };
  }

  /* ---------- 실내 (뒤벽·좌석) ---------- */
  function drawInterior(x, g) {
    var T = TRUCK, ws = g.ws;
    R(x, T.wall, ws.x, ws.y, ws.w, ws.h);
    R(x, T.liner, ws.x, ws.y, ws.w, 3);
    var rw = { x: ws.x + 22, y: ws.y + 8, w: 76, h: 13 };
    R(x, T.seatK, rw.x - 1, rw.y - 1, rw.w + 2, rw.h + 2);
    R(x, T.rearSky, rw.x, rw.y, rw.w, rw.h);
    dith(x, T.wall, rw.x, rw.y + rw.h - 3, rw.w, 3, 0, 4);
    R(x, T.seatK, rw.x + rw.w / 2 - 1, rw.y, 2, rw.h);
    [g.paxX, g.cx].forEach(function (sx) {
      var top = g.ty + 36, bw = 26, bx = sx - 13;
      R(x, T.seatK, bx + 2, top - 1, bw - 4, 1); R(x, T.seatK, bx + 1, top, 1, 1); R(x, T.seatK, bx + bw - 2, top, 1, 1);
      R(x, T.seatK, bx, top + 1, 1, 30); R(x, T.seatK, bx + bw - 1, top + 1, 1, 30);
      R(x, T.seat, bx + 2, top, bw - 4, 1); R(x, T.seat, bx + 1, top + 1, bw - 2, 30);
      R(x, T.seatD, bx + bw - 5, top + 1, 4, 30); R(x, T.seatD, bx + 8, top + 3, 1, 28); R(x, T.seatD, bx + 17, top + 3, 1, 28);
      R(x, T.seatK, sx - 4, g.ty + 31, 1, 5); R(x, T.seatK, sx + 4, g.ty + 31, 1, 5);
      R(x, T.seatK, sx - 7, g.ty + 24, 15, 1); R(x, T.seatK, sx - 8, g.ty + 25, 1, 6); R(x, T.seatK, sx + 8, g.ty + 25, 1, 6); R(x, T.seatK, sx - 7, g.ty + 31, 15, 1);
      R(x, T.seat, sx - 7, g.ty + 25, 15, 6); R(x, T.seatD, sx + 4, g.ty + 25, 4, 6);
    });
    R(x, T.visor, ws.x + 3, ws.y, 44, 4); R(x, T.seatK, ws.x + 3, ws.y + 4, 44, 1);
    R(x, T.visor, ws.x + 72, ws.y, 44, 1); R(x, T.seatK, ws.x + 72, ws.y + 1, 44, 1);
  }

  /* ---------- 르르르 (원본 프레임 그림체, 정면) ---------- */
  var HEAD_HALF = [
    '..........kkkk', '........kkwwww', '......kkwttttt', '.....kwttttttt', '....kttttttttt', '...ktttttttttt', '..kptttttttttt', '..kttttttttttt',
    '.kpttttttttttt', '.kpttttttttttt', '.kpttttttttttt', '.ktttttttttttt', '..kttttttttttt', '..kktttttttttt', '...kkttttttttt', '.....kkkkkkkkk'
  ];
  function headRows() {
    return HEAD_HALF.map(function (h, j) {
      var row = (h + h.slice(0, 13).split('').reverse().join('')).split('');
      if (j <= 2) for (var i = 16; i < 27; i++) if (row[i] === 'w') row[i] = 't';
      var rk = row.lastIndexOf('k');
      if (rk > 14 && row[rk - 1] === 't') row[rk - 1] = 's';
      if (j >= 7 && j <= 13 && row[rk - 2] === 't') row[rk - 2] = 's';
      return row.join('');
    });
  }
  var ANT = ['.kkk.', 'kwwtk', 'ktttk', 'ktttk', '.kkk.', '.kpk.', '.kpk.', '.kpk.', '.kpk.', '.kpk.', '.kpk.', '.kppk', '.kppk', '.kppk', '.kppk'];
  function drawAntenna(x, X, Y, lean) {
    ANT.forEach(function (r, j) { map(x, [r], RRR, X + (j <= 7 ? lean : 0), Y + j); });
  }
  function drawRrrBody(x, g, eyes) {
    var cx = g.cx, hy = g.headY, C = RRR, hx = cx - 13;
    blob(x, cx + 0.5, hy + 25, 13, 11, C.b, C.k, function (X, Y) { return X > cx + 8 ? C.m : (Y > hy + 30 ? C.m : null); });
    R(x, C.l, cx - 5, hy + 16, 11, 1); dith(x, C.m, cx - 5, hy + 17, 11, 1, 0, 2);
    map(x, ['w...w', 'wwwww', 'w...w'], C, cx - 2, hy + 18);
    blob(x, cx - 11.5, hy + 21, 3, 4, C.p, C.k, function (X) { return X === cx - 13 ? C.t : null; });
    blob(x, cx + 12.5, hy + 21, 3, 4, C.p, C.k, function (X) { return X >= cx + 13 ? C.s : null; });
    map(x, headRows(), C, hx, hy);
    var E = [[8, 6], [7, 7], [8, 7], [9, 7], [8, 8], [18, 6], [17, 7], [18, 7], [19, 7], [18, 8]];
    if (eyes === 'closed') [[7, 7], [8, 7], [9, 7], [17, 7], [18, 7], [19, 7]].forEach(function (e) { P(x, C.k, hx + e[0], hy + e[1]); });
    else E.forEach(function (e) { P(x, C.k, hx + e[0], hy + e[1]); });
    [[11, 10], [13, 10], [15, 10], [12, 11], [14, 11]].forEach(function (e) { P(x, C.k, hx + e[0], hy + e[1]); });
    R(x, C.p, hx + 3, hy + 10, 3, 1); R(x, C.p, hx + 21, hy + 10, 3, 1);
  }
  /* ---------- 정비사 (대체 주인공) ---------- */
  function drawMechBody(x, g, eyes) {
    var cx = g.cx, hy = g.headY, C = MECH;
    blob(x, cx + 0.5, hy + 25, 13, 11, C.v, C.k, function (X, Y) { return X > cx + 8 ? C.vd : null; });
    R(x, C.vl, cx - 5, hy + 15, 4, 3); R(x, C.vl, cx + 2, hy + 15, 4, 3); R(x, C.vd, cx - 1, hy + 15, 3, 6);
    R(x, C.patch, cx + 5, hy + 19, 4, 2);
    blob(x, cx - 11.5, hy + 21, 3, 4, C.v, C.k); blob(x, cx + 12.5, hy + 21, 3, 4, C.vd, C.k);
    blob(x, cx + 0.5, hy + 8.5, 10, 8.5, C.f, C.k, function (X) { return X > cx + 7 ? C.fs : null; });
    blob(x, cx - 9.5, hy + 9, 2, 2.5, C.f, C.k); blob(x, cx + 10.5, hy + 9, 2, 2.5, C.fs, C.k);
    R(x, C.h, cx - 9, hy + 3, 3, 5); R(x, C.h, cx + 7, hy + 3, 3, 5);
    blob(x, cx + 0.5, hy + 2, 10.5, 5.5, C.c, C.k, function (X, Y) { return Y < hy - 1 ? C.cl : (X > cx + 6 ? C.cd : null); });
    R(x, C.cd, cx - 9, hy + 4, 20, 2); R(x, C.k, cx - 9, hy + 6, 20, 1);
    R(x, C.patch, cx - 1, hy - 1, 3, 2);
    if (eyes === 'closed') { R(x, C.k, cx - 5, hy + 10, 3, 1); R(x, C.k, cx + 3, hy + 10, 3, 1); }
    else { R(x, C.k, cx - 4, hy + 9, 1, 2); R(x, C.k, cx + 4, hy + 9, 1, 2); }
    R(x, C.h, cx - 5, hy + 8, 3, 1); R(x, C.h, cx + 3, hy + 8, 3, 1);
    R(x, C.k, cx - 1, hy + 13, 3, 1); P(x, C.k, cx - 2, hy + 12); P(x, C.k, cx + 2, hy + 12);
    R(x, C.p, cx - 7, hy + 12, 2, 1); R(x, C.p, cx + 6, hy + 12, 2, 1);
  }
  function drawTuft(x, g, lean) {
    var cx = g.cx, hy = g.headY;
    map(x, ['..hh', '.hhk', 'hhk.'], { h: MECH.h, k: MECH.k }, cx + 8 + lean, hy + 1);
  }

  /* ---------- 운전대 + 두 손 ---------- */
  function drawWheel(x, g, driver) {
    var cx = g.cx, wy = g.headY + 26, T = TRUCK;
    R(x, T.wheel, cx - 2, wy, 5, 8);
    var outer = blobCells(cx + 0.5, wy, 15, 5), inner = blobCells(cx + 0.5, wy, 12, 3);
    Object.keys(outer).forEach(function (k) {
      if (inner[k]) return; var p = k.split(','); var X = +p[0], Y = +p[1];
      P(x, Y < wy - 2 ? T.wheelL : T.wheel, X, Y);
    });
    R(x, T.wheel, cx - 12, wy, 25, 1);
    var handFill = driver === 'mechanic' ? MECH.f : RRR.t, handShade = driver === 'mechanic' ? MECH.fs : RRR.s;
    blob(x, cx - 10.5, wy - 3, 3, 2.6, handFill, RRR.k);
    blob(x, cx + 11.5, wy - 3, 3, 2.6, handFill, RRR.k, function (X) { return X >= cx + 13 ? handShade : null; });
    P(x, RRR.k, cx - 11, wy - 3); P(x, RRR.k, cx + 12, wy - 3);
  }

  /* ---------- 대시보드 ---------- */
  function drawDash(x, g) {
    var T = TRUCK, ws = g.ws, dy = g.dashY;
    blob(x, g.cx + 0.5, dy + 1, 12, 3.5, T.hood, null);
    R(x, T.dashL, g.cx - 8, dy - 2, 18, 1);
    R(x, T.dashL, ws.x, dy, ws.w, 1);
    R(x, T.dash, ws.x, dy + 1, ws.w, ws.y + ws.h - dy - 1);
    dith(x, T.hood, ws.x, ws.y + ws.h - 3, ws.w, 3, 0, 2);
  }
  var TOY = ['..kkkkkk..', '.kgggggok.', 'kooooooook', 'koyoooyook', 'kkkkkkkkkk', '.kk....kk.'];
  function drawToy(x, g, dx, dyy) {
    var T = TRUCK, X = g.toyX, Y = g.dashY;
    map(x, ['.s.', 's.s', '.s.'], { s: T.spring }, X + 3 + Math.round(dx / 2), Y - 3);
    map(x, TOY, { k: RRR.k, o: T.toy, g: T.toyGlass, y: T.toyD }, X + dx, Y - 9 + dyy);
  }

  /* ---------- 트럭 몸체 (앞유리 자리는 비움) ---------- */
  function drawCab(x, g, c) {
    var T = TRUCK, tx = g.tx, ty = g.ty, ws = g.ws, E = CW - 4;
    R(x, T.cab, tx + 4, ty + 8, E - 4, 64);
    R(x, T.cab, tx + 6, ty + 6, E - 8, 2); R(x, T.cab, tx + 9, ty + 5, E - 14, 1);
    R(x, T.cabL, tx + 9, ty + 5, E - 14, 1); R(x, T.cabL, tx + 6, ty + 6, 3, 2); R(x, T.cabL, tx + 4, ty + 8, 3, 64);
    R(x, T.cabD, tx + E - 3, ty + 8, 3, 64); dith(x, T.cabD, tx + E - 6, ty + 8, 3, 64, 0, 2);
    x.clearRect(ws.x, ws.y, ws.w, ws.h);
    [[0, 0], [ws.w - 1, 0], [0, ws.h - 1], [ws.w - 1, ws.h - 1]].forEach(function (p) { P(x, T.cab, ws.x + p[0], ws.y + p[1]); });
    R(x, T.cab, ws.x, ws.y, 3, 1); R(x, T.cab, ws.x, ws.y, 1, 3); R(x, T.cab, ws.x + ws.w - 3, ws.y, 3, 1); R(x, T.cab, ws.x + ws.w - 1, ws.y, 1, 3);
    R(x, T.seal, ws.x + 3, ws.y - 1, ws.w - 6, 1); R(x, T.seal, ws.x - 1, ws.y + 3, 1, ws.h - 3); R(x, T.seal, ws.x + ws.w, ws.y + 3, 1, ws.h - 3);
    P(x, T.seal, ws.x + 1, ws.y + 1); P(x, T.seal, ws.x + 2, ws.y); P(x, T.seal, ws.x, ws.y + 2);
    P(x, T.seal, ws.x + ws.w - 2, ws.y + 1); P(x, T.seal, ws.x + ws.w - 3, ws.y); P(x, T.seal, ws.x + ws.w - 1, ws.y + 2);
    R(x, T.seal, ws.x - 1, ws.y + ws.h, ws.w + 2, 3);
    var mid = tx + CW / 2;
    R(x, T.lampHouse, mid - 18, ty + 2, 36, 4); R(x, T.amberD, mid - 16, ty, 32, 3);
    R(x, T.amber, mid - 15, ty, 14, 2); R(x, T.amber, mid + 1, ty, 14, 2); R(x, T.lampHouse, mid - 1, ty, 2, 3);
    R(x, T.cabD, tx + 24, ty + 9, 3, 2); R(x, T.cabD, tx + CW - 27, ty + 9, 3, 2);
    line(x, T.wiper, ws.x + 14, ws.y + ws.h - 2, ws.x + 64, ws.y + ws.h - 5);
    line(x, T.wiper, ws.x + 14, ws.y + ws.h - 1, ws.x + 30, ws.y + ws.h - 2);
    line(x, T.wiper, ws.x + 76, ws.y + ws.h - 2, ws.x + 126, ws.y + ws.h - 5);
    line(x, T.wiper, ws.x + 76, ws.y + ws.h - 1, ws.x + 92, ws.y + ws.h - 2);
    R(x, T.stripeL, tx + 4, ty + 66, E - 4, 1); R(x, T.stripe, tx + 4, ty + 67, E - 4, 5); R(x, T.stripeD, tx + 4, ty + 72, E - 4, 1);
    R(x, T.front, tx + 6, ty + 73, E - 8, 27); R(x, T.cabD, tx + E - 6, ty + 73, 4, 27); R(x, T.cabL, tx + 6, ty + 73, 2, 27);
    R(x, T.seal, mid - 30, ty + 76, 60, 21); R(x, T.grille, mid - 29, ty + 77, 58, 19);
    for (var s = 0; s < 6; s++) R(x, T.slat, mid - 27, ty + 79 + s * 3, 54, 1);
    R(x, T.cabD, mid - 30, ty + 97, 60, 1);
    [tx + 10, tx + CW - 38].forEach(function (lx, i) {
      R(x, T.seal, lx, ty + 77, 28, 13); R(x, T.lampHouse, lx + 1, ty + 78, 26, 11);
      blob(x, lx + (i ? 18 : 10), ty + 83.5, 6, 4.2, c.lamp, null, function (X, Y) { return Y < ty + 82 ? c.lampHi : null; });
      R(x, c.sig, lx + (i ? 3 : 20), ty + 81, 4, 5);
      R(x, T.cabD, lx, ty + 90, 28, 1);
    });
    R(x, T.bumperL, tx + 2, ty + 99, CW - 4, 2); R(x, T.bumper, tx + 2, ty + 101, CW - 4, 8); R(x, T.seal, tx + 2, ty + 109, CW - 4, 1);
    R(x, T.plate, mid - 12, ty + 102, 24, 5); R(x, T.seal, mid - 12, ty + 107, 24, 1);
    R(x, T.tire, tx + 12, ty + 110, 26, 8); R(x, T.tire, tx + CW - 38, ty + 110, 26, 8);
    dith(x, T.bumper, tx + 12, ty + 110, 26, 1, 0, 2); dith(x, T.bumper, tx + CW - 38, ty + 110, 26, 1, 0, 2);
    [[tx - 7, tx - 1], [tx + CW - 2, tx + CW - 5]].forEach(function (m) {
      var hx = m[0];
      R(x, T.mirror, m[1], ty + 20, 6, 2); R(x, T.mirror, m[1], ty + 32, 6, 2);
      R(x, T.mirror, hx, ty + 14, 9, 24); R(x, T.mirrorGlass, hx + 1, ty + 15, 7, 22);
      dith(x, T.mirrorGlassL, hx + 1, ty + 15, 7, 8, 0, 2); R(x, T.mirrorGlassL, hx + 1, ty + 15, 7, 2);
    });
  }

  /* ---------- 앞유리 반사 (주기 PR 칸, 위로 흐름) ---------- */
  var PR = 24;
  function drawReflection(x, w, h) {
    for (var y = 0; y < h; y++) {
      var r = ((y % PR) + PR) % PR;
      for (var X = 0; X < w; X++) {
        var tree = 10 + Math.round(1.5 * Math.sin(X * 0.45) + Math.sin(X * 0.17 + 2));
        if (r < tree && r >= 1 && (X + 2 * y) % 4 === 0) P(x, '#dfe7ea', X, y);
      }
    }
  }
  function drawGlare(x, w, h) {
    for (var y = 0; y < h; y++) for (var X = 0; X < w; X++) {
      var d = X + y * 0.9;
      if ((d > 14 && d < 21) || (d > 25 && d < 27)) { if ((X + y) % 2 === 0) P(x, '#e6edef', X, y); }
    }
  }

  var cache = {};
  function build(opts) {
    opts = norm(opts);
    var key = [opts.timeOfDay, opts.driver, opts.layout].join('|');
    if (cache[key]) return cache[key];
    var g = geom(opts.layout), L = g.L, night = opts.timeOfDay === 'night';
    function layer(fn, after) {
      var s = mk(L.Wu, L.Hu); fn(s.x); if (after !== 'nograde') grade(s.c, night); if (typeof after === 'function') after(s.x, s.c); return s.c;
    }
    function up(c) { var b = mk(c.width * L.unit, c.height * L.unit); b.x.drawImage(c, 0, 0, b.c.width, b.c.height); return b.c.toDataURL('image/png'); }
    var isM = opts.driver === 'mechanic';
    var glowFace = function (_x, c) { if (night) glow(c, g.headY + 7, g.headY + 20, g.cx - 16, g.cx + 18); };
    var glowHands = function (_x, c) { if (night) glow(c, g.headY + 19, g.headY + 28, g.cx - 16, g.cx + 18); };
    var out = {
      key: key, g: g, unit: L.unit, Wu: L.Wu, Hu: L.Hu, W: L.Wu * L.unit, H: L.Hu * L.unit, PR: PR,
      bg: up(layer(function (x) { drawBg(x, g, night); }, 'nograde')),
      rail: up(layer(function (x) { drawRail(x, g, night); }, 'nograde')),
      sprites: (function () {
        var o = {};
        LEVELS.forEach(function (H, lv) {
          [0, 1].forEach(function (k) { o['t' + k + lv] = up(treeSprite(H, k, night, false)); o['t' + k + 'f' + lv] = up(treeSprite(H, k, night, true)); });
          o['p' + lv] = up(poleSprite(H, night));
        });
        return o;
      })(),
      interior: up(layer(function (x) { drawInterior(x, g); })),
      bodyOpen: up(layer(function (x) { (isM ? drawMechBody : drawRrrBody)(x, g, 'open'); }, glowFace)),
      bodyClosed: up(layer(function (x) { (isM ? drawMechBody : drawRrrBody)(x, g, 'closed'); }, glowFace)),
      earA: [-1, 0, 1].map(function (l) { return up(layer(function (x) { if (isM) drawTuft(x, g, l); else drawAntenna(x, g.cx - 13 + 5, g.headY - 11, l); })); }),
      earB: [-1, 0, 1].map(function (l) { return up(layer(function (x) { if (!isM) drawAntenna(x, g.cx - 13 + 17, g.headY - 11, l); })); }),
      wheel: up(layer(function (x) { drawWheel(x, g, opts.driver); }, glowHands)),
      dash: up(layer(function (x) { drawDash(x, g); }, function (x) { if (night) { R(x, 'rgb(48,130,128)', g.cx - 8, g.dashY - 2, 18, 1); dith(x, 'rgb(40,100,104)', g.cx - 10, g.dashY - 3, 22, 1, 1, 4); } })),
      toy: [[-1, 0], [0, 0], [1, 0], [0, -1]].map(function (o) { return up(layer(function (x) { drawToy(x, g, o[0], o[1]); })); }),
      cab: up(layer(function (x) { drawCab(x, g, { lamp: TRUCK.lampGlass, lampHi: TRUCK.lampHi, sig: TRUCK.amberD }); }, function (x) {
        if (!night) return;
        [g.tx + 10, g.tx + CW - 38].forEach(function (lx, i) {
          var cxl = lx + (i ? 18 : 10);
          blob(x, cxl, g.ty + 83.5, 6, 4.2, '#8f7f58', null, function (X, Y) { var d = Math.abs(X + 0.5 - cxl) / 6 + Math.abs(Y + 0.5 - (g.ty + 83.5)) / 4.2; return d < 0.7 ? '#b3a06e' : null; });
        });
      })),
      refl: (function () { var s = mk(g.ws.w, g.ws.h + PR); drawReflection(s.x, g.ws.w, g.ws.h + PR); return up(s.c); })(),
      glare: (function () { var s = mk(g.ws.w, g.ws.h); drawGlare(s.x, g.ws.w, g.ws.h); return up(s.c); })(),
      reflOpacity: night ? 0.1 : 0.18, glareOpacity: night ? 0.06 : 0.12,
      caption: night ? { color: '#7e8696' } : { color: '#b4b8bb' }
    };
    cache[key] = out; return out;
  }
  function norm(o) {
    o = o || {};
    return { timeOfDay: o.timeOfDay === 'night' ? 'night' : 'day', driver: o.driver === 'mechanic' ? 'mechanic' : 'rrr', layout: o.layout === 'narrow' ? 'narrow' : 'wide', motion: o.motion || 'full' };
  }

  /* ---------- 타이밍 (반복 12초, 한 번에 하나만) ---------- */
  var TIMING = {
    bobCycles: 4, driverLag: 0.35,
    blinks: [1.0, 5.0, 9.0], blinkDur: 0.14,
    ears: { start: 2.2, steps: [[0, 0.45, 1], [0.45, 0.8, 0], [0.8, 1.15, 1], [1.15, 1.6, 0]], lagB: 0.15 },
    toy: { start: 6.2, steps: [[0, 0.3, 2], [0.3, 0.55, 1], [0.55, 0.85, 0], [0.85, 1.1, 1], [1.1, 1.35, 2], [1.35, 1.6, 3], [1.6, 1.9, 1]] }
  };
  function stepAt(steps, local, dflt) { for (var i = 0; i < steps.length; i++) if (local >= steps[i][0] && local < steps[i][1]) return steps[i][2]; return dflt; }
  function pose(t, total, opts) {
    opts = norm(opts); total = total || LOOP;
    var k = total / LOOP, m = opts.motion, still = m === 'still' || m === 'blinkOnly';
    var u = ((t % total) + total) % total;
    var ph = 2 * Math.PI * TIMING.bobCycles * u / total;
    var bob = still ? 0 : (Math.sin(ph) > 0 ? 1 : 0);
    var dBob = still ? 0 : (Math.sin(ph - 2 * Math.PI * TIMING.bobCycles * TIMING.driverLag / total) > 0 ? 1 : 0);
    var blink = m === 'still' ? false : TIMING.blinks.some(function (b) { return u >= b * k && u < b * k + TIMING.blinkDur; });
    var el = (u - TIMING.ears.start * k) / k;
    var earA = still ? 0 : stepAt(TIMING.ears.steps, el, 0), earB = still ? 0 : stepAt(TIMING.ears.steps, el - TIMING.ears.lagB, 0);
    var toy = still ? 1 : stepAt(TIMING.toy.steps, (u - TIMING.toy.start * k) / k, 1);
    var refl = still ? 0 : Math.floor(PR * u / total) % PR;
    return { st: still ? 0 : u / k, bob: bob, driverBob: dBob, blink: blink, earA: earA + 1, earB: earB + 1, toy: toy, refl: refl };
  }

  /* ---------- 캔버스 한 장에 그리기 (시제품용) ---------- */
  var imgCache = {};
  function img(src) { if (!imgCache[src]) { var i = new Image(); i.src = src; imgCache[src] = i; } return imgCache[src]; }
  function draw(ctx, t, opts) {
    var b = build(opts), p = pose(t, LOOP, opts), u = b.unit, g = b.g;
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(img(b.bg), 0, 0);
    var sn = scenery(p.st, g, opts && opts.timeOfDay === 'night', false);
    sn.rects.forEach(function (r) { ctx.fillStyle = r[4]; ctx.fillRect(r[0] * u, r[1] * u, r[2] * u, r[3] * u); });
    sn.sprites.forEach(function (sp) { ctx.drawImage(img(b.sprites[sp[0]]), sp[1] * u, sp[2] * u); });
    ctx.drawImage(img(b.rail), 0, 0);
    var y = p.bob * u, dy = (p.bob + p.driverBob) * u / 1;
    ctx.drawImage(img(b.interior), 0, y);
    ctx.drawImage(img(b.earA[p.earA]), 0, y + p.driverBob * u);
    ctx.drawImage(img(b.earB[p.earB]), 0, y + p.driverBob * u);
    ctx.drawImage(img(p.blink ? b.bodyClosed : b.bodyOpen), 0, y + p.driverBob * u);
    ctx.drawImage(img(b.wheel), 0, y);
    ctx.drawImage(img(b.dash), 0, y);
    ctx.drawImage(img(b.toy[p.toy]), 0, y);
    ctx.save(); ctx.beginPath(); ctx.rect(g.ws.x * u, g.ws.y * u + y, g.ws.w * u, g.ws.h * u); ctx.clip();
    ctx.globalAlpha = b.reflOpacity; ctx.drawImage(img(b.refl), g.ws.x * u, g.ws.y * u + y - p.refl * u);
    ctx.globalAlpha = b.glareOpacity; ctx.drawImage(img(b.glare), g.ws.x * u, g.ws.y * u + y);
    ctx.restore();
    ctx.drawImage(img(b.cab), 0, y);
  }

  window.DrivingScene = { build: build, pose: pose, draw: draw, scenery: scenery, TIMING: TIMING, LOOP: LOOP, PALETTE: { RRR: RRR, MECH: MECH, TRUCK: TRUCK, BG: BG, TEAL: TEAL } };
})();
