// Display-only map controls and actors; never mutate economy state.
let mapZoom=1, mapPan={x:0,y:0}, townRAF=0, actorClock=0, townActorState=null;
let townMotion=!window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const TOWN_ACTORS=[
 {name:'손님 · 산책',kind:'guest',a:[595,580],b:[825,695],duration:22,offset:0},
 {name:'손님 · 방문',kind:'guest2',a:[745,750],b:[945,850],duration:24,offset:8},
 {name:'정비사 · 순찰',kind:'mechanic',a:[1175,660],b:[1285,715],duration:16,offset:4},
 {name:'주유소 직원',kind:'fuel',a:[130,318],b:[300,302],duration:18,offset:6},  // 해상 건물 지붕에 올라가지 않도록 주유기 앞 바닥에서만 오간다
 {name:'렌트카 직원',kind:'staff',a:[720,595],b:[940,705],duration:21,offset:11},
 {name:'르르르 · 마을 산책',ruru:true,a:[670,665],b:[790,725],duration:20,offset:2}
];
const npcCache={};
function npcFrames(kind, rear=false){
 const cacheKey=kind+(rear?":rear":":front");
 if(npcCache[cacheKey])return npcCache[cacheKey];
 // Native 24×40 canvas art. Every mark uses integer pixel coordinates.
 const colors={guest:['#fff3dd','#292733'],guest2:['#ed91ac','#634038'],mechanic:['#e9b75d','#353040'],fuel:['#ec4055','#302839'],staff:['#234573','#26283d']}[kind];
 return npcCache[cacheKey]=[0,1,2,3,4,5,6,7].map(f=>{
  const cv=document.createElement('canvas');cv.width=24;cv.height=40;const c=cv.getContext('2d');c.imageSmoothingEnabled=false;
  const box=(x,y,w,h,col)=>{c.fillStyle=col;c.fillRect(x,y,w,h)};
  const outline=(x,y,w,h,col)=>{box(x,y,w,h,'#18283b');box(x+1,y+1,w-2,h-2,col)};
  const stride=[0,1,2,1,0,-1,-2,-1][f],bob=(f===2||f===6)?1:0;
  box(4,37,17,2,'#263d4855');
  for(const [hip,step,shade] of [[8,-stride,'#27394f'],[14,stride,'#405974']]){
   const ankle=hip+step*2, footY=35-Math.max(0,-step);
   for(let row=27;row<=footY;row++){
    const x=Math.round(hip+(ankle-hip)*(row-27)/(footY-27));
    box(x-2,row,5,1,'#18283b');box(x-1,row,3,1,shade);
   }
   box(ankle-2,footY,6,2,'#192a3c');
  }
  const [shirt,hair]=colors;
  const ink='#202638', skin='#f7d4aa', shade='#d99a77';
  const rounded=(x,y,w,h,color)=>{
   box(x+2,y,w-4,h,ink);box(x,y+2,w,h-4,ink);
   box(x+2,y+1,w-4,h-2,color);box(x+1,y+2,w-2,h-4,color);
  };
  rounded(3,19-bob+stride,5,9,shirt);
  rounded(17,19-bob-stride,5,9,shirt);
  box(4,25-bob+stride,3,2,skin);
  box(18,25-bob-stride,3,2,shade);
  rounded(6,17-bob,13,13,shirt);
  box(16,20-bob,2,8,kind==='staff'?'#172d53':'#39273930');
  if(!rear){
   box(10,18-bob,5,2,'#fff8e7');
   if(kind==='staff'){
    box(12,20-bob,2,7,'#85c8fb');box(8,22-bob,2,2,'#d7e8ff');
   }
   if(kind==='mechanic'){
    box(9,19-bob,1,8,'#435872');box(15,19-bob,1,8,'#435872');
    box(9,24-bob,7,4,'#435872');box(11,25-bob,3,1,'#ffe3a8');
   }
   if(kind==='fuel'){box(14,21-bob,3,2,'#fff0dc');box(8,20-bob,1,8,'#9b263f');}
  }
  rounded(5,3-bob,15,16,hair);
  box(8,5-bob,6,2,kind==='guest2'?'#92624c':'#44445b');
  if(!rear){
   rounded(7,7-bob,13,12,skin);
   box(8,9-bob,2,5,hair);box(8,7-bob,7,2,hair);
   box(10,9-bob,3,1,hair);
   box(12,11-bob,1,2,ink);box(17,11-bob,1,2,ink);
   box(10,14-bob,2,1,'#e7a48c');box(17,14-bob,2,1,'#e7a48c');
   box(14,15-bob,2,1,'#985d58');
  }
  if(kind==='guest2'){box(5,9-bob,3,10,hair);box(6,10-bob,1,7,'#92624c');}
  if(kind==='fuel'){
   rounded(5,2-bob,15,7,'#e7344e');box(8,4-bob,6,1,'#ff8290');
   if(!rear){box(14,8-bob,8,2,ink);box(14,8-bob,6,1,'#ad233d');}
  }
  return cv.toDataURL('image/png');
 });
}
function applyTownCamera(){
 const world=document.getElementById('townWorld');if(!world)return;
 world.style.transform=`translate(${Math.round(mapPan.x)}px,${Math.round(mapPan.y)}px) scale(${mapZoom})`;
 const readout=document.getElementById('zoomValue');if(readout)readout.textContent=Math.round(mapZoom*100)+'%';
 document.querySelector('[data-act="zoomOut"]').disabled=mapZoom<=.5;
 document.querySelector('[data-act="zoomIn"]').disabled=mapZoom>=1.5;
}
function setTownZoom(value){
 mapZoom=Math.max(.5,Math.min(1.5,Math.round(value*4)/4));
 mapPan={x:0,y:0};applyTownCamera();
}
function mountTown(){
 const vp=document.getElementById('townViewport');if(!vp)return;
 applyTownCamera();
 let dragging=null;
 vp.onpointerdown=e=>{
  if(mapZoom<=1||e.target.closest('button'))return;
  dragging={x:e.clientX,y:e.clientY,px:mapPan.x,py:mapPan.y};vp.setPointerCapture(e.pointerId);vp.classList.add('is-dragging');
 };
 vp.onpointermove=e=>{
  if(!dragging)return;
  const rect=vp.getBoundingClientRect(),limitX=rect.width*(mapZoom-1)/2,limitY=rect.height*(mapZoom-1)/2;
  mapPan.x=Math.max(-limitX,Math.min(limitX,dragging.px+e.clientX-dragging.x));
  mapPan.y=Math.max(-limitY,Math.min(limitY,dragging.py+e.clientY-dragging.y));applyTownCamera();
 };
 vp.onpointerup=vp.onpointercancel=()=>{dragging=null;vp.classList.remove('is-dragging');};
 const elements=TOWN_ACTORS.map((_,i)=>document.getElementById('townActor'+i));
 // Keep original transparent PNGs intact and preload before frame changes.
 Object.values(RURU_FRAMES).flat().forEach(src=>{const im=new Image();im.src=src;});
 let prev=performance.now();
 // 사람마다 자기 시계를 갖고, 앞에 다른 사람이 있으면 멈춰 기다린다.
 // 화면 아래(발 위치 y가 큰) 사람이 앞에 그려지도록 z-index를 발 위치로 정한다.
 const WAIT_MAX=2.5, SIDE=18;                    // 최대 대기 초, 비켜 서는 최대 거리(지도 좌표 1536×1024)
 // 그림 크기(지도 좌표): 사람 40×67, 르르르 64×59. 그림끼리 겹치는지 상자로 판단한다.
 const size=TOWN_ACTORS.map(p=>p.ruru?[64,59]:[40,67]);
 // 화면을 다시 그려도 걷던 자리에서 이어지도록 상태는 한 번만 만든다
 if(!townActorState)townActorState=TOWN_ACTORS.map(p=>({clock:p.offset,wait:0,nx:0,ny:0,pos:null}));
 const st=townActorState;
 function pose(p,clock){
  const phase=(clock/p.duration)%1;
  let t=0,walking=false,flip=false;
  if(phase<.1)t=0;
  else if(phase<.45){t=(phase-.1)/.35;walking=true;}
  else if(phase<.60){t=1;flip=phase>.53;}
  else if(phase<.95){t=1-(phase-.60)/.35;walking=true;flip=true;}
  if(walking){
   const progress=flip?1-t:t;
   const eased=progress-Math.sin(2*Math.PI*progress)*.08;
   t=flip?1-eased:eased;
  }
  return {x:p.a[0]+(p.b[0]-p.a[0])*t,y:p.a[1]+(p.b[1]-p.a[1])*t,t,walking,flip};
 }
 // 1 미만이면 두 그림이 겹친다(가로는 몸통 폭, 세로는 키 기준)
 const overlap=(i,j,a,b)=>Math.max(Math.abs(a.x-b.x)/((size[i][0]+size[j][0])/2*.75),Math.abs(a.y-b.y)/((size[i][1]+size[j][1])/2*.8));
 st.forEach((s,i)=>{if(!s.pos)s.pos=pose(TOWN_ACTORS[i],s.clock);});
 function tick(now){
  if(!vp.isConnected)return;
  const dt=(townMotion&&!document.hidden)?Math.min((now-prev)/1000,.1):0;prev=now;
  actorClock+=dt;
  // 1) 다음 위치 후보와 막힘 여부
  const cand=TOWN_ACTORS.map((p,i)=>pose(p,st[i].clock+dt));
  const blockedBy=TOWN_ACTORS.map((p,i)=>{
   if(!cand[i].walking||dt===0)return -1;
   for(let j=0;j<TOWN_ACTORS.length;j++){
    if(j===i)continue;
    const vNext=overlap(i,j,cand[i],st[j].pos),vNow=overlap(i,j,st[i].pos,st[j].pos);
    if(vNext<1.05&&vNext<vNow)return j;      // 그림이 겹칠 만큼 다가가는 중이면 막힘
   }
   return -1;
  });
  // 2) 서로 막으면 번호가 작은 쪽이 먼저 지나간다. 너무 오래 기다리면 그냥 간다.
  TOWN_ACTORS.forEach((p,i)=>{
   const j=blockedBy[i];
   let stop=j>=0&&!(blockedBy[j]===i&&i<j);
   if(stop&&st[i].wait>WAIT_MAX)stop=false;
   if(stop){st[i].wait+=dt;}else{if(j<0)st[i].wait=0;st[i].clock+=dt;st[i].pos=cand[i];}   // 길이 빌 때만 대기 시간 초기화
   st[i].stopped=stop;
  });
  // 3) 그림이 겹칠 만큼 가까우면 자기 길의 옆 방향으로 비켜 선다(그림만, 경로는 그대로)
  TOWN_ACTORS.forEach((p,i)=>{
   const L=Math.hypot(p.b[0]-p.a[0],p.b[1]-p.a[1])||1,perp=[-(p.b[1]-p.a[1])/L,(p.b[0]-p.a[0])/L];
   let px=0,py=0;
   TOWN_ACTORS.forEach((q,j)=>{
    if(i===j)return;
    const a=st[i].pos,b=st[j].pos,v=overlap(i,j,a,b);
    if(v>=1.25)return;
    let side=Math.sign((a.x-b.x)*perp[0]+(a.y-b.y)*perp[1]);
    if(side===0)side=i<j?-1:1;
    const k=(1.25-v)/1.25*SIDE;
    px+=perp[0]*side*k;py+=perp[1]*side*k;
   });
   st[i].nx+=(px-st[i].nx)*.15;st[i].ny+=(py-st[i].ny)*.15;
  });
  // 4) 그리기
  TOWN_ACTORS.forEach((p,i)=>{
   const el=elements[i],s=st[i],ps=s.pos,walking=ps.walking&&!s.stopped,flip=ps.flip;
   const x=Math.round(ps.x+s.nx),y=Math.round(ps.y+s.ny);
   el.style.left=(x/1536*100)+'%';el.style.top=(y/1024*100)+'%';
   el.style.zIndex=100+y;
   el.style.transform=`translate(-50%,-100%) scaleX(${flip?-1:1})`;
   const anim=p.ruru?RURU_FRAMES[walking?'walk':'idle']:npcFrames(p.kind,flip);
   const frame=walking?(p.ruru?Math.floor(actorClock*10):Math.floor(ps.t*Math.hypot(p.b[0]-p.a[0],p.b[1]-p.a[1])/3.8))%anim.length:p.ruru?Math.floor(actorClock*2)%anim.length:0;
   const key=(walking?'walk':'idle')+frame+(flip?'-back':'');
   if(el.dataset.frame!==key){el.src=anim[frame];el.dataset.frame=key;}
  });
  if(townMotion)townRAF=requestAnimationFrame(tick);
 }
 tick(prev);
}
