// Screen sharing (docs/api-v1.md "Chia sẻ màn hình"): the student shares the
// whole screen while in a running session; the session's teacher watches up
// to 15 students at once in a grid and caps each student's encoder frame
// rate by grid size (resolution stays full so code remains readable). Video goes peer to peer over the LAN
// (WebRTC without STUN/TURN); signaling uses the WebSocket (live.js).
import * as live from './live.js';
import { auth, onAuthChange } from './api.js';
import { html, mount, toast } from './ui.js';

const RTC_CONFIG = { iceServers: [] }; // same LAN: host candidates only

// ---------------------------------------------------------------- student

let stream = null;
let captureVersion = 0;
let sharingUser = auth.user?.id;
let confirmed = false;
let subscribedSession = null;
const peers = new Map(); // view_id -> RTCPeerConnection (student side)
const shareListeners = new Set();

export const isSharing = () => !!stream && confirmed;
export function onShareChange(fn) { shareListeners.add(fn); return () => shareListeners.delete(fn); }
function notifySharing() {
  for (const fn of shareListeners) { try { fn(isSharing()); } catch (e) { console.error(e); } }
}
function changed() {
  confirmed = false;
  live.send('screen.state', { sharing: !!stream });
  notifySharing();
}

export function screenSupported() {
  return window.isSecureContext && !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) && 'RTCPeerConnection' in window;
}

// startShare must run from a click (browsers require a user gesture). Only
// the whole screen is accepted, not a window or a tab.
export async function startShare() {
  const version = ++captureVersion;
  const s = await navigator.mediaDevices.getDisplayMedia({
    video: { displaySurface: 'monitor', frameRate: { max: 15 } },
    audio: false,
    monitorTypeSurfaces: 'include',
    selfBrowserSurface: 'exclude',
    surfaceSwitching: 'exclude',
  });
  if (version !== captureVersion) {
    s.getTracks().forEach(t => t.stop());
    throw new DOMException('Screen sharing was cancelled', 'AbortError');
  }
  const track = s.getVideoTracks()[0];
  const surface = track && track.getSettings().displaySurface;
  // Browsers that do not report the surface (older Firefox) are trusted.
  if (surface && surface !== 'monitor') {
    s.getTracks().forEach((t) => t.stop());
    throw new Error('Bạn phải chọn chia sẻ TOÀN BỘ MÀN HÌNH (không chọn cửa sổ hay tab).');
  }
  // Screen text: drop frames rather than resolution when bandwidth is short.
  if ('contentHint' in track) track.contentHint = 'detail';
  stopShare(false);
  stream = s;
  track.addEventListener('ended', () => {
    if (stream !== s) return;
    stream = null;
    closePeers();
    changed();
  });
  changed();
}

export function stopShare(notify = true) {
  captureVersion++;
  if (!stream) return;
  const s = stream;
  stream = null;
  s.getTracks().forEach((t) => t.stop());
  closePeers();
  if (notify) changed();
}

onAuthChange(() => {
  if (sharingUser !== auth.user?.id) stopShare();
  sharingUser = auth.user?.id;
});

function closePeers() {
  for (const [id, pc] of peers) { pc.close(); live.send('screen.stop', { view_id: id }); }
  peers.clear();
}

// The server forgets sharing state when the socket reconnects or the
// session changes; report it again on every subscription.
live.on('subscribed', ({ session_id }) => {
  if (subscribedSession !== session_id) { confirmed = false; notifySharing(); }
  subscribedSession = session_id;
  if (stream) live.send('screen.state', { sharing: true });
});
live.on('screen.state', ({ session_id, sharing }) => {
  if (session_id !== subscribedSession) return;
  confirmed = !!stream && sharing === true;
  notifySharing();
});
live.on('connection', ({ connected }) => {
  if (!connected) { confirmed = false; notifySharing(); }
});

// A teacher wants to watch: offer the shared screen to that viewer.
live.on('screen.view', async ({ view_id: id }) => {
  if (!stream) { live.send('screen.stop', { view_id: id }); return; }
  const pc = new RTCPeerConnection(RTC_CONFIG);
  peers.set(id, pc);
  for (const t of stream.getTracks()) pc.addTrack(t, stream);
  pc.onicecandidate = (e) => { if (e.candidate) live.send('webrtc.signal', { view_id: id, kind: 'ice', payload: e.candidate.toJSON() }); };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === 'failed') { pc.close(); peers.delete(id); live.send('screen.stop', { view_id: id }); }
  };
  try {
    await pc.setLocalDescription(await pc.createOffer());
    live.send('webrtc.signal', { view_id: id, kind: 'offer', payload: pc.localDescription.toJSON() });
  } catch (e) { console.error(e); }
});

// ---------------------------------------------------------------- shared

const viewers = new Map(); // view_id -> {pc, onTrack} (teacher side)

live.on('webrtc.signal', async ({ view_id: id, kind, payload }) => {
  try {
    const sp = peers.get(id);
    if (sp) { // student side: answer and candidates from the teacher
      if (kind === 'answer') await sp.setRemoteDescription(payload);
      else if (kind === 'ice') await sp.addIceCandidate(payload);
      else if (kind === 'quality') await applyQuality(sp, payload);
      return;
    }
    const v = viewers.get(id);
    if (!v) return;
    if (kind === 'offer') {
      await v.pc.setRemoteDescription(payload);
      await v.pc.setLocalDescription(await v.pc.createAnswer());
      live.send('webrtc.signal', { view_id: id, kind: 'answer', payload: v.pc.localDescription.toJSON() });
      if (v.h.quality) sendQuality(id, v.h.quality);
    } else if (kind === 'ice') await v.pc.addIceCandidate(payload);
  } catch (e) { console.error('webrtc', e); }
});

// applyQuality caps the encoder of one viewer's connection (student side):
// {fps, bitrate} from the teacher's grid size, full resolution kept.
async function applyQuality(pc, { fps, bitrate } = {}) {
  for (const sender of pc.getSenders()) {
    if (!sender.track || sender.track.kind !== 'video') continue;
    const p = sender.getParameters();
    if (!p.encodings || !p.encodings.length) continue; // not negotiated yet
    for (const e of p.encodings) {
      if (fps > 0) e.maxFramerate = fps;
      if (bitrate > 0) e.maxBitrate = bitrate;
      e.scaleResolutionDownBy = 1;
    }
    try {
      await sender.setParameters({ ...p, degradationPreference: 'maintain-resolution' });
    } catch {
      await sender.setParameters(p); // browsers without degradationPreference
    }
  }
}

live.on('screen.stop', ({ view_id: id }) => {
  const sp = peers.get(id);
  if (sp) { sp.close(); peers.delete(id); }
  const v = viewers.get(id);
  if (v) { v.pc.close(); viewers.delete(id); v.onEnd(); }
});

// ---------------------------------------------------------------- teacher

const sendQuality = (id, q) => live.send('webrtc.signal', { view_id: id, kind: 'quality', payload: q });

// watch(studentId, {onTrack(stream), onEnd(), quality}) asks the server to
// start a view; the student's browser sends the offer. Returns {stop,
// setQuality({fps, bitrate})}; the quality is sent once the view answers.
const pendingWatch = new Map(); // student_id -> handlers, until screen.view.started
export function watch(studentId, handlers) {
  pendingWatch.set(studentId, handlers);
  live.send('screen.view', { student_id: studentId });
  const each = (fn) => { for (const [id, v] of viewers) if (v.studentId === studentId) fn(id, v); };
  return {
    stop() {
      pendingWatch.delete(studentId);
      each((id, v) => { v.pc.close(); viewers.delete(id); live.send('screen.stop', { view_id: id }); });
    },
    setQuality(q) {
      handlers.quality = q;
      each((id, v) => { if (v.pc.remoteDescription) sendQuality(id, q); });
    },
  };
}

live.on('screen.view.started', ({ view_id: id, student_id: sid }) => {
  const h = pendingWatch.get(sid);
  if (!h) { live.send('screen.stop', { view_id: id }); return; }
  pendingWatch.delete(sid);
  const pc = new RTCPeerConnection(RTC_CONFIG);
  pc.addTransceiver('video', { direction: 'recvonly' });
  pc.ontrack = (e) => h.onTrack(e.streams[0] || new MediaStream([e.track]));
  pc.onicecandidate = (e) => { if (e.candidate) live.send('webrtc.signal', { view_id: id, kind: 'ice', payload: e.candidate.toJSON() }); };
  pc.onconnectionstatechange = () => { if (pc.connectionState === 'failed') h.onError && h.onError('Không kết nối được tới máy sinh viên'); };
  viewers.set(id, { pc, h, studentId: sid, onEnd: h.onEnd || (() => {}) });
});

live.on('error', ({ message }) => {
  // Errors for a pending watch (e.g. the student stopped sharing meanwhile).
  if (/sharing|screens open|subscribe/.test(message || '') && pendingWatch.size) {
    for (const [sid, h] of pendingWatch) { pendingWatch.delete(sid); h.onError && h.onError(message); }
  }
});

// ---------------------------------------------------------------- overlay

// lockOverlay shows a full-screen lock until the student shares the whole
// screen. Returns a cleanup function.
export function lockOverlay() {
  const el = document.createElement('div');
  el.className = 'lock';
  document.body.append(el);
  const draw = (err = '') => {
    mount(el, screenSupported() ? html`
      <div class="lock-box">
        <h2>Bắt buộc chia sẻ màn hình</h2>
        <p>Phiên thi yêu cầu chia sẻ <b>toàn bộ màn hình</b> để giáo viên giám sát trong lúc làm bài.
           Bấm nút bên dưới, chọn <b>Toàn bộ màn hình</b> (Entire screen) rồi bấm <b>Chia sẻ</b>.</p>
        <p class="muted small">Nếu tắt chia sẻ, trang làm bài sẽ bị khóa lại cho tới khi bạn chia sẻ lại. Giáo viên thấy trạng thái chia sẻ của bạn.</p>
        <button class="primary" id="share-btn">Chia sẻ toàn màn hình</button>
        <p class="errors">${err}</p>
      </div>` : html`
      <div class="lock-box">
        <h2>Trình duyệt không chia sẻ màn hình được</h2>
        <p>Hãy mở trang bằng <b>https://</b> (ví dụ <code>https://${location.hostname}:8443/</code>) trên Chrome, Edge hoặc Firefox bản mới.</p>
      </div>`);
    const b = el.querySelector('#share-btn');
    if (b) b.onclick = async () => {
      b.disabled = true;
      try { await startShare(); toast('Đã bắt đầu chia sẻ màn hình', 'ok'); }
      catch (e) { draw(e.name === 'NotAllowedError' ? 'Bạn đã hủy chia sẻ. Hãy thử lại.' : e.message); }
      finally { if (b.isConnected) b.disabled = false; }
    };
  };
  const sync = (sharing) => { el.hidden = sharing; if (!sharing) draw(); };
  sync(isSharing());
  const off = onShareChange(sync);
  return () => { off(); el.remove(); };
}
