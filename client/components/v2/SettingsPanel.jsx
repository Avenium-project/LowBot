'use client';
import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { api, downloadPath, isLocal } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Card, Empty, Field, Section, askConfirm, fmtTime, inputCls } from './ui';
import Integrations from './Integrations';

function Providers({ ws }) {
  const { t } = useT();
  const [data, setData] = useState({ profiles: [] });
  const [presets, setPresets] = useState([]);
  const [form, setForm] = useState({ kind: isLocal() ? 'xai' : 'openai_responses', name: '', base_url: '', api_key: '', default_model: '', models: '' });
  const [tests, setTests] = useState({});
  const [err, setErr] = useState('');
  const load = () => api('/providers').then(setData);
  useEffect(() => { load(); api('/providers/presets').then(setPresets); }, []);
  const preset = presets.find((p) => p.kind === form.kind);
  const save = async () => {
    setErr('');
    try {
      await api('/providers', { method: 'POST', body: { ...form, base_url: form.base_url || undefined,
        models: form.models.split(/[\s,]+/).filter(Boolean), api_key: form.api_key || undefined } });
      setForm({ ...form, api_key: '' }); load();
    } catch (e) { setErr(e.message); }
  };
  const test = async (id) => {
    setTests({ ...tests, [id]: { running: true } });
    try { setTests({ ...tests, [id]: await api(`/providers/${id}/test`, { method: 'POST', body: {} }) }); load(); }
    catch (e) { setTests({ ...tests, [id]: { error: e.message } }); }
  };
  return (
    <Section title={t('models')}>
      <Card className="space-y-2 mb-3">
        <Field label={t('provider')}><select className={inputCls} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
          {presets.map((p) => <option key={p.kind} value={p.kind}>{p.label}</option>)}</select></Field>
        <Field label={t('name')}><input className={inputCls} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
        {form.kind !== 'scripted_mock' && <>
          <Field label="Base URL" hint={preset?.base_url ? `default: ${preset.base_url}` : 'e.g. https://host/v1'}>
            <input className={inputCls} value={form.base_url} onChange={(e) => setForm({ ...form, base_url: e.target.value })} placeholder={preset?.base_url} /></Field>
          <Field label="API key" hint="Stored encrypted; never shown again."><input type="password" autoComplete="off" className={inputCls} value={form.api_key} onChange={(e) => setForm({ ...form, api_key: e.target.value })} /></Field>
        </>}
        <Field label={t('model')} hint="Exact model id from your provider (use Test connection to list models)."><input className={inputCls} value={form.default_model} onChange={(e) => setForm({ ...form, default_model: e.target.value })} /></Field>
        {form.kind === 'scripted_mock' && <div className="text-[13px] text-amber-400">{t('mockWarning')}</div>}
        <Button kind="primary" onClick={save}>{t('save')}</Button>
        {err && <div className="text-[13px] text-rose-400">{err}</div>}
      </Card>
      {data.profiles.map((p) => (
        <Card key={p.id} className="mb-2">
          <div className="flex justify-between flex-wrap gap-2">
            <div><b>{p.name}</b> {data.default_profile_id === p.id && <span className="text-[13px] text-emerald-400">default</span>}
              <div className="text-[13px] text-zinc-400">{p.kind} · {p.base_url || '—'} · {p.default_model || '—'} · key {p.api_key_configured ? '✓' : '✗'}</div>
              {p.is_mock && <div className="text-[13px] text-amber-400">{t('mockWarning')}</div>}
              <div className="text-[13px] text-zinc-500">tools: {String(p.capabilities?.tools ?? '?')} · vision: {String(p.capabilities?.vision ?? '?')} · streaming: {String(p.capabilities?.streaming ?? '?')}</div>
            </div>
            <div className="flex gap-1 flex-wrap">
              <Button small onClick={() => test(p.id)}>{t('testConnection')}</Button>
              <Button small onClick={() => api(`/providers/${p.id}/default`, { method: 'POST' }).then(load)}>default</Button>
              <Button small kind="danger" onClick={async () => { if (await askConfirm({ title: `Delete ${p.name}?`, message: 'Bots using this provider fall back to the default one.', confirmLabel: 'Delete', danger: true })) api(`/providers/${p.id}`, { method: 'DELETE' }).then(load); }}>{t('delete')}</Button>
            </div>
          </div>
          {tests[p.id] && <pre className="text-[12px] text-zinc-300 whitespace-pre-wrap mt-2 max-h-48 overflow-y-auto">{tests[p.id].running ? '…' : JSON.stringify(tests[p.id], null, 1)}</pre>}
        </Card>
      ))}
    </Section>
  );
}

function Mcp() {
  const { t } = useT();
  const [rows, setRows] = useState([]);
  const [form, setForm] = useState({ name: '', transport: 'http', url: '', command: '', args: '', auth_token: '' });
  const [err, setErr] = useState('');
  const load = () => api('/mcp/connections').then(setRows).catch((e) => setErr(e.message));
  useEffect(() => { load(); }, []);
  const save = async () => {
    setErr('');
    try {
      await api('/mcp/connections', { method: 'POST', body: { ...form, args: form.args.split(' ').filter(Boolean), auth_token: form.auth_token || undefined } });
      load();
    } catch (e) { setErr(e.message); }
  };
  return (
    <Section title={t('mcp')}>
      <Card className="space-y-2 mb-3">
        <div className="flex gap-2"><input className={inputCls} placeholder={t('name')} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <select className={inputCls} value={form.transport} onChange={(e) => setForm({ ...form, transport: e.target.value })}><option value="http">Streamable HTTP</option><option value="stdio">stdio</option></select></div>
        {form.transport === 'http'
          ? <><input className={inputCls} placeholder="https://server/mcp" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} />
            <input className={inputCls} type="password" placeholder="Bearer token (optional, stored encrypted)" value={form.auth_token} onChange={(e) => setForm({ ...form, auth_token: e.target.value })} /></>
          : <><input className={inputCls} placeholder="command (e.g. npx)" value={form.command} onChange={(e) => setForm({ ...form, command: e.target.value })} />
            <input className={inputCls} placeholder="args" value={form.args} onChange={(e) => setForm({ ...form, args: e.target.value })} /></>}
        <Button kind="primary" onClick={save}>{t('save')}</Button>
        {err && <div className="text-[13px] text-rose-400">{err}</div>}
      </Card>
      {rows.map((c) => (
        <Card key={c.id} className="mb-2">
          <div className="flex justify-between gap-2 flex-wrap"><div><b>{c.name}</b> · {c.transport} · {c.last_status || 'not tested'}
            <div className="text-[13px] text-zinc-400 break-all">{c.url || `${c.command} ${c.args.join(' ')}`}</div>
            <div className="text-[13px] text-zinc-500">{c.tools.map((x) => x.name).join(', ')}</div></div>
            <div className="flex gap-1"><Button small onClick={() => api(`/mcp/connections/${c.id}/test`, { method: 'POST' }).then(load)}>{t('testConnection')}</Button>
              <Button small kind="danger" onClick={() => api(`/mcp/connections/${c.id}`, { method: 'DELETE' }).then(load)}>{t('delete')}</Button></div></div>
          {c.logs.length > 0 && <details><summary className="text-[13px] text-zinc-500 cursor-pointer">logs</summary><pre className="text-[12px] whitespace-pre-wrap">{c.logs.join('\n')}</pre></details>}
        </Card>
      ))}
    </Section>
  );
}

function Devices() {
  const { t } = useT();
  const [rows, setRows] = useState([]);
  const [code, setCode] = useState(null);
  const [qr, setQr] = useState('');
  const load = () => api('/devices').then(setRows);
  useEffect(() => { load(); }, []);
  const pair = async () => {
    const c = await api('/pair/code', { method: 'POST' });
    setCode(c);
    setQr(await QRCode.toDataURL(c.qr_payload, { margin: 1, width: 220 }));
  };
  return (
    <Section title={t('devices')} actions={<Button small kind="primary" onClick={pair}>{t('pairDevice')}</Button>}>
      {code && <Card className="mb-2 text-center">
        <div className="text-[13px] text-zinc-400">{t('pairingCode')} · {t('expires')} {fmtTime(code.expires_at)}</div>
        <div className="text-2xl font-mono tracking-widest my-1">{code.code}</div>
        <div className="text-[13px] text-zinc-400 break-all">{code.server_url}</div>
        {qr && <img src={qr} alt="QR" className="mx-auto mt-2 rounded bg-white p-1" />}
        <div className="text-[12px] text-zinc-500 mt-1">One-time code. It does not contain the owner token.</div>
      </Card>}
      {rows.length ? rows.map((d) => (
        <div key={d.id} className="flex justify-between items-center py-2 border-b border-white/5 text-[15px] gap-2">
          <span>{d.platform === 'android' ? '📱' : d.platform === 'windows' ? '💻' : '🔌'} {d.name} <span className="text-[13px] text-zinc-500">· {fmtTime(d.last_seen_at)}</span>{d.revoked_at && <span className="text-[13px] text-rose-400"> revoked</span>}</span>
          {!d.revoked_at && <Button small kind="danger" onClick={() => api(`/devices/${d.id}`, { method: 'DELETE' }).then(load)}>{t('revoke')}</Button>}
        </div>)) : <Empty />}
    </Section>
  );
}

function Policies({ ws }) {
  const { t } = useT();
  const [data, setData] = useState(null);
  const [rule, setRule] = useState({ tool: '', effect: 'ask', bot_id: '' });
  const load = () => api('/policies').then(setData);
  useEffect(() => { load(); }, []);
  if (!data) return null;
  return (
    <Section title={t('policies')}>
      <label className="flex items-center gap-2 text-[15px] mb-2"><input type="checkbox" checked={data.hierarchy_enforced}
        onChange={(e) => api('/settings/hierarchy', { method: 'POST', body: { enforced: e.target.checked } }).then(load)} /> {t('hierarchy')}</label>
      <Card className="mb-2 flex gap-2 flex-wrap">
        <input className={inputCls} placeholder="tool pattern, e.g. http.* or mcp.github.*" value={rule.tool} onChange={(e) => setRule({ ...rule, tool: e.target.value })} />
        <select className={inputCls} value={rule.effect} onChange={(e) => setRule({ ...rule, effect: e.target.value })}><option>allow</option><option>ask</option><option>deny</option></select>
        <select className={inputCls} value={rule.bot_id} onChange={(e) => setRule({ ...rule, bot_id: e.target.value })}><option value="">all bots</option>{ws.bots.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select>
        <Button kind="primary" onClick={() => api('/policies', { method: 'POST', body: { ...rule, bot_id: rule.bot_id || null } }).then(load)}>{t('save')}</Button>
      </Card>
      {data.rules.map((r) => <div key={r.id} className="text-[15px] flex justify-between py-1"><span><code>{r.tool_pattern}</code> → <b>{r.effect}</b> {r.bot_id ? `(${ws.bots.find((b) => b.id === r.bot_id)?.name})` : ''}</span>
        <Button small kind="ghost" onClick={() => api(`/policies/${r.id}`, { method: 'DELETE' }).then(load)}>✕</Button></div>)}
      <details className="mt-2"><summary className="text-[13px] text-zinc-400 cursor-pointer">Tool defaults</summary>
        <div className="text-[13px]">{data.tools.map((x) => <div key={x.name}><code>{x.name}</code> · {x.effect_kind} · default <b>{x.default}</b>{x.hard_ask ? ' · always ask' : ''}</div>)}</div></details>
    </Section>
  );
}

// Grok Bot settings that apply to the phone-hosted backend.
function fmtBytes(n) { return n > 1e9 ? `${(n / 1e9).toFixed(1)} GB` : n > 1e6 ? `${(n / 1e6).toFixed(0)} MB` : `${Math.round(n / 1e3)} KB`; }

// The bots' Linux (Alpine via proot) on the phone: install, size, remove.
function LinuxSettings({ tick }) {
  const [st, setSt] = useState(null);
  const [err, setErr] = useState('');
  const [confirm, setConfirm] = useState(false);
  const load = () => api('/linux').then(setSt).catch((e) => setErr(e.message));
  useEffect(() => { load(); }, [tick]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (st?.state !== 'installing') return undefined;
    const h = setInterval(load, 1000);
    return () => clearInterval(h);
  }, [st?.state]); // eslint-disable-line react-hooks/exhaustive-deps
  const act = (path, method) => api(path, { method }).then(setSt).catch((e) => setErr(e.message));
  if (!st) return err ? <Section title="Linux terminal"><div className="text-[13px] text-rose-400">{err}</div></Section> : null;
  const pct = st.total ? Math.min(100, Math.round((st.progress / st.total) * 100)) : 0;
  return (
    <Section title="Linux terminal">
      <div className="rounded-[22px] bg-[#1f1f1f] p-4 space-y-3">
        <div className="text-[15px] leading-snug">Bots with the terminal switched on get their own Alpine Linux shell on this phone — install tools with <code>apk add</code> (python3, git, nodejs…). Shared files are in <code>/workspace</code>.</div>
        {!st.available ? <div className="text-[14px] text-amber-400">This build does not include the Linux runtime (proot), so the terminal is unavailable.</div>
          : st.state === 'installing' ? (
            <div className="space-y-2">
              <div className="text-[14px] text-zinc-300">Downloading Alpine Linux… {st.total ? `${pct}% of ${fmtBytes(st.total)}` : ''}</div>
              <div className="h-2 rounded-full bg-[#2a2a2a] overflow-hidden"><div className="h-full bg-white transition-all" style={{ width: `${pct}%` }} /></div>
            </div>)
          : st.installed ? (
            <div className="space-y-2">
              <div className="text-[14px] text-emerald-400">● Installed · Alpine {st.version} · {fmtBytes(st.size_bytes)} · {st.arch}</div>
              {st.sessions?.length > 0 && <div className="text-[13px] text-zinc-500">{st.sessions.length} shell{st.sessions.length === 1 ? '' : 's'} open — see Computer.</div>}
              {confirm
                ? <div className="flex gap-2"><Button kind="danger" onClick={() => { setConfirm(false); act('/linux', 'DELETE'); }}>Remove Linux and its files</Button><Button onClick={() => setConfirm(false)}>Cancel</Button></div>
                : <Button kind="danger" small onClick={() => setConfirm(true)}>Remove</Button>}
            </div>)
          : (
            <div className="space-y-2">
              {st.state === 'failed' && <div className="text-[13px] text-rose-400">Install failed: {st.error}</div>}
              <Button kind="primary" onClick={() => act('/linux/install', 'POST')}>Install Linux (about 3 MB download)</Button>
            </div>)}
        <div className="text-[12px] text-zinc-500 leading-snug">{st.network_note}</div>
      </div>
      {err && <div className="text-[13px] text-rose-400 mt-2">{err}</div>}
    </Section>
  );
}

function PhoneSettings() {
  const { lang } = useT();
  const pl = lang === 'pl';
  const [st, setSt] = useState(null);
  const [err, setErr] = useState('');
  useEffect(() => { api('/settings').then(setSt).catch((e) => setErr(e.message)); }, []);
  const save = (patch) => api('/settings', { method: 'POST', body: patch }).then(setSt).catch((e) => setErr(e.message));
  if (!st) return err ? <div className="text-[13px] text-rose-400">{err}</div> : null;
  return (
    <Section title={'Safety & execution'}>
      <label className="flex items-start gap-2 text-[15px] mb-3"><input type="checkbox" className="mt-1" checked={st.auto_review} onChange={(e) => save({ auto_review: e.target.checked })} />
        <span><b>Auto Review</b><br /><span className="text-[13px] text-zinc-400">{'A separate model call rates actions that need approval: allows obvious ones, denies harmful ones, leaves the rest to you. Payments, publishing and other always-ask actions always come to you.'}</span></span></label>
      <Field label={'Execution on this phone (terminal)'}>
        <select className={inputCls} value={st.local_execution} onChange={(e) => save({ local_execution: e.target.value })}>
          <option value="ask">{'Ask every time'}</option>
          <option value="always">{'Always allow'}</option>
          <option value="never">{'Never allow'}</option>
        </select></Field>
      <label className="flex items-start gap-2 text-[15px] my-3"><input type="checkbox" className="mt-1" checked={st.allow_private_network} onChange={(e) => save({ allow_private_network: e.target.checked })} />
        <span>{'Let bots reach your local network (LAN)'}</span></label>
      <Field label={'Routine time zone'}><input className={inputCls} defaultValue={st.timezone} onBlur={(e) => e.target.value !== st.timezone && save({ timezone: e.target.value })} /></Field>
      {err && <div className="text-[13px] text-rose-400">{err}</div>}
    </Section>
  );
}

function Admin() {
  const { t } = useT();
  const [b, setB] = useState(null);
  const [usage, setUsage] = useState(null);
  const [last, setLast] = useState(null);
  const load = () => { api('/admin/backups').then(setB); api('/usage').then(setUsage); };
  useEffect(() => { load(); }, []);
  const exportAudit = () => downloadPath('/audit/export', 'lowbot-audit.jsonl');
  return (
    <Section title={t('backup')} actions={<><Button small onClick={() => api('/admin/backup', { method: 'POST' }).then((r) => { setLast(r); load(); if (r.artifact_id) downloadPath(`/artifacts/${r.artifact_id}/download`, r.name); })}>{t('create')}</Button><Button small onClick={exportAudit}>Audit export</Button></>}>
      {last?.note && <div className="text-[13px] text-amber-400 mb-1">{last.name}: {last.note}</div>}
      {b && <div className="text-[13px] text-zinc-400 space-y-1">{b.backups.map((x) => <div key={x.name}>{x.name} · {(x.size / 1024).toFixed(0)} KB</div>)}<div>{b.restore}</div><div className="text-amber-400">{b.note}</div></div>}
      {usage && <details className="mt-2"><summary className="text-[13px] text-zinc-400 cursor-pointer">Usage</summary><pre className="text-[12px] whitespace-pre-wrap">{JSON.stringify(usage, null, 1)}</pre></details>}
    </Section>
  );
}

export default function SettingsPanel({ ws }) {
  return (
    <div>
      <Section title="Profile">
        <input className={inputCls} placeholder={'Your name (initial shown in the app)'}
          defaultValue={typeof window !== 'undefined' ? window.localStorage.getItem('opendots.name') || '' : ''}
          onChange={(e) => window.localStorage.setItem('opendots.name', e.target.value)} />
        {ws.health && <div className="text-[13px] text-zinc-500 mt-2">schema v{ws.health.schema_version} · {ws.health.timezone} · {ws.health.capabilities.join(', ')} · runs ≤ {ws.health.limits.max_active_runs}, screens ≤ {ws.health.limits.max_active_surfaces}</div>}
      </Section>
      {isLocal() && <PhoneSettings />}
      {isLocal() && <LinuxSettings tick={ws?.tick} />}
      <Integrations ws={ws} />
      <Providers ws={ws} />
      {!isLocal() && <Devices />}
      <Mcp />
      <Policies ws={ws} />
      <Admin />
    </div>
  );
}
