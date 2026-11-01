// REST client: access token in memory, refresh token in sessionStorage (per
// tab, so two tabs never race on the single-use refresh token).
const BASE = '/api/v1';
const RT_KEY = 'lanjudge.refresh';

export const auth = { access: null, accessExp: 0, user: null };
const listeners = new Set();
export function onAuthChange(fn) { listeners.add(fn); return () => listeners.delete(fn); }
function emit() { for (const fn of listeners) fn(auth); }

export class ApiError extends Error {
  constructor(status, body) {
    super((body && body.error) || `HTTP ${status}`);
    this.status = status;
    this.body = body || {};
  }
}

function store(tokens) {
  auth.access = tokens.access_token;
  auth.accessExp = Date.parse(tokens.access_expires_at);
  auth.user = tokens.user;
  try { sessionStorage.setItem(RT_KEY, tokens.refresh_token); } catch {}
  scheduleRefresh();
  emit();
}

let refreshTimer = null;
function scheduleRefresh() {
  clearTimeout(refreshTimer);
  const ms = auth.accessExp - Date.now() - 60_000;
  refreshTimer = setTimeout(() => refresh().catch(() => {}), Math.max(ms, 5_000));
}

let refreshing = null;
let sessionVersion = 0;

async function revoke(refreshToken) {
  if (!refreshToken) return;
  await fetch(BASE + '/auth/logout', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refresh_token: refreshToken }),
  }).catch(() => {});
}

function sessionChanged() {
  return new ApiError(401, { error: 'authentication session changed' });
}

export function refresh() {
  if (refreshing) return refreshing;
  let rt = null;
  try { rt = sessionStorage.getItem(RT_KEY); } catch {}
  if (!rt) return Promise.reject(new ApiError(401, { error: 'not logged in' }));
  const version = sessionVersion;
  const pending = fetch(BASE + '/auth/refresh', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refresh_token: rt }),
  }).then(async (res) => {
    const body = await res.json().catch(() => ({}));
    if (version !== sessionVersion) {
      // The server may already have rotated the token before logout. Revoke
      // the replacement too, without restoring or clearing a newer login.
      if (res.ok) await revoke(body.refresh_token);
      throw sessionChanged();
    }
    if (!res.ok) {
      // A network error keeps the token for the next try; a 401 means it is gone.
      if (res.status === 401) clearSession();
      throw new ApiError(res.status, body);
    }
    store(body);
    return auth;
  }).finally(() => { if (refreshing === pending) refreshing = null; });
  refreshing = pending;
  return pending;
}

function clearSession() {
  sessionVersion++;
  refreshing = null;
  clearTimeout(refreshTimer);
  auth.access = null; auth.accessExp = 0; auth.user = null;
  try { sessionStorage.removeItem(RT_KEY); } catch {}
  emit();
}

export async function login(code, password) {
  clearSession();
  const version = sessionVersion;
  const res = await fetch(BASE + '/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, password }),
  });
  const body = await res.json().catch(() => ({}));
  if (version !== sessionVersion) {
    if (res.ok) await revoke(body.refresh_token);
    throw sessionChanged();
  }
  if (!res.ok) throw new ApiError(res.status, body);
  store(body);
}

export async function logout() {
  let rt = null;
  try { rt = sessionStorage.getItem(RT_KEY); } catch {}
  clearSession();
  await revoke(rt);
}

// Restore the login after a page reload (refresh token survives in sessionStorage).
export async function resume() {
  try { await refresh(); return true; } catch { return false; }
}

async function token() {
  if (!auth.access || Date.now() > auth.accessExp - 30_000) await refresh();
  return auth.access;
}

// request(method, path, {json, form, query, as: 'json'|'text'|'blob'})
export async function request(method, path, opts = {}) {
  const version = sessionVersion;
  let url = BASE + path;
  if (opts.query) {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(opts.query)) if (v !== undefined && v !== null && v !== '') q.set(k, v);
    const s = q.toString();
    if (s) url += (url.includes('?') ? '&' : '?') + s;
  }
  const send = async () => {
    const access = await token();
    if (version !== sessionVersion) throw sessionChanged();
    const headers = { Authorization: 'Bearer ' + access };
    let body;
    if (opts.json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(opts.json); }
    else if (opts.form) body = opts.form;
    return fetch(url, { method, headers, body });
  };
  let res = await send();
  if (version !== sessionVersion) throw sessionChanged();
  if (res.status === 401) { await refresh(); res = await send(); }
  if (version !== sessionVersion) throw sessionChanged();
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, body);
  }
  if (res.status === 204) return null;
  const body = await (opts.as === 'text' ? res.text() : opts.as === 'blob' ? res.blob() : res.json());
  if (version !== sessionVersion) throw sessionChanged();
  return body;
}

export const get = (p, query) => request('GET', p, { query });
export const post = (p, json) => request('POST', p, { json });
export const put = (p, json) => request('PUT', p, { json });
export const del = (p) => request('DELETE', p);
export const upload = (p, form) => request('POST', p, { form });
