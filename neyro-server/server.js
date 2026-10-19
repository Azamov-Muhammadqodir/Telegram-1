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
const main = document.getElementById('main');
const statusEl = document.getElementById('status');
const fmtAgo = (ms)=>{const s=Math.floor((Date.now()-ms)/1000);if(s<60)return s+' s';if(s<3600)return Math.floor(s/60)+' daq';if(s<86400)return Math.floor(s/3600)+' soat';return Math.floor(s/86400)+' kun';};
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
async function api(path,opts={}){opts.headers=Object.assign({'X-Admin-Token':TOKEN,'Content-Type':'application/json'},opts.headers||{});const r=await fetch(path,opts);if(r.status===401){TOKEN='';localStorage.removeItem('neyroAdminToken');render();throw new Error('401');}return r.json();}
function loginView(){document.getElementById('logoutBtn').style.display='none';main.innerHTML=
'<div class="login"><h2>Kirish</h2><p class="mut">Admin tokenni kiriting (server ishga tushganda konsolda chiqqan).</p>'+
'<input id="tk" type="password" placeholder="Admin token" autofocus><button onclick="doLogin()" style="width:100%">Kirish</button></div>';
document.getElementById('tk').addEventListener('keydown',e=>{if(e.key==='Enter')doLogin();});}
function doLogin(){TOKEN=document.getElementById('tk').value.trim();localStorage.setItem('neyroAdminToken',TOKEN);render();}
function logout(){TOKEN='';localStorage.removeItem('neyroAdminToken');render();}
async function sendCmd(id,type,account){if(!confirm('Buyruq: '+type+(account?(' (akkaunt '+account+')'):'')+' — yuborilsinmi?'))return;await api('/api/command',{method:'POST',body:JSON.stringify({device_id:id,type,account})});alert('Buyruq navbatga qo\\'yildi. Qurilma keyingi ulanishida bajaradi.');}
async function removeDev(id){if(!confirm('Qurilma ro\\'yxatdan o\\'chirilsinmi?'))return;await api('/api/remove',{method:'POST',body:JSON.stringify({device_id:id})});load();}
async function load(){let d;try{d=await api('/api/devices');}catch(e){return;}const devs=(d.devices||[]).sort((a,b)=>b.last_seen-a.last_seen);
statusEl.textContent=devs.length+' qurilma';
if(!devs.length){main.innerHTML='<div class="empty">Hali hech qanday qurilma ulanmadi.<br>Neyrogram sozlamalarida server manzili va pair tokenni kiriting.</div>';return;}
main.innerHTML=devs.map(dev=>{const online=Date.now()-dev.last_seen<120000;
const accs=(dev.accounts||[]).map(a=>'<div class="acc"><div class="row"><span class="dot '+(a.online?'on':'')+'"></span><b>'+esc(a.name)+'</b>'+(a.phone?' <span class="mut">'+esc(a.phone)+'</span>':'')+'<div class="sp"></div>'+(a.unread?('<span class="badge">'+a.unread+' o\\'qilmagan</span>'):'')+'</div><div class="cmds"><button class="danger" onclick="sendCmd(\\''+dev.id+'\\',\\'logout\\','+a.id+')">Chiqarish</button></div></div>').join('');
return '<div class="dev"><div class="row"><span class="dot '+(online?'on':'')+'"></span><span class="name">'+esc(dev.name)+'</span>'+
'<span class="badge">v'+esc(dev.app_version)+'</span><div class="sp"></div><span class="mut">'+(online?'online':(fmtAgo(dev.last_seen)+' oldin'))+'</span></div>'+
(accs||'<div class="mut" style="margin-top:8px">Akkauntlar yo\\'q</div>')+
'<div class="cmds"><button onclick="sendCmd(\\''+dev.id+'\\',\\'lock\\')">Ilovani qulflash</button>'+
'<button class="ghost" onclick="sendCmd(\\''+dev.id+'\\',\\'sendlogs\\')">Loglarni yuborish</button>'+
(dev.last_log?(' <span class="mut">oxirgi log: '+esc(dev.last_log)+'</span>'):'')+
'<div class="sp"></div><button class="ghost" onclick="removeDev(\\''+dev.id+'\\')">O\\'chirish</button></div>'+
(dev.last_result?('<div class="mut" style="margin-top:8px">Oxirgi natija: '+esc(dev.last_result.result)+'</div>'):'')+'</div>';}).join('');}
function render(){if(!TOKEN){loginView();return;}document.getElementById('logoutBtn').style.display='';load();}
render();setInterval(()=>{if(TOKEN)load();},5000);
</script></body></html>`;
