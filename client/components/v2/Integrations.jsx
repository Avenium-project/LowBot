'use client';
// ChatGPT (via the official Codex CLI sign-in) and OpenCode Go.
import { useCallback, useEffect, useState } from 'react';
import { api, isLocal } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Card, Field, Section, inputCls } from './ui';

export function CodexConnect({ status, onChanged, compact }) {
  const { lang } = useT();
  const pl = lang === 'pl';
  const [login, setLogin] = useState(status?.codex?.login || null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const codex = status?.codex || {};
  const start = async () => {
    setErr(''); setBusy(true);
    try { setLogin(await api('/integrations/codex/login', { method: 'POST' })); } catch (e) { setErr(e.message); }
    setBusy(false);
  };
  useEffect(() => {
    if (!login || login.status !== 'waiting_for_user') return undefined;
    const h = setInterval(() => onChanged(), 3000);
    return () => clearInterval(h);
  }, [login, onChanged]);
  if (!codex.installed) {
    return <div className="text-sm text-zinc-400">{pl ? 'Codex CLI nie jest zainstalowany na serwerze (obraz Docker z WITH_AGENT_CLIS=1 lub npm i -g @openai/codex).' : 'Codex CLI is not installed on the server.'}</div>;
  }
  if (codex.logged_in) {
    return (
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <span className="text-sm text-emerald-400">● {pl ? 'Połączono z ChatGPT' : 'Connected to ChatGPT'}</span>
        {!compact && <Button small onClick={() => api('/integrations/codex/logout', { method: 'POST' }).then(onChanged)}>{pl ? 'Wyloguj' : 'Sign out'}</Button>}
      </div>
    );
  }
  let host = '';
  try { host = login?.url ? new URL(login.url).host : ''; } catch { host = ''; }
  return (
    <div className="space-y-3">
      <div className="text-sm text-zinc-400">{pl
        ? 'Zalogujesz się na stronie OpenAI swoim kontem ChatGPT. LowBot nie widzi hasła. Limity zależą od Twojego planu ChatGPT (to nie są kredyty API).'
        : 'You sign in on OpenAI’s page with your ChatGPT account. LowBot never sees your password. Usage follows your ChatGPT plan limits (not API credits).'}</div>
      {login?.url ? (
        <div className="rounded-2xl bg-black/30 p-4 text-center space-y-2">
          <div className="text-xs text-zinc-400">{pl ? 'Kod jednorazowy' : 'One-time code'}</div>
          <div className="text-3xl font-mono tracking-widest select-all">{login.code || '—'}</div>
          <div className="text-xs text-zinc-400">{pl ? 'Domena' : 'Domain'}: <b className="text-amber-300">{host}</b></div>
          <a href={login.url} target="_blank" rel="noopener noreferrer"
            className="inline-block rounded-full bg-white text-black px-5 py-2.5 font-medium">{pl ? 'Otwórz stronę logowania' : 'Open sign-in page'}</a>
          <div className="text-xs text-zinc-500">{pl ? 'Czekam na potwierdzenie…' : 'Waiting for confirmation…'}</div>
        </div>
      ) : (
        <button onClick={start} disabled={busy} className="w-full rounded-full bg-white text-black py-3 font-medium disabled:opacity-50">
          {pl ? 'Zaloguj przez ChatGPT' : 'Sign in with ChatGPT'}</button>
      )}
      {login?.status === 'failed' && <div className="text-xs text-rose-300 whitespace-pre-wrap">{(login.output || []).join('\n')}</div>}
      {err && <div className="text-xs text-rose-300">{err}</div>}
    </div>
  );
}

export default function Integrations({ ws }) {
  const { lang } = useT();
  const pl = lang === 'pl';
  const [status, setStatus] = useState(null);
  const [key, setKey] = useState('');
  const [model, setModel] = useState('');
  const [msg, setMsg] = useState('');
  const load = useCallback(() => api('/integrations').then(setStatus).catch(() => {}), []);
  useEffect(() => { load(); }, [load]);

  const addProfile = async (body) => {
    setMsg('');
    try {
      const p = await api('/providers', { method: 'POST', body });
      setMsg(pl ? `Dodano profil „${p.name}”. Wybierz go w ustawieniach bota.` : `Added profile “${p.name}”. Pick it in a bot's settings.`);
      ws?.reload();
    } catch (e) { setMsg(e.message); }
  };
  const saveKey = async () => {
    setMsg('');
    try { await api('/integrations/opencode/key', { method: 'POST', body: { api_key: key, default_model: model || undefined } }); setKey(''); load(); }
    catch (e) { setMsg(e.message); }
  };

  return (
    <Section title={pl ? 'Integracje' : 'Integrations'}>
      {isLocal() ? (
        <Card className="mb-3 space-y-2">
          <div className="font-semibold">ChatGPT · Codex</div>
          <div className="text-sm text-zinc-400">{pl
            ? 'Logowanie kontem ChatGPT działa tylko przez program Codex na komputerze — nie da się go uruchomić w aplikacji na telefonie. Na telefonie użyj klucza API: xAI (modele Grok), OpenAI, OpenCode Go albo OpenRouter.'
            : 'ChatGPT sign-in only works through the Codex program on a computer and cannot run inside a phone app. On the phone use an API key: xAI (Grok models), OpenAI, OpenCode Go or OpenRouter.'}</div>
        </Card>
      ) : (
      <Card className="mb-3 space-y-3">
        <div className="font-semibold">ChatGPT · Codex</div>
        {status && <CodexConnect status={status} onChanged={load} />}
        {status?.codex?.logged_in && (
          <Button kind="primary" onClick={() => addProfile({ kind: 'codex_cli', name: 'ChatGPT (Codex)' })}>
            {pl ? 'Dodaj profil modelu ChatGPT' : 'Add ChatGPT model profile'}</Button>)}
        <div className="text-xs text-zinc-500">{pl
          ? 'Bot z profilem ChatGPT odpowiada przez Codex (jego własny sandbox, bez narzędzi LowBot). Każdy bot może też dostać narzędzie codex.run — zadanie programistyczne w swoim workspace, zawsze po Twojej zgodzie.'
          : 'A bot on the ChatGPT profile answers through Codex (its own sandbox, no LowBot tools). Any bot can also use codex.run for coding tasks in its workspace, always after your approval.'}</div>
      </Card>)}
      <Card className="space-y-3">
        <div className="font-semibold">OpenCode Go</div>
        <div className="text-sm text-zinc-400">{pl
          ? 'Subskrypcja OpenCode Go (otwarte modele do programowania). Klucz API znajdziesz w panelu opencode.ai. Klucz jest szyfrowany.'
          : 'OpenCode Go subscription (open coding models). Get the API key from the opencode.ai console. Stored encrypted.'}</div>
        <div className="text-sm">{status?.opencode?.go_key_configured ? <span className="text-emerald-400">● {pl ? 'Klucz zapisany' : 'Key saved'}</span> : <span className="text-zinc-500">○ {pl ? 'Brak klucza' : 'No key'}</span>}</div>
        <Field label={pl ? 'Klucz API OpenCode Go' : 'OpenCode Go API key'}><input type="password" autoComplete="off" className={inputCls} value={key} onChange={(e) => setKey(e.target.value)} /></Field>
        <Field label={pl ? 'Model (id z OpenCode Go)' : 'Model (OpenCode Go id)'} hint={pl ? 'np. kimi-k2.6 — dokładny identyfikator z listy modeli OpenCode Go; „Testuj połączenie” pokaże dostępne.' : 'Exact id from the OpenCode Go model list; Test connection lists them.'}>
          <input className={inputCls} value={model} onChange={(e) => setModel(e.target.value)} /></Field>
        <div className="flex gap-2 flex-wrap">
          <Button kind="primary" disabled={!key} onClick={saveKey}>{pl ? 'Zapisz klucz' : 'Save key'}</Button>
          <Button disabled={!status?.opencode?.go_key_configured || !model}
            onClick={() => addProfile({ kind: 'opencode_go', name: `OpenCode Go · ${model}`, default_model: model })}>
            {pl ? 'Dodaj profil OpenCode Go' : 'Add OpenCode Go profile'}</Button>
        </div>
        <div className="text-xs text-zinc-500">{pl
          ? 'Profil OpenCode Go działa z pełnymi narzędziami LowBot (przeglądarka, delegowanie, zgody).'
            + (status?.opencode?.installed ? ' Na serwerze jest też OpenCode CLI — narzędzie opencode.run (powłoka i sieć wyłączone).' : '')
          : 'The OpenCode Go profile works with all LowBot tools (browser, delegation, approvals).'
            + (status?.opencode?.installed ? ' OpenCode CLI is installed too — opencode.run tool (shell and web disabled).' : '')}</div>
      </Card>
      {msg && <div className="text-sm text-amber-200 mt-2">{msg}</div>}
    </Section>
  );
}
