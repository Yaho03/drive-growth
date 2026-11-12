// Display-only map controls and actors; never mutate economy state.
let mapZoom=1, mapPan={x:0,y:0}, townRAF=0, actorClock=0;
let townMotion=!window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const TOWN_ACTORS=[
 {name:'손님 · 산책',kind:'guest',a:[595,580],b:[825,695],duration:22,offset:0},
 {name:'손님 · 방문',kind:'guest2',a:[745,750],b:[945,850],duration:24,offset:8},
 {name:'정비사 · 순찰',kind:'mechanic',a:[1175,660],b:[1285,715],duration:16,offset:4},
 {name:'주유소 직원',kind:'fuel',a:[210,285],b:[320,340],duration:18,offset:6},
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
 function tick(now){
  if(!vp.isConnected)return;
  if(townMotion&&!document.hidden)actorClock+=Math.min((now-prev)/1000,.1);prev=now;
  TOWN_ACTORS.forEach((p,i)=>{
   const el=elements[i],phase=((actorClock+p.offset)/p.duration)%1;
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
   const x=Math.round(p.a[0]+(p.b[0]-p.a[0])*t),y=Math.round(p.a[1]+(p.b[1]-p.a[1])*t);
   el.style.left=(x/1536*100)+'%';el.style.top=(y/1024*100)+'%';
   el.style.transform=`translate(-50%,-100%) scaleX(${flip?-1:1})`;
   const anim=p.ruru?RURU_FRAMES[walking?'walk':'idle']:npcFrames(p.kind,flip);
   const frame=walking?(p.ruru?Math.floor(actorClock*10):Math.floor(t*Math.hypot(p.b[0]-p.a[0],p.b[1]-p.a[1])/3.8))%anim.length:p.ruru?Math.floor(actorClock*2)%anim.length:0;
   const key=(walking?'walk':'idle')+frame+(flip?'-back':'');
   if(el.dataset.frame!==key){el.src=anim[frame];el.dataset.frame=key;}
  });
  if(townMotion)townRAF=requestAnimationFrame(tick);
 }
 tick(prev);
}
