"use client";

import React, { useState, useEffect } from "react";
import { FiX, FiEye, FiEyeOff, FiPlus, FiTrash2, FiChevronDown } from "react-icons/fi";
import { fetchSettings, saveSettings } from "../lib/api";

const inputClass = "w-full bg-[#222226] border border-[#36363d] rounded-lg px-3 py-2.5 text-xs text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-violet-400";
const cardClass = "bg-[#18181b] border border-[#27272a] rounded-2xl p-4 space-y-4";
const buttonClass = "rounded-lg px-3 py-2 text-xs font-medium bg-violet-500 text-white hover:bg-violet-400 disabled:opacity-50 disabled:cursor-not-allowed";

export default function AppSettingsDrawer({ models, isOpen, onClose, currentModel, onUpdateDefaultModel, onProfileUpdate }) {
  const [userName, setUserName] = useState("");
  const [userEmail, setUserEmail] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [wireApi, setWireApi] = useState("responses");
  const [apiKey, setApiKey] = useState("");
  const [keyConfigured, setKeyConfigured] = useState(false);
  const [showKey, setShowKey] = useState(false);
  const [modelIds, setModelIds] = useState("");
  const [defaultModel, setDefaultModel] = useState("");
  const [headersConfigured, setHeadersConfigured] = useState(false);
  const [headersMode, setHeadersMode] = useState("keep");
  const [headers, setHeaders] = useState([{ name: "", value: "" }]);
  const [composioKey, setComposioKey] = useState("");
  const [composioConfigured, setComposioConfigured] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState(null);
  const [connectorNotice, setConnectorNotice] = useState(null);

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    setLoaded(false);
    setNotice(null);
    setConnectorNotice(null);
    setApiKey("");
    setComposioKey("");
    setShowKey(false);
    setUserName(localStorage.getItem("open_dots_user_name") || "");
    setUserEmail(localStorage.getItem("open_dots_user_email") || "");
    fetchSettings().then((data) => {
      if (cancelled) return;
      if (!data) {
        setNotice({ error: true, text: "Could not load settings. Reopen this panel to retry." });
        return;
      }
      setBaseUrl(data.model_api_base_url || "");
      setWireApi(data.model_api_wire_api || "prediction");
      setKeyConfigured(Boolean(data.model_api_key_configured));
      setModelIds((data.model_ids || []).join("\n"));
      setDefaultModel(data.default_model || currentModel || "gpt-5-mini");
      setHeadersConfigured(Boolean(data.model_api_headers_configured));
      setHeadersMode("keep");
      setHeaders([{ name: "", value: "" }]);
      setComposioConfigured(Boolean(data.composio_api_key_configured));
      setLoaded(true);
    });
    return () => { cancelled = true; };
    // Reload when opening, without overwriting a draft when the active model changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  if (!isOpen) return null;

  const saveProvider = async (event) => {
    event.preventDefault();
    setNotice(null);
    setSaving(true);
    try {
      const url = new URL(baseUrl.trim());
      if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
        throw new Error("Enter an http(s) API base URL without credentials, query parameters, or fragments.");
      }
      const selectedModel = defaultModel.trim();
      if (!selectedModel) throw new Error("Enter a default model ID.");
      const ids = [...new Set([...modelIds.split(/[\n,]+/).map((id) => id.trim()).filter(Boolean), selectedModel])];
      const payload = {
        model_api_base_url: baseUrl.trim().replace(/\/+$/, ""),
        model_api_wire_api: wireApi,
        model_api_key: apiKey.trim(),
        model_ids: ids,
        default_model: selectedModel,
      };
      if (headersMode === "replace") {
        const entries = headers.map(({ name, value }) => [name.trim(), value]);
        if (!entries.length || entries.some(([name, value]) => !name || !value)) {
          throw new Error("Fill in a name and value for each header, or select Remove all.");
        }
        if (new Set(entries.map(([name]) => name.toLowerCase())).size !== entries.length) {
          throw new Error("Each custom header must have a unique name.");
        }
        payload.model_api_headers = Object.fromEntries(entries);
      } else if (headersMode === "remove") {
        payload.clear_model_api_headers = true;
      }
      const saved = await saveSettings(payload);
      setBaseUrl(saved.model_api_base_url);
      setApiKey("");
      setShowKey(false);
      setKeyConfigured(Boolean(saved.model_api_key_configured));
      setHeadersConfigured(Boolean(saved.model_api_headers_configured));
      setHeadersMode("keep");
      setHeaders([{ name: "", value: "" }]);
      setModelIds(saved.model_ids.join("\n"));
      setDefaultModel(saved.default_model);
      await onUpdateDefaultModel?.(saved.default_model);
      setNotice({ text: "Provider settings saved. Model menus updated." });
    } catch (error) {
      setNotice({ error: true, text: error.message || "Could not save provider settings." });
    } finally {
      setSaving(false);
    }
  };

  const saveConnector = async (event) => {
    event.preventDefault();
    setSaving(true);
    setConnectorNotice(null);
    try {
      const saved = await saveSettings({ composio_api_key: composioKey.trim() });
      setComposioKey("");
      setComposioConfigured(Boolean(saved.composio_api_key_configured));
      setConnectorNotice({ text: "Connector key saved." });
    } catch (error) {
      setConnectorNotice({ error: true, text: error.message });
    } finally {
      setSaving(false);
    }
  };

  return (
    <aside aria-label="App Settings" className="w-96 md:w-[420px] max-w-[100vw] h-screen bg-[#111113] border-l border-[#27272a] flex flex-col z-30 shadow-2xl flex-shrink-0">
      <div className="p-5 border-b border-[#27272a] flex items-center justify-between">
        <h2 className="text-sm font-bold text-zinc-100">App Settings</h2>
        <button onClick={onClose} title="Close App Settings" className="p-1 text-zinc-400 hover:text-white"><FiX /></button>
      </div>
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        <details className={`${cardClass} group`}>
          <summary className="cursor-pointer list-none [&::-webkit-details-marker]:hidden rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-violet-400">
            <span className="flex items-center justify-between gap-3">
              <span className="text-sm font-semibold text-zinc-100">Model provider</span>
              <FiChevronDown aria-hidden="true" className="text-zinc-400 transition-transform group-open:rotate-180" />
            </span>
            <span className="block text-xs text-zinc-400 mt-1 break-words">
              {loaded ? `${wireApi === "responses" ? "Responses API" : "Prediction API"} · ${defaultModel}` : "Configure API endpoint, credentials and models"}
            </span>
            <span className="block text-[11px] text-violet-300 mt-2">Click to configure</span>
          </summary>
          <form onSubmit={saveProvider} onChange={() => setNotice(null)} className="space-y-4 border-t border-[#27272a] pt-4">
          <p className="text-xs text-zinc-400">Configure your API endpoint and models. Shared by all assistants.</p>
          {!loaded && !notice && <p role="status" className="text-xs text-zinc-400">Loading settings…</p>}
          <fieldset disabled={!loaded || saving} className="space-y-4 disabled:opacity-60">
            <div className="space-y-1.5">
              <label htmlFor="provider-url" className="block text-xs font-medium">API Base URL</label>
              <input id="provider-url" type="url" required value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://your-provider.example/v1" className={inputClass} />
              <p className="text-[11px] text-zinc-500">Enter the API root, usually ending in /v1. Do not append /responses.</p>
            </div>
            <div className="space-y-1.5">
              <label htmlFor="provider-protocol" className="block text-xs font-medium">API protocol</label>
              <select id="provider-protocol" value={wireApi} onChange={(e) => setWireApi(e.target.value)} className={inputClass}>
                <option value="responses">Responses API</option>
                <option value="prediction">Prediction API (original adapter)</option>
              </select>
              <p className="text-[11px] text-zinc-500">{wireApi === "responses" ? "Uses /responses with Bearer authentication. Chat Completions is not supported yet." : "Uses /{model_id} and prediction polling with x-api-key authentication."}</p>
            </div>
            <div className="space-y-1.5">
              <label htmlFor="provider-key" className="block text-xs font-medium">Inference API Key</label>
              <div className="relative">
                <input id="provider-key" type={showKey ? "text" : "password"} autoComplete="new-password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder={keyConfigured ? "Stored securely — leave blank to keep" : "Enter API key"} className={`${inputClass} pr-10`} />
                <button type="button" title={showKey ? "Hide API key" : "Show API key"} onClick={() => setShowKey(!showKey)} className="absolute right-3 top-3 text-zinc-400">{showKey ? <FiEyeOff /> : <FiEye />}</button>
              </div>
            </div>
            <div className="space-y-1.5">
              <label htmlFor="provider-models" className="block text-xs font-medium">Model IDs</label>
              <textarea id="provider-models" rows={3} value={modelIds} onChange={(e) => setModelIds(e.target.value)} placeholder="One model ID per line" className={`${inputClass} font-mono resize-y`} />
              <p className="text-[11px] text-zinc-500">Use exact IDs from your provider, separated by lines or commas.</p>
            </div>
            <div className="space-y-1.5">
              <label htmlFor="provider-default-model" className="block text-xs font-medium">Default model ID</label>
              <input id="provider-default-model" required value={defaultModel} onChange={(e) => setDefaultModel(e.target.value)} list="provider-model-options" className={inputClass} />
              <datalist id="provider-model-options">{[...new Set([...modelIds.split(/[\n,]+/).map((id) => id.trim()).filter(Boolean), ...(models || []).map((model) => model.id)])].map((id) => <option key={id} value={id} />)}</datalist>
              <p className="text-[11px] text-zinc-500">Used for new assistants. Existing assistants keep their selected model.</p>
            </div>
            <details className="border border-[#36363d] rounded-lg p-3">
              <summary className="cursor-pointer text-xs font-medium">Custom headers · {headersConfigured ? "Configured" : "Optional"}</summary>
              <div className="mt-3 space-y-3">
                <label htmlFor="provider-header-action" className="block text-xs text-zinc-400">Header action</label>
                <select id="provider-header-action" value={headersMode} onChange={(e) => setHeadersMode(e.target.value)} className={inputClass}>
                  <option value="keep">Keep stored headers</option>
                  <option value="replace">Replace all headers</option>
                  <option value="remove">Remove all headers</option>
                </select>
                <p className="text-[11px] text-zinc-500">Values are encrypted and never shown after saving. Replace all requires the complete set.</p>
                {headersMode === "replace" && <>
                  {headers.map((header, index) => <div key={index} className="space-y-2 rounded-lg bg-[#111113] p-2">
                    <input aria-label={`Header name ${index + 1}`} value={header.name} onChange={(e) => setHeaders(headers.map((row, i) => i === index ? { ...row, name: e.target.value } : row))} placeholder="Header name" className={inputClass} />
                    <div className="flex gap-2">
                      <input aria-label={`Header value ${index + 1}`} type="password" autoComplete="new-password" value={header.value} onChange={(e) => setHeaders(headers.map((row, i) => i === index ? { ...row, value: e.target.value } : row))} placeholder="Header value" className={inputClass} />
                      <button type="button" title={`Remove header ${index + 1}`} onClick={() => setHeaders(headers.filter((_, i) => i !== index))} className="p-2 text-zinc-400 hover:text-red-400"><FiTrash2 /></button>
                    </div>
                  </div>)}
                  <button type="button" onClick={() => setHeaders([...headers, { name: "", value: "" }])} className="flex items-center gap-1 text-xs text-violet-300"><FiPlus /> Add header</button>
                </>}
              </div>
            </details>
            <button type="submit" className={`${buttonClass} w-full`}>{saving ? "Saving…" : "Save provider settings"}</button>
          </fieldset>
          {notice && <p role={notice.error ? "alert" : "status"} className={`text-xs ${notice.error ? "text-red-400" : "text-emerald-400"}`}>{notice.text}</p>}
          </form>
        </details>

        <form onSubmit={saveConnector} className={cardClass}>
          <h3 className="text-sm font-semibold">App connectors</h3>
          <label htmlFor="composio-key" className="block text-xs font-medium">Composio API Key</label>
          <input id="composio-key" type="password" autoComplete="new-password" value={composioKey} onChange={(e) => setComposioKey(e.target.value)} placeholder={composioConfigured ? "Stored securely — leave blank to keep" : "Optional connector key"} className={inputClass} disabled={!loaded || saving} />
          <button disabled={!loaded || saving} className={buttonClass}>Save connector key</button>
          {connectorNotice && <p role={connectorNotice.error ? "alert" : "status"} className={`text-xs ${connectorNotice.error ? "text-red-400" : "text-emerald-400"}`}>{connectorNotice.text}</p>}
        </form>

        <div className={cardClass}>
          <h3 className="text-sm font-semibold">Profile</h3>
          <p className="text-xs text-zinc-400">Saved as you type.</p>
          <label htmlFor="profile-name" className="block text-xs font-medium">Your name</label>
          <input id="profile-name" value={userName} onChange={(e) => { setUserName(e.target.value); localStorage.setItem("open_dots_user_name", e.target.value); onProfileUpdate?.(e.target.value); }} className={inputClass} />
          <label htmlFor="profile-email" className="block text-xs font-medium">Email</label>
          <input id="profile-email" type="email" value={userEmail} onChange={(e) => { setUserEmail(e.target.value); localStorage.setItem("open_dots_user_email", e.target.value); }} className={inputClass} />
        </div>
      </div>
    </aside>
  );
}
