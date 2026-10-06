/* Local-only test: compares disabled displays, enabled static displays, and moving players. */
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),{DatabaseSync}=require('node:sqlite'),mc=require('minecraft-protocol');
const repo=path.resolve(__dirname,'..'),dir=fs.mkdtempSync(path.join(process.env.MK_SMOKE_PARENT||os.tmpdir(),'mk-ranks-load-'));
const count=Number(process.env.MK_LOAD_PLAYERS||64),port=Number(process.env.MK_LOAD_PORT||25587);
assert(Number.isInteger(count)&&count>=1&&count<=200,'MK_LOAD_PLAYERS must be 1–200');
assert(process.env.PAPER_JAR&&fs.existsSync(process.env.PAPER_JAR),'Set PAPER_JAR to a Paper 1.21.11 jar. Running this local test accepts the Minecraft EULA.');
const config=fs.readFileSync(path.join(repo,'src/main/resources/config.yml'),'utf8');
fs.mkdirSync(path.join(dir,'plugins/MKSidebarRanks'),{recursive:true});fs.copyFileSync(process.env.PAPER_JAR,path.join(dir,'paper.jar'));
fs.copyFileSync(path.join(repo,'target/mk-sidebar-ranks-1.6.0.jar'),path.join(dir,'plugins/mk-sidebar-ranks.jar'));
const cfg=path.join(dir,'plugins/MKSidebarRanks/config.yml');fs.writeFileSync(cfg,config);
if(process.env.MK_LOAD_CACHE)for(const name of ['libraries','versions','cache']){const source=path.join(process.env.MK_LOAD_CACHE,name);if(fs.existsSync(source))fs.cpSync(source,path.join(dir,name),{recursive:true});}
fs.writeFileSync(path.join(dir,'eula.txt'),'eula=true\n');
fs.writeFileSync(path.join(dir,'server.properties'),`server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nmax-players=${count+5}\ngamemode=spectator\ndifficulty=peaceful\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\npause-when-empty-seconds=-1\n`);
let log='',child,clients=[],movement,activity,flips,flipError,flipBusy=false,flipBatches=0,maxConcurrentFlips=0;const measurements=[];
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function wait(check,label,timeout=30000){let started=Date.now();while(!check()){if(Date.now()-started>timeout)throw Error('Timed out: '+label+'\n'+log.slice(-2500));await sleep(100);}}
function command(value){child.stdin.write(value+'\n');}
function packets(){return clients.reduce((sum,s)=>{for(const [key,value] of Object.entries(s.packets))sum[key]=(sum[key]||0)+value;return sum;},{});}
async function connect(index){
  const state={name:'Load'+String(index).padStart(3,'0'),packets:{},scores:0,ended:false};
  state.client=mc.createClient({host:'127.0.0.1',port,username:state.name,auth:'offline',version:'1.21.11'});clients.push(state);
  state.client.on('error',error=>{state.error=error;});state.client.on('end',()=>{state.ended=true;});
  state.client.on('packet',(p,meta)=>{if(['scoreboard_score','player_info','playerlist_header','teams','window_items','set_slot'].includes(meta.name))state.packets[meta.name]=(state.packets[meta.name]||0)+1;});
  state.client.on('open_window',p=>{state.window={id:p.windowId,title:JSON.stringify(p.windowTitle),state:0,items:[]};});
  state.client.on('window_items',p=>{if(state.window?.id===p.windowId){state.window.items=p.items;state.window.state=p.stateId;}});
  state.client.on('set_slot',p=>{if(state.window?.id===p.windowId){state.window.items[p.slot]=p.item;state.window.state=p.stateId;}});
  state.client.on('close_window',p=>{if(state.window?.id===p.windowId)state.window=null;});
  state.client.on('scoreboard_score',()=>state.scores++);
  state.client.on('position',p=>{state.position={x:p.x,y:p.y,z:p.z};state.client.write('teleport_confirm',{teleportId:p.teleportId});});
  await wait(()=>state.scores>=9||state.error||state.ended,state.name+' display');assert(!state.error&&!state.ended,state.name+' must remain connected');
}
function click(p,slot){p.client.write('window_click',{windowId:p.window.id,stateId:p.window.state,slot,mouseButton:0,mode:0,changedSlots:[],cursorItem:undefined});}
async function flipBatch(){
  if(flipBusy)return;flipBusy=true;
  try{
    const pairs=Array.from({length:Math.min(16,Math.floor(count/2))},(_,i)=>[clients[i*2],clients[i*2+1]]);
    for(const [host] of pairs)host.client.write('chat_command',{command:'coinflip create 1'});
    const db=new DatabaseSync(path.join(dir,'plugins/MKSidebarRanks/market.db'));
    try{
      await wait(()=>db.prepare("SELECT COUNT(*) AS n FROM flips WHERE state='WAITING'").get().n===pairs.length,'waiting wagers');
      for(const [,joiner] of pairs)joiner.client.write('chat_command',{command:'coinflip'});
      await wait(()=>pairs.every(([host,p])=>p.window?.title.includes('MK COIN FLIPS')&&p.window.items.some(item=>JSON.stringify(item)?.includes(host.name))),'public lobby heads');
      for(const [host,p] of pairs)click(p,p.window.items.findIndex(item=>JSON.stringify(item)?.includes(host.name)));
      await wait(()=>pairs.every(([,p])=>p.window?.title.includes('CONFIRM COIN FLIP')&&p.window.items[11]),'join confirmations');await sleep(350);
      for(const [,p] of pairs)click(p,11);
      await wait(()=>db.prepare("SELECT COUNT(*) AS n FROM flips WHERE state='RUNNING'").get().n===pairs.length,'simultaneous animated matches');
      maxConcurrentFlips=Math.max(maxConcurrentFlips,pairs.length);flipBatches++;
    }finally{db.close();}
  }finally{flipBusy=false;}
}
async function sample(label){
  const before=packets(),samples=[];console.log('Sampling:',label);
  for(let i=0;i<6;i++){
    await sleep(5000);let offset=log.length;command('mksb performance');
    await wait(()=>log.slice(offset).includes('MK performance:'),'performance report');
    const clean=log.slice(offset).replace(/\x1b\[[0-9;]*m/g,'');
    const m=clean.match(/avg=([\d.]+)ms p95=([\d.]+)ms max=([\d.]+)ms queued=(\d+) server=([\d.]+)ms\/tick tps=([\d.]+) renders=(\d+) tab-updates=(\d+) sidebar-updates=(\d+)/);
    assert(m,'Performance metrics must parse');samples.push({avgMs:+m[1],p95Ms:+m[2],maxMs:+m[3],queued:+m[4],serverMs:+m[5],tps:+m[6],renders:+m[7],tabUpdates:+m[8],sidebarUpdates:+m[9]});
    assert(clients.every(s=>!s.error&&!s.ended),'All clients stay connected: '+clients.filter(s=>s.error||s.ended).map(s=>s.name+': '+(s.error?.message||'disconnected')).join(', '));
  }
  const after=packets(),delta={};for(const key of Object.keys(after))delta[key]=after[key]-(before[key]||0);
  const row={scenario:label,seconds:30,players:count,samples,incomingDisplayPacketsAcrossClients:delta};measurements.push(row);console.log('RESULT:',JSON.stringify(row));
  assert(samples.every(s=>s.avgMs<2),'Scheduled plugin work should average less than 2 ms per tick in this fixture');
  assert(samples.every(s=>s.queued<=count),'Update backlog stays bounded');
  assert((delta.playerlist_header||0)===0,'Unchanged credits footer must not be resent');
  assert((delta.teams||0)===0,'Unchanged nametags must not send repeated team packets');
}
(async()=>{
  try{
    child=spawn(process.env.JAVA_BIN||'java',['-Xms512M','-Xmx2048M','-jar','paper.jar','--nogui'],{cwd:dir,stdio:['pipe','pipe','pipe'],windowsHide:true});child.stdout.on('data',s=>{log+=s;});child.stderr.on('data',s=>{log+=s;});
    await wait(()=>log.includes('Done ('),'Paper startup',180000);assert(log.includes('MK Sidebar & Ranks enabled'));console.log('Load fixture:',dir);
    for(let i=0;i<count;i++){await connect(i);await sleep(75);}console.log('Connected clients:',count);
    const disabled=config.replace(/(sidebar:\r?\n\s+enabled:) true/,'$1 false').replace(/(tab:\r?\n\s+enabled:) true/,'$1 false').replace(/(nametags:\r?\n\s+enabled:) true/,'$1 false');
    assert(/sidebar:\r?\n\s+enabled: false/.test(disabled)&&/tab:\r?\n\s+enabled: false/.test(disabled)&&/nametags:\r?\n\s+enabled: false/.test(disabled),'Baseline must disable all displays');
    fs.writeFileSync(cfg,disabled);command('mksb reload');await sleep(12000);await sample('displays-disabled');
    fs.writeFileSync(cfg,config);command('mksb reload');await sleep(12000);await sample('enabled-static');
    let step=0;movement=setInterval(()=>{for(let i=0;i<clients.length;i+=8){let s=clients[i],p=s.position;if(p&&!s.ended)s.client.write('position',{x:p.x+Math.sin(step/3)*4,y:p.y,z:p.z+Math.cos(step/3)*4,flags:{onGround:false,hasHorizontalCollision:false}});}step++;},500);
    await sleep(12000);await sample('enabled-moving');clearInterval(movement);movement=null;
    command('op Load000');await sleep(300);clients[0].client.write('chat_command',{command:'mkmarket leaderboard place'});await sleep(1000);
    activity=setInterval(()=>{
      for(let i=0;i<Math.min(16,clients.length);i++){const next=clients[(i+1)%Math.min(16,clients.length)];clients[i].client.write('chat_command',{command:'pay '+next.name+' 1'});}
      for(let i=16;i<Math.min(24,clients.length);i++)clients[i].client.write('chat_command',{command:'auction'});
    },2000);
    await sleep(4000);await sample('enabled-market-activity');clearInterval(activity);activity=null;
    await sleep(3000);await flipBatch();flips=setInterval(()=>flipBatch().catch(e=>flipError=e),8000);await sample('enabled-animated-coinflips');clearInterval(flips);flips=null;if(flipError)throw flipError;await wait(()=>!flipBusy,'last flip batch');assert(flipBatches>=3,'Repeated batches exercise concurrent animation menus');
    const offset=log.length;command('mkmarket performance');await wait(()=>log.slice(offset).includes('MK market work:'),'market work timing');
    const marketTiming=log.slice(offset).replace(/\x1b\[[0-9;]*m/g,'').match(/MK market work:.*/)?.[0];console.log('MARKET TIMING:',marketTiming);
    assert(!log.includes("Couldn't look up profile properties"),'Textureless lobby heads must not trigger skin lookup requests');assert(!log.includes('Coin flip transaction error')&&!log.includes('Coin flip menu error')&&!log.includes('Market transaction error'),'No plugin errors during load');
    const report={version:'1.6.0',result:'PASS',paper:'1.21.11 build 132',fixture:dir,players:count,cpu:os.cpus()[0].model,javaHeapMiB:2048,measurements,marketTiming,flipBatches,maxConcurrentFlips,limitations:'Loopback protocol clients in a flat spectator world on the same Windows PC; no production-host or internet-latency guarantee. Scheduled loop metrics exclude commands, joins and database worker work. Market timing separately covers commands, menu clicks, callbacks, coin flip animation and maintenance. The activity phase sends 8 payments and 4 menu opens per second for 30 seconds, with a placed text leaderboard. The coin flip phase starts batches of 16 simultaneous animated matches every 8 seconds. Item transfer persistence is covered by the separate survival market fixture.'};
    fs.writeFileSync(path.join(dir,'load-report.json'),JSON.stringify(report,null,2));if(process.env.MK_LOAD_REPORT)fs.writeFileSync(process.env.MK_LOAD_REPORT,JSON.stringify(report,null,2));console.log('LOAD TEST PASSED');
  }catch(error){console.error(error.stack);process.exitCode=1;}
  finally{if(movement)clearInterval(movement);if(activity)clearInterval(activity);if(flips)clearInterval(flips);clients.forEach(s=>s.client.end());if(child&&child.exitCode==null){command('stop');try{await wait(()=>child.exitCode!=null,'shutdown',60000);}catch(e){console.error(e.message);child.kill();}}fs.writeFileSync(path.join(dir,'load-server.log'),log);}
})();
