// End-to-end check of screen sharing in two real (headless) Chrome browsers:
// a student must share the whole screen before the exam room unlocks, the
// teacher sees it in "Màn hình SV" and receives live video over WebRTC, and
// stopping the share locks the room again and ends the teacher's view.
//
//   TEACHER_CODE=GV001 TEACHER_PASSWORD=... node scripts/ui-e2e/screen-share.mjs
//
// Creates a temporary exam, session and student and removes them at the end.
// Chrome flags pick "Entire screen" automatically (no picker in headless).
import { execFileSync } from 'node:child_process';
import { launch } from './cdp.mjs';

const API = (process.env.BASE || 'http://localhost:8080') + '/api/v1';
const UI = process.env.UI || 'https://localhost:8443';
const { TEACHER_CODE, TEACHER_PASSWORD } = process.env;
if (!TEACHER_CODE || !TEACHER_PASSWORD) throw new Error('set TEACHER_CODE and TEACHER_PASSWORD');

let failed = 0;
const check = (name, ok, extra = '') => {
  console.log(`  ${ok ? '\x1b[32mOK\x1b[0m' : '\x1b[31mFAIL\x1b[0m'} ${name}${extra ? ' — ' + extra : ''}`);
  if (!ok) failed++;
};
async function call(method, path, token, body) {
  const res = await fetch(API + path, {
    method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body && JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${method} ${path}: ${res.status} ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}
const login = async (code, password) => (await fetch(API + '/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code, password }),
}).then((r) => r.json())).access_token;

// ---- data
const tt = await login(TEACHER_CODE, TEACHER_PASSWORD);
const code = 'E2ESV' + Date.now().toString().slice(-6);
await call('POST', '/students', tt, { code, fullname: 'Sinh vien E2E', date_of_birth: '01/01/2004' });
const exam = await call('POST', '/exams', tt, { title: 'E2E screen share', description: 'temporary' });
const now = Date.now();
const ses = await call('POST', `/exams/${exam.id}/sessions`, tt, {
  name: 'E2E', starts_at: new Date(now - 60e3).toISOString(), ends_at: new Date(now + 30 * 60e3).toISOString(),
});

let browser;
try {
  browser = await launch({ args: [
    '--ignore-certificate-errors', '--use-fake-ui-for-media-stream',
    '--auto-select-desktop-capture-source=Entire screen', '--use-fake-device-for-media-stream',
  ] });
  const uiLogin = async (page, c, p) => {
    await page.goto(UI + '/');
    await page.waitFor('document.querySelector("#login-form")');
    await page.eval(`(() => { const f = document.querySelector('#login-form'); f.code.value = ${JSON.stringify(c)};
      f.password.value = ${JSON.stringify(p)}; f.requestSubmit(); })()`);
    await page.waitFor('document.querySelector("#who")');
  };

  console.log('== student enters the running session');
  const sv = await browser.newPage();
  await uiLogin(sv, code, '01012004');
  await sv.eval(`location.hash = '#/sessions/${ses.id}'`);
  await sv.waitFor('document.querySelector(".lock")');
  check('room is locked until the screen is shared', await sv.eval('!document.querySelector(".lock").hidden'));
  check('page is a secure context over HTTPS', await sv.eval('window.isSecureContext'));
  await sv.eval('document.querySelector("#share-btn").click()');
  await sv.waitFor('document.querySelector(".lock").hidden');
  check('sharing the whole screen unlocks the room', true,
    await sv.eval('document.querySelector("#share-badge").innerText'));

  console.log('== teacher changes session times while the student is inside');
  const updateEnd = (offset) => call('PUT', `/sessions/${ses.id}`, tt, {
    name: ses.name, starts_at: ses.starts_at, ends_at: new Date(Date.now() + offset).toISOString(),
  });
  await updateEnd(-1000);
  await sv.waitFor('!document.querySelector("#finish") && document.querySelector(".room-countdown").innerText.includes("00:00")', 5000);
  check('shortening the session updates the open workspace immediately', true);
  await updateEnd(30 * 60e3);
  await sv.waitFor('document.querySelector("#share-btn")', 5000);
  await sv.eval('document.querySelector("#share-btn").click()');
  await sv.waitFor('document.querySelector(".lock").hidden');
  check('extending the session reopens it with screen sharing required', await sv.eval('!!document.querySelector("#finish")'));

  console.log('== teacher watches the student');
  const gv = await browser.newPage();
  await uiLogin(gv, TEACHER_CODE, TEACHER_PASSWORD);
  await gv.eval(`location.hash = '#/sessions/${ses.id}/screens'`);
  await gv.waitFor('document.querySelector("[data-watch]")');
  check('teacher list shows the student sharing', await gv.eval('document.querySelector("#scr-rows").innerText.includes("Đang chia sẻ")'),
    await gv.eval('document.querySelector("#scr-sum").innerText'));
  const t0 = Date.now();
  await gv.eval('document.querySelector("[data-watch]").click()');
  await gv.waitFor('(() => { const v = document.querySelector(".screen-tile video"); return v && v.videoWidth > 0 && v.getVideoPlaybackQuality().totalVideoFrames > 3; })()', 15000);
  const info = await gv.eval(`(() => { const v = document.querySelector('.screen-tile video');
    return v.videoWidth + 'x' + v.videoHeight + ', ' + v.getVideoPlaybackQuality().totalVideoFrames + ' frames'; })()`);
  check('teacher receives live video (WebRTC, no STUN/TURN)', true, `${info}, first frames after ${Date.now() - t0} ms`);

  for (const [label, page] of [['student', sv], ['teacher', gv]]) {
    const frames = await gv.eval('document.querySelector(".screen-tile video").getVideoPlaybackQuality().totalVideoFrames');
    await page.eval('import("/assets/api.js").then(m=>m.refresh()).then(()=>true)');
    await gv.waitFor(`document.querySelector(".screen-tile video")?.getVideoPlaybackQuality().totalVideoFrames > ${frames + 1}`, 10000);
    check(`${label} token refresh keeps the existing live video`, true);
  }
  await sv.eval(`import('/assets/live.js').then(m=>m.send('subscribe',{session_id:${ses.id}}))`);
  await new Promise(resolve => setTimeout(resolve, 300));
  check('subscribing to the same session preserves sharing and the view', await gv.eval('!!document.querySelector(".screen-tile video")?.videoWidth'));

  console.log('== student stops sharing');
  await sv.eval('import("/assets/screen.js").then((m) => m.stopShare())');
  await sv.waitFor('!document.querySelector(".lock").hidden');
  check('room locks again when sharing stops', true);
  await gv.waitFor('!document.querySelector(".screen-tile")', 10000);
  check('teacher view closes', true);
  await gv.waitFor('document.querySelector("#scr-rows").innerText.includes("tắt chia sẻ")');
  check('teacher list shows sharing turned off', true);

  console.log('== student shares again, finishes the session');
  await sv.eval('document.querySelector("#share-btn").click()');
  await sv.waitFor('document.querySelector(".lock").hidden');
  await gv.waitFor('document.querySelector("[data-watch]")');
  check('teacher sees sharing again', true);
  await sv.eval('window.confirm = () => true; document.querySelector("#finish").click()');
  await sv.waitFor('!document.querySelector(".lock")');
  check('finishing the session stops sharing and removes the lock', await sv.eval('!document.querySelector("#finish")'));
  await gv.waitFor('!document.querySelector("[data-watch]")');
  check('teacher list no longer offers the screen', true);
} catch (e) {
  failed++;
  console.log('  \x1b[31mFAIL\x1b[0m', e.message);
} finally {
  browser?.close();
  await call('DELETE', `/sessions/${ses.id}`, tt).catch((e) => console.log('cleanup:', e.message));
  await call('DELETE', `/exams/${exam.id}`, tt).catch((e) => console.log('cleanup:', e.message));
  // Students cannot be deleted over the API; remove the temporary one directly.
  try {
    execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c',
      `psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -qc "DELETE FROM users WHERE code = '${code}'"`], { stdio: 'ignore' });
  } catch (e) { console.log('cleanup student:', e.message); }
}
console.log(failed ? `\n${failed} check(s) failed` : '\nScreen sharing OK');
process.exit(failed ? 1 : 0);
