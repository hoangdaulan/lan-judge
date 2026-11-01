import assert from 'node:assert/strict';
import { test } from 'node:test';

let instance = 0;
const tokens = (id, refresh = `refresh-${id}`) => ({
  access_token: `access-${id}`, refresh_token: refresh,
  access_expires_at: new Date(Date.now() + 900000).toISOString(),
  user: { id, code: `SV${id}`, role: 'student' },
});
const json = (body, status = 200) => new Response(JSON.stringify(body), { status });
const deferred = () => {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
};
async function client(handler) {
  const storage = new Map();
  globalThis.sessionStorage = { getItem: k => storage.get(k) ?? null, setItem: (k,v) => storage.set(k,v), removeItem: k => storage.delete(k) };
  globalThis.fetch = handler;
  return import(new URL(`../web/static/api.js?test=${++instance}`, import.meta.url));
}

test('logout discards a delayed refresh and revokes the rotated token', async () => {
  const response = deferred(), started = deferred(), revoked = [];
  const api = await client(async (url, options) => {
    const body = JSON.parse(options.body || '{}');
    if (url.endsWith('/auth/login')) return json(tokens(1));
    if (url.endsWith('/auth/refresh')) { started.resolve(); return response.promise; }
    if (url.endsWith('/auth/logout')) revoked.push(body.refresh_token);
    return new Response(null, { status: 204 });
  });
  await api.login('SV1', 'password');
  const refresh = api.refresh();
  const rejected = assert.rejects(refresh, e => e.status === 401);
  await started.promise;
  await api.logout();
  response.resolve(json(tokens(1, 'rotated-after-logout')));
  await rejected;
  assert.equal(api.auth.user, null);
  assert.equal(sessionStorage.getItem('lanjudge.refresh'), null);
  assert.deepEqual(revoked, ['refresh-1', 'rotated-after-logout']);
});

for (const status of [200, 401]) {
  test(`an old refresh response (${status}) cannot replace or clear a new login`, async () => {
    const response = deferred(), started = deferred();
    const api = await client(async (url, options) => {
      const body = JSON.parse(options.body || '{}');
      if (url.endsWith('/auth/login')) return json(tokens(Number(body.code.slice(2))));
      if (url.endsWith('/auth/refresh')) { started.resolve(); return response.promise; }
      return new Response(null, { status: 204 });
    });
    try {
      await api.login('SV1', 'password');
      const rejected = assert.rejects(api.refresh(), e => e.status === 401);
      await started.promise;
      await api.logout();
      await api.login('SV2', 'password');
      response.resolve(json(status === 200 ? tokens(1, 'old-rotated') : { error: 'expired' }, status));
      await rejected;
      assert.equal(api.auth.user.id, 2);
      assert.equal(sessionStorage.getItem('lanjudge.refresh'), 'refresh-2');
    } finally { await api.logout(); }
  });
}

test('an old API response cannot retry using a different account', async () => {
  const response = deferred(), started = deferred();
  let refreshes = 0;
  const api = await client(async (url, options) => {
    const body = JSON.parse(options.body || '{}');
    if (url.endsWith('/auth/login')) return json(tokens(Number(body.code.slice(2))));
    if (url.endsWith('/auth/refresh')) { refreshes++; return json(tokens(2)); }
    if (url.endsWith('/private')) { started.resolve(); return response.promise; }
    return new Response(null, { status: 204 });
  });
  try {
    await api.login('SV1', 'password');
    const rejected = assert.rejects(api.get('/private'), e => e.status === 401);
    await started.promise;
    await api.logout();
    await api.login('SV2', 'password');
    response.resolve(json({ error: 'old token expired' }, 401));
    await rejected;
    assert.equal(api.auth.user.id, 2);
    assert.equal(refreshes, 0);
  } finally { await api.logout(); }
});

test('a response body arriving after an account switch is discarded', async () => {
  const body = deferred(), reading = deferred();
  const api = await client(async (url, options) => {
    const input = JSON.parse(options.body || '{}');
    if (url.endsWith('/auth/login')) return json(tokens(Number(input.code.slice(2))));
    if (url.endsWith('/private')) return { ok: true, status: 200, json() { reading.resolve(); return body.promise; } };
    return new Response(null, { status: 204 });
  });
  try {
    await api.login('SV1', 'password');
    const rejected = assert.rejects(api.get('/private'), e => e.status === 401);
    await reading.promise;
    await api.logout();
    await api.login('SV2', 'password');
    body.resolve({ privateSource: 'student one source' });
    await rejected;
    assert.equal(api.auth.user.id, 2);
  } finally { await api.logout(); }
});
