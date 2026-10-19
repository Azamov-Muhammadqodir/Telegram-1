// Neyrogram admin server — zero dependencies, Node.js 18+
//
// Ishga tushirish:
//   node server.js
// Birinchi ishga tushganda config.json yaratiladi va tokenlar chiqadi.
// Admin panel:  http://<server>:8787/admin
//
// Qurilmalar /d/beat ga PAIR_TOKEN bilan holat yuboradi; admin panel
// ADMIN_TOKEN bilan ularni ko'radi va buyruq beradi. Hammasi SIZNING
// serveringiz va SIZNING qurilmalaringiz uchun.

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DIR = __dirname;
const CONFIG_PATH = path.join(DIR, 'config.json');
const DATA_PATH = path.join(DIR, 'data.json');

function token() { return crypto.randomBytes(16).toString('hex'); }

let config;
if (fs.existsSync(CONFIG_PATH)) {
  config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
} else {
  config = { port: 8787, adminToken: token(), pairToken: token() };
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2));
  console.log('config.json yaratildi.');
}
const PORT = process.env.PORT || config.port || 8787;

// data.json: { devices: { <id>: {...} }, commands: { <id>: [ {...} ] } }
let data = { devices: {}, commands: {} };
if (fs.existsSync(DATA_PATH)) {
  try { data = JSON.parse(fs.readFileSync(DATA_PATH, 'utf8')); } catch (e) {}
}
if (!data.devices) data.devices = {};
if (!data.commands) data.commands = {};

// Chat keshi 1 soatda eskiradi. Serverda saqlanmaydi (faqat xotirada) — ishonchliroq.
const CACHE_TTL = 60 * 60 * 1000;
const cache = {}; // key -> { ts, payload }

function cacheGet(key) {
  const e = cache[key];
  if (!e) return null;
  if (Date.now() - e.ts > CACHE_TTL) { delete cache[key]; return null; }
  return e;
}
function cacheSet(key, payload) {
  cache[key] = { ts: Date.now(), payload };
}

// Bir xil buyruq navbatda bo'lsa qayta qo'shmaydi (panel tez-tez so'raganda yig'ilib qolmasin)
function queueOnce(deviceId, cmd) {
  const q = (data.commands[deviceId] = data.commands[deviceId] || []);
  const dup = q.some((c) => c.type === cmd.type && c.account === cmd.account && c.dialog_id === cmd.dialog_id);
  if (dup) return;
  cmd.id = crypto.randomBytes(4).toString('hex');
  cmd.ts = Date.now();
  q.push(cmd);
  save();
}
// har 10 daqiqada eskirgan keshni tozalash
setInterval(() => {
  const now = Date.now();
  for (const k of Object.keys(cache)) {
    if (now - cache[k].ts > CACHE_TTL) delete cache[k];
  }
}, 10 * 60 * 1000);

let saveTimer = null;
function save() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    fs.writeFile(DATA_PATH, JSON.stringify(data), () => {});
  }, 500);
}

function send(res, code, obj, type) {
  const body = typeof obj === 'string' ? obj : JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': type || 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-Admin-Token',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function readBody(req, cb) {
  let size = 0;
  const chunks = [];
  req.on('data', (c) => {
    size += c.length;
    if (size > 8 * 1024 * 1024) { req.destroy(); return; } // 8 MB limit
    chunks.push(c);
  });
  req.on('end', () => cb(Buffer.concat(chunks)));
}

function json(buf) {
  try { return JSON.parse(buf.toString('utf8')); } catch (e) { return null; }
}

function isAdmin(req, url) {
  const t = req.headers['x-admin-token'] || url.searchParams.get('token');
  return t && t === config.adminToken;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;

  if (req.method === 'OPTIONS') return send(res, 204, '');

  // ---- Qurilma: holat yuborish + buyruqlarni olish ----
  if (p === '/d/beat' && req.method === 'POST') {
    return readBody(req, (buf) => {
      const b = json(buf);
      if (!b || b.pair !== config.pairToken || !b.device_id) return send(res, 403, { ok: false });
      const id = String(b.device_id).slice(0, 64);
      data.devices[id] = {
        id,
        name: String(b.name || 'Qurilma').slice(0, 64),
        app_version: String(b.app_version || '').slice(0, 32),
        accounts: Array.isArray(b.accounts) ? b.accounts.slice(0, 10) : [],
        last_seen: Date.now(),
      };
      const queue = data.commands[id] || [];
      data.commands[id] = []; // yuborilgach o'chiramiz (qurilma ack bilan natijani qaytaradi)
      save();
      send(res, 200, { ok: true, commands: queue });
    });
  }

  // ---- Qurilma: tezkor buyruq pollingi (holat yubormasdan) ----
  if (p === '/d/poll' && req.method === 'POST') {
    return readBody(req, (buf) => {
      const b = json(buf);
      if (!b || b.pair !== config.pairToken || !b.device_id) return send(res, 403, { ok: false });
      const id = String(b.device_id).slice(0, 64);
      if (data.devices[id]) data.devices[id].last_seen = Date.now();
      const queue = data.commands[id] || [];
      data.commands[id] = [];
      if (queue.length) save();
      send(res, 200, { ok: true, commands: queue });
    });
  }

  // ---- Qurilma: buyruq natijasini qaytarish ----
  if (p === '/d/ack' && req.method === 'POST') {
    return readBody(req, (buf) => {
      const b = json(buf);
      if (!b || b.pair !== config.pairToken) return send(res, 403, { ok: false });
      const dev = data.devices[b.device_id];
      if (dev) {
        dev.last_result = { cmd_id: b.cmd_id, result: String(b.result || '').slice(0, 256), ts: Date.now() };
        save();
      }
      send(res, 200, { ok: true });
    });
  }

  // ---- Qurilma: chat ro'yxatini yuklash (kesh) ----
  if (p === '/d/chats' && req.method === 'POST') {
    return readBody(req, (buf) => {
      const b = json(buf);
      if (!b || b.pair !== config.pairToken || !b.device_id) return send(res, 403, { ok: false });
      cacheSet('chats:' + b.device_id + ':' + b.account, { account: b.account, dialogs: b.dialogs || [] });
      send(res, 200, { ok: true });
    });
  }

  // ---- Qurilma: bitta chat xabarlarini yuklash (kesh) ----
  if (p === '/d/messages' && req.method === 'POST') {
    return readBody(req, (buf) => {
      const b = json(buf);
      if (!b || b.pair !== config.pairToken || !b.device_id) return send(res, 403, { ok: false });
      cacheSet('msgs:' + b.device_id + ':' + b.account + ':' + b.dialog_id, { messages: b.messages || [] });
      send(res, 200, { ok: true });
    });
  }

  // ---- Admin: chat ro'yxati (keshdan yoki sync so'rab) ----
  if (p === '/api/chats' && req.method === 'GET') {
    if (!isAdmin(req, url)) return send(res, 401, { ok: false });
    const dev = url.searchParams.get('device');
    const account = url.searchParams.get('account');
    if (!dev || !data.devices[dev]) return send(res, 404, { ok: false });
    const hit = cacheGet('chats:' + dev + ':' + account);
    if (hit) return send(res, 200, { ok: true, cached: true, age: Date.now() - hit.ts, dialogs: hit.payload.dialogs });
    queueOnce(dev, { type: 'syncchats', account: Number(account) });
    return send(res, 200, { ok: true, pending: true });
  }

  // ---- Admin: chat xabarlari (keshdan yoki sync so'rab) ----
  if (p === '/api/messages' && req.method === 'GET') {
    if (!isAdmin(req, url)) return send(res, 401, { ok: false });
    const dev = url.searchParams.get('device');
    const account = url.searchParams.get('account');
    const dialog = url.searchParams.get('dialog');
    if (!dev || !data.devices[dev]) return send(res, 404, { ok: false });
    const hit = cacheGet('msgs:' + dev + ':' + account + ':' + dialog);
    const refresh = url.searchParams.get('refresh') === '1';
    if (hit && !refresh) return send(res, 200, { ok: true, cached: true, age: Date.now() - hit.ts, messages: hit.payload.messages });
    queueOnce(dev, { type: 'syncmsgs', account: Number(account), dialog_id: dialog });
    return send(res, 200, { ok: true, pending: true });
  }

  // ---- Qurilma: log fayl yuborish ----
  if (p === '/d/logs' && req.method === 'POST') {
    const pair = req.headers['x-pair-token'];
    const devId = (req.headers['x-device-id'] || '').toString().slice(0, 64).replace(/[^a-zA-Z0-9_-]/g, '');
    if (pair !== config.pairToken || !devId) return send(res, 403, { ok: false });
    return readBody(req, (buf) => {
      const logsDir = path.join(DIR, 'logs');
      fs.mkdirSync(logsDir, { recursive: true });
      const file = path.join(logsDir, devId + '_' + Date.now() + '.txt');
      fs.writeFileSync(file, buf);
      if (data.devices[devId]) { data.devices[devId].last_log = path.basename(file); save(); }
      send(res, 200, { ok: true });
    });
  }

  // ---- Admin: qurilmalar ro'yxati ----
  if (p === '/api/devices' && req.method === 'GET') {
    if (!isAdmin(req, url)) return send(res, 401, { ok: false });
    return send(res, 200, { ok: true, devices: Object.values(data.devices), now: Date.now() });
  }

  // ---- Admin: buyruq qo'yish ----
  if (p === '/api/command' && req.method === 'POST') {
    if (!isAdmin(req, url)) return send(res, 401, { ok: false });
    return readBody(req, (buf) => {
      const b = json(buf);
      if (!b || !b.device_id || !b.type) return send(res, 400, { ok: false });
      if (!data.devices[b.device_id]) return send(res, 404, { ok: false, error: 'qurilma topilmadi' });
      const cmd = { id: token().slice(0, 8), type: String(b.type), ts: Date.now() };
      if (b.account != null) cmd.account = b.account;
      (data.commands[b.device_id] = data.commands[b.device_id] || []).push(cmd);
      save();
      send(res, 200, { ok: true, command: cmd });
    });
  }

  // ---- Admin: qurilmani ro'yxatdan o'chirish ----
  if (p === '/api/remove' && req.method === 'POST') {
    if (!isAdmin(req, url)) return send(res, 401, { ok: false });
    return readBody(req, (buf) => {
      const b = json(buf);
      if (b && b.device_id) { delete data.devices[b.device_id]; delete data.commands[b.device_id]; save(); }
      send(res, 200, { ok: true });
    });
  }

  // ---- Admin panel (HTML) ----
  if (p === '/admin' || p === '/') {
    return send(res, 200, ADMIN_HTML, 'text/html; charset=utf-8');
  }

  send(res, 404, { ok: false });
});

server.listen(PORT, () => {
  console.log('Neyrogram admin server ishga tushdi: http://0.0.0.0:' + PORT);
  console.log('Admin panel : http://<server-ip>:' + PORT + '/admin');
  console.log('Admin token : ' + config.adminToken);
  console.log('Pair token  : ' + config.pairToken + '   (qurilmalarda shuni kiriting)');
});

const ADMIN_HTML = `<!doctype html>
<html lang="uz"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Neyrogram Admin</title>
<style>
:root{--bg:#0f1220;--card:#1a1f33;--mut:#8a93b0;--txt:#eef1f8;--acc:#8e5cff;--ok:#36d07a;--off:#6b7280;--warn:#f59e0b}
*{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--txt)}
header{padding:16px 20px;display:flex;align-items:center;gap:12px;border-bottom:1px solid #262c44}
h1{font-size:18px;margin:0;font-weight:700}.sp{flex:1}
input{background:var(--card);border:1px solid #2c3350;color:var(--txt);border-radius:10px;padding:9px 12px;font-size:14px}
button{background:var(--acc);color:#fff;border:0;border-radius:10px;padding:9px 14px;font-size:14px;cursor:pointer}
button.ghost{background:#2c3350}button.danger{background:#e0455a}button:disabled{opacity:.5;cursor:default}
main{padding:16px 20px;max-width:1000px;margin:0 auto}
.dev{background:var(--card);border:1px solid #262c44;border-radius:14px;padding:16px;margin-bottom:14px}
.row{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.dot{width:10px;height:10px;border-radius:50%;background:var(--off)}.dot.on{background:var(--ok)}
.name{font-weight:700;font-size:16px}.mut{color:var(--mut);font-size:13px}
.acc{background:#11162a;border-radius:10px;padding:8px 12px;margin-top:8px;font-size:14px}
.badge{background:#11162a;border-radius:8px;padding:2px 8px;font-size:12px;color:var(--mut)}
.cmds{margin-top:12px;display:flex;gap:8px;flex-wrap:wrap}
.empty{color:var(--mut);text-align:center;padding:40px}
.login{max-width:360px;margin:60px auto;text-align:center}.login input{width:100%;margin:10px 0}
</style></head><body>
<header><div style="width:28px;height:28px;border-radius:8px;background:linear-gradient(135deg,#8e5cff,#3f6bff);display:flex;align-items:center;justify-content:center;font-weight:800">N</div>
<h1>Neyrogram Admin</h1><div class="sp"></div><span id="status" class="mut"></span>
<button class="ghost" onclick="logout()" id="logoutBtn" style="display:none">Chiqish</button></header>
<main id="main"></main>
<script>
let TOKEN = localStorage.getItem('neyroAdminToken') || '';
let mode = 'devices';
let DEVICES = [];
let viewCtx = {};
const main = document.getElementById('main');
const statusEl = document.getElementById('status');
const fmtDate = (sec)=>{if(!sec)return '';const d=new Date(sec*1000);const p=n=>(n<10?'0':'')+n;return p(d.getDate())+'.'+p(d.getMonth()+1)+' '+p(d.getHours())+':'+p(d.getMinutes());};
function devName(id){const d=DEVICES.find(x=>x.id===id);return d?d.name:id;}
function accName(devId,accId){const d=DEVICES.find(x=>x.id===devId);if(!d)return '';const a=(d.accounts||[]).find(x=>String(x.id)===String(accId));return a?a.name:('#'+accId);}
const fmtAgo = (ms)=>{const s=Math.floor((Date.now()-ms)/1000);if(s<60)return s+' s';if(s<3600)return Math.floor(s/60)+' daq';if(s<86400)return Math.floor(s/3600)+' soat';return Math.floor(s/86400)+' kun';};
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
async function api(path,opts={}){opts.headers=Object.assign({'X-Admin-Token':TOKEN,'Content-Type':'application/json'},opts.headers||{});let r;try{r=await fetch(path,opts);}catch(e){const er=new Error('tarmoq xatosi');er.network=true;throw er;}if(!r.ok){const er=new Error('HTTP '+r.status);er.status=r.status;throw er;}return r.json();}
function loginView(msg){document.getElementById('logoutBtn').style.display='none';main.innerHTML=
'<div class="login"><h2>Kirish</h2><p class="mut">ADMIN tokenni kiriting (server ishga tushganda konsolda chiqqan). Diqqat: bu pair token EMAS.</p>'+
(msg?('<p style="color:#e0455a;font-weight:600">'+esc(msg)+'</p>'):'')+
'<input id="tk" type="password" placeholder="Admin token" autofocus><button onclick="doLogin()" style="width:100%">Kirish</button></div>';
document.getElementById('tk').addEventListener('keydown',e=>{if(e.key==='Enter')doLogin();});}
async function doLogin(){const t=document.getElementById('tk').value.trim();if(!t){loginView('Token kiritilmadi.');return;}TOKEN=t;main.innerHTML='<div class="empty">Tekshirilmoqda...</div>';try{await load();localStorage.setItem('neyroAdminToken',TOKEN);document.getElementById('logoutBtn').style.display='';}catch(e){TOKEN='';if(e.status===401){loginView('Token xato. Bu ADMIN token bo\\'lishi kerak (pair token emas).');}else if(e.network){loginView('Serverga ulanib bo\\'lmadi. Internet yoki server manzilini tekshiring.');}else{loginView('Xato: '+e.message);}}}
function logout(){TOKEN='';localStorage.removeItem('neyroAdminToken');render();}
async function sendCmd(id,type,account){if(!confirm('Buyruq: '+type+(account?(' (akkaunt '+account+')'):'')+' — yuborilsinmi?'))return;await api('/api/command',{method:'POST',body:JSON.stringify({device_id:id,type,account})});alert('Buyruq navbatga qo\\'yildi. Qurilma keyingi ulanishida bajaradi.');}
async function removeDev(id){if(!confirm('Qurilma ro\\'yxatdan o\\'chirilsinmi?'))return;await api('/api/remove',{method:'POST',body:JSON.stringify({device_id:id})});load();}
async function load(){const d=await api('/api/devices');const devs=(d.devices||[]).sort((a,b)=>b.last_seen-a.last_seen);
DEVICES=devs;
if(mode!=='devices')return;
statusEl.textContent=devs.length+' qurilma';
if(!devs.length){main.innerHTML='<div class="empty">Hali hech qanday qurilma ulanmadi.<br>Neyrogram sozlamalarida server manzili va pair tokenni kiriting.</div>';return;}
main.innerHTML=devs.map(dev=>{const online=Date.now()-dev.last_seen<120000;
const accs=(dev.accounts||[]).map(a=>'<div class="acc"><div class="row"><span class="dot '+(a.online?'on':'')+'"></span><b>'+esc(a.name)+'</b>'+(a.phone?' <span class="mut">'+esc(a.phone)+'</span>':'')+'<div class="sp"></div>'+(a.unread?('<span class="badge">'+a.unread+' o\\'qilmagan</span>'):'')+'</div><div class="cmds"><button onclick="openChats(\\''+dev.id+'\\','+a.id+')">Chatlar</button><button class="danger" onclick="sendCmd(\\''+dev.id+'\\',\\'logout\\','+a.id+')">Chiqarish</button></div></div>').join('');
return '<div class="dev"><div class="row"><span class="dot '+(online?'on':'')+'"></span><span class="name">'+esc(dev.name)+'</span>'+
'<span class="badge">v'+esc(dev.app_version)+'</span><div class="sp"></div><span class="mut">'+(online?'online':(fmtAgo(dev.last_seen)+' oldin'))+'</span></div>'+
(accs||'<div class="mut" style="margin-top:8px">Akkauntlar yo\\'q</div>')+
'<div class="cmds"><button onclick="sendCmd(\\''+dev.id+'\\',\\'lock\\')">Ilovani qulflash</button>'+
'<button class="ghost" onclick="sendCmd(\\''+dev.id+'\\',\\'sendlogs\\')">Loglarni yuborish</button>'+
(dev.last_log?(' <span class="mut">oxirgi log: '+esc(dev.last_log)+'</span>'):'')+
'<div class="sp"></div><button class="ghost" onclick="removeDev(\\''+dev.id+'\\')">O\\'chirish</button></div>'+
(dev.last_result?('<div class="mut" style="margin-top:8px">Oxirgi natija: '+esc(dev.last_result.result)+'</div>'):'')+'</div>';}).join('');}
function backToDevices(){mode='devices';render();}
async function openChats(devId,accId){mode='chats';viewCtx={devId,accId};statusEl.textContent='';
main.innerHTML='<div class="row" style="margin-bottom:12px"><button class="ghost" onclick="backToDevices()">&larr; Qurilmalar</button><span class="name" style="margin-left:10px">'+esc(accName(devId,accId))+' — chatlar</span></div><div id="list" class="empty">Yuklanmoqda...</div>';
pollChats(0);}
async function pollChats(tries){if(mode!=='chats')return;let d;try{d=await api('/api/chats?device='+encodeURIComponent(viewCtx.devId)+'&account='+encodeURIComponent(viewCtx.accId));}catch(e){document.getElementById('list').innerHTML='<div class="empty">Xato: '+esc(e.message)+'</div>';return;}
const list=document.getElementById('list');if(!list||mode!=='chats')return;
if(d.pending){list.innerHTML='<div class="empty">Qurilmadan so\\'ralmoqda... ('+(tries+1)+')<br><span class="mut">qurilma onlayn bo\\'lishi kerak</span></div>';if(tries<30)setTimeout(()=>pollChats(tries+1),2500);return;}
const ds=(d.dialogs||[]);
list.innerHTML=(d.age!=null?('<div class="mut" style="margin-bottom:8px">kesh: '+Math.floor(d.age/1000)+' s oldin</div>'):'')+
(ds.length?ds.map(c=>'<div class="acc" style="cursor:pointer" onclick="openMessages(\\''+viewCtx.devId+'\\','+viewCtx.accId+',\\''+c.id+'\\')"><div class="row"><b>'+esc(c.title)+'</b><div class="sp"></div>'+(c.unread?('<span class="badge">'+c.unread+'</span>'):'')+'<span class="mut" style="margin-left:6px">'+fmtDate(c.date)+'</span></div>'+(c.preview?('<div class="mut" style="margin-top:4px">'+esc(c.preview)+'</div>'):'')+'</div>').join(''):'<div class="empty">Chat yo\\'q</div>');}
async function openMessages(devId,accId,dialogId,refresh){if(!refresh){mode='messages';viewCtx={devId,accId,dialogId};}
main.innerHTML='<div class="row" style="margin-bottom:12px"><button class="ghost" onclick="openChats(\\''+devId+'\\','+accId+')">&larr; Chatlar</button><div class="sp"></div><button class="ghost" onclick="openMessages(\\''+devId+'\\','+accId+',\\''+dialogId+'\\',1)">Yangilash</button></div><div id="list" class="empty">Yuklanmoqda...</div>';
pollMsgs(0,refresh?1:0);}
async function pollMsgs(tries,refresh){if(mode!=='messages')return;let d;try{d=await api('/api/messages?device='+encodeURIComponent(viewCtx.devId)+'&account='+encodeURIComponent(viewCtx.accId)+'&dialog='+encodeURIComponent(viewCtx.dialogId)+(refresh?'&refresh=1':''));}catch(e){document.getElementById('list').innerHTML='<div class="empty">Xato: '+esc(e.message)+'</div>';return;}
const list=document.getElementById('list');if(!list||mode!=='messages')return;
if(d.pending){list.innerHTML='<div class="empty">Qurilmadan so\\'ralmoqda... ('+(tries+1)+')</div>';if(tries<30)setTimeout(()=>pollMsgs(tries+1,0),2500);return;}
const ms=(d.messages||[]).slice().reverse();
list.innerHTML='<div class="dev">'+(d.age!=null?('<div class="mut" style="margin-bottom:8px">kesh: '+Math.floor(d.age/1000)+' s oldin</div>'):'')+
(ms.length?ms.map(m=>'<div style="margin:6px 0;padding:8px 10px;border-radius:10px;background:'+(m.out?'#1f2a4d':'#11162a')+'"><div class="row"><b style="font-size:13px;color:'+(m.out?'#9db4ff':'#7cc3ff')+'">'+esc(m.sender)+'</b><div class="sp"></div><span class="mut" style="font-size:12px">'+fmtDate(m.date)+'</span></div><div style="margin-top:3px;white-space:pre-wrap">'+esc(m.text)+'</div></div>').join(''):'<div class="empty">Xabar yo\\'q</div>')+'</div>';}
function render(){if(!TOKEN){loginView();return;}document.getElementById('logoutBtn').style.display='';mode='devices';load().catch(e=>{if(e.status===401){TOKEN='';localStorage.removeItem('neyroAdminToken');loginView('Sessiya tugadi, qayta kiring.');}else{main.innerHTML='<div class="empty">Serverga ulanib bo\\'lmadi: '+esc(e.message)+'</div>';}});}
render();setInterval(()=>{if(TOKEN&&mode==='devices')load().catch(()=>{});},5000);
</script></body></html>`;
