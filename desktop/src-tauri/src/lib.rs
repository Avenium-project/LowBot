//! Open Dots desktop shell (LowBot extension).
//!
//! The window only loads the bundled static UI. The only native surface
//! exposed to it is a tiny secret store for the per-device token, backed by
//! the OS credential manager (Windows Credential Manager via `keyring`).
//! No shell, filesystem or arbitrary-URL access is granted to the webview.
//! Local computer access is NOT provided by installing this client.

const SERVICE: &str = "open-dots";
// Only these keys may be stored; the UI cannot use this as a generic vault.
const ALLOWED_KEYS: &[&str] = &["device_token"];

fn entry(key: &str) -> Result<keyring::Entry, String> {
    if !ALLOWED_KEYS.contains(&key) {
        return Err("key not allowed".into());
    }
    keyring::Entry::new(SERVICE, key).map_err(|e| e.to_string())
}

#[tauri::command]
fn secret_get(key: String) -> Result<Option<String>, String> {
    match entry(&key)?.get_password() {
        Ok(v) => Ok(Some(v)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
fn secret_set(key: String, value: String) -> Result<(), String> {
    if value.len() > 512 {
        return Err("value too long".into());
    }
    entry(&key)?.set_password(&value).map_err(|e| e.to_string())
}

#[tauri::command]
fn secret_delete(key: String) -> Result<(), String> {
    match entry(&key)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![secret_get, secret_set, secret_delete])
        .run(tauri::generate_context!())
        .expect("error while running Open Dots");
}
