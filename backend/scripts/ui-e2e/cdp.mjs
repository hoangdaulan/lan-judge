// Minimal Chrome DevTools Protocol client (no npm dependencies): launches
// headless Chrome and evaluates JavaScript in pages. Used by the browser
// end-to-end checks in this folder.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

function chromeExecutable() {
  if (process.env.CHROME) return process.env.CHROME;
  const candidates = process.platform === 'darwin'
    ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
    : process.platform === 'win32'
      ? [process.env.PROGRAMFILES, process.env['PROGRAMFILES(X86)'], process.env.LOCALAPPDATA]
        .filter(Boolean).map((dir) => join(dir, 'Google', 'Chrome', 'Application', 'chrome.exe'))
      : ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
  return candidates.find((path) => existsSync(path)) || 'google-chrome';
}

export async function launch({ chrome = chromeExecutable(), port = 9333, args = [] } = {}) {
  const profile = mkdtempSync(join(tmpdir(), 'lanjudge-e2e-'));
  const proc = spawn(chrome, [
    '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', ...args, 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  let launchError;
  proc.on('error', (error) => { launchError = error; });
  // Drain stderr so a verbose Chrome cannot block on a full pipe.
  proc.stderr.resume();
  let ver;
  for (let i = 0; i < 50 && !ver; i++) {
    await new Promise((r) => setTimeout(r, 200));
    if (launchError || proc.exitCode !== null) break;
    ver = await fetch(`http://127.0.0.1:${port}/json/version`).then((r) => r.json()).catch(() => null);
  }
  if (!ver) {
    proc.kill();
    rmSync(profile, { recursive: true, force: true });
    throw new Error(`Chrome did not start (${chrome}). Install Chrome in the same OS as Node.js or set CHROME to its executable path.${launchError ? ' ' + launchError.message : ''}`);
  }
  const browser = await connect(ver.webSocketDebuggerUrl);
  browser.close = () => { proc.kill(); setTimeout(() => rmSync(profile, { recursive: true, force: true }), 500); };
  return browser;
}

async function connect(url) {
  const ws = new WebSocket(url);
  await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
  let id = 0;
  const pending = new Map();
  const listeners = [];
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const { ok, fail } = pending.get(m.id);
      pending.delete(m.id);
      m.error ? fail(new Error(m.error.message)) : ok(m.result);
    } else for (const l of listeners) l(m);
  };
  const send = (method, params = {}, sessionId) => new Promise((ok, fail) => {
    pending.set(++id, { ok, fail });
    ws.send(JSON.stringify({ id, method, params, sessionId }));
  });
  return {
    send,
    on: (fn) => listeners.push(fn),
    // newPage opens a tab in its own browser context (separate storage).
    async newPage() {
      const { browserContextId } = await send('Target.createBrowserContext');
      const { targetId } = await send('Target.createTarget', { url: 'about:blank', browserContextId });
      const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
      await send('Runtime.enable', {}, sessionId);
      await send('Page.enable', {}, sessionId);
      const page = {
        send: (method, params = {}) => send(method, params, sessionId),
        async goto(url) {
          await send('Page.navigate', { url }, sessionId);
          await page.waitFor('document.readyState === "complete"');
        },
        async eval(expr) {
          const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true, userGesture: true }, sessionId);
          if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
          return r.result.value;
        },
        async waitFor(expr, ms = 10000) {
          const end = Date.now() + ms;
          for (;;) {
            if (await page.eval(`!!(${expr})`).catch(() => false)) return;
            if (Date.now() > end) throw new Error('timeout waiting for ' + expr);
            await new Promise((r) => setTimeout(r, 100));
          }
        },
        async screenshot() {
          const { data } = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
          return Buffer.from(data, 'base64');
        },
      };
      return page;
    },
  };
}
