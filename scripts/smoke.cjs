/* Local-only integration fixture. Never point this script at a production server directory. */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawn, spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');
const mc = require('minecraft-protocol');
const nbt = require('prismarine-nbt');
const repo = path.resolve(__dirname, '..');
const dir = fs.mkdtempSync(path.join(process.env.MK_SMOKE_PARENT || os.tmpdir(), 'mk-ranks-smoke-'));
const paper = process.env.PAPER_JAR;
if (!paper || !fs.existsSync(paper)) throw new Error('Set PAPER_JAR to a Paper 1.21.11 server jar. Running this fixture accepts the Minecraft EULA for the local test server.');
const port = Number(process.env.MK_SMOKE_PORT || 25586);
fs.mkdirSync(path.join(dir, 'plugins', 'MKSidebarRanks'), { recursive: true });
fs.copyFileSync(paper, path.join(dir, 'paper.jar'));
fs.copyFileSync(path.join(repo, 'target', 'mk-sidebar-ranks-1.3.1.jar'), path.join(dir, 'plugins', 'mk-sidebar-ranks.jar'));
const defaultConfig = fs.readFileSync(path.join(repo, 'src/main/resources/config.yml'), 'utf8');
const configPath = path.join(dir, 'plugins/MKSidebarRanks/config.yml');
fs.writeFileSync(configPath, fs.readFileSync(path.join(repo, 'src/main/resources/config-v3.yml'), 'utf8'));
fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
fs.writeFileSync(path.join(dir, 'server.properties'), `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-players=10\npause-when-empty-seconds=-1\n`);
function offlineUuid(name) {
  const bytes = crypto.createHash('md5').update('OfflinePlayer:' + name).digest(); bytes[6] = (bytes[6] & 15) | 48; bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytes.toString('hex'); return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
fs.mkdirSync(path.join(dir, 'world/stats'), { recursive: true });
fs.writeFileSync(path.join(dir, `world/stats/${offlineUuid('VeteranTester')}.json`), JSON.stringify({ stats: { 'minecraft:custom': { 'minecraft:play_time': 1728000 } }, DataVersion: 4671 }));
let child, fullLog = '', clients = [];
const observations = [], results = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitFor(check, label, timeout = 15000) {
  const start = Date.now(); while (!check()) { if (Date.now() - start > timeout) throw new Error(`Timed out: ${label}\n${fullLog.slice(-3000)}`); await sleep(100); }
}
function component(raw) {
  if (raw == null) return null;
  try { return nbt.simplify(raw); } catch { return raw; }
}
function text(raw) {
  if (raw == null) return '';
  if (typeof raw === 'string') return raw;
  if (typeof raw === 'number') return String(raw);
  if (Array.isArray(raw)) return raw.map(text).join('');
  if (typeof raw[''] === 'string') return raw['']; // anonymous NBT string component
  if (raw.text != null || raw.extra != null) return (raw.text || '') + text(raw.extra || []);
  if (raw.translate) return raw.translate + text(raw.with || []);
  return '';
}
function command(line) { child.stdin.write(line + '\n'); }
async function boot() {
  fullLog = ''; child = spawn(process.env.JAVA_BIN || 'java', ['-Xms512M', '-Xmx1536M', '-jar', 'paper.jar', '--nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  child.stdout.on('data', data => { fullLog += data; }); child.stderr.on('data', data => { fullLog += data; });
  child.on('error', error => { fullLog += error.stack; });
  await waitFor(() => fullLog.includes('Done ('), 'Paper startup', 180000);
  assert(fullLog.includes('MK Sidebar & Ranks enabled'), 'Plugin must enable successfully');
  console.log('Paper ready:', dir);
}
async function stop() {
  clients.forEach(c => c.client.end()); clients = [];
  if (child && child.exitCode == null) { command('stop'); await waitFor(() => child.exitCode != null, 'Paper shutdown', 60000); }
  fs.appendFileSync(path.join(dir, 'smoke-server.log'), fullLog);
}
async function connect(name) {
  const state = { name, client: mc.createClient({ host: '127.0.0.1', port, username: name, auth: 'offline', version: '1.21.11' }), teams: new Map(),lines: new Map(), tabs: new Map(), objectives: [], messages: [], sounds: [], footer: null, positions: [], packetCounts: {} };
  clients.push(state);
  state.client.on('error', error => { state.error = error; });
  state.client.on('packet', (packet, meta) => { state.packetCounts[meta.name] = (state.packetCounts[meta.name] || 0) + 1; if(meta.name==='sound_effect' || meta.name==='entity_sound_effect') state.sounds.push(packet); });
  state.client.on('playerlist_header', p => { state.footer=component(p.footer); });
  trackTeams(state);
  state.client.on('scoreboard_objective', p => { state.objectives.push({ ...p, component: component(p.displayText) }); });
  state.client.on('scoreboard_score', p => { state.lines.set(p.itemName, { ...p, component: component(p.display_name) }); });
  state.client.on('player_info', p => { for (const entry of p.data) if (entry.displayName != null) state.tabs.set(entry.uuid, component(entry.displayName)); });
  state.client.on('system_chat', p => state.messages.push(component(p.content)));
  state.client.on('position', p => { state.positions.push(p); state.client.write('teleport_confirm', { teleportId: p.teleportId }); });
  await waitFor(() => state.lines.size >= 9 || state.error, `${name} sidebar`, 30000);
  if (state.error) throw state.error;
  return state;
}
function line(state, i) { return text(state.lines.get('mk_line_' + i)?.component); }
function tab(state, name) { return text(state.tabs.get(offlineUuid(name))); }
function trackTeams(state){state.client.on('teams',p=>{
  if(p.mode==='remove'){state.teams.delete(p.team);return;}
  let team=state.teams.get(p.team);
  if(p.mode==='add'){team={prefix:component(p.prefix),players:new Set(p.players||[]),visibility:p.nameTagVisibility};state.teams.set(p.team,team);}
  if(!team)return;
  if(p.mode==='change'){team.prefix=component(p.prefix);team.visibility=p.nameTagVisibility;}
  if(p.mode==='join')for(const name of p.players){for(const other of state.teams.values())other.players.delete(name);team.players.add(name);}
  if(p.mode==='leave')for(const name of p.players)team.players.delete(name);
});}
function head(state,name){return [...state.teams.values()].find(team=>team.players.has(name));}
function headText(state,name){const team=head(state,name);return team?text(team.prefix)+name:'';}
function announcements(state, name) { return state.messages.filter(m => text(m).includes(name) && text(m).includes('ranked up to')).length; }
// minecraft-data sound IDs are one-based; decoded registry holder IDs are zero-based.
const rankSoundId=require('minecraft-data')('1.21.11').soundsByName['ui.toast.challenge_complete'].id-1;
function rankSounds(state) { return state.sounds.filter(p => p.sound?.soundId === rankSoundId || JSON.stringify(p.sound).includes('ui.toast.challenge_complete')).length; }
function pass(label) { results.push(label); console.log('PASS:', label); }
function snapshot(state, label) { observations.push({ label, name: state.name, footer: state.footer, promotionSoundCount: rankSounds(state), overhead:[...state.teams.values()].map(t=>({prefix:t.prefix,players:[...t.players],visibility:t.visibility})),lines: [...state.lines.values()].map(p => ({ order: p.value, component: p.component })), title: state.objectives.at(-1)?.component, tabs: [...state.tabs].map(([uuid, value]) => ({ uuid, component: value })) }); }

(async () => {
  try {
    await boot();
    assert(fs.readdirSync(path.dirname(configPath)).some(name=>name.startsWith('config-before-v1.3.1-')));
    assert(fs.readFileSync(configPath,'utf8').includes('MK/108e'));
    pass('Existing v1.1.1 default config upgraded and backed up automatically');
    const owner = await connect('OwnerTester'), fresh = await connect('NewTester');
    assert.match(line(fresh,1),/^Rank: New Player$/);assert.match(line(fresh,2),/^Health: 20\.0\/20\.0$/);
    assert.match(line(fresh,3),/^XYZ: -?\d+ -?\d+ -?\d+$/);assert.match(line(fresh,0),/^Name: NewTester$/);
    assert.match(line(fresh,4),/^Hours: \d+\.\d{2}$/);
    pass('Default compact sidebar includes the viewing player\'s hours played');
    await waitFor(()=>text(fresh.footer)==='Credits: MK/108e' && text(owner.footer)==='Credits: MK/108e','Tab credits');
    pass('Compact inline label/value rows and single XYZ row; exact MK/108e Tab credits');
    assert.equal(owner.objectives.find(o => o.name === 'mk_sidebar' && o.number_format === 0)?.number_format, 0);
    pass('Right-side sidebar, health, unique blank lines, and hidden score numbers');
    command('mkrank set OwnerTester owner');
    await waitFor(() => line(owner, 1).includes('OWNER') && tab(fresh, 'OwnerTester').includes('[OWNER]'), 'owner labels');
    await waitFor(()=>rankSounds(owner)===1 && rankSounds(fresh)===1,'manual OWNER sound to everyone');
    await waitFor(()=>headText(fresh,'OwnerTester')==='[OWNER] OwnerTester' && headText(owner,'NewTester')==='[New Player] NewTester','overhead rank badges');
    assert(JSON.stringify(head(fresh,'OwnerTester').prefix).includes('red'));assert.equal(head(fresh,'OwnerTester').visibility,'always');
    pass('Colored OWNER and New Player badges appear beside names on observer scoreboards');
    pass('Manual rank assignment plays one celebration sound for every online player');
    assert.match(tab(fresh, 'OwnerTester'), /OwnerTester.*\d+ms/);
    assert(JSON.stringify(owner.lines.get('mk_line_1').component).includes('red'));
    pass('Red OWNER rank and numeric ping visible to other players in Tab');
    const veteran = await connect('VeteranTester');
    await waitFor(() => line(veteran, 1).includes('OG Player') && announcements(fresh, 'VeteranTester') === 1, 'existing playtime promotion');
    assert.equal(announcements(owner, 'VeteranTester'), 1);
    await waitFor(()=>headText(fresh,'VeteranTester')==='[OG Player] VeteranTester','imported OG nametag');assert.match(line(veteran,4),/^Hours: 24\.\d{2}$/);
    pass('Imported playtime appears as hours; automatic OG rank reaches overhead tags');
    await waitFor(()=>rankSounds(owner)===2 && rankSounds(fresh)===2 && rankSounds(veteran)===1,'rank-up sound for everyone');
    pass('Every online player receives the rank-up sound alongside the announcement');
    pass('Existing 24-hour playtime imported; OG announcement broadcast to everyone');
    snapshot(owner, 'default-owner'); snapshot(fresh, 'default-new'); snapshot(veteran, 'default-og');
    await sleep(1200);const stable = fresh.packetCounts.scoreboard_objective,stableTeams=fresh.packetCounts.teams; await sleep(2200);
    assert.equal(fresh.packetCounts.scoreboard_objective, stable);
    assert.equal(fresh.packetCounts.teams,stableTeams);pass('Unchanged overhead tags send no repeated team updates');
    pass('Sidebar objective remains stable during updates');
    const shortConfig = defaultConfig.replace('hours: 24', 'hours: 0.003'); fs.writeFileSync(configPath, shortConfig); command('mksb reload');
    await waitFor(() => line(fresh, 1).includes('OG Player') && announcements(owner, 'NewTester') === 1, 'connected-time promotion', 20000);
    assert.equal(announcements(fresh, 'NewTester'), 1); assert.match(line(owner, 1), /OWNER/);
    await waitFor(()=>headText(owner,'NewTester')==='[OG Player] NewTester','connected-time overhead promotion');
    await waitFor(()=>rankSounds(owner)===3 && rankSounds(fresh)===3 && rankSounds(veteran)===2,'second promotion sound');
    pass('Connected playtime promotes once; manual OWNER stays protected');
    command('mkrank create builder #55ffff Master Builder'); command('mkrank set NewTester builder');
    await waitFor(() => line(fresh, 1).includes('Master Builder') && tab(owner, 'NewTester').includes('[Master Builder]'), 'custom rank');
    await waitFor(()=>rankSounds(owner)===4 && rankSounds(fresh)===4 && rankSounds(veteran)===3,'custom assignment sound');
    await waitFor(()=>headText(owner,'NewTester')==='[Master Builder] NewTester','custom overhead label');
    pass('Custom rank assignment also sounds for every online player');
    command('mkrank set NewTester builder'); await sleep(500);
    assert.equal(rankSounds(owner),4);assert.equal(rankSounds(fresh),4);assert.equal(rankSounds(veteran),3);
    pass('Reassigning the same rank does not replay the sound');
    command('mkrank color builder light_purple');
    await waitFor(() => JSON.stringify(fresh.lines.get('mk_line_1').component).includes('light_purple'), 'custom color');
    await waitFor(()=>JSON.stringify(head(owner,'NewTester')?.prefix).includes('light_purple'),'custom overhead color');pass('Custom labels and rank color changes update overhead tags');
    assert.equal(rankSounds(owner),4);assert.equal(rankSounds(fresh),4);assert.equal(rankSounds(veteran),3);
    command('mkrank reset NewTester'); await waitFor(() => line(fresh, 1).includes('OG Player'), 'reset earned rank');
    await waitFor(()=>rankSounds(owner)===5 && rankSounds(fresh)===5 && rankSounds(veteran)===4,'reset sound');
    command('mkrank reset NewTester');await sleep(500);
    assert.equal(rankSounds(owner),5);assert.equal(rankSounds(fresh),5);assert.equal(rankSounds(veteran),4);
    pass('Reset sounds once when the displayed rank changes; repeat reset stays silent');
    assert.equal(announcements(owner, 'NewTester'), 1); pass('Custom rank creation, color changes, assignment, and reset');
    fresh.client.write('chat_command', { command: 'mkrank set NewTester owner' });
    await sleep(500); assert.match(line(fresh, 1), /OG Player/);
    assert.equal(rankSounds(owner),5);
    pass('Non-operator cannot assign OWNER');
    fresh.client.write('chat_command', { command: 'mksb toggle' });
    await waitFor(() => fresh.messages.some(m => text(m).includes('Sidebar hidden.')), 'sidebar toggle');
    await waitFor(()=>headText(fresh,'OwnerTester')==='[OWNER] OwnerTester' && headText(fresh,'NewTester')==='[OG Player] NewTester','overhead with sidebar hidden');pass('Hiding the sidebar keeps overhead ranks active on the restored scoreboard');
    assert.match(tab(owner, 'NewTester'), /OG Player/); pass('Personal sidebar toggle leaves Tab formatting active');
    command('tp OwnerTester -125.5 64 -320.5'); await waitFor(() => line(owner,3)==='XYZ: -126 64 -321', 'negative coordinates');
    pass('Coordinates use block positions, including negative values');
    fs.writeFileSync(configPath, shortConfig.replace('hours: 0.003', 'hours: -1')); command('mksb reload');
    await waitFor(() => fullLog.includes('promotion.hours must be a positive number'), 'invalid config rejected');
    assert.match(line(owner, 1), /OWNER/); pass('Invalid reload retains working configuration');
    // Configure every original placeholder across two valid sidebar pages.
    const groups = [
      ['player_name','player_displayname','player_health','player_max_health','player_food','player_level','player_exp','player_ping','player_ping_color','player_world','player_gamemode','player_x','player_y','player_z','player_deaths'],
      ['player_kills','player_blocks_walked','player_playtime_hours','player_armor','player_direction','player_item_in_hand','player_biome','player_rank','player_rank_badge','server_name','server_online','server_max_players','server_tps','server_uptime']
    ];
    for (const keys of groups) {
      const yaml = shortConfig.replace(/  lines:\n[\s\S]*?\nnametags:/, '  lines:\n' + keys.map(k => `    - '${k}: %${k}%'`).join('\n') + '\nnametags:');
      fs.writeFileSync(configPath, yaml); command('mksb reload');
      await waitFor(() => line(owner, 0).startsWith(keys[0] + ':'), 'placeholder page');
      for (let i = 0; i < keys.length; i++) { assert(line(owner, i).startsWith(keys[i] + ':')); assert(!line(owner, i).includes('%')); assert(!line(owner, i).includes('<mk_')); }
      snapshot(owner, 'placeholder-page-' + groups.indexOf(keys));
    }
    pass('All requested and added placeholders resolve on real Paper players');
    // Restore defaults for display tests, retaining a tiny test-only threshold.
    fs.writeFileSync(configPath, shortConfig + '\n'); command('mksb reload'); await sleep(600);
    const mutedConfig=shortConfig.replace('sound:\n    enabled: true','sound:\n    enabled: false');
    fs.writeFileSync(configPath,mutedConfig);command('mksb reload');await waitFor(()=>fullLog.includes('Configuration reloaded.'),'muted reload');await sleep(600);
    command('mkrank set OwnerTester new_player');await waitFor(()=>line(owner,1).includes('New Player'),'muted assignment');
    command('mkrank set OwnerTester owner');await waitFor(()=>line(owner,1).includes('OWNER'),'muted restoration');await sleep(500);
    assert.equal(rankSounds(owner),5);assert.equal(rankSounds(fresh),5);assert.equal(rankSounds(veteran),4);
    pass('Disabling promotion.sound also mutes manual rank changes');
    fs.writeFileSync(configPath,shortConfig);command('mksb reload');await sleep(600);
    // Reapply a custom rank to exercise offline changes after a clean shutdown.
    command('mkrank create vip gold VIP'); await waitFor(() => fullLog.includes('Created rank vip.'), 'persisted custom definition');
    veteran.client.end(); await waitFor(() => fullLog.includes('VeteranTester lost connection:'), 'offline veteran');
    await waitFor(()=>!head(owner,'VeteranTester'),'disconnected overhead entry removed');pass('Disconnected player entries are removed from overhead teams');
    command('mkrank set VeteranTester vip'); await waitFor(() => fullLog.includes('Assigned vip to VeteranTester.'), 'offline assignment');
    await waitFor(()=>rankSounds(owner)===6 && rankSounds(fresh)===6,'offline assignment sound');
    assert.equal(rankSounds(veteran),4);
    pass('Offline rank changes sound only for the players currently online');
    command('mkrank reset OwnerTester');await waitFor(()=>line(owner,1).includes('OG Player'),'eligible reset promotion');
    await waitFor(()=>rankSounds(owner)===7 && rankSounds(fresh)===7,'eligible reset single sound');await sleep(600);
    assert.equal(rankSounds(owner),7);assert.equal(rankSounds(fresh),7);
    pass('Reset triggering an automatic promotion plays one sound without duplication');
    command('mkrank set OwnerTester owner');await waitFor(()=>line(owner,1).includes('OWNER') && rankSounds(owner)===8 && rankSounds(fresh)===8,'restore OWNER');
    await stop();
    const oldId=crypto.randomUUID();
    const duplicate=spawnSync(process.env.JAVA_BIN||'java',['-cp',path.join(repo,'target/mk-sidebar-ranks-1.3.1.jar'),path.join(__dirname,'insert-duplicate.java'),path.join(dir,'plugins/MKSidebarRanks/players.db'),oldId,'OwnerTester'],{encoding:'utf8',windowsHide:true});
    assert.equal(duplicate.status,0,duplicate.stderr||duplicate.stdout);
    await boot(); const owner2 = await connect('OwnerTester');
    const hidden = { name: 'NewTester', client: mc.createClient({ host: '127.0.0.1', port, username: 'NewTester', auth: 'offline', version: '1.21.11' }), teams:new Map(),messages: [], tabs: new Map(), lines: new Map() }; clients.push(hidden);trackTeams(hidden);
    hidden.client.on('error', e => { hidden.error = e; }); hidden.client.on('position', p => hidden.client.write('teleport_confirm', { teleportId: p.teleportId }));
    hidden.client.on('system_chat', p => hidden.messages.push(component(p.content)));hidden.client.on('scoreboard_score', p => hidden.lines.set(p.itemName, p));
    hidden.client.on('player_info', p => { for(const e of p.data) if(e.displayName != null) hidden.tabs.set(e.uuid,component(e.displayName)); });
    await waitFor(() => tab(owner2, 'NewTester').includes('[OG Player]'), 'saved automatic rank'); await sleep(1500);
    assert.equal(hidden.lines.size, 0); assert.match(line(owner2, 1), /OWNER/); assert.equal(announcements(owner2, 'NewTester'), 0);
    const veteran2 = await connect('VeteranTester');assert.match(line(veteran2, 1), /VIP/); assert.equal(announcements(owner2, 'VeteranTester'), 0);
    await waitFor(()=>headText(hidden,'OwnerTester')==='[OWNER] OwnerTester' && headText(owner2,'VeteranTester')==='[VIP] VeteranTester','restored nametags');pass('Overhead ranks restore after restart, including offline rank assignments');
    assert.equal(rankSounds(owner2),0);assert.equal(rankSounds(veteran2),0);assert.equal(text(owner2.footer),'Credits: MK/108e');
    pass('Restart preserves OWNER, OG, offline custom assignments, hidden sidebar, and announcement markers');
    command('mkrank info OwnerTester');await waitFor(()=>fullLog.includes(`OwnerTester | ${offlineUuid('OwnerTester')}`),'online info selects current UUID');
    command('mkrank set OwnerTester vip');await waitFor(()=>line(owner2,1).includes('VIP') && headText(veteran2,'OwnerTester')==='[VIP] OwnerTester','online rank assignment with duplicate saved name');
    assert(!fullLog.includes('That name matches multiple records. Use a UUID.'));
    command('mkrank set OwnerTester owner');await waitFor(()=>line(owner2,1).includes('OWNER'),'restore OWNER after duplicate-name check');
    pass('Online name commands choose the connected UUID when historical duplicate records exist');
    hidden.client.write('chat_command', { command: 'mksb toggle' }); await waitFor(() => hidden.lines.size >= 9, 'restored sidebar');
    const noTags=fs.readFileSync(configPath,'utf8').replace('nametags:\n  enabled: true','nametags:\n  enabled: false');fs.writeFileSync(configPath,noTags);command('mksb reload');await waitFor(()=>!head(owner2,'VeteranTester')&&!head(veteran2,'OwnerTester'),'nametags independently disabled');assert.match(line(owner2,1),/OWNER/);pass('Independent nametag switch removes owned teams while retaining sidebar and Tab');
    // Disabling formatting restores original Tab names and clears our sidebar.
    const disabled = fs.readFileSync(configPath,'utf8').replace('enabled: true','enabled: false').replace('tab:\n  enabled: true','tab:\n  enabled: false');fs.writeFileSync(configPath, disabled);command('mksb reload');
    await waitFor(() => tab(owner2,'OwnerTester') === 'OwnerTester', 'original Tab name restored');
    await waitFor(()=>text(owner2.footer)==='', 'credits footer released');
    pass('Independent configuration switches release sidebar and Tab ownership');
    await stop();
    const errors = fs.readFileSync(path.join(dir, 'smoke-server.log'),'utf8').split('\n').filter(line => /ERROR|SEVERE/.test(line) && /MKSidebar|MK Sidebar|sidebar\./.test(line));
    assert.equal(errors.length,0,errors.join('\n'));
    const report = { paper: '1.21.11 build 132', result: 'PASS', results, observations, fixture: dir };
    fs.writeFileSync(path.join(dir,'smoke-report.json'),JSON.stringify(report,null,2));
    if(process.env.MK_SMOKE_REPORT) fs.writeFileSync(process.env.MK_SMOKE_REPORT,JSON.stringify(report,null,2));
    console.log('ALL SMOKE CHECKS PASSED:', results.length);
  } catch (error) {
    fs.writeFileSync(path.join(dir,'sound-packets.json'),JSON.stringify(clients.map(s=>({name:s.name,sounds:s.sounds})),(_,v)=>typeof v==='bigint'?String(v):v,2));
    console.error(error.stack); fs.writeFileSync(path.join(dir,'smoke-failure.log'),fullLog); try { await stop(); } catch(e) { console.error(e.message); if(child) child.kill(); }
    process.exitCode = 1;
  }
})();
