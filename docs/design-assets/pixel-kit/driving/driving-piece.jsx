// 주행 중 화면 — 컴포지션. 모든 층은 DrivingScene(driving-scene.js)이 그리고, 여기서는 T로 배치만 한다.
const MOTION = {
  // 모든 움직임은 단계(픽셀) 값 — 부드러운 보간 없음
  bob: (p, unit) => p.bob * unit,
  driver: (p, unit) => p.driverBob * unit,
  scroll: (p, unit) => -p.refl * unit,
};

// 도로 점선·가드레일 기둥·나무·가로등 — 원근 투영으로 앞쪽(화면 아래)으로 다가와 지나감
function Scenery({ b, p, night }) {
  const sn = window.DrivingScene.scenery(p.st, b.g, night, false);
  const u = b.unit;
  return (
    <div style={{ position: 'absolute', inset: 0 }}>
      {sn.rects.map((r, i) => <div key={'r' + i} style={{ position: 'absolute', left: r[0] * u, top: r[1] * u, width: r[2] * u, height: r[3] * u, background: r[4] }} />)}
      {sn.sprites.map((s, i) => <img key={'s' + i} src={b.sprites[s[0]]} style={{ position: 'absolute', left: s[1] * u, top: s[2] * u, width: s[3] * u, height: s[4] * u, imageRendering: 'pixelated' }} />)}
    </div>
  );
}

function DrivingScreen({ opts, caption }) {
  const { T, authoredTotal } = useComposition();
  const b = React.useMemo(() => window.DrivingScene.build(opts), [opts.timeOfDay, opts.driver, opts.layout]);
  const p = window.DrivingScene.pose(T, authoredTotal, opts);
  const u = b.unit, g = b.g;
  const full = { position: 'absolute', left: 0, top: 0, width: b.W, height: b.H, imageRendering: 'pixelated', pointerEvents: 'none' };
  const off = Math.round(((opts.layout === 'narrow' ? 840 : 1360) - b.W) / 2);
  const truckY = MOTION.bob(p, u), drvY = MOTION.driver(p, u);
  return (
    <div data-screen-label={`t=${Math.floor(T)}s`} style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: '#111' }}>
      <div style={{ position: 'absolute', left: off, top: 0, width: b.W, height: b.H }}>
        <img src={b.bg} style={full} />
        <Scenery b={b} p={p} night={opts.timeOfDay === 'night'} />
        <img src={b.rail} style={full} />
        <div style={{ position: 'absolute', inset: 0, transform: `translateY(${truckY}px)` }}>
          <img src={b.interior} style={full} />
          <div style={{ position: 'absolute', inset: 0, transform: `translateY(${drvY}px)` }}>
            {b.earA.map((s, i) => <img key={'a' + i} src={s} style={{ ...full, opacity: i === p.earA ? 1 : 0 }} />)}
            {b.earB.map((s, i) => <img key={'b' + i} src={s} style={{ ...full, opacity: i === p.earB ? 1 : 0 }} />)}
            <img src={b.bodyOpen} style={{ ...full, opacity: p.blink ? 0 : 1 }} />
            <img src={b.bodyClosed} style={{ ...full, opacity: p.blink ? 1 : 0 }} />
          </div>
          <img src={b.wheel} style={full} />
          <img src={b.dash} style={full} />
          {b.toy.map((s, i) => <img key={'t' + i} src={s} style={{ ...full, opacity: i === p.toy ? 1 : 0 }} />)}
          <div style={{ position: 'absolute', left: g.ws.x * u, top: g.ws.y * u, width: g.ws.w * u, height: g.ws.h * u, overflow: 'hidden' }}>
            <img src={b.refl} style={{ position: 'absolute', left: 0, top: MOTION.scroll(p, u), width: g.ws.w * u, height: (g.ws.h + b.PR) * u, opacity: b.reflOpacity, imageRendering: 'pixelated' }} />
            <img src={b.glare} style={{ position: 'absolute', left: 0, top: 0, width: g.ws.w * u, height: g.ws.h * u, opacity: b.glareOpacity, imageRendering: 'pixelated' }} />
          </div>
          <img src={b.cab} style={full} />
        </div>
      </div>
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: opts.layout === 'narrow' ? 64 : 30, textAlign: 'center',
        font: `400 ${opts.layout === 'narrow' ? 24 : 22}px/1.2 Galmuri11, 'Gowun Dodum', sans-serif`, letterSpacing: '0.02em', color: b.caption.color,
      }}>{caption}</div>
    </div>
  );
}

function DrivingPiece(props) {
  const opts = {
    timeOfDay: props.timeOfDay === 'night' ? 'night' : 'day',
    driver: props.driver === 'mechanic' ? 'mechanic' : 'rrr',
    layout: props.layout === 'narrow' ? 'narrow' : 'wide',
    motion: props.motion || 'full',
  };
  const W = opts.layout === 'narrow' ? 840 : 1360, H = opts.layout === 'narrow' ? 1184 : 948;
  return (
    <CompositionStage key={opts.layout} width={W} height={H} scenes={window.OM_SCENES} playback={window.OM_PLAYBACK} bg="#111">
      <DrivingScreen opts={opts} caption={props.caption ?? '운전 중에는 화면을 쉬어요'} />
    </CompositionStage>
  );
}
window.DrivingPiece = DrivingPiece;
