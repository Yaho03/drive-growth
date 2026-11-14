// 7차 휴게소 연출 — 르르르가 트럭 운전석에서 잠든 밤 (Claude Design 보드 「르르르 휴게소 수면 애니메이션」)
// 그림은 rest-stop-scene.js, 소리는 rest-stop-sound.js를 보드에서 그대로 가져왔다. 이 파일은 정보 카드와 조립만 한다.
// d: { variant: 'rururu' | 'blanket', state: 'ok' | 'pending' | 'unknown', sound, onClose, 글자들 }
// 타이밍은 briefs/rest-stop-timing.md. 0–6초 첫 재생, 그 뒤 10초 반복이라 끝이 없다(닫기로 닫는다).
const RS_FONT = "'Galmuri11', 'Galmuri9', 'Noto Sans KR', sans-serif";
const RS_TEXT = { chip: '휴식 중', bonus: '출발하면 20분 동안 기본 수익 1.5배', rested: '12분째',
  pending: '조금만 더 쉬면 휴식 보너스가 준비돼요', pendingMinutes: 3, unknown: '이 환경에서는 휴게소를 확인할 수 없어요' };

function RsHighlight({ text }) {
  return String(text).split(/([\d.]+배)/).map((p, i) => /[\d.]+배/.test(p)
    ? <span key={i} style={{ color: '#f2b25c' }}>{p}</span> : <React.Fragment key={i}>{p}</React.Fragment>);
}

function RsChip({ children, warm }) {
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, alignSelf: 'flex-start', padding: '6px 14px', borderRadius: 999, fontSize: 20, lineHeight: 1,
      background: warm ? '#f2b25c' : '#2f3566', color: warm ? '#1c1e2c' : '#c8ccf4' }}>
      <span style={{ width: 8, height: 8, background: warm ? '#1c1e2c' : '#8e94c0' }}></span>{children}
    </div>
  );
}

function RsClose({ onClose }) {
  return <button type="button" onClick={e => { e.stopPropagation(); onClose && onClose(); }} style={{ flexShrink: 0, padding: '14px 28px', borderRadius: 14, fontSize: 22, lineHeight: 1,
    background: '#262b54', border: '3px solid #3a4070', color: '#eef0ff', fontFamily: RS_FONT, cursor: 'pointer' }}>닫기</button>;
}

function RsCard({ d, appear }) {
  const tx = Object.assign({}, RS_TEXT, d);
  const y = Math.round((1 - (1 - Math.pow(1 - appear, 3))) * 64 / 4) * 4, op = Math.round(appear * 4) / 4;
  const total = 5, done = Math.max(0, Math.min(total, tx.pendingMinutes));
  const state = d.state || 'ok';
  return (
    <div style={{ position: 'absolute', left: 290, width: 780, bottom: 40, transform: `translateY(${y}px)`, opacity: op,
      background: 'rgba(11,13,30,0.94)', border: '4px solid #2a2f5c', borderRadius: 24, boxShadow: '0 8px 0 rgba(0,0,0,0.4)',
      padding: '24px 28px 24px 30px', display: 'flex', alignItems: 'center', gap: 28, fontFamily: RS_FONT, color: '#eef0ff', boxSizing: 'border-box' }}>
      {state === 'ok' && <>
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <RsChip warm>{tx.chip}</RsChip>
          <div style={{ fontSize: 28, lineHeight: 1.35 }}><RsHighlight text={tx.bonus} /></div>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 6, flexShrink: 0 }}>
          <div style={{ fontSize: 18, color: '#9aa0c8' }}>쉰 시간</div>
          <div style={{ fontSize: 40, lineHeight: 1 }}>{tx.rested}</div>
        </div>
      </>}
      {state === 'pending' && <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 14 }}>
        <RsChip>휴식 준비 중</RsChip>
        <div style={{ fontSize: 26, lineHeight: 1.35 }}>{tx.pending}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <div style={{ flex: 1, display: 'flex', gap: 4, padding: 4, background: '#151935', border: '3px solid #2a2f5c', borderRadius: 8 }}>
            {Array.from({ length: total * 2 }).map((_, i) => <div key={i} style={{ flex: 1, height: 12, borderRadius: 2, background: i < done * 2 ? '#f2b25c' : '#262b54' }}></div>)}
          </div>
          <div style={{ fontSize: 20, color: '#c8ccf4', flexShrink: 0 }}>{total - done}분 남음</div>
        </div>
      </div>}
      {state === 'unknown' && <div style={{ flex: 1, minWidth: 0, fontSize: 24, lineHeight: 1.4, color: '#c8ccf4' }}>{tx.unknown}</div>}
      <RsClose onClose={d.onClose} />
    </div>
  );
}

function RestStop({ t, d, reduced }) {
  const RS = window.RestStopScene, cv = React.useRef(null);
  const [skip, setSkip] = React.useState(0);   // 건너뛰기: 첫 재생을 넘기고 반복 구간(6초)부터
  const T = t + skip, opts = { variant: d.variant === 'blanket' ? 'blanket' : 'rururu', reduced };
  React.useEffect(() => { if (d.sound) window.RestStopSound.setEnabled(true); return () => window.RestStopSound.setEnabled(false); }, []);
  React.useEffect(() => {
    const g = cv.current && cv.current.getContext('2d'); if (!g) return;
    g.imageSmoothingEnabled = false; g.drawImage(RS.render(T, opts), 0, 0);
    window.RestStopSound.update(T, true, { reduced, jazz: false });   // 재즈는 휴게소 브금 파일이 맡는다
  });
  const appear = RS.cardProgress(T, reduced);
  return (
    <div style={{ position: 'absolute', inset: 0, background: '#04050a', overflow: 'hidden' }}>
      <canvas ref={cv} width={RS.WIDTH} height={RS.HEIGHT} aria-label="휴게소에 세운 트럭 운전석에서 사장님이 담요를 덮고 자는 장면"
        style={{ position: 'absolute', left: 0, top: 0, width: RS.WIDTH * RS.SCALE, height: RS.HEIGHT * RS.SCALE, imageRendering: 'pixelated', display: 'block' }} />
      {!reduced && T < RS.INTRO && <button type="button" onClick={e => { e.stopPropagation(); setSkip(RS.INTRO - t); }} style={{ position: 'absolute', top: 28, right: 28,
        padding: '10px 18px', borderRadius: 12, border: '3px solid #3a4070', background: 'rgba(11,13,30,0.85)', color: '#c8ccf4', fontFamily: RS_FONT, fontSize: 18, cursor: 'pointer' }}>건너뛰기</button>}
      {appear > 0 && <RsCard d={d} appear={appear} />}
    </div>
  );
}

Object.assign(window.DV.PIECES, { rest: (t, o) => <RestStop t={t} d={o.d} reduced={!!o.reduced} /> });
