'use client';
import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { api, downloadPath, isLocal } from '../../lib/v2/api';
import { useT } from '../../lib/v2/i18n';
import { Button, Card, Empty, Field, Section, askConfirm, fmtTime, inputCls } from './ui';
import Integrations, { ChatGptPhone } from './Integrations';
import { FiArchive, FiBell, FiChevronLeft, FiChevronRight, FiClock, FiCpu, FiEye, FiFolder, FiGrid, FiLink, FiShield, FiSmartphone, FiTerminal } from 'react-icons/fi';
import WorkspacesPanel from './WorkspacesPanel';
import { WidgetsSettings } from './Widgets';
import { RoutinesPanel } from './AutomationPanels';
import { BotBlob, Toggle, cls } from './ui';
import { applyAppearance, loadAppearance, saveAppearance } from '../../lib/v2/appearance';

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

function fmtBytes(n) { return n > 1e9 ? `${(n / 1e9).toFixed(1)} GB` : n > 1e6 ? `${(n / 1e6).toFixed(0)} MB` : `${Math.round(n / 1e3)} KB`; }

// ---------------------------------------------------------------- building blocks
const group = 'rounded-[22px] bg-[#1f1f1f] overflow-hidden';
function Row({ icon, label, value, onClick, right, danger }) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag type={onClick ? 'button' : undefined} onClick={onClick}
      className={cls('w-full flex items-center gap-4 px-5 min-h-[56px] py-3 text-left border-b border-white/5 last:border-b-0', onClick && 'lb-press')}>
      {icon && <span className={cls('text-[20px] shrink-0', danger ? 'text-rose-400' : 'text-zinc-300')}>{icon}</span>}
      <span className={cls('flex-1 min-w-0 truncate text-[17px]', danger && 'text-rose-400')}>{label}</span>
      {value != null && <span className="text-[15px] text-zinc-500 truncate max-w-[45%]">{value}</span>}
      {right !== undefined ? right : onClick && <FiChevronRight className="text-zinc-500 shrink-0" />}
    </Tag>
  );
}
function Label({ children }) { return <div className="px-5 pt-5 pb-2 text-[14px] text-zinc-500">{children}</div>; }
function Segmented({ value, options, onChange }) {
  return (
    <div className="flex rounded-full bg-[#2a2a2a] p-1">
      {options.map(([v, l]) => (
        <button key={v} type="button" onClick={() => onChange(v)}
          className={cls('flex-1 rounded-full py-2 text-[14px] transition-colors', value === v ? 'bg-white text-black font-medium' : 'text-zinc-300')}>{l}</button>))}
    </div>
  );
}

// ---------------------------------------------------------------- pages
function Appearance() {
  const [a, setA] = useState(loadAppearance());
  const set = (patch) => { const n = { ...a, ...patch }; setA(n); saveAppearance(n); applyAppearance(n); };
  return (
    <>
      <Label>Text size</Label>
      <div className="px-1"><Segmented value={a.size} onChange={(size) => set({ size })} options={[['s', 'Small'], ['m', 'Default'], ['l', 'Large'], ['xl', 'Huge']]} /></div>
      <Label>Motion</Label>
      <div className={group}>
        <Row label="Animations" right={<Toggle on={a.motion} label="Animations" onChange={(motion) => set({ motion })} />} />
        <Row label="Bot characters move" right={<Toggle on={a.ghosts} label="Bot characters move" onChange={(ghosts) => set({ ghosts })} />} />
      </div>
      <Label>Chat list</Label>
      <div className={group}><Row label="Show message previews" right={<Toggle on={a.previews} label="Show message previews" onChange={(previews) => set({ previews })} />} /></div>
    </>
  );
}

function ProviderForm({ presets, onDone }) {
  const [form, setForm] = useState({ kind: presets[0]?.kind || 'xai', api_key: '', default_model: '', base_url: '' });
  const [err, setErr] = useState('');
  const preset = presets.find((p) => p.kind === form.kind);
  const save = async () => {
    setErr('');
    try {
      await api('/providers', { method: 'POST', body: { kind: form.kind, name: preset?.label || form.kind, api_key: form.api_key || undefined,
        default_model: form.default_model || undefined, base_url: form.base_url || undefined } });
      onDone();
    } catch (e) { setErr(e.message); }
  };
  return (
    <div className="lb-rise rounded-[22px] bg-[#1f1f1f] p-4 space-y-3">
      <select className={inputCls} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })} aria-label="Provider">
        {presets.filter((p) => p.kind !== 'chatgpt_oauth').map((p) => <option key={p.kind} value={p.kind}>{p.label}</option>)}</select>
      {!preset?.base_url && form.kind !== 'scripted_mock' && <input className={inputCls} value={form.base_url} onChange={(e) => setForm({ ...form, base_url: e.target.value })} placeholder="Base URL (https://…/v1)" />}
      {form.kind !== 'scripted_mock' && <input type="password" autoComplete="off" className={inputCls} value={form.api_key} onChange={(e) => setForm({ ...form, api_key: e.target.value })} placeholder="API key" />}
      <input className={inputCls} value={form.default_model} onChange={(e) => setForm({ ...form, default_model: e.target.value })} placeholder="Model (empty = first available)" />
      <div className="flex gap-2"><Button kind="primary" onClick={save}>Add</Button><Button onClick={() => onDone(true)}>Cancel</Button></div>
      {err && <div className="text-[13px] text-rose-400">{err}</div>}
    </div>
  );
}

function Models({ ws }) {
  const [data, setData] = useState({ profiles: [] });
  const [presets, setPresets] = useState([]);
  const [status, setStatus] = useState(null);
  const [adding, setAdding] = useState(false);
  const [open, setOpen] = useState(null);
  const [test, setTest] = useState({});
  const load = () => api('/providers').then(setData).catch(() => {});
  const loadStatus = () => api('/integrations').then(setStatus).catch(() => {});
  useEffect(() => { load(); loadStatus(); api('/providers/presets').then(setPresets).catch(() => {}); }, []);
  const runTest = async (id) => {
    setTest({ ...test, [id]: 'Testing…' });
    try {
      const r = await api(`/providers/${id}/test`, { method: 'POST', body: {} });
      setTest({ ...test, [id]: r.ok === false || r.error ? `✗ ${r.error || 'failed'}` : `✓ Works${r.models?.length ? ` · ${r.models.length} models` : ''}` });
      load();
    } catch (e) { setTest({ ...test, [id]: `✗ ${e.message}` }); }
  };
  return (
    <>
      {isLocal() && status && <ChatGptPhone status={status} onChanged={loadStatus} ws={ws} compact />}
      {!isLocal() && <Integrations ws={ws} />}
      <Label>Providers</Label>
      <div className={group}>
        {data.profiles.map((p) => (
          <div key={p.id}>
            <Row label={p.name} value={data.default_profile_id === p.id ? `default · ${p.default_model || 'auto'}` : p.default_model || 'auto'}
              onClick={() => setOpen(open === p.id ? null : p.id)} right={<FiChevronRight className={cls('text-zinc-500 transition-transform', open === p.id && 'rotate-90')} />} />
            {open === p.id && (
              <div className="lb-rise px-5 pb-4 flex flex-wrap gap-2 items-center">
                <Button small onClick={() => runTest(p.id)}>Test</Button>
                {data.default_profile_id !== p.id && <Button small onClick={() => api(`/providers/${p.id}/default`, { method: 'POST' }).then(load)}>Make default</Button>}
                <Button small kind="danger" onClick={async () => { if (await askConfirm({ title: `Delete ${p.name}?`, confirmLabel: 'Delete', danger: true })) api(`/providers/${p.id}`, { method: 'DELETE' }).then(load); }}>Delete</Button>
                {test[p.id] && <span className={cls('text-[13px]', test[p.id].startsWith('✓') ? 'text-emerald-400' : test[p.id].startsWith('✗') ? 'text-rose-400' : 'text-zinc-400')}>{test[p.id]}</span>}
              </div>)}
          </div>))}
        {!adding && <Row label={<span className="text-sky-400">+ Add provider</span>} onClick={() => setAdding(true)} right={null} />}
      </div>
      {adding && <div className="mt-3"><ProviderForm presets={presets} onDone={() => { setAdding(false); load(); }} /></div>}
    </>
  );
}

function Notifications({ ws }) {
  const toggle = (b, on) => api(`/bots/${b.id}`, { method: 'PATCH', body: { notify: on } }).then(() => ws.reload());
  return (
    <>
      {isLocal() && window.LowBotNative?.requestNotifications && (
        <div className={cls(group, 'mt-1')}><Row icon={<FiSmartphone />} label="Allow notifications on this phone" onClick={() => window.LowBotNative.requestNotifications()} /></div>)}
      <Label>Bots</Label>
      <div className={group}>
        {ws.bots.map((b) => (
          <Row key={b.id} icon={<BotBlob bot={b} size={30} still />} label={b.name} right={<Toggle on={b.notify !== false} label={`Notify: ${b.name}`} onChange={(on) => toggle(b, on)} />} />))}
      </div>
    </>
  );
}

function Terminal({ tick }) {
  const [st, setSt] = useState(null);
  const [cfg, setCfg] = useState(null);
  const [err, setErr] = useState('');
  const load = () => api('/linux').then(setSt).catch((e) => setErr(e.message));
  useEffect(() => { load(); api('/settings').then(setCfg).catch(() => {}); }, [tick]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (st?.state !== 'installing') return undefined;
    const h = setInterval(load, 1000);
    return () => clearInterval(h);
  }, [st?.state]); // eslint-disable-line react-hooks/exhaustive-deps
  const save = (patch) => api('/settings', { method: 'POST', body: patch }).then(setCfg).catch((e) => setErr(e.message));
  const act = (path, method) => api(path, { method }).then(setSt).catch((e) => setErr(e.message));
  const pct = st?.total ? Math.min(100, Math.round((st.progress / st.total) * 100)) : 0;
  return (
    <>
      <div className={group}>
        {!st ? <Row label="Linux" value="…" />
          : !st.available ? <Row label="Linux" value="not in this build" />
            : st.state === 'installing' ? (
              <div className="px-5 py-4 space-y-2">
                <div className="flex justify-between text-[17px]"><span>Installing Linux</span><span className="text-zinc-500 text-[15px]">{pct}%</span></div>
                <div className="h-1.5 rounded-full bg-[#2a2a2a] overflow-hidden"><div className="h-full bg-white transition-all" style={{ width: `${pct}%` }} /></div>
              </div>)
              : st.installed ? <>
                <Row label={`Alpine ${st.version}`} value={fmtBytes(st.size_bytes)} right={<span className="h-2 w-2 rounded-full bg-emerald-400" />} />
                <Row label="Remove Linux" danger onClick={async () => { if (await askConfirm({ title: 'Remove Linux?', message: 'Installed packages are deleted. Workspace files stay.', confirmLabel: 'Remove', danger: true })) act('/linux', 'DELETE'); }} right={null} />
              </> : <Row label={<span className="text-sky-400">Install Linux</span>} value={st.state === 'failed' ? 'failed — retry' : '≈4 MB'} onClick={() => act('/linux/install', 'POST')} />}
      </div>
      {cfg && <>
        <Label>Bot commands</Label>
        <div className="px-1"><Segmented value={cfg.local_execution} onChange={(v) => save({ local_execution: v })} options={[['ask', 'Ask'], ['always', 'Allow'], ['never', 'Block']]} /></div>
        <Label>Network</Label>
        <div className={group}><Row label="Local network (LAN)" right={<Toggle on={cfg.allow_private_network} label="Local network" onChange={(v) => save({ allow_private_network: v })} />} /></div>
      </>}
      {err && <div className="text-[13px] text-rose-400 px-2 mt-2">{err}</div>}
    </>
  );
}

function RoutinesPage({ ws }) {
  const [cfg, setCfg] = useState(null);
  useEffect(() => { if (isLocal()) api('/settings').then(setCfg).catch(() => {}); }, []);
  return (
    <>
      <RoutinesPanel ws={ws} />
      {cfg && <><Label>Time zone</Label>
        <input className={inputCls} defaultValue={cfg.timezone} aria-label="Time zone"
          onBlur={(e) => e.target.value !== cfg.timezone && api('/settings', { method: 'POST', body: { timezone: e.target.value } }).then(setCfg)} /></>}
    </>
  );
}


function Profile() {
  const [name, setName] = useState(() => { try { return window.localStorage.getItem('opendots.name') || ''; } catch { return ''; } });
  return (
    <div className={group}>
      <div className="flex items-center gap-4 px-5 min-h-[56px]">
        <span className="text-[17px]">Name</span>
        <input className="flex-1 min-w-0 bg-transparent text-right text-[17px] text-zinc-300 outline-none placeholder:text-zinc-600" value={name} placeholder="Your name"
          onChange={(e) => { setName(e.target.value); try { window.localStorage.setItem('opendots.name', e.target.value); } catch { /* storage unavailable */ } }} />
      </div>
    </div>
  );
}

function Safety({ ws }) {
  const [cfg, setCfg] = useState(null);
  const [pol, setPol] = useState(null);
  const [rule, setRule] = useState({ tool: '', effect: 'ask', bot_id: '' });
  const [adding, setAdding] = useState(false);
  const [err, setErr] = useState('');
  const loadPol = () => api('/policies').then(setPol).catch((e) => setErr(e.message));
  useEffect(() => { if (isLocal()) api('/settings').then(setCfg).catch(() => {}); loadPol(); }, []);
  const save = (patch) => api('/settings', { method: 'POST', body: patch }).then(setCfg).catch((e) => setErr(e.message));
  const add = () => api('/policies', { method: 'POST', body: { ...rule, bot_id: rule.bot_id || null } })
    .then(() => { setAdding(false); setRule({ tool: '', effect: 'ask', bot_id: '' }); loadPol(); }).catch((e) => setErr(e.message));
  const botName = (id) => ws.bots.find((b) => b.id === id)?.name;
  return (
    <>
      {cfg && <div className={group}><Row label="Auto Review" right={<Toggle on={cfg.auto_review} label="Auto Review" onChange={(v) => save({ auto_review: v })} />} /></div>}
      {pol && <>
        <Label>Tool permissions</Label>
        <div className={group}>
          {pol.rules.map((r) => (
            <Row key={r.id} label={<span className="font-mono text-[15px]">{r.tool_pattern}</span>} value={`${r.effect}${r.bot_id ? ` · ${botName(r.bot_id) || 'bot'}` : ''}`}
              right={<button type="button" aria-label="Remove rule" onClick={() => api(`/policies/${r.id}`, { method: 'DELETE' }).then(loadPol)} className="lb-press text-zinc-500 px-1">✕</button>} />))}
          {!adding && <Row label={<span className="text-sky-400">+ Add rule</span>} onClick={() => setAdding(true)} right={null} />}
        </div>
        {adding && (
          <div className="lb-rise mt-3 rounded-[22px] bg-[#1f1f1f] p-4 space-y-3">
            <input className={cls(inputCls, 'font-mono')} placeholder="tool, e.g. http.* or linux.run" value={rule.tool} onChange={(e) => setRule({ ...rule, tool: e.target.value })} />
            <Segmented value={rule.effect} onChange={(effect) => setRule({ ...rule, effect })} options={[['allow', 'Allow'], ['ask', 'Ask'], ['deny', 'Deny']]} />
            <select className={inputCls} value={rule.bot_id} onChange={(e) => setRule({ ...rule, bot_id: e.target.value })} aria-label="Bot">
              <option value="">All bots</option>{ws.bots.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</select>
            <div className="flex gap-2"><Button kind="primary" disabled={!rule.tool.trim()} onClick={add}>Add</Button><Button onClick={() => setAdding(false)}>Cancel</Button></div>
          </div>)}
      </>}
      {err && <div className="text-[13px] text-rose-400 px-2 mt-2">{err}</div>}
    </>
  );
}

function McpServers() {
  const [rows, setRows] = useState([]);
  const [form, setForm] = useState({ name: '', url: '', auth_token: '' });
  const [adding, setAdding] = useState(false);
  const [open, setOpen] = useState(null);
  const [err, setErr] = useState('');
  const load = () => api('/mcp/connections').then(setRows).catch((e) => setErr(e.message));
  useEffect(() => { load(); }, []);
  const save = async () => {
    setErr('');
    try {
      await api('/mcp/connections', { method: 'POST', body: { name: form.name, transport: 'http', url: form.url, auth_token: form.auth_token || undefined, args: [] } });
      setAdding(false); setForm({ name: '', url: '', auth_token: '' }); load();
    } catch (e) { setErr(e.message); }
  };
  return (
    <>
      <div className={group}>
        {rows.map((c) => (
          <div key={c.id}>
            <Row label={c.name} value={c.last_status || `${c.tools.length} tools`} onClick={() => setOpen(open === c.id ? null : c.id)}
              right={<FiChevronRight className={cls('text-zinc-500 transition-transform', open === c.id && 'rotate-90')} />} />
            {open === c.id && (
              <div className="lb-rise px-5 pb-4 space-y-2">
                <div className="text-[13px] text-zinc-500 break-all">{c.url || `${c.command} ${c.args.join(' ')}`}</div>
                {c.tools.length > 0 && <div className="text-[13px] text-zinc-400">{c.tools.map((x) => x.name).join(', ')}</div>}
                <div className="flex gap-2"><Button small onClick={() => api(`/mcp/connections/${c.id}/test`, { method: 'POST' }).then(load)}>Test</Button>
                  <Button small kind="danger" onClick={async () => { if (await askConfirm({ title: `Remove ${c.name}?`, confirmLabel: 'Remove', danger: true })) api(`/mcp/connections/${c.id}`, { method: 'DELETE' }).then(load); }}>Remove</Button></div>
              </div>)}
          </div>))}
        {!adding && <Row label={<span className="text-sky-400">+ Add server</span>} onClick={() => setAdding(true)} right={null} />}
      </div>
      {adding && (
        <div className="lb-rise mt-3 rounded-[22px] bg-[#1f1f1f] p-4 space-y-3">
          <input className={inputCls} placeholder="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <input className={inputCls} placeholder="https://server/mcp" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} />
          <input className={inputCls} type="password" autoComplete="off" placeholder="Token (optional)" value={form.auth_token} onChange={(e) => setForm({ ...form, auth_token: e.target.value })} />
          <div className="flex gap-2"><Button kind="primary" disabled={!form.name || !form.url} onClick={save}>Add</Button><Button onClick={() => setAdding(false)}>Cancel</Button></div>
        </div>)}
      {err && <div className="text-[13px] text-rose-400 px-2 mt-2">{err}</div>}
    </>
  );
}

function Backup() {
  const [b, setB] = useState(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const load = () => api('/admin/backups').then(setB).catch((e) => setErr(e.message));
  useEffect(() => { load(); }, []);
  const make = async () => {
    setBusy(true); setErr('');
    try {
      const r = await api('/admin/backup', { method: 'POST' });
      if (r.artifact_id) await downloadPath(`/artifacts/${r.artifact_id}/download`, r.name);
      load();
    } catch (e) { setErr(e.message); }
    setBusy(false);
  };
  return (
    <>
      <div className={group}>
        <Row label={<span className="text-sky-400">{busy ? 'Creating backup…' : 'Create backup'}</span>} onClick={busy ? undefined : make} right={null} />
        <Row label={<span className="text-sky-400">Export audit log</span>} onClick={() => downloadPath('/audit/export', 'lowbot-audit.jsonl')} right={null} />
      </div>
      {b?.backups?.length > 0 && <>
        <Label>Backups</Label>
        <div className={group}>{b.backups.map((x) => <Row key={x.name} label={<span className="text-[15px]">{x.name}</span>} value={fmtBytes(x.size)} />)}</div>
      </>}
      {err && <div className="text-[13px] text-rose-400 px-2 mt-2">{err}</div>}
    </>
  );
}

const PAGES = [
  ['appearance', 'Appearance', <FiEye key="i" />],
  ['models', 'Model', <FiCpu key="i" />],
  ['routines', 'Routines', <FiClock key="i" />],
  ['notifications', 'Notifications', <FiBell key="i" />],
  ['workspaces', 'Workspaces', <FiFolder key="i" />],
  ['widgets', 'Widgets', <FiGrid key="i" />],
  ['terminal', 'Terminal', <FiTerminal key="i" />],
];
const MORE = [
  ['safety', 'Safety & permissions', <FiShield key="i" />],
  ['mcp', 'MCP servers', <FiLink key="i" />],
  ['backup', 'Backup & audit', <FiArchive key="i" />],
];

export default function SettingsPanel({ ws, onOpenConversation }) {
  const [page, setPage] = useState(null);
  useEffect(() => { window.scrollTo?.(0, 0); }, [page]);
  if (page) {
    const title = [...PAGES, ...MORE].find((p) => p[0] === page)?.[1] || 'Devices';
    return (
      <div key={page} className="lb-side-in">
        <button type="button" onClick={() => setPage(null)} className="lb-press flex items-center gap-1 px-1 pb-2 text-[16px] text-sky-400"><FiChevronLeft /> Settings</button>
        <div className="px-1 pb-2 text-[24px] font-semibold">{title}</div>
        {page === 'appearance' && <Appearance />}
        {page === 'models' && <Models ws={ws} />}
        {page === 'routines' && <RoutinesPage ws={ws} />}
        {page === 'notifications' && <Notifications ws={ws} />}
        {page === 'workspaces' && <WorkspacesPanel ws={ws} onOpenConversation={onOpenConversation} />}
        {page === 'terminal' && <Terminal tick={ws.tick} />}
        {page === 'widgets' && <WidgetsSettings ws={ws} />}
        {page === 'devices' && <Devices />}
        {page === 'safety' && <Safety ws={ws} />}
        {page === 'mcp' && <McpServers />}
        {page === 'backup' && <Backup />}
      </div>
    );
  }
  const pages = PAGES.filter(([k]) => isLocal() || !['terminal', 'workspaces', 'widgets'].includes(k));
  return (
    <div className="lb-rise">
      <div className="mb-4"><Profile /></div>
      <div className={group}>{pages.map(([k, l, icon]) => <Row key={k} icon={icon} label={l} onClick={() => setPage(k)} />)}</div>
      <div className={cls(group, 'mt-4')}>{MORE.map(([k, l, icon]) => <Row key={k} icon={icon} label={l} onClick={() => setPage(k)} />)}</div>
      {!isLocal() && <div className={cls(group, 'mt-4')}><Row icon={<FiSmartphone />} label="Devices" onClick={() => setPage('devices')} /></div>}
    </div>
  );
}
