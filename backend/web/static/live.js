// WebSocket client for /api/v1/ws (docs/api-v1.md "Chi tiết WebSocket"):
// auth as the first message, one subscribed session, reconnect with backoff,
// and a "resync" callback whenever events may have been missed.
import { auth, onAuthChange, refresh } from './api.js';

const handlers = new Map(); // type -> Set(fn)
let ws = null;
let sessionId = null;
let wanted = false;
let backoff = 1000;
let retryTimer = null;
let connected = false;
let lostConnection = false; // set when an open socket drops

const el = () => document.getElementById('conn');

function setStatus(on) {
  const changed = connected !== on;
  connected = on;
  if (changed) dispatch('connection', { connected: on });
  const e = el();
  if (!e) return;
  e.hidden = !wanted;
  e.className = 'conn ' + (on ? 'on' : 'off');
  e.textContent = on ? '● Trực tuyến' : '● Mất kết nối — đang nối lại…';
}

export function on(type, fn) {
  if (!handlers.has(type)) handlers.set(type, new Set());
  handlers.get(type).add(fn);
  return () => handlers.get(type).delete(fn);
}

function dispatch(type, data) {
  for (const fn of handlers.get(type) || []) {
    try { fn(data); } catch (e) { console.error(e); }
  }
}

// subscribe(id) opens the socket if needed and follows that session.
export function subscribe(id) {
  if (wanted && sessionId === id && ws) return;
  sessionId = id;
  wanted = true;
  if (ws && ws.readyState === WebSocket.OPEN && authed) send('subscribe', { session_id: id });
  else if (!ws) connect();
  setStatus(connected);
}

export function stop() {
  wanted = false;
  sessionId = null;
  clearTimeout(retryTimer);
  authed = false;
  if (ws) { const w = ws; ws = null; w.close(1000); }
  setStatus(false);
}

let authed = false;
// send queues nothing: a message sent while disconnected is dropped (callers
// re-send their state on "subscribed").
export function send(type, data) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type, data }));
}

let connecting = false;
async function connect() {
  clearTimeout(retryTimer);
  if (!wanted || connecting || ws) return;
  if (!auth.access || Date.now() > auth.accessExp - 30_000) {
    connecting = true;
    try { await refresh(); } catch { connecting = false; return retry(); }
    connecting = false;
  }
  if (!wanted || ws) return;
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const sock = new WebSocket(`${proto}//${location.host}/api/v1/ws`);
  ws = sock;
  authed = false;
  sock.onopen = () => send('auth', { token: auth.access });
  sock.onmessage = (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    if (msg.type === 'auth.ok') {
      const firstAuth = !authed;
      authed = true;
      backoff = 1000;
      if (firstAuth && sessionId) send('subscribe', { session_id: sessionId });
      return;
    }
    if (msg.type === 'subscribed') {
      const reconnect = lostConnection;
      lostConnection = false;
      setStatus(true);
      // Anything that happened while we were away is reloaded over REST.
      if (reconnect) dispatch('resync', {});
      dispatch('subscribed', msg.data);
      return;
    }
    dispatch(msg.type, msg.data);
  };
  sock.onclose = () => {
    if (ws !== sock) return;
    ws = null;
    authed = false;
    lostConnection = true;
    setStatus(false);
    retry();
  };
  sock.onerror = () => {};
}

function retry() {
  if (!wanted) return;
  setStatus(false);
  retryTimer = setTimeout(connect, backoff);
  backoff = Math.min(backoff * 2, 10_000);
}

// A refreshed access token is re-sent so the socket outlives the 15-minute JWT.
onAuthChange(() => {
  if (!auth.access) { stop(); return; }
  if (ws && authed) send('auth', { token: auth.access });
});
