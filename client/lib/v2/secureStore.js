'use client';
// Device-token storage.
//  * Windows (Tauri): Rust commands backed by the OS credential manager
//    (`keyring` crate, see desktop/src-tauri/src/main.rs).
//  * Android (Capacitor): capacitor-secure-storage-plugin (Android Keystore).
//  * Web: device tokens are not used (HttpOnly cookie session instead); an
//    in-memory fallback keeps a pasted token for the current tab only.

const memory = new Map();

async function tauriInvoke(cmd, args) {
  const invoke = window.__TAURI_INTERNALS__?.invoke || window.__TAURI__?.core?.invoke;
  if (!invoke) throw new Error('no tauri');
  return invoke(cmd, args);
}

function capacitorPlugin() {
  return window.Capacitor?.Plugins?.SecureStoragePlugin;
}

export const secureStore = {
  async get(key) {
    if (typeof window === 'undefined') return null;
    try { return await tauriInvoke('secret_get', { key }); } catch { /* not tauri */ }
    const cap = capacitorPlugin();
    if (cap) { try { return (await cap.get({ key })).value; } catch { return null; } }
    return memory.get(key) ?? null;
  },
  async set(key, value) {
    try { await tauriInvoke('secret_set', { key, value }); return; } catch { /* not tauri */ }
    const cap = capacitorPlugin();
    if (cap) { await cap.set({ key, value }); return; }
    memory.set(key, value);
  },
  async remove(key) {
    try { await tauriInvoke('secret_delete', { key }); return; } catch { /* not tauri */ }
    const cap = capacitorPlugin();
    if (cap) { try { await cap.remove({ key }); } catch { /* ignore */ } return; }
    memory.delete(key);
  },
};
