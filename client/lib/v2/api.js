'use client';
// LowBot client (v2 API).
//
// Two auth modes:
//  * web    – same-site HttpOnly session cookie created by /api/v1/auth/login
//  * device – per-device Bearer token from the pairing flow, kept in the OS
//             secure store by the native shell (never localStorage, never URL).
//
// Messages carry a client_msg_id; the outbox is replayed after reconnects and
// the server de-duplicates, so a flaky network never duplicates work.

import { secureStore } from './secureStore';

const CONFIG_KEY = 'opendots.server';
const OUTBOX_KEY = 'opendots.outbox';
const CURSOR_KEY = 'opendots.cursor';

function defaultServer() {
  if (process.env.NEXT_PUBLIC_API_URL) return process.env.NEXT_PUBLIC_API_URL.replace(/\/api\/v1\/?$/, '');
  if (typeof window === 'undefined') return '';
  const { protocol, hostname } = window.location;
  if (protocol.startsWith('http') && window.location.port === '3000') return `${protocol}//${hostname}:8000`;
  if (protocol.startsWith('http') && !isNative()) return window.location.origin;
  return '';
}

// LowBot for Android hosts the whole backend inside the app; the UI reaches it
// through window.LowBotNative.request (no network, no server, no token).
export function isLocal() {
  if (typeof window === 'undefined') return false;
  try { return Boolean(window.LowBotNative?.isLocal?.()); } catch { return false; }
}

const pending = new Map();
let seq = 0;
function installLocalHooks() {
  if (window.__lowbotResolve) return;
  window.__lowbotResolve = (id, status, type, payload, isB64, filename) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    let body;
    if (isB64) {
      const bin = atob(payload);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i += 1) bytes[i] = bin.charCodeAt(i);
      body = new Blob([bytes], { type });
    } else body = payload;
    // Header values must be Latin-1: non-ASCII file names (ą, ł, …) go in the RFC 5987 form.
    const headers = { 'Content-Type': type || 'application/octet-stream' };
    if (filename) headers['Content-Disposition'] = `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`;
    try { p(new Response(body, { status, headers })); } catch { p(new Response(body, { status })); }
  };
}

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1] || '');
    r.onerror = reject;
    r.readAsDataURL(blob);
  });
}

async function localFetch(path, method, body, form) {
  installLocalHooks();
  let payload = body === undefined ? '' : JSON.stringify(body);
  if (form) {
    const file = form.get('file');
    payload = JSON.stringify({ name: file?.name || 'upload', mime: file?.type || '', data_base64: file ? await blobToBase64(file) : '' });
  }
  seq += 1;
  const id = `r${Date.now()}_${seq}`;
  return new Promise((resolve) => {
    pending.set(id, resolve);
    window.LowBotNative.request(id, method, `/api/v2${path}`, payload);
  });
}

export function isNative() {
  if (typeof window === 'undefined') return false;
  return Boolean(window.LowBotNative || window.__TAURI_INTERNALS__ || window.__TAURI__);
}

export function platform() {
  if (typeof window === 'undefined') return 'web';
  if (window.LowBotNative) return 'android';
  if (window.__TAURI_INTERNALS__ || window.__TAURI__) return 'windows';
  return 'web';
}

export function getConfig() {
  if (typeof window === 'undefined') return { server: '', mode: 'web' };
  if (isLocal()) return { server: '', mode: 'local' };
  try {
    const saved = JSON.parse(window.localStorage.getItem(CONFIG_KEY) || 'null');
    if (saved?.server) return saved; // only the server URL + mode; no credentials
  } catch { /* ignore */ }
  return { server: defaultServer(), mode: isNative() ? 'device' : 'web' };
}

export function setConfig(cfg) {
  window.localStorage.setItem(CONFIG_KEY, JSON.stringify({ server: cfg.server.replace(/\/+$/, ''), mode: cfg.mode }));
}

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function authHeaders() {
  const cfg = getConfig();
  if (cfg.mode === 'device') {
    const token = await secureStore.get('device_token');
    return token ? { Authorization: `Bearer ${token}` } : {};
  }
  return {};
}

export async function api(path, { method = 'GET', body, form, signal, raw } = {}) {
  const cfg = getConfig();
  let res;
  if (cfg.mode === 'local') {
    res = await localFetch(path, method, body, form);
  } else {
    const headers = { ...(await authHeaders()) };
    let payload;
    if (form) payload = form;
    else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
    res = await fetch(`${cfg.server}/api/v2${path}`, {
      method, headers, body: payload, signal, credentials: cfg.mode === 'web' ? 'include' : 'omit',
    });
  }
  if (res.status === 401) {
    window.dispatchEvent(new Event('opendots:auth-required'));
    throw new ApiError(401, 'Authentication required');
  }
  if (raw) return res;
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, (data && (typeof data.detail === 'string' ? data.detail : JSON.stringify(data.detail))) || res.statusText);
  return data;
}

export async function webLogin(token) {
  const cfg = getConfig();
  const res = await fetch(`${cfg.server}/api/v1/auth/login`, {
    method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token }),
  });
  if (!res.ok) throw new ApiError(res.status, res.status === 401 ? 'Wrong owner token' : 'Login failed');
  return res.json();
}

export async function pairDevice(server, code, name) {
  const res = await fetch(`${server.replace(/\/+$/, '')}/api/v2/pair/exchange`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, name, platform: platform() }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, data.detail || 'Pairing failed');
  await secureStore.set('device_token', data.token);
  setConfig({ server, mode: 'device' });
  return data;
}

// Native apps can sign in with the owner token once: it is used only to mint a
// one-time pairing code, exchanged for a revocable device token, and then
// forgotten. Only the device token is stored (OS keystore).
export async function pairWithOwnerToken(server, ownerToken, name) {
  const base = server.replace(/\/+$/, '');
  const res = await fetch(`${base}/api/v2/pair/code`, { method: 'POST', headers: { Authorization: `Bearer ${ownerToken}` } });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, res.status === 401 ? 'Wrong owner token' : (data.detail || 'Pairing failed'));
  return pairDevice(base, data.code, name);
}

export async function saveBlob(blob, name) {
  if (window.LowBotNative?.saveFile) {
    const b64 = await new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result).split(',')[1] || '');
      r.onerror = reject;
      r.readAsDataURL(blob);
    });
    if (!window.LowBotNative.saveFile(name || 'file', blob.type || 'application/octet-stream', b64)) throw new ApiError(500, 'Could not save the file to Downloads.');
    return true;
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = name || 'file'; a.click();
  return true;
}

function blobToB64(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1] || '');
    r.onerror = reject;
    r.readAsDataURL(blob);
  });
}

/** Opens a downloaded file in another app (mode 'view') or the share sheet ('share'). */
export async function openBlob(blob, name, mode = 'view') {
  if (window.LowBotNative?.openFile) {
    const r = window.LowBotNative.openFile(name || 'file', blob.type || 'application/octet-stream', await blobToB64(blob), mode);
    if (r !== 'ok') throw new ApiError(500, 'Could not open the file.');
    return true;
  }
  if (mode === 'share' && navigator.share && navigator.canShare?.({ files: [new File([blob], name || 'file', { type: blob.type })] })) {
    await navigator.share({ files: [new File([blob], name || 'file', { type: blob.type })] });
    return true;
  }
  window.open(URL.createObjectURL(blob), '_blank');
  return true;
}

export async function fetchBlob(path) {
  const res = await api(path, { raw: true });
  if (!res.ok) throw new ApiError(res.status, res.status === 404 ? 'The file no longer exists.' : 'Download failed');
  return res.blob();
}

export async function downloadPath(path, name) {
  const res = await api(path, { raw: true });
  if (!res.ok) throw new ApiError(res.status, 'download failed');
  return saveBlob(await res.blob(), name);
}

export async function signOutDevice() {
  await secureStore.remove('device_token');
}

// ------------------------------------------------------------------ outbox --
function readOutbox() {
  try { return JSON.parse(window.localStorage.getItem(OUTBOX_KEY) || '[]'); } catch { return []; }
}
function writeOutbox(items) { window.localStorage.setItem(OUTBOX_KEY, JSON.stringify(items.slice(-50))); }

export function newClientId() {
  return (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`);
}

export async function sendMessage(conversationId, text, attachments = []) {
  const item = { conversationId, text, attachments, client_msg_id: newClientId(), at: Date.now() };
  writeOutbox([...readOutbox(), item]);
  return flushOne(item);
}

async function flushOne(item) {
  const res = await api(`/conversations/${item.conversationId}/messages`, {
    method: 'POST', body: { text: item.text, client_msg_id: item.client_msg_id, attachments: item.attachments },
  });
  writeOutbox(readOutbox().filter((x) => x.client_msg_id !== item.client_msg_id));
  return res;
}

export async function flushOutbox() {
  for (const item of readOutbox()) {
    try { await flushOne(item); } catch (e) { if (e.status && e.status < 500 && e.status !== 401) writeOutbox(readOutbox().filter((x) => x.client_msg_id !== item.client_msg_id)); else break; }
  }
}

export function pendingOutbox(conversationId) {
  return readOutbox().filter((x) => x.conversationId === conversationId);
}

// -------------------------------------------------------------- event stream --
// SSE over fetch so device tokens travel in a header (EventSource cannot set
// headers, and tokens must never go into URLs). Resumes from the last cursor.
export function subscribeEvents(onEvent, onStatus) {
  if (isLocal()) {
    // Events are pushed by the in-app backend; nothing to reconnect.
    const prev = window.__lowbotEvent;
    window.__lowbotEvent = (ev) => { try { onEvent(ev); } catch { /* ignore */ } };
    window.LowBotNative.subscribe();
    onStatus?.('live');
    return () => { window.__lowbotEvent = prev; };
  }
  let stopped = false;
  let controller;
  let cursor = Number(window.sessionStorage.getItem(CURSOR_KEY) || 0) || null;
  let backoff = 500;

  async function loop() {
    while (!stopped) {
      controller = new AbortController();
      try {
        onStatus?.('connecting');
        const q = cursor ? `?after=${cursor}` : '';
        const res = await api(`/events/stream${q}`, { raw: true, signal: controller.signal });
        if (!res.ok || !res.body) throw new ApiError(res.status, 'stream failed');
        onStatus?.('live');
        backoff = 500;
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += decoder.decode(value, { stream: true });
          let idx;
          while ((idx = buf.indexOf('\n\n')) >= 0 || (idx = buf.indexOf('\r\n\r\n')) >= 0) {
            const chunk = buf.slice(0, idx);
            buf = buf.slice(idx + (buf[idx] === '\r' ? 4 : 2));
            let ev = 'message'; const data = [];
            for (const line of chunk.split(/\r?\n/)) {
              if (line.startsWith('event:')) ev = line.slice(6).trim();
              else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
            }
            if (!data.length) continue;
            let parsed; try { parsed = JSON.parse(data.join('\n')); } catch { continue; }
            if (ev === 'hello' && !cursor) cursor = parsed.cursor;
            if (ev === 'event') {
              cursor = parsed.id;
              window.sessionStorage.setItem(CURSOR_KEY, String(cursor));
              onEvent(parsed);
            }
          }
        }
      } catch (e) {
        if (stopped) return;
        if (e.status === 401) { onStatus?.('auth'); return; }
      }
      if (stopped) return;
      onStatus?.('offline');
      await new Promise((r) => setTimeout(r, backoff));
      backoff = Math.min(backoff * 2, 15000);
      flushOutbox().catch(() => {});
    }
  }
  loop();
  return () => { stopped = true; controller?.abort(); };
}

export function artifactUrl(id) {
  return `${getConfig().server}/api/v2/artifacts/${id}/download`;
}

export async function fetchBlobUrl(path) {
  const res = await api(path, { raw: true });
  if (!res.ok) throw new ApiError(res.status, 'download failed');
  return URL.createObjectURL(await res.blob());
}
