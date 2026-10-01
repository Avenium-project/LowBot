'use client';
// First-run wizard: instance -> pairing / sign-in -> model -> test -> first bot.
import { useEffect, useState } from 'react';
import { api, getConfig, isLocal, isNative, pairDevice, pairWithOwnerToken, setConfig, webLogin } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Card, Field, inputCls } from './ui';
import { ChatGptPhone, CodexConnect } from './Integrations';

export default function SetupWizard({ onReady, startAt = 0 }) {
  const { t, lang, setLang } = useT();
  const cfg = getConfig();
  const [step, setStep] = useState(startAt);
  const [server, setServer] = useState(cfg.server);
  const [code, setCode] = useState('');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const local = isLocal();
  const [prov, setProv] = useState({ kind: local ? 'xai' : 'codex_cli', base_url: '', api_key: '', default_model: '' });
  const [presets, setPresets] = useState([]);
  const [profile, setProfile] = useState(null);
  const [test, setTest] = useState(null);
  const [botName, setBotName] = useState('Assistant');
  const [integ, setInteg] = useState(null);
  const loadInteg = () => api('/integrations').then(setInteg).catch(() => {});
  useEffect(() => {
    if (step !== 2) return;
    loadInteg();
    if (!presets.length) api('/providers/presets').then(setPresets).catch(() => {});
    if (local && !profile) api('/providers').then((d) => { if (d.profiles.length) { setProfile(d.profiles[0]); setStep(4); } }).catch(() => {});
  }, [step, presets.length]); // eslint-disable-line react-hooks/exhaustive-deps
  const native = isNative();

  useEffect(() => {
    // Deep link opendots://pair?server=...&code=... (scanned QR) pre-fills the form.
    const params = new URLSearchParams(window.location.search);
    if (params.get('server')) setServer(params.get('server'));
    if (params.get('code')) setCode(params.get('code'));
  }, []);

  const run = async (fn) => { setBusy(true); setErr(''); try { await fn(); } catch (e) { setErr(e.message); } finally { setBusy(false); } };

  const checkServer = () => run(async () => {
    const res = await fetch(`${server.replace(/\/+$/, '')}/api/v2/health`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const h = await res.json();
    if (h.api !== 'v2') throw new Error('Not an LowBot server');
    setConfig({ server, mode: native ? 'device' : 'web' });
    setStep(1);
  });

  const authenticate = () => run(async () => {
    if (code) await pairDevice(server, code, native ? 'LowBot (Android)' : 'Browser');
    else if (native) await pairWithOwnerToken(server, token, 'LowBot (Android)');
    else await webLogin(token);
    const [d, bots] = await Promise.all([api('/providers'), api('/bots')]);
    if (bots.length) { onReady(); return; } // existing install: straight to the chats
    setPresets(await api('/providers/presets'));
    if (d.profiles.length) { setProfile(d.profiles[0]); setStep(4); } else setStep(2);
  });

  const saveProvider = () => run(async () => {
    if (prov.kind === 'chatgpt_oauth') { // the profile is created on sign-in
      const d = await api('/providers');
      const existing = d.profiles.find((x) => x.kind === 'chatgpt_oauth');
      if (existing) { setProfile(existing); setStep(3); return; }
    }
    const p = await api('/providers', { method: 'POST', body: { ...prov, base_url: prov.base_url || undefined, api_key: prov.api_key || undefined } });
    setProfile(p); setStep(3);
  });

  const runTest = () => run(async () => { setTest(await api(`/providers/${profile.id}/test`, { method: 'POST', body: {} })); });

  const createBot = () => run(async () => {
    const bots = await api('/bots');
    if (!bots.length) await api('/bots', { method: 'POST', body: { name: botName, provider_profile_id: profile?.id } });
    window.LowBotNative?.requestNotifications?.();
    onReady();
  });

  const steps = local
    ? [null, null, t('models'), t('testConnection'), t('firstBot')]
    : [t('serverUrl'), native ? t('pairDevice') : t('signIn'), t('models'), t('testConnection'), t('firstBot')];
  return (
    <div className="min-h-[100dvh] flex items-center justify-center p-4 bg-zinc-950 text-zinc-100 select-text">
      <Card className="lb-rise w-full max-w-md space-y-3">
        <div className="flex justify-between items-center">
          <h1 className="text-lg font-semibold">{t('setupTitle')}</h1>
        </div>
        {local && step === 2 && <div className="text-[15px] text-zinc-400">{'LowBot runs entirely on this phone — no server. Pick a model provider and paste your API key (e.g. xAI for Grok models, or OpenCode Go). The key is encrypted with the Android keystore.'}</div>}
        <ol className="flex gap-1 text-[12px] text-zinc-500 flex-wrap">{steps.map((s, i) => s && <li key={s} className={i === step ? 'text-sky-400' : ''}>{local ? i - 1 : i + 1}. {s}{i < steps.length - 1 ? ' ›' : ''}</li>)}</ol>
        {step === 0 && <>
          <Field label={t('serverUrl')} hint={'Your LowBot server (use HTTPS outside your LAN).'}>
            <input className={inputCls} value={server} onChange={(e) => setServer(e.target.value)} placeholder="https://dots.example.com" inputMode="url" /></Field>
          <Button kind="primary" disabled={busy || !server} onClick={checkServer}>{t('next')}</Button>
        </>}
        {step === 1 && <>
          {!token ? <Field label={t('pairingCode')} hint={'Create a code in Settings → Devices on a signed-in device. One-time, expires.'}>
            <input className={`${inputCls} font-mono tracking-widest`} value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="ABCD-EFGH" /></Field> : null}
          {!code && <Field label={t('ownerToken')} hint={native
              ? ('Or the owner token; used once to create a revocable device token, never stored.')
              : ('Or the owner token (exchanged for an HttpOnly session).')}>
            <input className={inputCls} type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} /></Field>}
          <div className="flex gap-2"><Button onClick={() => setStep(0)}>{t('back')}</Button><Button kind="primary" disabled={busy || (!code && !token)} onClick={authenticate}>{t('next')}</Button></div>
        </>}
        {step === 2 && <>
          <Field label={t('provider')}><select className={inputCls} value={prov.kind} onChange={(e) => setProv({ ...prov, kind: e.target.value })}>
            {presets.map((p) => <option key={p.kind} value={p.kind}>{p.label}</option>)}</select></Field>
          {prov.kind === 'codex_cli' && integ && <CodexConnect status={integ} onChanged={loadInteg} compact />}
          {prov.kind === 'chatgpt_oauth' && integ && <ChatGptPhone status={integ} onChanged={loadInteg} compact />}
          {!['scripted_mock', 'codex_cli', 'opencode_cli', 'chatgpt_oauth'].includes(prov.kind) && <>
            <Field label="Base URL"><input className={inputCls} value={prov.base_url} placeholder={presets.find((p) => p.kind === prov.kind)?.base_url} onChange={(e) => setProv({ ...prov, base_url: e.target.value })} /></Field>
            <Field label="API key"><input className={inputCls} type="password" autoComplete="off" value={prov.api_key} onChange={(e) => setProv({ ...prov, api_key: e.target.value })} /></Field>
          </>}
          <Field label={t('model')} hint={prov.kind === 'codex_cli' ? ('Optional — empty = your ChatGPT plan default.') : ('Exact model id at your provider (empty = first model the provider lists, after the test).')}><input className={inputCls} value={prov.default_model} onChange={(e) => setProv({ ...prov, default_model: e.target.value })} /></Field>
          {prov.kind === 'scripted_mock' && <div className="text-[13px] text-amber-400">{t('mockWarning')}</div>}
          <Button kind="primary" disabled={busy || (prov.kind === 'codex_cli' && !integ?.codex?.logged_in) || (prov.kind === 'chatgpt_oauth' && !integ?.chatgpt?.logged_in)} onClick={saveProvider}>{t('next')}</Button>
        </>}
        {step === 3 && <>
          <Button disabled={busy} onClick={runTest}>{t('testConnection')}</Button>
          {test && <div className="text-[13px] space-y-1">{Object.entries(test.checks).map(([k, v]) => <div key={k}>{v.ok ? '✅' : '⚠️'} {k}: {v.error || v.detail || v.sample || ''}</div>)}
            {test.is_mock && <div className="text-amber-400">{t('mockWarning')}</div>}</div>}
          <div className="flex gap-2"><Button onClick={() => setStep(2)}>{t('back')}</Button><Button kind="primary" onClick={() => setStep(4)}>{t('next')}</Button></div>
        </>}
        {step === 4 && <>
          <Field label={t('firstBot')}><input className={inputCls} value={botName} onChange={(e) => setBotName(e.target.value)} /></Field>
          <Button kind="primary" disabled={busy} onClick={createBot}>{t('done')}</Button>
        </>}
        {err && <div className="text-[15px] text-rose-400 break-words">{err}</div>}
      </Card>
    </div>
  );
}
