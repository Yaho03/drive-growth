// Display-only map controls and actors; never mutate economy state.
let mapZoom=1, mapPan={x:0,y:0}, townRAF=0, actorClock=0;
let townFocus=null, townFocusAnim=false, townBusy=false;   // 지도에서 고른 차(상세 보기)
let townMotion=!window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const TOWN_ACTORS=[
 {name:'손님 · 산책',kind:'guest',a:[595,580],b:[825,695],duration:22,offset:0},
 {name:'손님 · 방문',kind:'guest2',a:[745,750],b:[945,850],duration:24,offset:8},
 {name:'정비사 · 순찰',kind:'mechanic',a:[1175,660],b:[1285,715],duration:16,offset:4},
 {name:'주유소 직원',kind:'fuel',a:[130,318],b:[300,302],duration:18,offset:6},  // 해상 건물 지붕에 올라가지 않도록 주유기 앞 바닥에서만 오간다
 {name:'렌트카 직원',kind:'staff',a:[720,595],b:[940,705],duration:21,offset:11},
 {name:'르르르 · 마을 산책',ruru:true,a:[670,665],b:[790,725],duration:20,offset:2},
 {name:'손님 · 가게 드나들기',kind:'visitor',a:[700,720],b:[700,720],duration:20,offset:0},
 {name:'손님 · 가게 드나들기',kind:'visitor2',a:[880,760],b:[880,760],duration:20,offset:0}
];
const npcCache={};
function npcFrames(kind, rear=false){
 const cacheKey=kind+(rear?":rear":":front");
 if(npcCache[cacheKey])return npcCache[cacheKey];
 // Native 24×40 canvas art. Every mark uses integer pixel coordinates.
 const colors={guest:['#fff3dd','#292733'],guest2:['#ed91ac','#634038'],mechanic:['#e9b75d','#353040'],fuel:['#ec4055','#302839'],staff:['#234573','#26283d'],visitor:['#6ed2be','#5a3a2a'],visitor2:['#a98be0','#2a2a38']}[kind];
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
 world.style.transform=(townFocus&&focusTransform(townFocus))||`translate(${Math.round(mapPan.x)}px,${Math.round(mapPan.y)}px) scale(${mapZoom})`;
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
  if(townFocus||mapZoom<=1||e.target.closest('button'))return;
  dragging={x:e.clientX,y:e.clientY,px:mapPan.x,py:mapPan.y};vp.setPointerCapture(e.pointerId);vp.classList.add('is-dragging');
 };
 vp.onpointermove=e=>{
  if(!dragging)return;
  const rect=vp.getBoundingClientRect(),limitX=rect.width*(mapZoom-1)/2,limitY=rect.height*(mapZoom-1)/2;
  mapPan.x=Math.max(-limitX,Math.min(limitX,dragging.px+e.clientX-dragging.x));
  mapPan.y=Math.max(-limitY,Math.min(limitY,dragging.py+e.clientY-dragging.y));applyTownCamera();
 };
 vp.onpointerup=vp.onpointercancel=()=>{dragging=null;vp.classList.remove('is-dragging');};
 mountActors(vp,document.getElementById('townWorld'));
}

// ── 차를 누르면: 헤드라이트 번쩍 → 그 차로 확대 → 상세 UI ──
const FOCUS_ZOOM=2.6, FOCUS_AT=[-.22,.2];       // 확대 배율, 차를 둘 화면 위치(가운데 기준, 지도 틀 폭·높이 비율): 왼쪽 위 카드 아래, 오른쪽 목록 왼쪽
const reduceMotion=()=>window.matchMedia('(prefers-reduced-motion: reduce)').matches;
function focusTransform(id){
 const vp=document.getElementById('townViewport');
 const i=S.cars.slice(0,cap()).findIndex(c=>c.id===id);
 if(!vp||i<0)return null;
 const sl=SLOTS[i],Wv=vp.clientWidth,Hv=vp.clientHeight,Ww=Wv,Hw=Wv*2/3,s=FOCUS_ZOOM;
 const px=(sl.x/1536-.5)*Ww,py=((sl.y+6)/1024-.5)*Hw;
 let tx=FOCUS_AT[0]*Wv-px*s,ty=FOCUS_AT[1]*Hv-py*s;
 const lx=Math.max(0,(s*Ww-Wv)/2),ly=Math.max(0,(s*Hw-Hv)/2);   // 지도 밖(빈 곳)이 보이지 않게
 tx=Math.max(-lx,Math.min(lx,tx));ty=Math.max(-ly,Math.min(ly,ty));
 return `translate(${Math.round(tx)}px,${Math.round(ty)}px) scale(${s})`;
}
// 등급 색(일반 흰 · 고급 파랑 · 희귀 코랄 · 전설 진한 주황)
const FLASH_COL={common:['#d6dce6','#f6f6f2','#f6f6f2'],advanced:['#346cd6','#68a0f0','#aad6f6'],
 rare:['#f68068','#f08caa','#e8d6b4'],legend:['#c86e28','#f6aa3c','#fcdc64']};
function flashCar(id){
 const car=document.querySelector(`.town-car[data-carid="${id}"]`);if(!car)return;
 if(!car.complete||!car.naturalWidth){car.addEventListener('load',()=>flashCar(id),{once:true});return;}
 const rar=car.dataset.rar||'common',[main,light,pale]=FLASH_COL[rar]||FLASH_COL.common;
 const w=car.naturalWidth,h=car.naturalHeight,P=14;
 const cv=document.createElement('canvas');cv.width=w+2*P;cv.height=h+2*P;cv.className='flash-cv';
 const wPct=parseFloat(car.style.width);
 Object.assign(cv.style,{left:car.style.left,top:car.style.top,width:(wPct*(w+2*P)/w)+'%',
  transform:'translate(-50%,-50%) scaleX(-1)',zIndex:(+car.style.zIndex||100)+1});
 car.parentNode.appendChild(cv);
 const g=cv.getContext('2d');
 // 앞(↘) 헤드라이트 두 개: 차 그림 폭·높이 비율(여러 차종에서 잰 평균)
 const lights=[[Math.round(w*.66)+P,Math.round(h*.70)+P],[Math.round(w*.95)+P,Math.round(h*.58)+P]];
 const put=(x,y,c,a=1)=>{g.globalAlpha=a;g.fillStyle=c;g.fillRect(x,y,1,1);};
 const base={common:1,advanced:2,rare:3,legend:5}[rar]||1, powers=[1,2,3,3,2,1];
 let k=0;
 const step=()=>{
  g.clearRect(0,0,cv.width,cv.height);
  if(k>=powers.length){cv.remove();return;}
  const power=powers[k],size=base+power-1;
  for(const [x,y] of lights){
   for(let dy=-size;dy<=size;dy++)for(let dx=-size;dx<=size;dx++){
    const d=dx*dx+dy*dy;if(d>0&&d<=(size*.7)**2&&((x+dx+y+dy)&1)===0)put(x+dx,y+dy,light,.8);}
   for(let r=1;r<=size+1;r++){const c=r<=Math.floor(size/2)+1?'#ffffff':main;
    put(x+r,y,c);put(x-r,y,c);put(x,y+r,c);put(x,y-r,c);}
   if(rar==='legend')for(let r=1;r<=Math.floor(size/2)+1;r++){put(x+r,y+r,pale);put(x-r,y-r,pale);put(x+r,y-r,pale);put(x-r,y+r,pale);}
   put(x,y,'#ffffff');
   if(rar==='rare'||rar==='legend'){                // 앞으로 뻗는 빛줄기
    const L=Math.floor({rare:8,legend:12}[rar]*power/3);
    for(let s=2;s<L;s++)for(const v of[-1,0,1]){const xx=x+s+1,yy=y+Math.floor(s/2)+v;
     if(v===0||((xx+yy)&1)===0)put(xx,yy,s<L*.6?light:main,.85);}
   }
  }
  k++;setTimeout(step,100);
 };
 step();
}
// 화면을 새 배치로 바로 다시 그리되, 지도가 누르기 전 자리·크기 그대로 보이도록 맞춘 뒤
// 지도 틀 넓어짐과 차로 확대를 같은 시간·같은 곡선으로 한 번에 움직인다(FLIP).
const CAM_MS=720, CAM_EASE='cubic-bezier(.4,0,.2,1)';   // 천천히 시작해 가운데서 빠르고 끝에서 부드럽게 멈춤
function worldRectAt1(map){const r=map.getBoundingClientRect(),h=r.width*2/3;return {x:r.left,y:r.top+(r.height-h)/2,w:r.width,h};}
function matchTransform(from,base){
 const k=from.width/base.w;
 const tx=(from.left+from.width/2)-(base.x+base.w/2),ty=(from.top+from.height/2)-(base.y+base.h/2);
 return `translate(${tx}px,${ty}px) scale(${k})`;
}
function flipTo(oldWorld,oldMap,targetTransform){
 const map=document.getElementById('townViewport'),w=document.getElementById('townWorld');
 if(!map||!w||!oldWorld)return;
 const nm=map.getBoundingClientRect();
 w.classList.remove('cam-move');
 w.style.transform=matchTransform(oldWorld,worldRectAt1(map));
 // 새 틀이 더 크면 누르기 전 틀 크기로 잘라 두었다가 함께 넓힌다
 const ins=[oldMap.top-nm.top,nm.right-oldMap.right,nm.bottom-oldMap.bottom,oldMap.left-nm.left].map(v=>Math.max(0,Math.round(v)));
 map.style.transition='';map.style.clipPath=`inset(${ins.join('px ')}px round 22px)`;
 void w.offsetWidth;
 map.style.transition=`clip-path ${CAM_MS}ms ${CAM_EASE}`;
 w.style.transition=`transform ${CAM_MS}ms ${CAM_EASE}`;
 w.style.transform=targetTransform;map.style.clipPath='inset(0px 0px 0px 0px round 22px)';
 setTimeout(()=>{map.style.clipPath='';map.style.transition='';w.style.transition='';},CAM_MS+40);
}
function townFocusCar(id){
 if(townBusy||!S.cars.some(c=>c.id===id))return;
 const world=document.getElementById('townWorld'),map=document.getElementById('townViewport');if(!world||!map)return;
 townBusy=true;
 const fast=reduceMotion(),oldWorld=world.getBoundingClientRect(),oldMap=map.getBoundingClientRect();
 townFocus=id;townFocusAnim=true;render();townFocusAnim=false;
 if(!fast){flipTo(oldWorld,oldMap,focusTransform(id));flashCar(id);}
 setTimeout(()=>{townBusy=false;},fast?0:CAM_MS);
}
let townFocusH=0;
// 확대 때 지도 틀 높이: 지도 칸 위쪽부터 하단 메뉴 바로 위까지
function focusMapHeight(){
 const nav=document.getElementById('nav'),shell=document.querySelector('.map-shell');
 // 창 높이 − 지도 칸 위치(스크롤 포함) − 하단 메뉴 높이 − 아래 여백·간격
 if(nav&&shell&&!nav.hidden){const top=shell.getBoundingClientRect().top+window.scrollY;
  const h=Math.round(window.innerHeight-top-nav.offsetHeight-16-12-4);if(h>200)townFocusH=h;}
 return townFocusH||Math.max(240,Math.round(window.innerHeight-160));
}
function townUnfocus(){
 if(!townFocus||townBusy)return;
 townBusy=true;
 const fast=reduceMotion();
 document.querySelectorAll('.focus-actions,.focus-info').forEach(e=>e.classList.add('leaving'));
 setTimeout(()=>{
  const world=document.getElementById('townWorld'),map=document.getElementById('townViewport');
  const oldWorld=world&&world.getBoundingClientRect(),oldMap=map&&map.getBoundingClientRect();
  townFocus=null;render();
  const shop=document.querySelector('.shop');
  if(!fast&&oldWorld){
   flipTo(oldWorld,oldMap,`translate(${Math.round(mapPan.x)}px,${Math.round(mapPan.y)}px) scale(${mapZoom})`);
   if(shop){shop.classList.add('returning');setTimeout(()=>shop.classList.remove('returning'),CAM_MS+200);}
  }
  townBusy=false;
 },fast?0:160);
}
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&townFocus&&!document.querySelector('#layer .overlay'))townUnfocus();});
// 창 크기가 바뀌면 확대 화면의 지도 틀 높이와 카메라를 다시 맞춘다
window.addEventListener('resize',()=>{
 if(!townFocus||townBusy)return;
 const map=document.getElementById('townViewport');if(!map)return;
 townFocusH=0;map.style.height=focusMapHeight()+'px';
 const w=document.getElementById('townWorld');if(w){w.classList.remove('cam-move');w.style.transform=focusTransform(townFocus);}
});

// ── 주민: 역할별 구역 안에서 자유롭게 걷고, 누르면 손 흔들며 웃고, 꾹 누르면 들어 올려져 버둥거린다 ──
// 걸을 수 있는 곳(발 위치, 지도 좌표 1536×1024). 모두 볼록 다각형이라 안의 두 점을 잇는 직선도 안에 있다.
const WALK_ZONES={
 lot:[[560,560],[980,710],[820,800],[680,840],[380,700]],     // 가운데 주차장·마당
 right:[[1010,730],[1110,650],[1390,800],[1300,860],[1120,840]],// 오른쪽 주차장
 fuel:[[110,318],[160,290],[400,282],[300,312]],              // 주유기 앞 바닥(해상 지붕에 닿지 않게)
 plaza:[[610,510],[660,495],[880,590],[840,625]],             // 렌트카 본관 앞 보도
 wash:[[1150,660],[1230,650],[1330,700],[1250,720]],          // 세차장 앞
};
// 역할: 다닐 구역(가중치), 걷는 속도(지도 px/초), 쉬는 시간(초)
const ROLE={
 guest:{zones:{lot:3,plaza:1},speed:36,rest:[1.2,3.5],shop:.06},
 guest2:{zones:{lot:2,right:2},speed:34,rest:[1.5,4]},
 mechanic:{zones:{wash:2,right:1},speed:42,rest:[1,2.5],repairs:true},
 fuel:{zones:{fuel:1},speed:30,rest:[1.5,4]},
 staff:{zones:{plaza:2,lot:2},speed:38,rest:[1,3],visitsCars:true,shop:.08},
 ruru:{zones:{lot:2,plaza:1},speed:28,rest:[1.5,3.5]},
 visitor:{zones:{lot:2,plaza:2},speed:35,rest:[1.5,4],shop:.3},
 visitor2:{zones:{lot:2,right:1,plaza:1},speed:33,rest:[1.5,4],shop:.25},
};
const roleOf=p=>ROLE[p.ruru?'ruru':p.kind];
const SHOP_APPROACH=[806,568];                     // 본관 문 앞 보도에 있는 출입 시작점
const SHOP_STAY=[10,25];                          // 가게 안에 머무는 시간(초)
const npcRnd=(a,b)=>a+Math.random()*(b-a);
function polyArea(t){return Math.abs((t[1][0]-t[0][0])*(t[2][1]-t[0][1])-(t[2][0]-t[0][0])*(t[1][1]-t[0][1]))/2;}
function randomInPoly(P){                          // 볼록 다각형 안의 고른 점(부채꼴 삼각형으로 나눠 넓이만큼 뽑기)
 const tris=[];for(let k=1;k<P.length-1;k++)tris.push([P[0],P[k],P[k+1]]);
 const areas=tris.map(polyArea),sum=areas.reduce((a,b)=>a+b,0);let r=Math.random()*sum,t=tris[0];
 for(let k=0;k<tris.length;k++){if((r-=areas[k])<=0){t=tris[k];break;}}
 let u=Math.random(),v=Math.random();if(u+v>1){u=1-u;v=1-v;}
 return [t[0][0]+u*(t[1][0]-t[0][0])+v*(t[2][0]-t[0][0]),t[0][1]+u*(t[1][1]-t[0][1])+v*(t[2][1]-t[0][1])];
}
function inPoly(x,y,P){let c=false;for(let i=0,j=P.length-1;i<P.length;j=i++){const[a,b]=P[i],[e,f]=P[j];if((b>y)!==(f>y)&&x<(e-a)*(y-b)/(f-b)+a)c=!c;}return c;}
const walkable=(x,y)=>Object.values(WALK_ZONES).some(P=>inPoly(x,y,P));
// 구역 사이를 잇는 보도·차도(여기로는 지나가기만 하고 목적지로는 고르지 않는다)
const WALK_BRIDGES=[
 [[560,560],[610,510],[840,625],[980,710]],        // 가운데 주차장 ↔ 본관 앞 보도
 [[980,710],[1010,730],[1120,840],[820,800]],      // 가운데 ↔ 오른쪽 주차장
 [[1110,650],[1150,660],[1250,720],[1170,700]],    // 오른쪽 주차장 ↔ 세차장 앞
];
const PATH_GRID=16,PGW=Math.ceil(1536/PATH_GRID),PGH=Math.ceil(1024/PATH_GRID);
const inWalkMask=(x,y)=>walkable(x,y)||WALK_BRIDGES.some(P=>inPoly(x,y,P));
// 주차된 차의 바닥(쿼터뷰 타원, 사람 발 폭만큼 넉넉히)
function carFootprints(){return S.cars.slice(0,cap()).map((c,k)=>{const sl=SLOTS[k],light=M[c.model]&&M[c.model].type==='light';return [sl.x,sl.y+4,light?46:52,27];});}
const onCar=(x,y,E)=>E.some(([cx,cy,rx,ry])=>((x-cx)/rx)**2+((y-cy)/ry)**2<1);
let pathGrid=null,pathGridKey='';
function walkGrid(){
 const E=carFootprints(),key=JSON.stringify(E);if(key===pathGridKey&&pathGrid)return pathGrid;
 const g=new Uint8Array(PGW*PGH);
 for(let gy=0;gy<PGH;gy++)for(let gx=0;gx<PGW;gx++){const x=gx*PATH_GRID+8,y=gy*PATH_GRID+8;g[gy*PGW+gx]=inWalkMask(x,y)&&!onCar(x,y,E)?1:0;}
 pathGridKey=key;return pathGrid={g,E};
}
function freeAt(x,y,{g,E}=walkGrid()){const gx=Math.floor(x/PATH_GRID),gy=Math.floor(y/PATH_GRID);
 return gx>=0&&gy>=0&&gx<PGW&&gy<PGH&&g[gy*PGW+gx]===1&&inWalkMask(x,y)&&!onCar(x,y,E);}
function nearestFreeCell(x,y,{g}=walkGrid()){                      // 가장 가까운 걸을 수 있는 칸(넓혀 가며 찾기)
 const cx=Math.floor(x/PATH_GRID),cy=Math.floor(y/PATH_GRID);
 for(let r=0;r<14;r++)for(let dy=-r;dy<=r;dy++)for(let dx=-r;dx<=r;dx++){
  if(Math.max(Math.abs(dx),Math.abs(dy))!==r)continue;const gx=cx+dx,gy=cy+dy;
  if(gx>=0&&gy>=0&&gx<PGW&&gy<PGH&&g[gy*PGW+gx])return [gx,gy];}
 return null;
}
function clearLine(x0,y0,x1,y1,grid=walkGrid()){const d=Math.hypot(x1-x0,y1-y0),n=Math.ceil(d/6);for(let k=1;k<=n;k++){const s=k/n;if(!freeAt(x0+(x1-x0)*s,y0+(y1-y0)*s,grid))return false;}return true;}
function findPath(x0,y0,x1,y1,grid=walkGrid()){      // A*(8방향). 결과는 꺾이는 점만 남긴 길
 if(!freeAt(x0,y0,grid)||!freeAt(x1,y1,grid))return null;
 if(clearLine(x0,y0,x1,y1,grid))return [[x1,y1]];
 const {g}=grid,s=nearestFreeCell(x0,y0,grid),e=nearestFreeCell(x1,y1,grid);
 const id=(x,y)=>y*PGW+x,start=id(...s),goal=id(...e);
 const gs=new Float32Array(PGW*PGH).fill(Infinity),from=new Int32Array(PGW*PGH).fill(-1),closed=new Uint8Array(PGW*PGH);
 const open=[start];gs[start]=0;const h=i=>Math.hypot(i%PGW-e[0],Math.floor(i/PGW)-e[1]);
 const fs=new Map([[start,h(start)]]);
 while(open.length){
  let bi=0;for(let k=1;k<open.length;k++)if(fs.get(open[k])<fs.get(open[bi]))bi=k;
  const cur=open.splice(bi,1)[0];if(cur===goal)break;closed[cur]=1;
  const cx=cur%PGW,cy=Math.floor(cur/PGW);
  for(const [dx,dy] of [[1,0],[-1,0],[0,1],[0,-1],[1,1],[1,-1],[-1,1],[-1,-1]]){
   const nx=cx+dx,ny=cy+dy;if(nx<0||ny<0||nx>=PGW||ny>=PGH)continue;const ni=id(nx,ny);
   if(!g[ni]||closed[ni])continue;if(dx&&dy&&(!g[id(cx+dx,cy)]||!g[id(cx,cy+dy)]))continue;   // 모서리 끼기 금지
   if(!clearLine(cx*PATH_GRID+8,cy*PATH_GRID+8,nx*PATH_GRID+8,ny*PATH_GRID+8,grid))continue;
   const ng=gs[cur]+(dx&&dy?1.414:1);if(ng<gs[ni]){gs[ni]=ng;from[ni]=cur;fs.set(ni,ng+h(ni));if(!open.includes(ni))open.push(ni);}
  }
 }
 if(from[goal]<0&&goal!==start)return null;
 const cells=[];for(let c=goal;c>=0;c=from[c]){cells.push([c%PGW*PATH_GRID+8,Math.floor(c/PGW)*PATH_GRID+8]);if(c===start)break;}
 cells.reverse();cells.push([x1,y1]);
 const out=[];let cx=x0,cy=y0,k=0;                  // 보이는 곳까지 한 번에 가도록 점 줄이기
 while(k<cells.length){let far=cells.length-1;
  while(far>=k&&!clearLine(cx,cy,...cells[far],grid))far--;
  if(far<k)return null;
  out.push(cells[far]);[cx,cy]=cells[far];k=far+1;}
 return out;
}
function pickZone(role){const ent=Object.entries(role.zones),sum=ent.reduce((a,[,w])=>a+w,0);let r=Math.random()*sum;for(const[k,w]of ent)if((r-=w)<=0)return k;return ent[0][0];}

// ── 사람 그림(24×40, 1px 정수 좌표). mode: walk(걷기·서기) / wave(손 흔들기·웃기) / held(들려서 버둥) / work(렌치질)
const npcPoseCache={};
function npcSprite(kind,mode,f,rear){
 const key=[kind,mode,f,rear].join(':');if(npcPoseCache[key])return npcPoseCache[key];
 const cv=document.createElement('canvas');cv.width=24;cv.height=40;const c=cv.getContext('2d');c.imageSmoothingEnabled=false;
 const box=(x,y,w,h,col)=>{c.fillStyle=col;c.fillRect(x,y,w,h)};
 const colors={guest:['#fff3dd','#292733'],guest2:['#ed91ac','#634038'],mechanic:['#e9b75d','#353040'],fuel:['#ec4055','#302839'],staff:['#234573','#26283d'],visitor:['#6ed2be','#5a3a2a'],visitor2:['#a98be0','#2a2a38']}[kind];
 const [shirt,hair]=colors,ink='#202638',skin='#f7d4aa',shade='#d99a77',mouth='#985d58';
 const rounded=(x,y,w,h,color)=>{box(x+2,y,w-4,h,ink);box(x,y+2,w,h-4,ink);box(x+2,y+1,w-4,h-2,color);box(x+1,y+2,w-2,h-4,color);};
 let stride=0,bob=0;
 if(mode==='walk'){stride=[0,1,2,1,0,-1,-2,-1][f];bob=(f===2||f===6)?1:0;}
 if(mode==='held')stride=[2,-2,1,-1][f%4];
 if(mode==='wave')bob=f%2;
 if(mode!=='held')box(4,37,17,2,'#263d4855');
 // 다리: 들렸을 때는 발이 바닥에서 떨어져 앞뒤로 차며 버둥거린다
 for(const [hip,step,legShade] of [[8,-stride,'#27394f'],[14,stride,'#405974']]){
  let ankle=hip+step*2,footY=35-Math.max(0,-step);
  if(mode==='held'){ankle=hip+step;footY=32+(step>0?1:-1);}
  for(let row=27;row<=footY;row++){const x=Math.round(hip+(ankle-hip)*(row-27)/Math.max(1,footY-27));box(x-2,row,5,1,'#18283b');box(x-1,row,3,1,legShade);}
  box(ankle-2,footY,6,2,'#192a3c');
 }
 const raised=mode==='wave'||mode==='held';
 if(!raised){
  if(mode==='work'){                                // 오른손으로 렌치를 위아래로
   const w=f%2;rounded(3,19,5,9,shirt);box(4,25,3,2,skin);
   if(w){rounded(17,12,5,9,shirt);box(18,10,3,2,skin);box(20,4,2,7,'#9aa3b5');box(19,3,4,2,'#c3cad6');}   // 렌치를 머리 위로
   else{rounded(17,20,5,8,shirt);box(18,27,3,2,skin);box(21,26,2,6,'#9aa3b5');box(20,31,4,2,'#c3cad6');}  // 내려치기
  }else{rounded(3,19-bob+stride,5,9,shirt);rounded(17,19-bob-stride,5,9,shirt);box(4,25-bob+stride,3,2,skin);box(18,25-bob-stride,3,2,shade);}
 }else if(mode==='wave'){rounded(3,19,5,9,shirt);box(4,25,3,2,skin);}
 rounded(6,17-bob,13,13,shirt);
 box(16,20-bob,2,8,kind==='staff'?'#172d53':'#39273930');
 if(!rear){
  box(10,18-bob,5,2,'#fff8e7');
  if(kind==='staff'){box(12,20-bob,2,7,'#85c8fb');box(8,22-bob,2,2,'#d7e8ff');}
  if(kind==='mechanic'){box(9,19-bob,1,8,'#435872');box(15,19-bob,1,8,'#435872');box(9,24-bob,7,4,'#435872');box(11,25-bob,3,1,'#ffe3a8');}
  if(kind==='fuel'){box(14,21-bob,3,2,'#fff0dc');box(8,20-bob,1,8,'#9b263f');}
 }
 rounded(5,3-bob,15,16,hair);
 box(8,5-bob,6,2,kind==='guest2'?'#92624c':'#44445b');
 if(!rear){
  rounded(7,7-bob,13,12,skin);
  box(8,9-bob,2,5,hair);box(8,7-bob,7,2,hair);box(10,9-bob,3,1,hair);
  const y=-bob;
  if(mode==='wave'){                               // 웃는 눈(^ ^)과 올라간 입꼬리
   for(const ex of [11,16]){box(ex,12+y,1,1,ink);box(ex+1,11+y,1,1,ink);box(ex+2,12+y,1,1,ink);}
   box(10,14+y,2,1,'#e7a48c');box(17,14+y,2,1,'#e7a48c');
   box(12,14+y,1,1,mouth);box(13,15+y,4,1,mouth);box(17,14+y,1,1,mouth);box(14,16+y,2,1,'#e98a8a');
  }else if(mode==='held'){                         // 질끈 감은 눈(> <)과 동그란 입, 빨개진 볼
   box(11,10,1,1,ink);box(12,11,1,1,ink);box(11,12,1,1,ink);
   box(18,10,1,1,ink);box(17,11,1,1,ink);box(18,12,1,1,ink);
   box(9,13,3,2,'#f0a0a0');box(17,13,3,2,'#f0a0a0');box(14,14,2,2,mouth);
  }else{
   box(12,11+y,1,2,ink);box(17,11+y,1,2,ink);
   box(10,14+y,2,1,'#e7a48c');box(17,14+y,2,1,'#e7a48c');box(14,15+y,2,1,mouth);
  }
 }
 if(kind==='guest2'){box(5,9-bob,3,10,hair);box(6,10-bob,1,7,'#92624c');}
 if(kind==='fuel'){rounded(5,2-bob,15,7,'#e7344e');box(8,4-bob,6,1,'#ff8290');if(!rear){box(14,8-bob,8,2,ink);box(14,8-bob,6,1,'#ad233d');}}
 if(mode==='wave'){const w=f%2;rounded(19,8+w,5,10,shirt);box(20,5+w,3,3,skin);}     // 오른손을 머리 옆으로 들어 흔든다
 if(mode==='held'){const w=f%2;rounded(0,8+w,5,10,shirt);box(1,5+w,3,3,skin);rounded(19,9-w,5,10,shirt);box(20,6-w,3,3,skin);}
 return npcPoseCache[key]=cv.toDataURL('image/png');
}
// 머리 위 표시: 하트(웃을 때), 땀방울(들렸을 때). 2프레임씩
const emoteCache={};
function emoteSprite(kind,f){
 const key=kind+f;if(emoteCache[key])return emoteCache[key];
 const cv=document.createElement('canvas');cv.width=14;cv.height=14;const c=cv.getContext('2d');
 const px=(x,y,col)=>{c.fillStyle=col;c.fillRect(x,y,1,1)};
 if(kind==='spark'){                              // 렌치가 닿는 순간 튀는 불꽃
  const r=f?5:3;for(let k=-r;k<=r;k++){const col=Math.abs(k)<=1?'#ffffff':Math.abs(k)<r?'#fcdc64':'#f6aa3c';px(7+k,7,col);px(7,7+k,col);}
  if(f){px(3,3,'#fcdc64');px(11,3,'#fcdc64');px(3,11,'#f6aa3c');px(11,11,'#f6aa3c');px(2,7,'#ffffff');px(12,7,'#ffffff');}
  else{px(5,5,'#fcdc64');px(9,5,'#fcdc64');px(5,9,'#fcdc64');px(9,9,'#fcdc64');}
  return emoteCache[key]=cv.toDataURL('image/png');
 }
 if(kind==='wrench'){                             // 머리 위 말풍선 안의 렌치
  const W='#ffffff',K='#202638',G='#9aa3b5',L='#c3cad6';
  for(let x=2;x<=11;x++){px(x,0,K);px(x,10,K);}for(let y=1;y<=9;y++){px(1,y,K);px(12,y,K);for(let x=2;x<=11;x++)px(x,y,W);}
  px(5,11,K);px(6,11,W);px(6,12,K);px(7,11,K);                    // 말풍선 꼬리
  const o=f?0:1;[[3,8],[4,7],[5,6],[6,5],[7,4]].forEach(([x,y])=>{px(x+o,y,G);px(x+o+1,y,L);});
  px(8+o,2,G);px(9+o,3,G);px(9+o,1,L);px(10+o,2,L);px(8+o,3,L);  // 렌치 머리
  return emoteCache[key]=cv.toDataURL('image/png');
 }
 if(kind==='heart'){
  const H=['.##..##.','#rr##rr#','#rrrrrw#','#rrrrrr#','.#rrrr#.','..#rr#..','...##...'];
  H.forEach((row,y)=>[...row].forEach((ch,x)=>{if(ch==='#')px(x+3,y+3+(f?0:1),'#7a1e33');if(ch==='r')px(x+3,y+3+(f?0:1),'#f05a78');if(ch==='w')px(x+3,y+3+(f?0:1),'#ffd0da');}));
 }else{                                            // 땀방울 두세 개가 아래로
  const drop=(x,y)=>{px(x,y,'#68a0f0');px(x-1,y+1,'#68a0f0');px(x,y+1,'#aad6f6');px(x+1,y+1,'#68a0f0');px(x-1,y+2,'#346cd6');px(x,y+2,'#68a0f0');px(x+1,y+2,'#346cd6');px(x,y+3,'#346cd6');};
  drop(3,1+f*2);drop(9,4+f*2);if(f)drop(6,0);
 }
 return emoteCache[key]=cv.toDataURL('image/png');
}

let townAgents=null, cancelTownPress=null;
function mountActors(vp,world){
 cancelTownPress?.();
 const elements=TOWN_ACTORS.map((_,i)=>document.getElementById('townActor'+i));
 Object.values(RURU_FRAMES).flat().forEach(src=>{const im=new Image();im.src=src;});
 const size=TOWN_ACTORS.map(p=>p.ruru?[64,59]:[40,67]);
 const overlap=(i,j,a,b)=>Math.max(Math.abs(a.x-b.x)/((size[i][0]+size[j][0])/2*.7),Math.abs(a.y-b.y)/((size[i][1]+size[j][1])/2*.75));
 // 화면을 다시 그려도 걷던 자리·상태가 이어지도록 상태는 한 번만 만든다
 if(!townAgents)townAgents=TOWN_ACTORS.map((p,i)=>{
  const [x,y]=walkable(p.a[0],p.a[1])?p.a:randomInPoly(WALK_ZONES[pickZone(roleOf(p))]);
  return {x,y,path:null,pi:0,state:'idle',t:npcRnd(.2,2)+i*.3,dist:0,wait:0,nx:0,ny:0,flip:false,rear:false,act:null,lift:0,bounce:0,clock:Math.random()*10};
 });
 const A=townAgents;
 A.forEach(a=>{if(!freeAt(a.x,a.y)&&!['inside','entering','exiting'].includes(a.state)){const c=nearestFreeCell(a.x,a.y);if(c){a.x=c[0]*PATH_GRID+8;a.y=c[1]*PATH_GRID+8;a.path=null;}}});
 // 머리 위 표시 그림
 // NPC 위에서 시작한 터치만 직접 이동용으로 예약한다. 나머지 지도에서는 세로 스크롤을 유지한다.
 const touchTargets=elements.map((el,i)=>{
  if(!el)return null;
  const target=document.createElement('span');
  target.className='town-actor-touch';
  target.setAttribute('aria-hidden','true');
  target.style.width=(size[i][0]*.7/1536*100)+'%';
  target.style.height=(size[i][1]/1024*100)+'%';
  world.appendChild(target);return target;
 });
 const emotes=TOWN_ACTORS.map(()=>{const e=document.createElement('img');e.className='town-emote';e.alt='';e.style.display='none';world.appendChild(e);return e;});
 const sparks=TOWN_ACTORS.map(()=>{const e=document.createElement('img');e.className='town-emote';e.alt='';e.style.display='none';world.appendChild(e);return e;});
 const parked=()=>S.cars.slice(0,cap()).map((c,k)=>({c,sl:SLOTS[k]}));
 function pickTarget(i,grid){
  const role=roleOf(TOWN_ACTORS[i]),a=A[i];
  const target=(x,y,act=null)=>{
   const path=findPath(a.x,a.y,x,y,grid);if(!path)return false;
   a.path=path;a.pi=0;a.act=act;return true;
  };
  a.act=null;a.path=null;
  if(role.repairs){
   for(const repair of parked().filter(o=>o.c.st==='repair')){
    // 아래쪽에 길이 없으면 위쪽 보도에서도 정비한다.
    for(const [ox,oy] of [[16,42],[48,30],[0,48],[-30,26],[0,-32],[16,-32],[32,-24],[-16,-32]]){
     if(target(repair.sl.x+ox,repair.sl.y+oy,'work')){a.workId=repair.c.id;return true;}
    }
   }
  }
  if(role.shop&&Math.random()<role.shop&&target(SHOP_APPROACH[0]+npcRnd(-6,6),SHOP_APPROACH[1],'enter'))return true;
  if(role.visitsCars&&Math.random()<.35){
   const ps=parked();
   if(ps.length){const o=ps[Math.floor(Math.random()*ps.length)];if(target(o.sl.x+60,o.sl.y+14,'car'))return true;}
  }
  for(let k=0;k<12;k++){
   const [x,y]=randomInPoly(WALK_ZONES[pickZone(role)]);
   if(target(x,y))return true;
  }
  return false;
 }
 function update(dt){
  let grid;
  A.forEach((a,i)=>{
   const p=TOWN_ACTORS[i],role=roleOf(p);
   if(a.bounce>0)a.bounce=Math.max(0,a.bounce-dt);
   if(!townMotion&&['walk','idle','work','entering','inside','exiting'].includes(a.state))return;   // 움직임 멈춤: 걷기만 멈추고 누르기 반응은 그대로
   a.clock+=dt;
   if(a.state==='held'){a.lift+=(1-a.lift)*Math.min(1,dt*14);return;}
   if(a.lift>0&&a.state!=='drop')a.lift=Math.max(0,a.lift-dt*6);
   if(a.state==='drop'){a.t-=dt;a.lift=Math.max(0,a.t/.18);if(a.t<=0){a.state='idle';a.t=.5;a.bounce=.35;}return;}
   if(a.state==='wave'){a.t-=dt;if(a.t<=0){a.state='idle';a.t=npcRnd(.4,1);}return;}
   if(a.state==='work'){
    const still=S.cars.some(c=>c.id===a.workId&&c.st==='repair');
    a.t-=dt;if(!still||a.t<=0){if(!still){a.state='idle';a.t=npcRnd(.5,1.2);}else a.t=1;}
    return;
   }
   if(a.state==='entering'){a.t-=dt;a.y-=dt*10;if(a.t<=0){a.state='inside';a.t=npcRnd(...SHOP_STAY);}return;}
   if(a.state==='inside'){a.t-=dt;if(a.t<=0){a.state='exiting';a.t=.45;a.x=SHOP_APPROACH[0]+npcRnd(-6,6);a.y=SHOP_APPROACH[1]-4;a.rear=false;a.flip=Math.random()<.5;}return;}
   if(a.state==='exiting'){a.t-=dt;a.y+=dt*10;if(a.t<=0){a.state='idle';a.t=npcRnd(.6,1.4);}return;}
   grid??=walkGrid();
   if(a.state==='idle'){
    a.t-=dt;
    if(a.t<=0){
     if(pickTarget(i,grid)){a.state='walk';a.wait=0;}
     else a.t=npcRnd(...role.rest);
    }
    return;
   }
   // 지도 갱신으로 길이 무효화되면 다음 유효 목적지를 다시 고른다.
   if(!a.path){a.state='idle';a.t=.4;return;}
   let wp=a.path[a.pi];
   if(a.pi<a.path.length-1&&Math.hypot(wp[0]-a.x,wp[1]-a.y)<4&&clearLine(a.x,a.y,...a.path[a.pi+1],grid)){
    wp=a.path[++a.pi];
   }
   const dx=wp[0]-a.x,dy=wp[1]-a.y,d=Math.hypot(dx,dy);
   if(d<.001&&a.pi<a.path.length-1){a.state='idle';a.t=.4;return;}
   if(a.pi>=a.path.length-1&&d<2&&freeAt(...wp,grid)){
    if(a.act==='enter'){a.state='entering';a.t=.45;a.rear=true;return;}
    a.state=a.act==='work'?'work':'idle';
    a.t=a.act==='work'?1:npcRnd(...role.rest);
    if(a.act==='car'){a.flip=true;a.rear=true;}
    if(a.act==='work'){a.flip=true;a.rear=false;}
    else if(Math.random()<.4)a.flip=!a.flip;
    return;
   }
   const step=Math.min(d,role.speed*dt);
   if(!freeAt(...wp,grid)){a.state='idle';a.t=.4;return;}
   // 부딪힘은 발밑(쿼터뷰라 세로 2배)만 본다. 둘 다 걷는 중이면 번호가 작은 쪽이 먼저(뒤쪽이 양보),
   // 서 있는 사람은 모두가 피해 간다. 잠깐 스쳐 지나가는 중(ghost)에는 사람과 부딪힘을 무시한다
   const feet=(q,b)=>Math.hypot(q.x-b.x,(q.y-b.y)*2);
   const blockedAt=n=>a.ghost>0?false:A.some((b,j)=>j!==i&&!['inside','entering','exiting','held'].includes(b.state)
     &&feet(n,b)<20&&feet(n,b)<feet(a,b)&&(b.state!=='walk'||j<i));
   let moved=null;
   for(const ang of [0,.6,-.6,1.2,-1.2,1.75,-1.75,2.4,-2.4]){
    const cs=Math.cos(ang),sn=Math.sin(ang),ux=(dx/d)*cs-(dy/d)*sn,uy=(dx/d)*sn+(dy/d)*cs;
    const n={x:a.x+ux*step,y:a.y+uy*step};
    if(!clearLine(a.x,a.y,n.x,n.y,grid))continue;
    if(!blockedAt(n)){moved=n;break;}
   }
   if(a.ghost>0)a.ghost-=dt;
   if(!moved){a.wait+=dt;if(a.wait>1.2){a.ghost=.9;a.wait=0;}return;}   // 오래 막히면 잠깐 스쳐 지나가 엉킨 걸 푼다
   a.wait=0;a.x=moved.x;a.y=moved.y;a.dist+=step;
   const fx=dx/d,fy=dy/d;if(fx<-.3)a.flip=true;else if(fx>.3)a.flip=false;if(fy<-.25)a.rear=true;else if(fy>.25)a.rear=false;
  });
  // 가까이 붙으면 서로 살짝 비켜 선다(그림만)
  A.forEach((a,i)=>{let px=0,py=0;A.forEach((b,j)=>{if(i===j||a.state==='inside'||b.state==='inside'||a.state==='entering'||a.state==='exiting')return;const v=overlap(i,j,a,b);if(v>=1.2)return;
   const ddx=a.x-b.x,ddy=a.y-b.y,L=Math.hypot(ddx,ddy)||1,k=(1.2-v)/1.2*16;px+=ddx/L*k+(L<1?(i<j?-8:8):0);py+=ddy/L*k*.5;});
   a.nx+=(px-a.nx)*.15;a.ny+=(py-a.ny)*.15;});
 }
 function draw(){
  A.forEach((a,i)=>{
   const p=TOWN_ACTORS[i],el=elements[i],em=emotes[i];if(!el)return;
   const held=a.state==='held',liftPx=a.lift*26,hop=a.state==='wave'?Math.abs(Math.sin(a.clock*9))*5:(a.bounce>0?Math.sin(a.bounce/.35*Math.PI)*4:0);
   const x=Math.round(a.x+a.nx),y=Math.round(a.y+a.ny),drawY=Math.round(y-liftPx-hop);
   el.style.left=(x/1536*100)+'%';el.style.top=(drawY/1024*100)+'%';
   el.style.zIndex=held?2450:100+y;
   const wob=held&&p.ruru?Math.sin(a.clock*16)*12:(held?Math.sin(a.clock*14)*4:0);
   el.style.transform=`translate(-50%,-100%) scaleX(${a.flip?-1:1}) rotate(${wob.toFixed(1)}deg)`;
   let src,key;
   if(p.ruru){
    const walking=a.state==='walk';const anim=RURU_FRAMES[walking?'walk':'idle'];
    const fr=walking?Math.floor(a.clock*10)%anim.length:Math.floor(a.clock*(held?8:2))%anim.length;
    src=anim[fr];key=(walking?'w':'i')+fr;
   }else{
    const mode=held?'held':a.state==='wave'?'wave':a.state==='work'?'work':'walk';
    const fr=mode==='walk'?(a.state==='walk'?Math.floor(a.dist/3.8)%8:(a.state==='entering'||a.state==='exiting')?Math.floor(a.clock*8)%8:0):mode==='held'?Math.floor(a.clock*10)%4:Math.floor(a.clock*(mode==='wave'?6:5))%2;
    const rear=mode==='walk'?a.rear:false;
    src=npcSprite(p.kind,mode,fr,rear);key=mode+fr+rear;
   }
   if(el.dataset.frame!==key){el.src=src;el.dataset.frame=key;}
   const target=touchTargets[i];
   target.hidden=Boolean(townFocus)||['inside','entering','exiting'].includes(a.state);
   target.style.left=el.style.left;target.style.top=el.style.top;target.style.zIndex=3100+(held?2048:y);
   el.style.visibility=a.state==='inside'?'hidden':'';
   el.style.opacity=a.state==='entering'?Math.max(0,a.t/.45).toFixed(2):a.state==='exiting'?(1-Math.max(0,a.t/.45)).toFixed(2):'';
   // 머리 위 표시
   // 렌치질: 렌치가 내려칠 때마다 차 쪽 손끝에서 불꽃이 튄다
   const sp=sparks[i],hit=a.state==='work'&&Math.floor(a.clock*5)%2===0;
   if(!hit){sp.style.display='none';}else{sp.style.display='';sp.src=emoteSprite('spark',Math.floor(a.clock*10)%2);
    sp.style.width='2.3%';sp.style.left=((x+(a.flip?-18:18))/1536*100)+'%';sp.style.top=((drawY-10)/1024*100)+'%';sp.style.zIndex=101+y;}
   const show=a.state==='wave'?'heart':held?'sweat':a.state==='work'?'wrench':null;
   // hidden 속성은 .map img{display:block}에 덮이므로 display로 직접 숨긴다
   if(!show){em.style.display='none';}else{
    em.style.display='';const h=p.ruru?59:67,ef=Math.floor(a.clock*(show==='heart'?4:6))%2;
    em.src=emoteSprite(show,show==='wrench'?Math.floor(a.clock*2)%2:ef);
    em.style.width=show==='wrench'?'2.4%':'';            // 렌치 말풍선은 크게
    em.style.left=((x+(show==='sweat'?(a.flip?-16:16):0))/1536*100)+'%';
    em.style.top=((drawY-h-(show==='heart'?4:show==='wrench'?2:-14))/1024*100)+'%';
    em.style.zIndex=held?2460:2400;
   }
  });
 }
 // ── 누르기: 짧게 → 손 흔들며 웃기, 꾹(0.35초) → 들어 올리기. 사람이 차 버튼 위에 있어도 사람을 먼저 잡는다
 const toMap=e=>{const r=world.getBoundingClientRect();return [(e.clientX-r.left)/r.width*1536,(e.clientY-r.top)/r.height*1024];};
 function hitActor(e){
  let best=-1,bz=-1;
  elements.forEach((el,i)=>{if(!el||['inside','entering','exiting'].includes(A[i].state))return;const r=el.getBoundingClientRect();
   if(e.clientX>=r.left+r.width*.15&&e.clientX<=r.right-r.width*.15&&e.clientY>=r.top&&e.clientY<=r.bottom){const z=+el.style.zIndex||0;if(z>bz){bz=z;best=i;}}});
  return best;
 }
 let press=null, clickPointer=null;
 const observer=new MutationObserver(()=>{if(!vp.isConnected)cancelPress();});
 function finishPress(completed){
  if(!press)return;
  const {i,id,timer,held}=press,a=A[i];
  press=null;
  clearTimeout(timer);
  observer.disconnect();
  if(cancelTownPress===cancelPress)cancelTownPress=null;
  if(vp.hasPointerCapture(id))vp.releasePointerCapture(id);
  if(held){
   if(!completed||!freeAt(a.x,a.y)){[a.x,a.y]=a.home;}
   a.state=completed?'drop':'idle';a.t=completed ? .18 : .5;
   if(!completed)a.lift=0;
   a.path=null;
  }else if(completed){a.state='wave';a.t=1.6;a.flip=false;}
  clickPointer=completed?id:null;
 }
 function cancelPress(){finishPress(false);}
 vp.addEventListener('pointerdown',e=>{
  clickPointer=null;
  if(press){e.stopPropagation();return;}
  if(e.button!==0||townFocus||townBusy)return;
  const i=hitActor(e);if(i<0)return;
  e.stopPropagation();e.preventDefault();
  try{vp.setPointerCapture(e.pointerId);}catch(_){}
  const a=A[i];
  if(!looping){prev=performance.now();looping=true;townRAF=requestAnimationFrame(tick);}
  const current={i,id:e.pointerId,x:e.clientX,y:e.clientY,held:false,timer:setTimeout(()=>{
   if(press!==current||!vp.isConnected)return;
   current.held=true;a.home=[a.x,a.y];a.state='held';a.lift=0;
   const [mx,my]=toMap(e);a.grab=[a.x-mx,a.y-my];
  },350)};
  press=current;
  cancelTownPress=cancelPress;
  observer.observe(document.getElementById('screen'),{childList:true});
 },true);
 vp.addEventListener('pointermove',e=>{
  if(!press)return;
  e.stopPropagation();
  if(e.pointerId!==press.id)return;
  if(!press.held){
   if(Math.hypot(e.clientX-press.x,e.clientY-press.y)>8)cancelPress();
   return;
  }
  const a=A[press.i],[mx,my]=toMap(e);
  a.x=Math.max(20,Math.min(1516,mx+a.grab[0]));a.y=Math.max(80,Math.min(1010,my+a.grab[1]));
 },true);
 function release(e){
  if(!press||e.pointerId!==press.id)return;
  e.stopPropagation();
  finishPress(e.type==='pointerup');
 }
 vp.addEventListener('pointerup',release,true);
 vp.addEventListener('pointercancel',release,true);
 vp.addEventListener('lostpointercapture',release,true);
 vp.addEventListener('click',e=>{
  if(clickPointer===null||e.detail===0||e.pointerId!==clickPointer)return;
  e.stopPropagation();e.preventDefault();clickPointer=null;
 },true);
 let prev=performance.now(),looping=true;
 const reacting=()=>A.some(a=>a.state==='wave'||a.state==='held'||a.state==='drop'||a.bounce>0);
 function tick(now){
  if(!vp.isConnected){cancelPress();looping=false;return;}
  const dt=document.hidden?0:Math.min((now-prev)/1000,.1);prev=now;
  if(dt>0)update(dt);
  draw();
  // 움직임 멈춤이어도 손 흔들기·들기 반응이 끝날 때까지는 계속 그린다
  if(townMotion||reacting()||press)townRAF=requestAnimationFrame(tick);else looping=false;
 }
 tick(prev);
}
