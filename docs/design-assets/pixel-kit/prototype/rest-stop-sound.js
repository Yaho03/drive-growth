// 휴게소 연출 — 소리 (Web Audio로 합성, 파일 없음). 타임라인 T에 맞춰 울린다.
(function () {
'use strict';
const LOOP = 10, INTRO = 6, TOTAL = 16;
let ctx = null, master, rainBus, rainLP, rainHP, rainG, humG, roofG, jazzBus, enabled = false, lastT = null, nodesReady = false;

function noiseBuffer(sec, brown){
  const n = Math.floor(ctx.sampleRate*sec), b = ctx.createBuffer(2, n, ctx.sampleRate);
  for (let ch = 0; ch < 2; ch++){ const d = b.getChannelData(ch); let last = 0;
    for (let i = 0; i < n; i++){ const w = Math.random()*2 - 1; if (brown){ last = (last + 0.02*w)/1.02; d[i] = last*3.5; } else d[i] = w; } }
  return b;
}
function loopSrc(buf){ const s = ctx.createBufferSource(); s.buffer = buf; s.loop = true; s.start(); return s; }

function build(){
  ctx = new (window.AudioContext || window.webkitAudioContext)();
  master = ctx.createGain(); master.gain.value = 0; master.connect(ctx.destination);
  const comp = ctx.createDynamicsCompressor(); comp.threshold.value = -18; comp.connect(master);
  const white = noiseBuffer(3), brown = noiseBuffer(4, true);
  // 빗소리: 쏴아 + 지붕/유리에 떨어지는 낮은 웅웅
  rainHP = ctx.createBiquadFilter(); rainHP.type = 'highpass'; rainHP.frequency.value = 400;
  rainLP = ctx.createBiquadFilter(); rainLP.type = 'lowpass'; rainLP.frequency.value = 5000;
  rainG = ctx.createGain(); rainG.gain.value = 0.12;
  loopSrc(white).connect(rainHP); rainHP.connect(rainLP); rainLP.connect(rainG); rainG.connect(comp);
  const roofLP = ctx.createBiquadFilter(); roofLP.type = 'lowpass'; roofLP.frequency.value = 500;
  roofG = ctx.createGain(); roofG.gain.value = 0;
  loopSrc(brown).connect(roofLP); roofLP.connect(roofG); roofG.connect(comp);
  // 멀리 고속도로 웅웅
  const humLP = ctx.createBiquadFilter(); humLP.type = 'lowpass'; humLP.frequency.value = 160;
  humG = ctx.createGain(); humG.gain.value = 0;
  loopSrc(brown).connect(humLP); humLP.connect(humG); humG.connect(comp);
  rainBus = comp;
  jazzBus = ctx.createGain(); jazzBus.gain.value = 0.05;
  const jLP = ctx.createBiquadFilter(); jLP.type = 'lowpass'; jLP.frequency.value = 1400;
  jazzBus.connect(jLP); jLP.connect(comp);
  ctx._white = white;
  nodesReady = true;
}

function noiseHit({ at = 0, dur = 0.1, f0 = 2000, f1, q = 1, gain = 0.1, type = 'bandpass', pan = 0, pan1, attack = 0.005 }){
  const t = ctx.currentTime + at, s = ctx.createBufferSource(); s.buffer = ctx._white;
  const bf = ctx.createBiquadFilter(); bf.type = type; bf.Q.value = q; bf.frequency.setValueAtTime(f0, t); if (f1) bf.frequency.exponentialRampToValueAtTime(f1, t + dur);
  const g = ctx.createGain(); g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(gain, t + attack); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  const p = ctx.createStereoPanner(); p.pan.setValueAtTime(pan, t); if (pan1 != null) p.pan.linearRampToValueAtTime(pan1, t + dur);
  s.connect(bf); bf.connect(g); g.connect(p); p.connect(rainBus); s.start(t, Math.random()*2); s.stop(t + dur + 0.05);
  return { bf, g, p, t };
}
function tone({ at = 0, freq = 440, dur = 1, gain = 0.05, type = 'sine', dest, attack = 0.02, vib = 0 }){
  const t = ctx.currentTime + at, o = ctx.createOscillator(); o.type = type; o.frequency.value = freq;
  if (vib){ const l = ctx.createOscillator(), lg = ctx.createGain(); l.frequency.value = vib; lg.gain.value = freq*0.03; l.connect(lg); lg.connect(o.frequency); l.start(t); l.stop(t + dur); }
  const g = ctx.createGain(); g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(gain, t + attack); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  o.connect(g); g.connect(dest || rainBus); o.start(t); o.stop(t + dur + 0.05);
}

// ── 사건 ──
function wiper(){
  // 고무가 유리를 한 번 쓸고 돌아옴 + 끝에서 '툭'
  noiseHit({ dur: 0.45, f0: 900, f1: 1800, q: 6, gain: 0.05, attack: 0.08, pan: -0.2, pan1: 0.2 });
  noiseHit({ at: 0.45, dur: 0.45, f0: 1800, f1: 900, q: 6, gain: 0.045, attack: 0.08, pan: 0.2, pan1: -0.2 });
  tone({ at: 0.9, freq: 90, dur: 0.12, gain: 0.08, type: 'triangle' });
}
function passingCar(){
  // 젖은 도로 타이어 쉬익 + 엔진 웅, 왼쪽→오른쪽, 가운데에서 가장 큼
  const d = 2.7, t = ctx.currentTime;
  [{ f: 2600, q: 0.7, g: 0.09 }, { f: 220, q: 0.8, g: 0.16, type: 'lowpass' }].forEach(L => {
    const s = ctx.createBufferSource(); s.buffer = ctx._white;
    const bf = ctx.createBiquadFilter(); bf.type = L.type || 'bandpass'; bf.Q.value = L.q;
    bf.frequency.setValueAtTime(L.f*0.6, t); bf.frequency.linearRampToValueAtTime(L.f*1.15, t + d*0.48); bf.frequency.linearRampToValueAtTime(L.f*0.5, t + d);
    const g = ctx.createGain(); g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(L.g, t + d*0.5); g.gain.exponentialRampToValueAtTime(0.0001, t + d);
    const p = ctx.createStereoPanner(); p.pan.setValueAtTime(-0.9, t); p.pan.linearRampToValueAtTime(0.9, t + d);
    s.connect(bf); bf.connect(g); g.connect(p); p.connect(rainBus); s.start(t, Math.random()); s.stop(t + d + 0.1);
  });
}
function breath(){
  noiseHit({ dur: 1.4, f0: 500, f1: 700, q: 1.2, gain: 0.025, attack: 0.6 });
  noiseHit({ at: 1.6, dur: 1.5, f0: 650, f1: 380, q: 1.2, gain: 0.02, attack: 0.3 });
}
function ornament(){ tone({ freq: 1180, dur: 0.6, gain: 0.025, vib: 9 }); tone({ at: 0.05, freq: 1770, dur: 0.3, gain: 0.01 }); }
function jolt(){ tone({ freq: 70, dur: 0.18, gain: 0.08, type: 'triangle' }); noiseHit({ dur: 0.12, f0: 300, q: 2, gain: 0.03 }); }
// 휴게소 재즈 (아주 작게): 반복 10초에 맞춘 네 화음, 부드러운 전자 피아노
const CHORDS = [[146.8, 174.6, 220, 261.6], [130.8, 164.8, 196, 246.9], [123.5, 146.8, 174.6, 220], [110, 138.6, 164.8, 196]];
function chord(i){
  CHORDS[i % 4].forEach((f, k) => { tone({ at: k*0.04, freq: f, dur: 2.4, gain: 0.05, dest: jazzBus, attack: 0.03, vib: 5 }); tone({ at: k*0.04, freq: f*2, dur: 1.2, gain: 0.012, dest: jazzBus, type: 'triangle' }); });
  tone({ at: 0, freq: CHORDS[i % 4][0]/2, dur: 2.2, gain: 0.07, dest: jazzBus, type: 'triangle' });
}
function drip(){ tone({ freq: 1400 + Math.random()*1400, dur: 0.07, gain: 0.012 + Math.random()*0.01 }); }

// 반복 구간 기준 사건 (lt = t - 6)
const BREATH = LOOP/3;
const EVENTS = [
  { t: 2.0, fn: wiper, intro: true },
  { lt: 1.5, fn: passingCar },
  { lt: 7.9, fn: jolt },
  { lt: 8.0, fn: ornament },
  { lt: 0, fn: breath }, { lt: BREATH, fn: breath }, { lt: BREATH*2, fn: breath },
  { lt: 0, fn: () => chord(0) }, { lt: 2.5, fn: () => chord(1) }, { lt: 5, fn: () => chord(2) }, { lt: 7.5, fn: () => chord(3) },
  { t: 0, fn: () => chord(0), intro: true }, { t: 2.5, fn: () => chord(1), intro: true }, { t: 5, fn: () => chord(2), intro: true }
];
const wrapT = t => t < INTRO ? t : INTRO + (((t - INTRO) % LOOP) + LOOP) % LOOP;
function fireBetween(a, b, opts){
  const reduced = opts.reduced;
  for (const e of EVENTS){
    if (reduced && e.fn !== breath && e.intro) continue;
    if (reduced && (e.fn === wiper || e.fn === passingCar || e.fn === jolt || e.fn === ornament)) continue;
    if (e.t != null){ if (e.t >= a && e.t < b) e.fn(); continue; }
    const at = INTRO + e.lt; if (at >= a && at < b) e.fn();
  }
}

function update(T, playing, opts){
  if (!enabled || !nodesReady) return;
  const now = ctx.currentTime, reduced = !!opts.reduced;
  const tt = reduced ? INTRO + (((T % LOOP) + LOOP) % LOOP) : wrapT(T);
  const cabin = tt >= 3.5 ? 1 : tt >= 2.9 ? (tt - 2.9)/0.6 : 0;
  // 전경: 트인 빗소리 + 고속도로 / 운전석: 먹먹한 빗소리 + 지붕 두드림
  master.gain.setTargetAtTime(playing ? 0.9 : 0, now, 0.08);
  rainLP.frequency.setTargetAtTime(reduced ? 1200 : 5200 - cabin*4000, now, 0.1);
  rainG.gain.setTargetAtTime(reduced ? 0.05 : 0.13 - cabin*0.05, now, 0.1);
  roofG.gain.setTargetAtTime(reduced ? 0.05 : cabin*0.12, now, 0.1);
  humG.gain.setTargetAtTime(reduced ? 0 : 0.06*(1 - cabin) + 0.015, now, 0.2);
  jazzBus.gain.setTargetAtTime(opts.jazz ? 0.05 : 0, now, 0.2);
  if (playing && lastT != null){
    const d = T - lastT;
    if (d > 0 && d < 0.3){
      const a = reduced ? lastT : lastT, b = T;
      if (reduced){ const la = ((a % LOOP) + LOOP) % LOOP, lb = la + d; fireBetween(INTRO + la, INTRO + lb, opts); if (lb > LOOP) fireBetween(INTRO, INTRO + lb - LOOP, opts); }
      else {
        const wa = wrapT(a), wb = wa + d;
        fireBetween(wa, Math.min(wb, TOTAL), opts);
        if (wb > TOTAL) fireBetween(INTRO, INTRO + (wb - TOTAL), opts);
      }
      if (Math.random() < d*(cabin ? 5 : 2)) drip();
    }
  }
  lastT = T;
}

async function setEnabled(on){
  enabled = on;
  if (on){ if (!ctx) build(); if (ctx.state !== 'running') await ctx.resume(); }
  else if (ctx){ master.gain.setTargetAtTime(0, ctx.currentTime, 0.05); }
}
window.RestStopSound = { update, setEnabled, isEnabled: () => enabled };
})();
