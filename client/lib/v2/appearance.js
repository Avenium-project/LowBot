// Per-device look of the app (text size, motion, previews), kept in localStorage.
const KEY = 'lowbot.appearance';
const ZOOM = { s: 0.9, m: 1, l: 1.12, xl: 1.25 };
const DEFAULTS = { size: 'm', motion: true, ghosts: true, previews: true };

export function loadAppearance() {
  try { return { ...DEFAULTS, ...JSON.parse(window.localStorage.getItem(KEY) || '{}') }; } catch { return { ...DEFAULTS }; }
}

export function saveAppearance(a) {
  try { window.localStorage.setItem(KEY, JSON.stringify(a)); } catch { /* storage unavailable */ }
}

export function applyAppearance(a = loadAppearance()) {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  root.style.zoom = String(ZOOM[a.size] || 1);
  root.classList.toggle('lb-no-motion', !a.motion);
  root.classList.toggle('lb-still-ghosts', !a.ghosts);
  root.classList.toggle('lb-no-previews', !a.previews);
}
